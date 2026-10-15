package org.example.visiontrack.export;

import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.detection.OnnxYoloDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Starts exported runs; knows the model and app versions that go into every record. */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final DetectionSink sink;
    private final OnnxYoloDetector detector;
    private final Path modelPath;
    private final ObjectProvider<BuildProperties> buildProperties;
    private volatile ModelInfo model;

    public ExportService(DetectionSink sink, OnnxYoloDetector detector, VisionTrackProperties properties,
                         ObjectProvider<BuildProperties> buildProperties) {
        this.sink = sink;
        this.detector = detector;
        this.modelPath = properties.model().path();
        this.buildProperties = buildProperties;
    }

    public ExportedRun start(String videoId, VideoRunSummary.Source source) {
        return new ExportedRun(RunInfo.start(videoId, source), model(), sink);
    }

    private ModelInfo model() {
        ModelInfo current = model;
        if (current == null) {
            synchronized (this) {
                if (model == null) {
                    BuildProperties build = buildProperties.getIfAvailable();
                    model = new ModelInfo(detector.modelName(), shortHash(modelPath), detector.labels(),
                            build != null ? build.getVersion() : "unknown");
                }
                current = model;
            }
        }
        return current;
    }

    /** The first 12 hex digits of the model file's SHA-256: changes whenever the exported model changes. */
    static String shortHash(Path file) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), sha256)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(sha256.digest()).substring(0, 12);
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("Cannot hash the model file {}: {}", file, e.toString());
            return "unknown";
        }
    }
}
