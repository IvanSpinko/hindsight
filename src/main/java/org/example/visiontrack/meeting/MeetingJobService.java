package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.example.visiontrack.analysis.ProgressListener;
import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes recorded meetings in the background, one at a time: finds the slides now; speech recognition,
 * notes and the Notion export are added as further steps. Progress is saved in {@code meeting.json}, and
 * meetings interrupted by a restart are processed again on the next start.
 */
@Service
public class MeetingJobService {

    static final String STEP_WAITING = "Waiting";
    static final String STEP_SLIDES = "Finding slides";

    private static final Logger log = LoggerFactory.getLogger(MeetingJobService.class);

    private final MeetingStore store;
    private final SlideExtractor slides;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "meeting-processing");
        thread.setDaemon(true);
        return thread;
    });

    public MeetingJobService(MeetingStore store, VisionTrackProperties properties, ObjectMapper objectMapper) {
        this.store = store;
        VisionTrackProperties.Meetings settings = properties.meetings();
        this.slides = new SlideExtractor(
                new SlideChangeDetector.Settings(settings.slideStableSeconds(), settings.slideMinChangedShare()), objectMapper);
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
            Meeting.Track screen = meeting.screenTrack();
            List<SlideExtractor.Slide> found = List.of();
            if (screen != null && Files.isRegularFile(folder.resolve(screen.file()))) {
                found = slides.extract(folder.resolve(screen.file()), folder, progressSaver(id, STEP_SLIDES));
            }
            int count = found.size();
            store.find(id).ifPresent(m -> store.save(m.ready(count)));
            log.info("Meeting {} processed: {} slides", id, count);
        } catch (Exception e) {
            log.error("Meeting {}: processing failed", id, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.find(id).ifPresent(m -> store.save(m.failed("Processing failed: " + message)));
        }
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

    public List<SlideExtractor.Slide> slides(String id) throws IOException {
        Path folder = store.folder(id).orElseThrow(() -> new IllegalArgumentException("Unknown meeting " + id));
        return slides.read(folder);
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
