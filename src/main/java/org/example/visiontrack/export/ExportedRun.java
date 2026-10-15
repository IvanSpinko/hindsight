package org.example.visiontrack.export;

import org.example.visiontrack.analysis.FrameObserver;
import org.example.visiontrack.detection.Detection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the detections of one run into a {@link DetectionSink}. Export problems are logged and switch the
 * export off for this run; they never fail the analysis itself.
 */
public class ExportedRun implements FrameObserver {

    private static final Logger log = LoggerFactory.getLogger(ExportedRun.class);

    private final RunInfo run;
    private final ModelInfo model;
    private final long startedNanos = System.nanoTime();
    private DetectionSink.RunRecorder recorder;

    private int width;
    private int height;
    private double frameRate;
    private long durationMicros;
    private long lastTimestampMs = -1;
    private long frames;
    private long detections;
    private boolean finished;

    ExportedRun(RunInfo run, ModelInfo model, DetectionSink sink) {
        this.run = run;
        this.model = model;
        try {
            this.recorder = sink.open(run);
        } catch (IOException | RuntimeException e) {
            log.warn("Run {}: detection export disabled, cannot open the export: {}", run.runId(), e.toString());
        }
    }

    public RunInfo run() {
        return run;
    }

    @Override
    public synchronized void onStart(int width, int height, double frameRate, long durationMicros) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.durationMicros = durationMicros;
    }

    @Override
    public synchronized void onFrame(long frameIndex, long timestampMicros, List<Detection> detections) {
        record(frameIndex, timestampMicros / 1_000, width, height, detections);
    }

    @Override
    public synchronized void onEnd(long decodedFrames) {
        frames = Math.max(frames, decodedFrames);
    }

    /** One analysed frame; frames of a live session may differ in size, so the size comes with each frame. */
    public synchronized void record(long frameIndex, long timestampMs, int frameWidth, int frameHeight,
                                    List<Detection> found) {
        if (finished) return;
        frames = Math.max(frames, frameIndex + 1);
        lastTimestampMs = Math.max(lastTimestampMs, timestampMs);
        if (recorder == null || found.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return;

        Instant now = Instant.now();
        List<DetectionEvent> events = new ArrayList<>(found.size());
        for (Detection detection : found) {
            events.add(DetectionEvent.of(run, model, frameIndex, timestampMs, frameWidth, frameHeight, detection, now));
        }
        try {
            recorder.append(events);
            detections += events.size();
        } catch (IOException | RuntimeException e) {
            disable("cannot write detections", e);
        }
    }

    public synchronized void rollPart() {
        if (finished || recorder == null) return;
        try {
            recorder.rollPart();
        } catch (IOException | RuntimeException e) {
            disable("cannot close a part file", e);
        }
    }

    /** Writes the run summary; later calls are ignored, so it is safe to call from a {@code finally} block. */
    public synchronized void finish(VideoRunSummary.Status status) {
        if (finished) return;
        finished = true;
        if (recorder == null) return;

        Long durationMs = durationMicros > 0 ? Long.valueOf(durationMicros / 1_000)
                : lastTimestampMs >= 0 ? Long.valueOf(lastTimestampMs) : null;
        Double fps = frameRate > 0 ? Double.valueOf(frameRate)
                : durationMs != null && durationMs > 0 && frames > 1 ? Double.valueOf(frames * 1_000.0 / durationMs)
                : null;
        if (fps != null) fps = Math.round(fps * 100) / 100.0;

        VideoRunSummary summary = new VideoRunSummary(DetectionEvent.SCHEMA_VERSION, run.runId(),
                run.videoId(), run.source(), durationMs, fps, frames, detections, model.classes(),
                (System.nanoTime() - startedNanos) / 1_000_000, run.startedAt(), Instant.now(), model.appVersion(),
                status);
        try {
            recorder.finish(summary);
            log.info("Run {} exported: {} detections, status {}", run.runId(), detections, status.json());
        } catch (IOException | RuntimeException e) {
            log.warn("Run {}: cannot write the run summary: {}", run.runId(), e.toString());
        }
    }

    private void disable(String what, Exception e) {
        log.warn("Run {}: detection export disabled, {}: {}", run.runId(), what, e.toString());
        recorder = null;
    }
}
