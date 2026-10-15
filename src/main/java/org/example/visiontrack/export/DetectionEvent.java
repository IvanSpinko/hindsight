package org.example.visiontrack.export;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.Detection.BoundingBox;

import java.time.Instant;
import java.util.UUID;

/**
 * One detected object on one analysed frame: a line of {@code part-NNNN.jsonl.gz}.
 * The box is normalised to 0..1 of the analysed frame, so it does not depend on the frame size.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DetectionEvent(
        int schemaVersion,
        UUID runId,
        String videoId,
        long frameIndex,
        long timestampMs,
        String classLabel,
        float confidence,
        float bboxX,
        float bboxY,
        float bboxWidth,
        float bboxHeight,
        int frameWidth,
        int frameHeight,
        String modelName,
        String modelVersion,
        Instant detectedAt
) {

    public static final int SCHEMA_VERSION = 1;

    public static DetectionEvent of(RunInfo run, ModelInfo model, long frameIndex, long timestampMs,
                                    int frameWidth, int frameHeight, Detection detection, Instant detectedAt) {
        BoundingBox b = detection.box();
        float x1 = clamp(b.x1() / frameWidth);
        float y1 = clamp(b.y1() / frameHeight);
        float x2 = clamp(b.x2() / frameWidth);
        float y2 = clamp(b.y2() / frameHeight);
        return new DetectionEvent(SCHEMA_VERSION, run.runId(), run.videoId(), frameIndex, timestampMs,
                detection.label(), round(detection.confidence()),
                round(x1), round(y1), round(Math.max(0f, x2 - x1)), round(Math.max(0f, y2 - y1)),
                frameWidth, frameHeight, model.name(), model.version(), detectedAt);
    }

    private static float clamp(float value) {
        return Math.min(1f, Math.max(0f, value));
    }

    private static float round(float value) {
        return Math.round(value * 1_000_000f) / 1_000_000f;
    }
}
