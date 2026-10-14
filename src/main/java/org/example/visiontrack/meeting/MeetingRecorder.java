package org.example.visiontrack.meeting;

import jakarta.annotation.PreDestroy;
import org.example.visiontrack.GpuRequiredException;
import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Records one meeting at a time: the screen and the microphone, each in its own ffmpeg process writing
 * segments, so a crash loses at most the unfinished end of a segment. On stop the segments become one
 * {@code recording.mp4} (screen and sound, for watching) plus {@code audio/mic.wav} for speech recognition,
 * and the meeting is handed to {@link MeetingJobService}. Nothing is analysed while recording, to keep the
 * meeting itself smooth.
 */
@Service
public class MeetingRecorder {

    public record StartRequest(String title, Integer monitor, String microphone) {}

    static final String SCREEN = "screen";
    static final String MIC = "mic";
    static final String RECORDING_FILE = "recording.mp4";

    private static final Logger log = LoggerFactory.getLogger(MeetingRecorder.class);
    private static final Duration DEVICE_OPEN_TIMEOUT = Duration.ofMillis(2500);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

    private record Active(String id, Path folder, CaptureProcess screen, CaptureProcess mic, Set<String> reported) {}

    private final MeetingStore store;
    private final VisionTrackProperties.Meetings settings;
    private final ApplicationEventPublisher events;
    private final MeetingJobService jobs;
    private final ScheduledExecutorService watchdog;

    private volatile Ffmpeg ffmpeg;
    private volatile CaptureDevices devices;
    private volatile Active active;

    public MeetingRecorder(MeetingStore store, VisionTrackProperties properties, ApplicationEventPublisher events,
                           MeetingJobService jobs) {
        this.store = store;
        this.jobs = jobs;
        this.settings = properties.meetings();
        this.events = events;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "meeting-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleWithFixedDelay(this::checkCaptures, 2, 2, TimeUnit.SECONDS);
    }

    Ffmpeg ffmpeg() {
        Ffmpeg current = ffmpeg;
        if (current == null) {
            synchronized (this) {
                if (ffmpeg == null) ffmpeg = Ffmpeg.locate(settings.ffmpegDir());
                current = ffmpeg;
            }
        }
        return current;
    }

    /** Cached device list; {@code refresh} probes again (not while recording, the devices are busy then). */
    public CaptureDevices devices(boolean refresh) {
        CaptureDevices current = devices;
        if (current == null || (refresh && active == null)) {
            current = CaptureDevices.detect(ffmpeg());
            devices = current;
        }
        return current;
    }

    public Optional<String> activeId() {
        Active current = active;
        return current == null ? Optional.empty() : Optional.of(current.id());
    }

    public synchronized Meeting start(StartRequest request) {
        if (active != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A recording is already running");
        }
        CaptureDevices available = devices(false);
        int monitorIndex = request.monitor() == null ? 0 : request.monitor();
        CaptureDevices.Monitor monitor = available.monitors().stream()
                .filter(m -> m.index() == monitorIndex).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Monitor not found: " + (monitorIndex + 1)));
        String microphone = chooseMicrophone(request.microphone(), available.microphones());

        String id = UUID.randomUUID().toString();
        String title = StringUtils.hasText(request.title()) ? request.title().strip() : MeetingStore.DEFAULT_TITLE;
        Path folder;
        try {
            folder = store.createFolder(id, title, LocalDateTime.now());
            Files.createDirectories(folder.resolve("video"));
            Files.createDirectories(folder.resolve("audio"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the meeting folder in " + store.root(), e);
        }

        long startNanos = System.nanoTime();
        CaptureProcess screen = null;
        CaptureProcess mic = null;
        try {
            // ddagrab is only offered when probing found the monitor (width > 0)
            ScreenCapture.Method method = monitor.width() > 0 ? ScreenCapture.Method.DDAGRAB : ScreenCapture.Method.GDIGRAB;
            screen = startScreen(method, monitor.index(), folder);
            mic = startMic(microphone, folder);

            boolean screenOk = screen.survives(DEVICE_OPEN_TIMEOUT);
            if (!mic.survives(Duration.ZERO)) {
                throw new CaptureFailure("Microphone \"" + microphone + "\" could not be opened. Is it connected and not used exclusively by another app? (" + mic.errorTail() + ")");
            }
            if (!screenOk && method == ScreenCapture.Method.DDAGRAB) {
                log.warn("ddagrab failed ({}), falling back to gdigrab", screen.errorTail());
                method = ScreenCapture.Method.GDIGRAB;
                deleteParts(folder.resolve("video"), SCREEN);
                screen = startScreen(method, monitor.index(), folder);
                screenOk = screen.survives(DEVICE_OPEN_TIMEOUT);
            }
            if (!screenOk) {
                String error = screen.errorTail();
                // The screen is encoded by NVENC: without an NVIDIA GPU ffmpeg fails in h264_nvenc or nvcuda.dll
                if (error.toLowerCase(Locale.ROOT).matches("(?s).*(nvenc|nvcuda|cuda).*")) {
                    throw new CaptureFailure(GpuRequiredException.message("Screen recording") + " (" + error + ")");
                }
                throw new CaptureFailure("Screen capture could not start (" + error + ")");
            }

            List<Meeting.Track> tracks = List.of(
                    new Meeting.Track(SCREEN, "video/screen.mp4", offsetMillis(startNanos, screen), 0),
                    new Meeting.Track(MIC, "audio/mic.wav", offsetMillis(startNanos, mic), 0));
            Meeting meeting = Meeting.recording(id, title, method.name().toLowerCase() + " · " + monitor.name(), microphone,
                    tracks, List.of(screen.pid(), mic.pid()));
            store.save(meeting);
            active = new Active(id, folder, screen, mic, new HashSet<>());
            log.info("Recording meeting {} into {} ({}, {})", id, folder, meeting.screenCapture(), microphone);
            events.publishEvent(new RecordingStateChanged(true, meeting.startedAt()));
            return meeting;
        } catch (CaptureFailure | InterruptedException | IOException e) {
            if (screen != null) screen.stop(STOP_TIMEOUT);
            if (mic != null) mic.stop(STOP_TIMEOUT);
            deleteQuietly(folder);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Could not start recording: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    private static String chooseMicrophone(String requested, List<String> microphones) {
        if (microphones.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No microphone found. Connect one and press Refresh.");
        }
        if (!StringUtils.hasText(requested)) return microphones.getFirst();
        if (!microphones.contains(requested)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Microphone not found: " + requested);
        }
        return requested;
    }

    private CaptureProcess startScreen(ScreenCapture.Method method, int monitor, Path folder) throws IOException {
        List<String> args = ScreenCapture.args(method, monitor, settings.screenFps(), folder.resolve("video"),
                settings.segmentSeconds(), maxSeconds());
        return CaptureProcess.start(SCREEN, ffmpeg().executable(), args, folder.resolve("logs/screen.log"));
    }

    private CaptureProcess startMic(String device, Path folder) throws IOException {
        List<String> args = AudioCapture.args(device, folder.resolve("audio"), MIC, settings.segmentSeconds(), maxSeconds());
        return CaptureProcess.start(MIC, ffmpeg().executable(), args, folder.resolve("logs/mic.log"));
    }

    private int maxSeconds() {
        return settings.maxRecordingMinutes() * 60;
    }

    private static long offsetMillis(long startNanos, CaptureProcess process) {
        return Math.max(0, (process.startedAtNanos() - startNanos) / 1_000_000);
    }

    public synchronized Meeting stop(String id) {
        Active current = active;
        if (current == null || !current.id().equals(id)) {
            Meeting meeting = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found"));
            if (meeting.status() == Meeting.Status.RECORDING) {
                stopOrphanedCaptures(meeting);
                return finish(meeting, store.folder(id).orElseThrow(), true);
            }
            return meeting;
        }
        current.screen().stop(STOP_TIMEOUT);
        current.mic().stop(STOP_TIMEOUT);
        active = null;
        Meeting meeting = store.find(id).orElseThrow();
        try {
            return finish(meeting, current.folder(), false);
        } finally {
            events.publishEvent(new RecordingStateChanged(false, meeting.startedAt()));
        }
    }

    /**
     * Joins the segments of every track, records durations and marks the meeting as recorded.
     * A {@code recovered} meeting was cut off by a crash; it ends where its last segment ends.
     */
    private Meeting finish(Meeting meeting, Path folder, boolean recovered) {
        if (recovered) meeting = meeting.withWarning("Recovered: the app was closed during the recording");
        Instant stoppedAt = Instant.now();
        List<Meeting.Track> tracks = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Meeting.Track track : meeting.tracks()) {
            Path output = folder.resolve(track.file());
            try {
                List<Path> parts = parts(output.getParent(), track.kind());
                if (parts.isEmpty()) {
                    problems.add("No " + track.kind() + " data was recorded");
                    continue;
                }
                if (recovered) stoppedAt = Files.getLastModifiedTime(parts.getLast()).toInstant();
                ffmpeg().concat(parts, output);
                parts.forEach(MeetingRecorder::deleteQuietly);
                tracks.add(new Meeting.Track(track.kind(), track.file(), track.startOffsetMillis(), ffmpeg().durationSeconds(output)));
            } catch (IOException e) {
                log.warn("Meeting {}: cannot join the {} parts: {}", meeting.id(), track.kind(), e.getMessage());
                problems.add("Could not join the " + track.kind() + " parts (kept in the folder): " + e.getMessage());
            }
        }
        tracks = combineIntoRecording(meeting.id(), folder, tracks, problems);
        Meeting result;
        if (tracks.isEmpty()) {
            result = meeting.failed(String.join("; ", problems));
        } else {
            result = meeting.recorded(stoppedAt, tracks);
            for (String problem : problems) result = result.withWarning(problem);
        }
        store.save(result);
        log.info("Meeting {} {}: {}", result.id(), result.status().name().toLowerCase(),
                result.tracks().stream().map(t -> "%s %.0f s".formatted(t.kind(), t.durationSeconds())).toList());
        if (result.status() == Meeting.Status.RECORDED) jobs.submit(result.id());
        return result;
    }

    /**
     * Replaces the joined screen video with {@code recording.mp4} that also carries the microphone, so the
     * user gets one file to watch. {@code audio/mic.wav} stays for speech recognition.
     */
    private List<Meeting.Track> combineIntoRecording(String id, Path folder, List<Meeting.Track> tracks, List<String> problems) {
        Meeting.Track screen = tracks.stream().filter(t -> t.kind().equals(SCREEN)).findFirst().orElse(null);
        if (screen == null) return tracks;
        Meeting.Track mic = tracks.stream().filter(t -> t.kind().equals(MIC)).findFirst().orElse(null);
        Path video = folder.resolve(screen.file());
        try {
            double offset = mic == null ? 0 : (mic.startOffsetMillis() - screen.startOffsetMillis()) / 1000.0;
            ffmpeg().mux(video, mic == null ? null : folder.resolve(mic.file()), offset, folder.resolve(RECORDING_FILE));
            deleteQuietly(video.getParent());
            return tracks.stream()
                    .map(t -> t == screen ? new Meeting.Track(SCREEN, RECORDING_FILE, t.startOffsetMillis(), t.durationSeconds()) : t)
                    .toList();
        } catch (IOException e) {
            log.warn("Meeting {}: cannot create {}: {}", id, RECORDING_FILE, e.getMessage());
            problems.add("Could not combine screen and sound into " + RECORDING_FILE + "; the separate files are kept");
            return tracks;
        }
    }

    /** Kills ffmpeg captures left running by a crashed app, so their parts are complete before joining. */
    private static void stopOrphanedCaptures(Meeting meeting) {
        for (long pid : meeting.capturePids()) {
            ProcessHandle.of(pid)
                    .filter(p -> p.info().command().map(c -> Path.of(c).getFileName().toString().toLowerCase().startsWith("ffmpeg")).orElse(false))
                    .filter(p -> p.info().startInstant().map(t -> !t.isBefore(meeting.startedAt().minusSeconds(60))).orElse(true))
                    .ifPresent(p -> {
                        log.info("Meeting {}: stopping orphaned capture process {}", meeting.id(), pid);
                        p.destroyForcibly();
                        p.onExit().completeOnTimeout(p, 5, TimeUnit.SECONDS).join();
                    });
        }
    }

    private static List<Path> parts(Path directory, String prefix) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> {
                String name = f.getFileName().toString();
                return name.startsWith(prefix + "-") && name.endsWith("." + CaptureArgs.SEGMENT_EXTENSION);
            }).sorted().toList();
        }
    }

    /** Flags a capture that died mid-recording (device unplugged, display mode change) so the UI can show it. */
    private void checkCaptures() {
        Active current = active;
        if (current == null) return;
        for (CaptureProcess process : List.of(current.screen(), current.mic())) {
            if (process.diedUnexpectedly() && current.reported().add(process.name())) {
                String warning = (process.name().equals(SCREEN) ? "Screen" : "Microphone")
                        + " capture stopped during the recording: " + process.errorTail();
                log.warn("Meeting {}: {}", current.id(), warning);
                synchronized (this) {
                    if (active == current) store.find(current.id()).ifPresent(m -> store.save(m.withWarning(warning)));
                }
            }
        }
    }

    /** Finishes recordings left unfinished by a crash or a forced shutdown. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        Thread.ofVirtual().name("meeting-recovery").start(() -> {
            for (Meeting meeting : store.findAll()) {
                if (meeting.status() == Meeting.Status.RECORDING && activeId().filter(meeting.id()::equals).isEmpty()) {
                    log.info("Recovering meeting {} interrupted during recording", meeting.id());
                    try {
                        stop(meeting.id());
                    } catch (RuntimeException e) {
                        log.warn("Could not recover meeting {}: {}", meeting.id(), e.getMessage());
                    }
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        Active current = active;
        if (current != null) {
            log.info("Application is stopping: finishing meeting {}", current.id());
            stop(current.id());
        }
        watchdog.shutdownNow();
    }

    private static void deleteParts(Path directory, String prefix) throws IOException {
        for (Path part : parts(directory, prefix)) deleteQuietly(part);
    }

    private static void deleteQuietly(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static final class CaptureFailure extends Exception {
        CaptureFailure(String message) {
            super(message);
        }
    }
}
