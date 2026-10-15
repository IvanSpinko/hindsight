package org.example.visiontrack.export;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Identity of a run; it also fixes where its files go (the date partition comes from {@code startedAt}). */
public record RunInfo(UUID runId, String videoId, VideoRunSummary.Source source, Instant startedAt) {

    public static RunInfo start(String videoId, VideoRunSummary.Source source) {
        return new RunInfo(UUID.randomUUID(), videoId, source, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** {@code dt=YYYY-MM-DD/run_id=<uuid>}, shared by the detection parts and the summary. */
    public String partitionPath() {
        return "dt=" + startedAt.atZone(ZoneOffset.UTC).toLocalDate() + "/run_id=" + runId;
    }

    public String detectionsPart(int part) {
        return "detections/" + partitionPath() + "/part-%04d.jsonl.gz".formatted(part);
    }

    public String summary() {
        return "runs/" + partitionPath() + "/run.json";
    }
}
