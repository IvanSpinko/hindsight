package org.example.visiontrack.export;

import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Gzipped JSON Lines on the local disk, always on. Parts are written under {@code in-progress/} and moved
 * atomically to {@code outbox/} when closed, so the outbox only ever holds complete files.
 */
@Component
public class JsonlDetectionSink implements DetectionSink {

    private static final Logger log = LoggerFactory.getLogger(JsonlDetectionSink.class);

    private final Path inProgress;
    private final Path outbox;

    @Autowired
    public JsonlDetectionSink(VisionTrackProperties properties) {
        this(properties.export().directory());
    }

    JsonlDetectionSink(Path directory) {
        this.inProgress = directory.resolve("in-progress");
        this.outbox = directory.resolve("outbox");
    }

    public Path outbox() {
        return outbox;
    }

    @Override
    public RunRecorder open(RunInfo run) {
        return new Recorder(run);
    }

    private final class Recorder implements RunRecorder {

        private final RunInfo run;
        private int part;
        private Path partFile;
        private OutputStream stream;
        private boolean finished;

        Recorder(RunInfo run) {
            this.run = run;
        }

        @Override
        public synchronized void append(List<DetectionEvent> events) throws IOException {
            if (finished) throw new IllegalStateException("Run " + run.runId() + " is already finished");
            if (events.isEmpty()) return;
            if (stream == null) openPart();
            for (DetectionEvent event : events) {
                stream.write(ExportJson.MAPPER.writeValueAsBytes(event));
                stream.write('\n');
            }
        }

        @Override
        public synchronized void rollPart() throws IOException {
            if (stream == null) return;
            stream.close();
            stream = null;
            publish(partFile, run.detectionsPart(part));
            part++;
        }

        @Override
        public synchronized void finish(VideoRunSummary summary) throws IOException {
            if (finished) return;
            rollPart();
            finished = true;
            Path file = inProgress.resolve(run.summary());
            Files.createDirectories(file.getParent());
            Files.write(file, ExportJson.MAPPER.writeValueAsBytes(summary));
            publish(file, run.summary());
        }

        private void openPart() throws IOException {
            partFile = inProgress.resolve(run.detectionsPart(part));
            Files.createDirectories(partFile.getParent());
            stream = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(partFile)), 64 * 1024);
        }
    }

    private void publish(Path file, String relative) throws IOException {
        Path target = outbox.resolve(relative);
        Files.createDirectories(target.getParent());
        try {
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("Export file ready: {}", relative);
        deleteEmptyParents(file.getParent());
    }

    private void deleteEmptyParents(Path dir) {
        while (dir != null && !dir.equals(inProgress)) {
            try {
                Files.deleteIfExists(dir);
            } catch (IOException e) {
                return; // not empty
            }
            dir = dir.getParent();
        }
    }
}
