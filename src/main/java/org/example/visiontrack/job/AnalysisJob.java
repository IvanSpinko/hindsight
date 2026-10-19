package org.example.visiontrack.job;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.example.visiontrack.analysis.AnalysisResult;
import org.example.visiontrack.export.RunInfo;

import java.time.Instant;

/** {@code exportRun} names the exported detection run of this analysis, so its cloud upload can be followed. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisJob(
        String id,
        String fileName,
        String storageKey,
        JobStatus status,
        int progressPercent,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String error,
        AnalysisResult result,
        String annotatedVideoKey,
        RunInfo exportRun
) {

    public enum JobStatus { QUEUED, PROCESSING, COMPLETED, FAILED }

    public static AnalysisJob queued(String id, String fileName, String storageKey) {
        return new AnalysisJob(id, fileName, storageKey, JobStatus.QUEUED, 0, Instant.now(), null, null, null, null, null, null);
    }

    public AnalysisJob started() {
        return new AnalysisJob(id, fileName, storageKey, JobStatus.PROCESSING, 0, createdAt, Instant.now(), null, null, null, null, exportRun);
    }

    public AnalysisJob withProgress(int percent) {
        return new AnalysisJob(id, fileName, storageKey, status, percent, createdAt, startedAt, finishedAt, error, result, annotatedVideoKey, exportRun);
    }

    public AnalysisJob withExportRun(RunInfo run) {
        return new AnalysisJob(id, fileName, storageKey, status, progressPercent, createdAt, startedAt, finishedAt, error, result, annotatedVideoKey, run);
    }

    public AnalysisJob completed(AnalysisResult result, String annotatedVideoKey) {
        return new AnalysisJob(id, fileName, storageKey, JobStatus.COMPLETED, 100, createdAt, startedAt, Instant.now(), null, result, annotatedVideoKey, exportRun);
    }

    public AnalysisJob failed(String error) {
        return new AnalysisJob(id, fileName, storageKey, JobStatus.FAILED, progressPercent, createdAt, startedAt, Instant.now(), error, null, null, exportRun);
    }
}
