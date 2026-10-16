package org.example.visiontrack.detection;

import ai.onnxruntime.OrtException;
import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Loads the detector on first use and can unload it again, so its VRAM is free while a meeting is processed by
 * the speech and notes models. When it cannot be loaded (no NVIDIA GPU, missing model) the app still runs; video
 * analysis and the live camera report the reason instead.
 * <p>
 * Deliberately not closed on shutdown: closing a CUDA session while the JVM exits crashes inside onnxruntime.dll
 * (EXCEPTION_ACCESS_VIOLATION), and the process ending frees the GPU memory anyway.
 */
@Component
public class ObjectDetectorProvider {

    private static final Logger log = LoggerFactory.getLogger(ObjectDetectorProvider.class);

    private final VisionTrackProperties.Model config;
    private OnnxYoloDetector detector;
    private int users;

    public ObjectDetectorProvider(VisionTrackProperties properties) {
        this.config = properties.model();
    }

    /**
     * Runs {@code work} with the detector, loading it if needed; it is not unloaded while in use. Throws the reason
     * it cannot be loaded, a {@link org.example.visiontrack.GpuRequiredException} without CUDA.
     */
    public <T> T use(Function<OnnxYoloDetector, T> work) {
        OnnxYoloDetector current;
        synchronized (this) {
            if (detector == null) detector = load();
            current = detector;
            users++;
        }
        try {
            return work.apply(current);
        } finally {
            synchronized (this) {
                users--;
            }
        }
    }

    /** Fails fast with the reason when the detector cannot be loaded. */
    public void check() {
        use(d -> null);
    }

    /** Frees the detector's VRAM unless a video analysis or the live camera is using it right now. */
    public synchronized void unloadIfUnused() {
        if (detector == null || users > 0) return;
        try {
            detector.close();
            log.info("Object detection unloaded to free the GPU");
        } catch (OrtException e) {
            log.warn("Cannot unload object detection: {}", e.getMessage());
        }
        detector = null;
    }

    private OnnxYoloDetector load() {
        try {
            return OnnxYoloDetector.load(config);
        } catch (OrtException e) {
            throw logged(new IllegalStateException("Cannot load the object detection model: " + e.getMessage(), e));
        } catch (RuntimeException e) {
            throw logged(e);
        }
    }

    private static RuntimeException logged(RuntimeException e) {
        log.error("Object detection is unavailable: {}", e.getMessage(), e.getCause());
        return e;
    }
}
