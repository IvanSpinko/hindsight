package org.example.visiontrack.web;

import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.ObjectDetectorProvider;
import org.example.visiontrack.detection.RgbImage;
import org.example.visiontrack.export.LiveExportSessions;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

@RestController
public class LiveDetectionController {

    public record LiveObject(String label, float confidence, int x, int y, int w, int h) {}

    public record LiveResult(int width, int height, long inferenceMillis, List<LiveObject> objects) {}

    private final ObjectDetectorProvider detectors;
    private final LiveExportSessions exportSessions;

    public LiveDetectionController(ObjectDetectorProvider detectors, LiveExportSessions exportSessions) {
        this.detectors = detectors;
        this.exportSessions = exportSessions;
    }

    /** {@code X-Live-Session}: a UUID the page creates when the camera starts; frames with it are exported. */
    @PostMapping(path = "/api/live/detect", consumes = {"image/jpeg", "image/png"})
    public LiveResult detect(@RequestBody byte[] frame,
                             @RequestHeader(name = "X-Live-Session", required = false) String session) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame));
        if (image == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The frame is not a valid image");
        }
        long started = System.nanoTime();
        List<Detection> detections = detectors.use(detector -> detector.detect(toRgb(image)));
        long inferenceMillis = (System.nanoTime() - started) / 1_000_000;
        exportSessions.record(session, image.getWidth(), image.getHeight(), detections);

        List<LiveObject> objects = detections.stream()
                .map(d -> new LiveObject(d.label(), Math.round(d.confidence() * 1000) / 1000f,
                        Math.round(d.box().x1()), Math.round(d.box().y1()),
                        Math.round(d.box().width()), Math.round(d.box().height())))
                .toList();
        return new LiveResult(image.getWidth(), image.getHeight(), inferenceMillis, objects);
    }

    @PostMapping("/api/live/sessions/{id}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String id) {
        exportSessions.stop(id);
    }

    private static RgbImage toRgb(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] rgb = new byte[width * height * 3];
        for (int i = 0, o = 0; i < argb.length; i++, o += 3) {
            int p = argb[i];
            rgb[o] = (byte) (p >> 16);
            rgb[o + 1] = (byte) (p >> 8);
            rgb[o + 2] = (byte) p;
        }
        return new RgbImage(width, height, width * 3, ByteBuffer.wrap(rgb));
    }
}
