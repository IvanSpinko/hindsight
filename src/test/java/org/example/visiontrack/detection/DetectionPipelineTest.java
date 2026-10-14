package org.example.visiontrack.detection;

import org.example.visiontrack.detection.Detection.BoundingBox;
import org.example.visiontrack.detection.ImagePreprocessor.Letterbox;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DetectionPipelineTest {

    @Test
    void iouOfIdenticalDisjointAndHalfOverlappingBoxes() {
        BoundingBox a = new BoundingBox(0, 0, 10, 10);
        assertThat(a.iou(a)).isEqualTo(1f);
        assertThat(a.iou(new BoundingBox(20, 20, 30, 30))).isZero();
        assertThat(a.iou(new BoundingBox(5, 0, 15, 10))).isCloseTo(50f / 150f, within(1e-6f));
    }

    @Test
    void letterboxFitsWideFrameAndMapsCoordinatesBack() {
        Letterbox lb = Letterbox.fit(1280, 720, 960, 960);

        assertThat(lb.scale()).isEqualTo(0.75);
        assertThat(lb.resizedWidth()).isEqualTo(960);
        assertThat(lb.resizedHeight()).isEqualTo(540);
        assertThat(lb.padX()).isZero();
        assertThat(lb.padY()).isEqualTo(210);
        assertThat(lb.toSourceX(480)).isEqualTo(640f);
        assertThat(lb.toSourceY(210 + 270)).isEqualTo(360f);
    }

    @Test
    void nmsKeepsBestBoxPerOverlappingSameClassGroup() {
        Detection best = det(0, 0.9f, 0, 0, 100, 100);
        Detection duplicate = det(0, 0.6f, 5, 5, 105, 105);
        Detection otherClassSamePlace = det(1, 0.5f, 0, 0, 100, 100);
        Detection elsewhere = det(0, 0.7f, 300, 300, 400, 400);

        List<Detection> kept = YoloOutputDecoder.nonMaxSuppression(List.of(duplicate, otherClassSamePlace, best, elsewhere), 0.45f, 100);

        assertThat(kept).containsExactly(best, elsewhere, otherClassSamePlace);
    }

    @Test
    void decoderReadsStandardLayoutAndMapsBoxesToSourceImage() {
        int classes = 2, candidates = 10;
        float[] raw = new float[(4 + classes) * candidates];
        set(raw, candidates, 0,  480,  480,  96,  48,  0.1f, 0.8f);
        set(raw, candidates, 1, 100, 300, 20, 20, 0.2f, 0.1f);
        set(raw, candidates, 2, 480, 100, 50, 50, 0.9f, 0.0f);

        YoloOutputDecoder.Layout layout = YoloOutputDecoder.Layout.fromShape(new long[]{1, 4 + classes, candidates});
        Letterbox lb = Letterbox.fit(1280, 720, 960, 960);
        List<Detection> detections = YoloOutputDecoder.decode(FloatBuffer.wrap(raw), layout, lb, 1280, 720,
                0.35f, List.of("cat", "dog"));

        assertThat(detections).hasSize(1);
        Detection d = detections.get(0);
        assertThat(d.label()).isEqualTo("dog");
        assertThat(d.confidence()).isEqualTo(0.8f);
        assertThat(d.box()).isEqualTo(new BoundingBox(576, 328, 704, 392));
    }

    @Test
    void decoderDetectsTransposedLayout() {
        YoloOutputDecoder.Layout layout = YoloOutputDecoder.Layout.fromShape(new long[]{1, 18900, 84});
        assertThat(layout.transposed()).isTrue();
        assertThat(layout.numClasses()).isEqualTo(80);
        assertThat(layout.numCandidates()).isEqualTo(18900);
    }

    @Test
    void preprocessorPadsBordersAndConvertsToChwPlanes() {
        int w = 4, h = 2, stride = 16;
        ByteBuffer pixels = ByteBuffer.allocateDirect(stride * h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixels.put(y * stride + x * 3, (byte) 255);

        Letterbox lb = Letterbox.fit(w, h, 8, 8);
        float[] out = new float[3 * 64];
        ImagePreprocessor.toChwTensor(new RgbImage(w, h, stride, pixels), lb, out);

        int inside = (lb.padY() + 1) * 8 + 3;
        assertThat(out[inside]).isCloseTo(1f, within(1e-6f));
        assertThat(out[64 + inside]).isZero();
        assertThat(out[128 + inside]).isZero();
        assertThat(out[0]).isEqualTo(ImagePreprocessor.PAD_VALUE);
    }

    @Test
    void labelsFallBackToCocoForEightyClassModels() {
        assertThat(YoloOutputDecoder.resolveLabels(null, 80).get(0)).isEqualTo("person");
        assertThat(YoloOutputDecoder.resolveLabels(null, 3).get(2)).isEqualTo("class_2");
    }

    private static Detection det(int cls, float conf, float x1, float y1, float x2, float y2) {
        return new Detection(cls, "c" + cls, conf, new BoundingBox(x1, y1, x2, y2));
    }

    private static void set(float[] raw, int candidates, int i, float cx, float cy, float w, float h, float... scores) {
        float[] values = new float[4 + scores.length];
        values[0] = cx; values[1] = cy; values[2] = w; values[3] = h;
        System.arraycopy(scores, 0, values, 4, scores.length);
        for (int a = 0; a < values.length; a++) raw[a * candidates + i] = values[a];
    }
}
