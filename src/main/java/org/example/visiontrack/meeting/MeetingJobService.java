package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.example.visiontrack.GpuRequiredException;
import org.example.visiontrack.analysis.ProgressListener;
import org.example.visiontrack.config.SettingsService;
import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.detection.ObjectDetectorProvider;
import org.example.visiontrack.detection.OnnxYoloDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes meetings in the background, one at a time: speech, notes, then the optional Notion export.
 * Progress is saved in {@code meeting.json}; unfinished meetings resume on the next start.
 */
@Service
public class MeetingJobService {

    static final String STEP_WAITING = "Waiting";
    static final String STEP_PREPARING = "Preparing the recording";
    static final String STEP_SPEECH = "Recognising speech";
    static final String STEP_NOTES = "Writing notes";
    /** Warnings of the notes step start with this, so processing again replaces them. */
    static final String NOTES_WARNING = "Notes: ";
    static final String NOTES_JSON = "notes.json";
    /** Video memory the notes model needs on top of its file size: context (16k tokens) and work buffers. */
    static final long NOTES_EXTRA_MIB = 1024;

    private static final Logger log = LoggerFactory.getLogger(MeetingJobService.class);

    private final MeetingStore store;
    private final VisionTrackProperties.Whisper whisper;
    private final VisionTrackProperties.SpeechEngine speechEngine;
    private final VisionTrackProperties.Parakeet parakeet;
    private final VisionTrackProperties.Model gpuLibraries;
    private final Path ffmpegDir;
    private volatile Ffmpeg ffmpeg;
    private final ObjectMapper json;
    private final TranscriptWriter transcripts;
    private final VisionTrackProperties.Notes notesSettings;
    private final NotesExporter markdown = new MarkdownExporter();
    /** Object detection is unloaded while a meeting is processed, so the speech and notes models have the GPU. */
    private final ObjectDetectorProvider detectors;
    private final NotionExportService notion;
    private final SettingsService preferences;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "meeting-processing");
        thread.setDaemon(true);
        return thread;
    });

    public MeetingJobService(MeetingStore store, VisionTrackProperties properties, ObjectMapper objectMapper,
                             ObjectDetectorProvider detectors, NotionExportService notion, SettingsService preferences) {
        this.store = store;
        this.preferences = preferences;
        this.detectors = detectors;
        this.notion = notion;
        VisionTrackProperties.Meetings settings = properties.meetings();
        this.whisper = settings.whisper();
        this.speechEngine = settings.speechEngine();
        this.parakeet = settings.parakeet();
        this.gpuLibraries = properties.model();
        this.ffmpegDir = settings.ffmpegDir();
        this.json = objectMapper;
        this.transcripts = new TranscriptWriter(objectMapper);
        this.notesSettings = settings.notes();
    }

    /**
     * Renames a meeting or changes its start time and rewrites transcript.md and notes.md; the folder keeps its name.
     *
     * @throws NoSuchElementException if there is no such meeting
     * @throws IllegalStateException  while the meeting is recorded or processed
     */
    public Meeting edit(String id, String title, Instant startedAt) throws IOException {
        Meeting meeting = store.find(id).orElseThrow(() -> new NoSuchElementException("Meeting not found"));
        if (meeting.status() == Meeting.Status.RECORDING) throw new IllegalStateException("The meeting is still being recorded");
        if (meeting.status() == Meeting.Status.PROCESSING || queued.contains(id)) {
            throw new IllegalStateException("The meeting is still being processed");
        }
        String newTitle = title == null || title.isBlank() ? meeting.title() : title.strip();
        Meeting edited = meeting.edited(newTitle, startedAt == null ? meeting.startedAt() : startedAt);
        store.save(edited);
        Path folder = store.folder(id).orElseThrow(() -> new NoSuchElementException("Meeting not found"));
        Path transcript = folder.resolve(TranscriptWriter.JSON_FILE);
        if (Files.isRegularFile(transcript)) {
            TranscriptWriter.TranscriptFile file = json.readValue(transcript.toFile(), TranscriptWriter.TranscriptFile.class);
            Files.writeString(folder.resolve(TranscriptWriter.MARKDOWN_FILE), TranscriptWriter.markdown(newTitle,
                    new Transcriber.Result(file.segments(), file.model(), file.backend(), file.languages())), StandardCharsets.UTF_8);
        }
        Path notesFile = folder.resolve(NOTES_JSON);
        if (edited.notes() != null && Files.isRegularFile(notesFile)) {
            markdown.export(folder, edited, json.readValue(notesFile.toFile(), MeetingNotes.class), edited.notes().model());
        }
        log.info("Meeting {} edited: \"{}\", {}", id, newTitle, edited.startedAt());
        return edited;
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
            meeting = prepareUpload(meeting, folder);
            detectors.unloadIfUnused();
            Meeting.Transcription transcription = transcribe(meeting, folder);
            Meeting.NotesInfo notes = writeNotes(meeting, folder);
            store.find(id).ifPresent(m -> store.save(m.ready(transcription, notes)));
            if (transcription != null && preferences.meetings().deletesSpeechAudio()) deleteSpeechAudio(meeting, folder);
            log.info("Meeting {} processed: {} speech segments, notes {}", id,
                    transcription == null ? 0 : transcription.segments(), notes == null ? "skipped" : "written");
            if (notes != null) notion.afterProcessing(id);
        } catch (GpuRequiredException e) {
            log.error("Meeting {}: {}", id, e.getMessage(), e.getCause());
            store.find(id).ifPresent(m -> store.save(m.failed(e.getMessage())));
        } catch (Exception e) {
            log.error("Meeting {}: processing failed", id, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.find(id).ifPresent(m -> store.save(m.failed("Processing failed: " + message)));
        }
    }

    /** Transcribes the speech tracks and removes echoes; null (with a warning) when recognition is not installed. */
    private Meeting.Transcription transcribe(Meeting meeting, Path folder) throws IOException {
        List<Transcriber.Track> tracks = meeting.tracks().stream()
                .filter(t -> SPEECH_TRACKS.contains(t.kind()))
                .filter(t -> Files.isRegularFile(folder.resolve(t.file())))
                .map(t -> new Transcriber.Track(folder.resolve(t.file()), t.startOffsetMillis() / 1000.0, t.kind()))
                .toList();
        // Speech audio was deleted after an earlier run: keep that transcript
        if (tracks.isEmpty()) return meeting.transcription();
        Transcriber transcriber = transcriber(meeting.id(), folder);
        if (transcriber == null) {
            String warning = "Speech recognition is not installed (" + whisper.directory() + "), the transcript was skipped";
            log.warn("Meeting {}: {}", meeting.id(), warning);
            store.find(meeting.id()).ifPresent(m -> store.save(m.withWarning(warning)));
            return null;
        }
        Transcriber.Result recognised = transcriber.transcribe(tracks, progressSaver(meeting.id(), STEP_SPEECH));
        List<TranscriptSegment> withoutEchoes = EchoFilter.removeEchoes(recognised.segments());
        if (withoutEchoes.size() < recognised.segments().size()) {
            log.info("Meeting {}: {} echoed segments removed from the microphone track", meeting.id(),
                    recognised.segments().size() - withoutEchoes.size());
        }
        Transcriber.Result result = new Transcriber.Result(withoutEchoes, recognised.model(), recognised.backend(), recognised.languages());
        transcripts.write(folder, meeting.title(), result);
        return new Meeting.Transcription(result.model(), result.backend(), result.languages(), result.segments().size());
    }

    /** The configured speech recognition, Whisper when Parakeet is not installed, or null when neither is. */
    private Transcriber transcriber(String id, Path folder) {
        WhisperCppTranscriber whisperCpp = WhisperCppTranscriber.isInstalled(whisper)
                ? new WhisperCppTranscriber(whisper, json, folder.resolve("logs")) : null;
        if (preferences.meetings().speechEngine(speechEngine) == VisionTrackProperties.SpeechEngine.PARAKEET) {
            if (ParakeetTranscriber.isInstalled(parakeet.directory(), whisper)) {
                // Whisper, when installed, is the backup for pieces written in Cyrillic
                return new ParakeetTranscriber(parakeet.directory(), whisper,
                        () -> OnnxYoloDetector.preloadNativeLibraries(gpuLibraries.nativeDir(), gpuLibraries.nativePreload()),
                        whisperCpp);
            }
            log.warn("Meeting {}: Parakeet is not installed ({}), using Whisper", id, parakeet.directory());
        }
        return whisperCpp;
    }

    /** Writes notes.json and notes.md. Without the model, or when it fails, notes are skipped with a warning. */
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
                duration, segments, preferences.meetings().notesLanguage());
        String model = notesSettings.model().getFileName().toString().replaceFirst("(?i)\\.gguf$", "");
        store.find(meeting.id()).ifPresent(m -> store.save(m.processing(STEP_NOTES, 0)));
        List<LlamaServer.CudaDevice> gpus;
        try {
            gpus = LlamaServer.cudaDevices(server, notesSettings.cudaDir());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Writing notes was interrupted", e);
        }
        if (gpus.isEmpty()) throw new GpuRequiredException("Writing notes");
        String memory = notesMemoryProblem(gpus.getFirst(), Files.size(notesSettings.model()));
        if (memory != null) {
            warn(meeting.id(), NOTES_WARNING + memory);
            return null;
        }
        try (LlamaServer llama = LlamaServer.start(server, notesSettings.model(), notesSettings.contextTokens(),
                notesSettings.cudaDir(), folder.resolve("logs/llama.log"), json)) {
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

    /** Checks free VRAM first (model size plus context), so the user gets a clear message instead of a failed start. */
    static String notesMemoryProblem(LlamaServer.CudaDevice gpu, long modelBytes) {
        long neededMiB = modelBytes / (1024 * 1024) + NOTES_EXTRA_MIB;
        if (gpu.freeMiB() >= neededMiB) return null;
        return String.format(Locale.ROOT, "not enough free video memory for the notes model: %.1f GB free of %.1f GB, about %.1f GB "
                        + "needed. Close programs that use the graphics card (games, video editors, other AI apps) and press Process again.",
                gpu.freeMiB() / 1024.0, gpu.totalMiB() / 1024.0, neededMiB / 1024.0);
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

    /** Tracks that hold speech: the microphone, the system sound, or an uploaded recording with everyone on it. */
    private static final Set<String> SPEECH_TRACKS = Set.of(MeetingRecorder.MIC, MeetingRecorder.SYSTEM, RecordingImport.MIXED);

    /** An uploaded file becomes a playable copy and a speech track before anything else (once; kept on restart). */
    private Meeting prepareUpload(Meeting meeting, Path folder) throws IOException {
        Meeting.Track upload = meeting.track(RecordingImport.UPLOAD);
        if (upload == null) return meeting;
        store.save(meeting.processing(STEP_PREPARING, 0));
        List<Meeting.Track> tracks = RecordingImport.prepare(ffmpeg(), folder, upload, progressSaver(meeting.id(), STEP_PREPARING));
        Meeting prepared = store.find(meeting.id()).orElse(meeting).withTracks(tracks);
        store.save(prepared);
        log.info("Meeting {}: uploaded recording prepared, {} s", meeting.id(),
                Math.round(tracks.stream().mapToDouble(Meeting.Track::durationSeconds).max().orElse(0)));
        return prepared;
    }

    /** Deletes the speech WAV files; the recording, transcript and notes stay. */
    private void deleteSpeechAudio(Meeting meeting, Path folder) {
        Meeting.Track playback = meeting.playback();
        if (playback == null || !Files.isRegularFile(folder.resolve(playback.file()))) return;
        for (Meeting.Track track : meeting.tracks()) {
            if (!SPEECH_TRACKS.contains(track.kind())) continue;
            try {
                Files.deleteIfExists(folder.resolve(track.file()));
            } catch (IOException e) {
                log.warn("Meeting {}: cannot delete {}: {}", meeting.id(), track.file(), e.getMessage());
            }
        }
        log.info("Meeting {}: speech audio deleted after processing", meeting.id());
    }

    private Ffmpeg ffmpeg() {
        if (ffmpeg == null) ffmpeg = Ffmpeg.locate(ffmpegDir);
        return ffmpeg;
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
