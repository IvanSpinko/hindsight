package org.example.visiontrack.export;

import jakarta.annotation.PreDestroy;
import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.detection.Detection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Live camera sessions as export runs: a part is closed every {@code live-batch-seconds},
 * and a session ends when the camera stops or sends nothing for a while.
 */
@Component
public class LiveExportSessions {

    private static final Logger log = LoggerFactory.getLogger(LiveExportSessions.class);
    private static final int MAX_SESSIONS = 16;

    private final ExportService exports;
    private final long batchNanos;
    private final long idleNanos;
    private final LongSupplier nanoClock;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    public LiveExportSessions(ExportService exports, VisionTrackProperties properties) {
        this(exports, Duration.ofSeconds(properties.export().liveBatchSeconds()),
                Duration.ofSeconds(properties.export().liveIdleTimeoutSeconds()), System::nanoTime);
    }

    LiveExportSessions(ExportService exports, Duration batch, Duration idle, LongSupplier nanoClock) {
        this.exports = exports;
        this.batchNanos = batch.toNanos();
        this.idleNanos = idle.toNanos();
        this.nanoClock = nanoClock;
    }

    /** Ignores frames without a valid session id (old pages, scripts) so the endpoint keeps working as before. */
    public void record(String sessionId, int width, int height, List<Detection> detections) {
        UUID id = parse(sessionId);
        if (id == null) return;
        long now = nanoClock.getAsLong();
        Session session = sessions.get(id);
        if (session == null) {
            if (sessions.size() >= MAX_SESSIONS) {
                log.warn("Too many live sessions, frames of {} are not exported", id);
                return;
            }
            session = sessions.computeIfAbsent(id, key -> new Session(
                    exports.start("live-" + key, VideoRunSummary.Source.LIVE_CAMERA), now));
        }
        session.record(now, width, height, detections);
    }

    public void stop(String sessionId) {
        UUID id = parse(sessionId);
        Session session = id == null ? null : sessions.remove(id);
        if (session != null) session.run.finish(VideoRunSummary.Status.COMPLETED);
    }

    /** Closes parts older than the batch interval and ends idle sessions. */
    @Scheduled(fixedDelay = 1_000)
    public void tick() {
        long now = nanoClock.getAsLong();
        sessions.forEach((id, session) -> {
            if (now - session.lastFrame() >= idleNanos) {
                if (sessions.remove(id, session)) {
                    log.info("Live session {} ended after inactivity", id);
                    session.run.finish(VideoRunSummary.Status.COMPLETED);
                }
            } else if (now - session.partStarted() >= batchNanos) {
                session.roll(now);
            }
        });
    }

    @PreDestroy
    public void finishAll() {
        sessions.values().forEach(s -> s.run.finish(VideoRunSummary.Status.COMPLETED));
        sessions.clear();
    }

    int activeSessions() {
        return sessions.size();
    }

    private static UUID parse(String sessionId) {
        if (sessionId == null || sessionId.length() != 36) return null;
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class Session {
        final ExportedRun run;
        final long started;
        long frames;
        long lastFrame;
        long partStarted;

        Session(ExportedRun run, long now) {
            this.run = run;
            this.started = now;
            this.lastFrame = now;
            this.partStarted = now;
        }

        synchronized void record(long now, int width, int height, List<Detection> detections) {
            run.record(frames++, (now - started) / 1_000_000, width, height, detections);
            lastFrame = now;
        }

        synchronized void roll(long now) {
            run.rollPart();
            partStarted = now;
        }

        synchronized long lastFrame() {
            return lastFrame;
        }

        synchronized long partStarted() {
            return partStarted;
        }
    }
}
