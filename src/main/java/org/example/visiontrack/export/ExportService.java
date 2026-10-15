package org.example.visiontrack.export;

import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.detection.ObjectDetectorProvider;
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
import java.util.List;

/** Starts exported runs; knows the model and app versions that go into every record. */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final DetectionSink sink;
    private final ObjectDetectorProvider detectors;
    private final Path modelPath;
    private final ObjectProvider<BuildProperties> buildProperties;
    private volatile ModelInfo model;

    public ExportService(DetectionSink sink, ObjectDetectorProvider detectors, VisionTrackProperties properties,
                         ObjectProvider<BuildProperties> buildProperties) {
        this.sink = sink;
        this.detectors = detectors;
        this.modelPath = properties.model().path();
        this.buildProperties = buildProperties;
    }

    public ExportedRun start(String videoId, VideoRunSummary.Source source) {
        return new ExportedRun(RunInfo.start(videoId, source), model(), sink);
    }

    /**
     * Name and classes come from the loaded detector (it is about to be used by the run anyway). If it cannot be
     * loaded, the run fails right after; its summary then names the model file without classes.
     */
    private ModelInfo model() {
        ModelInfo current = model;
        if (current != null) return current;
        synchronized (this) {
            if (model != null) return model;
            BuildProperties build = buildProperties.getIfAvailable();
            String appVersion = build != null ? build.getVersion() : "unknown";
            List<String> labels;
            String name;
            try {
                labels = detectors.use(OnnxYoloDetector::labels);
                name = detectors.use(OnnxYoloDetector::modelName);
            } catch (RuntimeException e) {
                return new ModelInfo(String.valueOf(modelPath.getFileName()), "unknown", List.of(), appVersion);
            }
            model = new ModelInfo(name, shortHash(modelPath), labels, appVersion);
            return model;
        }
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
