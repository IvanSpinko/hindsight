package org.example.visiontrack.export.cloud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.stream.Stream;

/**
 * Sends finished files from {@code outbox/} to the cloud and moves them to {@code uploaded/} (same relative
 * path). A failed file waits 5 s, 10 s, 20 s ... up to 15 min (with jitter) before its next try; the app keeps
 * working offline and the outbox simply drains when the network or credentials are back.
 */
public class OutboxUploader {

    private static final Logger log = LoggerFactory.getLogger(OutboxUploader.class);
    static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    static final Duration MAX_RETRY = Duration.ofMinutes(15);

    private final Path outbox;
    private final Path uploaded;
    private final RunUploader uploader;
    private final String keyPrefix;
    private final Clock clock;
    private final DoubleSupplier jitter;
    private final Map<Path, Backoff> failures = new ConcurrentHashMap<>();

    public OutboxUploader(Path exportDirectory, RunUploader uploader, String keyPrefix) {
        this(exportDirectory, uploader, keyPrefix, Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble(0.8, 1.2));
    }

    OutboxUploader(Path exportDirectory, RunUploader uploader, String keyPrefix, Clock clock, DoubleSupplier jitter) {
        this.outbox = exportDirectory.resolve("outbox");
        this.uploaded = exportDirectory.resolve("uploaded");
        this.uploader = uploader;
        this.keyPrefix = keyPrefix;
        this.clock = clock;
        this.jitter = jitter;
    }

    record Backoff(int attempts, Instant nextAttempt) {}

    /** One pass over the outbox; detection parts go before run summaries. Returns the number of files sent. */
    public int uploadPending() {
        int sent = 0;
        for (Path file : pendingFiles()) {
            Backoff backoff = failures.get(file);
            if (backoff != null && clock.instant().isBefore(backoff.nextAttempt())) continue;

            String key = keyFor(file);
            try {
                RunUploader.Result result = uploader.upload(key, file);
                markUploaded(file);
                failures.remove(file);
                sent++;
                if (result == RunUploader.Result.ALREADY_PRESENT) {
                    log.info("Already in the cloud, not uploaded again: {}", uploader.describe(key));
                } else {
                    log.debug("Uploaded {}", uploader.describe(key));
                }
            } catch (RuntimeException e) {
                Backoff next = nextBackoff(backoff);
                failures.put(file, next);
                log.warn("Upload of {} failed (attempt {}), next try in {} s: {}", uploader.describe(key),
                        next.attempts(), Duration.between(clock.instant(), next.nextAttempt()).toSeconds(), e.toString());
            }
        }
        if (sent > 0) log.info("Uploaded {} export file(s) to the cloud", sent);
        return sent;
    }

    String keyFor(Path file) {
        return keyPrefix + outbox.relativize(file).toString().replace('\\', '/');
    }

    Backoff backoffOf(Path file) {
        return failures.get(file);
    }

    private Backoff nextBackoff(Backoff previous) {
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        long base = FIRST_RETRY.toMillis() << Math.min(attempts - 1, 20);
        long delay = (long) (Math.min(base, MAX_RETRY.toMillis()) * jitter.getAsDouble());
        return new Backoff(attempts, clock.instant().plusMillis(delay));
    }

    private List<Path> pendingFiles() {
        if (!Files.isDirectory(outbox)) return List.of();
        try (Stream<Path> walk = Files.walk(outbox)) {
            return walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString().equals("run.json"))
                            .thenComparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            log.warn("Cannot list the export outbox {}: {}", outbox, e.toString());
            return List.of();
        }
    }

    private void markUploaded(Path file) {
        Path target = uploaded.resolve(outbox.relativize(file));
        try {
            Files.createDirectories(target.getParent());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            deleteEmptyParents(file.getParent());
        } catch (IOException e) {
            // Next pass uploads it again; the conditional PUT turns that into ALREADY_PRESENT
            log.warn("Uploaded but cannot move {} out of the outbox: {}", file, e.toString());
        }
    }

    private void deleteEmptyParents(Path dir) {
        while (dir != null && !dir.equals(outbox)) {
            try {
                Files.deleteIfExists(dir);
            } catch (IOException e) {
                return; // not empty
            }
            dir = dir.getParent();
        }
    }
}
