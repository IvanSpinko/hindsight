package org.example.visiontrack.web;

import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.OnnxYoloDetector;
import org.example.visiontrack.detection.RgbImage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final OnnxYoloDetector detector;

    public LiveDetectionController(OnnxYoloDetector detector) {
        this.detector = detector;
    }

    @PostMapping(path = "/api/live/detect", consumes = {"image/jpeg", "image/png"})
    public LiveResult detect(@RequestBody byte[] frame) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame));
        if (image == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The frame is not a valid image");
        }
        long started = System.nanoTime();
        List<Detection> detections = detector.detect(toRgb(image));
        long inferenceMillis = (System.nanoTime() - started) / 1_000_000;

        List<LiveObject> objects = detections.stream()
                .map(d -> new LiveObject(d.label(), Math.round(d.confidence() * 1000) / 1000f,
                        Math.round(d.box().x1()), Math.round(d.box().y1()),
                        Math.round(d.box().width()), Math.round(d.box().height())))
                .toList();
        return new LiveResult(image.getWidth(), image.getHeight(), inferenceMillis, objects);
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
