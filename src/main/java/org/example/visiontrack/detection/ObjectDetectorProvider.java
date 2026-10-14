package org.example.visiontrack.detection;

import ai.onnxruntime.OrtException;
import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads the detector once at startup. When it cannot be loaded (no NVIDIA GPU, missing model) the app still
 * starts, so meetings can be recorded; video analysis and the live camera then report the reason instead.
 */
@Component
public class ObjectDetectorProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ObjectDetectorProvider.class);

    private final OnnxYoloDetector detector;
    private final RuntimeException failure;

    public ObjectDetectorProvider(VisionTrackProperties properties) {
        OnnxYoloDetector loaded = null;
        RuntimeException error = null;
        try {
            loaded = OnnxYoloDetector.load(properties.model());
        } catch (OrtException e) {
            error = new IllegalStateException("Cannot load the object detection model: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            error = e;
        }
        if (error != null) log.error("Object detection is unavailable: {}", error.getMessage(), error.getCause());
        this.detector = loaded;
        this.failure = error;
    }

    /** The detector, or the reason it could not be loaded (a {@link org.example.visiontrack.GpuRequiredException} without CUDA). */
    public OnnxYoloDetector get() {
        if (detector == null) throw failure;
        return detector;
    }

    @Override
    public void close() throws OrtException {
        if (detector != null) detector.close();
    }
}
