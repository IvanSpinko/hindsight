package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.example.visiontrack.GpuRequiredException;
import org.example.visiontrack.analysis.ProgressListener;
import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.detection.ObjectDetectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes recorded meetings in the background, one at a time: recognises the speech, then has a local model
 * write the notes; the Notion export is added later. The screen recording is only kept for people to watch.
 * Progress is saved in {@code meeting.json}, and meetings interrupted by a restart are processed again on the next
 * start.
 */
@Service
public class MeetingJobService {

    static final String STEP_WAITING = "Waiting";
    static final String STEP_SPEECH = "Recognising speech";
    static final String STEP_NOTES = "Writing notes";
    /** Warnings of the notes step start with this, so processing again replaces them. */
    static final String NOTES_WARNING = "Notes: ";
    static final String NOTES_JSON = "notes.json";

    private static final Logger log = LoggerFactory.getLogger(MeetingJobService.class);

    private final MeetingStore store;
    private final VisionTrackProperties.Whisper whisper;
    private final ObjectMapper json;
    private final TranscriptWriter transcripts;
    private final VisionTrackProperties.Notes notesSettings;
    private final NotesExporter markdown = new MarkdownExporter();
    /** Object detection is unloaded while a meeting is processed, so the speech and notes models have the GPU. */
    private final ObjectDetectorProvider detectors;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "meeting-processing");
        thread.setDaemon(true);
        return thread;
    });

    public MeetingJobService(MeetingStore store, VisionTrackProperties properties, ObjectMapper objectMapper,
                             ObjectDetectorProvider detectors) {
        this.store = store;
        this.detectors = detectors;
        VisionTrackProperties.Meetings settings = properties.meetings();
        this.whisper = settings.whisper();
        this.json = objectMapper;
        this.transcripts = new TranscriptWriter(objectMapper);
        this.notesSettings = settings.notes();
    }

    /** Queues a recorded meeting; does nothing if it is already queued or running. */
    public void submit(String id) {
        Meeting meeting = store.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown meeting " + id));
        if (meeting.status() == Meeting.Status.RECORDING) {
            throw new IllegalStateException("The meeting is still being recorded");
        }
        if (!queued.add(id)) return;
        store.save(meeting.processing(STEP_WAITING, 0));
        executor.execute(() -> {
            try {
                run(id);
            } finally {
                queued.remove(id);
            }
        });
    }

    private void run(String id) {
        Meeting meeting = store.find(id).orElse(null);
        Path folder = store.folder(id).orElse(null);
        if (meeting == null || folder == null) return;
        try {
            detectors.unloadIfUnused();
            Meeting.Transcription transcription = transcribe(meeting, folder);
            Meeting.NotesInfo notes = writeNotes(meeting, folder);
            store.find(id).ifPresent(m -> store.save(m.ready(transcription, notes)));
            log.info("Meeting {} processed: {} speech segments, notes {}", id,
                    transcription == null ? 0 : transcription.segments(), notes == null ? "skipped" : "written");
        } catch (GpuRequiredException e) {
            log.error("Meeting {}: {}", id, e.getMessage(), e.getCause());
            store.find(id).ifPresent(m -> store.save(m.failed(e.getMessage())));
        } catch (Exception e) {
            log.error("Meeting {}: processing failed", id, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.find(id).ifPresent(m -> store.save(m.failed("Processing failed: " + message)));
        }
    }

    /** Recognises the microphone track; returns null (with a warning) when speech recognition is not installed. */
    private Meeting.Transcription transcribe(Meeting meeting, Path folder) throws IOException {
        Meeting.Track mic = meeting.track(MeetingRecorder.MIC);
        if (mic == null || !Files.isRegularFile(folder.resolve(mic.file()))) return null;
        if (!WhisperCppTranscriber.isInstalled(whisper)) {
            String warning = "Speech recognition is not installed (" + whisper.directory() + "), the transcript was skipped";
            log.warn("Meeting {}: {}", meeting.id(), warning);
            store.find(meeting.id()).ifPresent(m -> store.save(m.withWarning(warning)));
            return null;
        }
        Transcriber transcriber = new WhisperCppTranscriber(whisper, json, folder.resolve("logs"));
        Transcriber.Result result = transcriber.transcribe(folder.resolve(mic.file()), mic.startOffsetMillis() / 1000.0,
                MeetingRecorder.MIC, progressSaver(meeting.id(), STEP_SPEECH));
        transcripts.write(folder, meeting.title(), result);
        return new Meeting.Transcription(result.model(), result.backend(), result.languages(), result.segments().size());
    }

    /**
     * Has the local model write notes.json and notes.md from the transcript. Without the model, or when it fails,
     * the step is skipped with a warning and the transcript is kept (spec 5.4); without CUDA the meeting fails.
     */
    private Meeting.NotesInfo writeNotes(Meeting meeting, Path folder) throws IOException {
        store.find(meeting.id()).ifPresent(m -> store.save(m.withoutWarnings(NOTES_WARNING)));
        Files.deleteIfExists(folder.resolve(NOTES_JSON));
        Files.deleteIfExists(folder.resolve(MarkdownExporter.FILE));
        List<TranscriptSegment> segments = readTranscript(folder);
        if (segments.isEmpty()) return null;

        Path server = notesSettings.directory().resolve(Ffmpeg.isWindows() ? "llama-server.exe" : "llama-server");
        if (!Files.isRegularFile(server) || !Files.isRegularFile(notesSettings.model())) {
            warn(meeting.id(), NOTES_WARNING + "the notes model is not installed (" + notesSettings.model() + "), notes were skipped");
            return null;
        }
        double duration = meeting.tracks().stream().mapToDouble(Meeting.Track::durationSeconds).max().orElse(0);
        NotesGenerator.Input input = new NotesGenerator.Input(meeting.title(), meeting.startedAt().atZone(ZoneId.systemDefault()),
                duration, segments);
        String model = notesSettings.model().getFileName().toString().replaceFirst("(?i)\\.gguf$", "");
        store.find(meeting.id()).ifPresent(m -> store.save(m.processing(STEP_NOTES, 0)));
        try {
            if (!LlamaServer.hasCudaDevice(server)) throw new GpuRequiredException("Writing notes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Writing notes was interrupted", e);
        }
        try (LlamaServer llama = LlamaServer.start(server, notesSettings.model(), notesSettings.contextTokens(),
                folder.resolve("logs/llama.log"), json)) {
            NotesGenerator.Result result = new LocalNotesGenerator(llama, model, notesSettings.chunkMinutes() * 60.0,
                    notesSettings.contextTokens(), notesSettings.maxTokens(), json)
                    .generate(input, progressSaver(meeting.id(), STEP_NOTES));
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve(NOTES_JSON).toFile(), result.notes());
            markdown.export(folder, store.find(meeting.id()).orElse(meeting), result.notes(), result.model());
            return new Meeting.NotesInfo(result.model(), result.notes().decisions().size(), result.notes().actionItems().size());
        } catch (IOException e) {
            log.warn("Meeting {}: notes failed: {}", meeting.id(), e.getMessage(), e.getCause());
            warn(meeting.id(), NOTES_WARNING + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Writing notes was interrupted", e);
        }
    }

    private List<TranscriptSegment> readTranscript(Path folder) throws IOException {
        Path file = folder.resolve(TranscriptWriter.JSON_FILE);
        if (!Files.isRegularFile(file)) return List.of();
        return json.readValue(file.toFile(), TranscriptWriter.TranscriptFile.class).segments();
    }

    private void warn(String id, String warning) {
        log.warn("Meeting {}: {}", id, warning);
        store.find(id).ifPresent(m -> store.save(m.withWarning(warning)));
    }

    /** Saves progress to meeting.json in 5% steps, so the UI can show it without rewriting the file every frame. */
    private ProgressListener progressSaver(String id, String step) {
        AtomicInteger saved = new AtomicInteger(-5);
        return fraction -> {
            int percent = (int) (fraction * 100);
            if (percent >= saved.get() + 5) {
                saved.set(percent);
                store.find(id).ifPresent(m -> store.save(m.processing(step, percent)));
            }
        };
    }

    /** Picks up meetings whose processing was cut short by a restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeUnfinished() {
        Thread.ofVirtual().name("meeting-resume").start(() -> {
            for (Meeting meeting : store.findAll()) {
                if (meeting.status() == Meeting.Status.RECORDED || meeting.status() == Meeting.Status.PROCESSING) {
                    log.info("Resuming processing of meeting {}", meeting.id());
                    submit(meeting.id());
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
