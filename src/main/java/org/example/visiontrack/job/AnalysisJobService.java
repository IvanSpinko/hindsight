package org.example.visiontrack.job;

import org.example.visiontrack.analysis.AnalysisResult;
import org.example.visiontrack.analysis.ProgressListener;
import org.example.visiontrack.analysis.VideoAnalyzer;
import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.render.AnnotatedVideoRenderer;
import org.example.visiontrack.storage.VideoStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AnalysisJobService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobService.class);

    private final JobRepository jobs;
    private final VideoStorage storage;
    private final VideoAnalyzer analyzer;
    private final AnnotatedVideoRenderer renderer;
    private final TaskExecutor executor;
    private final VisionTrackProperties.Storage storageSettings;
    private final boolean renderAnnotatedVideo;

    public AnalysisJobService(JobRepository jobs, VideoStorage storage, VideoAnalyzer analyzer,
                              AnnotatedVideoRenderer renderer,
                              @Qualifier("analysisExecutor") TaskExecutor executor,
                              VisionTrackProperties properties) {
        this.jobs = jobs;
        this.storage = storage;
        this.analyzer = analyzer;
        this.renderer = renderer;
        this.executor = executor;
        this.storageSettings = properties.storage();
        this.renderAnnotatedVideo = properties.analysis().annotatedVideo();
    }

    public AnalysisJob submit(String originalFilename, InputStream content) {
        String fileName = StringUtils.getFilename(StringUtils.cleanPath(originalFilename == null ? "" : originalFilename));
        if (!StringUtils.hasText(fileName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The upload has no file name");
        }
        validateExtension(fileName);

        String id = UUID.randomUUID().toString();
        String key;
        try {
            key = storage.store(id, fileName, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store upload", e);
        }

        AnalysisJob job = AnalysisJob.queued(id, fileName, key);
        jobs.save(job);
        try {
            executor.execute(() -> run(id));
        } catch (TaskRejectedException e) {
            jobs.delete(id);
            storage.delete(key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The analysis queue is full, please try again later");
        }
        log.info("Queued job {} for {}", id, fileName);
        return job;
    }

    public Optional<AnalysisJob> find(String id) {
        return jobs.findById(id);
    }

    public List<AnalysisJob> findAll() {
        return jobs.findAll();
    }

    private void run(String id) {
        AnalysisJob job = jobs.findById(id).orElse(null);
        if (job == null) return;
        jobs.update(id, AnalysisJob::started);

        AtomicInteger lastPercent = new AtomicInteger();
        ProgressListener progress = fraction -> {
            int percent = (int) (fraction * 99);
            if (percent > lastPercent.get()) {
                lastPercent.set(percent);
                jobs.update(id, j -> j.withProgress(percent));
            }
        };
        double analysisShare = renderAnnotatedVideo ? 0.75 : 1.0;

        try {
            Path video = storage.localPath(job.storageKey());
            AnalysisResult result = analyzer.analyze(video, progress.range(0, analysisShare));
            String annotatedKey = renderAnnotatedVideo
                    ? renderAnnotatedVideo(id, video, result, progress.range(analysisShare, 1.0))
                    : null;
            jobs.update(id, j -> j.completed(result, annotatedKey));
            log.info("Job {} completed: {} objects", id, result.totalObjects());
        } catch (Exception e) {
            log.error("Job {} failed", id, e);
            jobs.update(id, j -> j.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (storageSettings.deleteAfterProcessing()) {
                storage.delete(job.storageKey());
            }
        }
    }

    private String renderAnnotatedVideo(String id, Path video, AnalysisResult result, ProgressListener progress) {
        String key = id + "-annotated.mp4";
        Path temp = null;
        try {
            temp = Files.createTempFile("visiontrack-" + id, ".mp4");
            renderer.render(video, result, temp, progress);
            storage.storeFile(key, temp);
            return key;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Job interrupted");
            log.warn("Job {}: could not render the annotated video", id, e);
            return null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public Optional<Path> annotatedVideo(String id) {
        return jobs.findById(id)
                .map(AnalysisJob::annotatedVideoKey)
                .map(key -> {
                    try {
                        return storage.localPath(key);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .filter(Files::isRegularFile);
    }

    private void validateExtension(String fileName) {
        String extension = StringUtils.getFilenameExtension(fileName);
        List<String> allowed = storageSettings.allowedExtensions();
        if (extension == null || !allowed.contains(extension.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported file type. Allowed: " + String.join(", ", allowed));
        }
    }
}
