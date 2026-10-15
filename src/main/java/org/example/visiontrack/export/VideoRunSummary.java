package org.example.visiontrack.export;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** One analysis run (an uploaded video or a live camera session): the {@code run.json} file. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VideoRunSummary(
        int schemaVersion,
        UUID runId,
        String videoId,
        Source source,
        Long durationMs,
        Double fps,
        long totalFrames,
        long totalDetections,
        List<String> requestedClasses,
        long processingTimeMs,
        Instant startedAt,
        Instant finishedAt,
        String appVersion,
        Status status
) {

    public enum Source {
        UPLOAD, LIVE_CAMERA;

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Status {
        COMPLETED, FAILED, CANCELLED;

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
