package org.example.visiontrack.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Analyses in memory, each also saved as {@code <id>.json}.
 * One that was still running when the app closed is loaded as failed.
 */
@Repository
public class FileJobRepository implements JobRepository {

    static final String INTERRUPTED = "Interrupted: the app was closed during the analysis";

    private static final Logger log = LoggerFactory.getLogger(FileJobRepository.class);
    private static final Pattern FILE_ID = Pattern.compile("[0-9a-fA-F-]{36}");

    private final Path directory;
    private final ObjectMapper json;
    private final Map<String, AnalysisJob> jobs = new ConcurrentHashMap<>();

    @Autowired
    public FileJobRepository(VisionTrackProperties properties, ObjectMapper objectMapper) {
        this(properties.jobs().directory(), objectMapper);
    }

    FileJobRepository(Path directory, ObjectMapper objectMapper) {
        this.directory = directory.toAbsolutePath().normalize();
        this.json = objectMapper.copy()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    @Override
    public void save(AnalysisJob job) {
        if (!FILE_ID.matcher(job.id()).matches()) throw new IllegalArgumentException("Invalid job id: " + job.id());
        jobs.put(job.id(), job);
        write(job.id());
    }

    @Override
    public Optional<AnalysisJob> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<AnalysisJob> findAll() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(AnalysisJob::createdAt).reversed())
                .toList();
    }

    @Override
    public void update(String id, UnaryOperator<AnalysisJob> change) {
        if (jobs.computeIfPresent(id, (key, job) -> change.apply(job)) != null) write(id);
    }

    @Override
    public synchronized void delete(String id) {
        if (jobs.remove(id) == null) return;
        try {
            Files.deleteIfExists(file(id));
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", file(id), e.getMessage());
        }
    }

    private void load() {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(f -> {
                try {
                    AnalysisJob job = json.readValue(f.toFile(), AnalysisJob.class);
                    if (job.status() == AnalysisJob.JobStatus.QUEUED || job.status() == AnalysisJob.JobStatus.PROCESSING) {
                        jobs.put(job.id(), job.failed(INTERRUPTED));
                        write(job.id());
                    } else {
                        jobs.put(job.id(), job);
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("Skipping {}: {}", f, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Cannot list analyses in {}: {}", directory, e.getMessage());
        }
        if (!jobs.isEmpty()) log.info("Loaded {} analyses from {}", jobs.size(), directory);
    }

    /** Writes the current state; a deleted job is not written back. */
    private synchronized void write(String id) {
        AnalysisJob job = jobs.get(id);
        if (job == null) return;
        Path target = file(id);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            json.writeValue(temp.toFile(), job);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save " + target, e);
        }
    }

    private Path file(String id) {
        return directory.resolve(id + ".json");
    }
}
