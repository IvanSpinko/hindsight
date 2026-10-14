package org.example.visiontrack.detection;

import org.example.visiontrack.detection.Detection.BoundingBox;
import org.example.visiontrack.detection.ImagePreprocessor.Letterbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

public final class YoloOutputDecoder {

    private static final Logger log = LoggerFactory.getLogger(YoloOutputDecoder.class);

    static final List<String> COCO = List.of(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush");

    private YoloOutputDecoder() {}

    public record Layout(int numClasses, int numCandidates, boolean transposed) {

        public static Layout fromShape(long[] shape) {
            if (shape.length != 3 || shape[1] <= 0 || shape[2] <= 0) {
                throw new IllegalArgumentException("Unsupported YOLO output shape " + Arrays.toString(shape)
                        + "; expected [1, 4 + classes, candidates] with a fixed size");
            }
            boolean transposed = shape[1] > shape[2];
            int attributes = (int) (transposed ? shape[2] : shape[1]);
            int candidates = (int) (transposed ? shape[1] : shape[2]);
            if (attributes <= 4) {
                throw new IllegalArgumentException("YOLO output has no class scores: " + Arrays.toString(shape));
            }
            return new Layout(attributes - 4, candidates, transposed);
        }

        float get(FloatBuffer data, int attribute, int candidate) {
            int index = transposed
                    ? candidate * (numClasses + 4) + attribute
                    : attribute * numCandidates + candidate;
            return data.get(index);
        }
    }

    public static List<String> resolveLabels(Path labelsFile, int modelClassCount) {
        if (labelsFile != null && Files.exists(labelsFile)) {
            List<String> fromFile = readLabels(labelsFile);
            if (fromFile.size() == modelClassCount) {
                log.info("Loaded {} class labels from {}", fromFile.size(), labelsFile);
                return fromFile;
            }
            log.warn("{} has {} labels but the model outputs {} classes – ignoring the file",
                    labelsFile, fromFile.size(), modelClassCount);
        }
        if (modelClassCount == COCO.size()) {
            log.info("Using the built-in COCO labels");
            return COCO;
        }
        log.warn("No labels for a {}-class model – using generic names", modelClassCount);
        return IntStream.range(0, modelClassCount).mapToObj(i -> "class_" + i).toList();
    }

    private static List<String> readLabels(Path file) {
        try {
            return Files.readAllLines(file).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read class labels from " + file, e);
        }
    }

    public static List<Detection> decode(FloatBuffer data, Layout layout, Letterbox letterbox,
                                         int sourceWidth, int sourceHeight,
                                         float confidenceThreshold, List<String> labels) {
        List<Detection> detections = new ArrayList<>();

        for (int i = 0; i < layout.numCandidates(); i++) {
            float bestScore = 0f;
            int bestClass = -1;
            for (int c = 0; c < layout.numClasses(); c++) {
                float score = layout.get(data, 4 + c, i);
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0 || bestScore < confidenceThreshold) continue;

            float cx = layout.get(data, 0, i);
            float cy = layout.get(data, 1, i);
            float halfW = layout.get(data, 2, i) / 2f;
            float halfH = layout.get(data, 3, i) / 2f;

            float x1 = clamp(letterbox.toSourceX(cx - halfW), sourceWidth);
            float y1 = clamp(letterbox.toSourceY(cy - halfH), sourceHeight);
            float x2 = clamp(letterbox.toSourceX(cx + halfW), sourceWidth);
            float y2 = clamp(letterbox.toSourceY(cy + halfH), sourceHeight);
            if (x2 <= x1 || y2 <= y1) continue;

            detections.add(new Detection(bestClass, labels.get(bestClass), bestScore,
                    new BoundingBox(x1, y1, x2, y2)));
        }
        return detections;
    }

    public static List<Detection> nonMaxSuppression(List<Detection> detections, float iouThreshold, int maxDetections) {
        List<Detection> sorted = new ArrayList<>(detections);
        sorted.sort(Comparator.comparingDouble(Detection::confidence).reversed());

        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : sorted) {
            if (kept.size() >= maxDetections) break;
            boolean suppressed = false;
            for (Detection k : kept) {
                if (k.classId() == candidate.classId() && k.box().iou(candidate.box()) > iouThreshold) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) kept.add(candidate);
        }
        return kept;
    }

    private static float clamp(float value, int max) {
        return Math.max(0f, Math.min(max, value));
    }
}
