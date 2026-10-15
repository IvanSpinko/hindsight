package org.example.visiontrack.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.Detection.BoundingBox;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ExportSchemaTest {

    static final UUID RUN_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    static final RunInfo RUN = new RunInfo(RUN_ID, "video-1", VideoRunSummary.Source.UPLOAD,
            Instant.parse("2026-10-15T23:59:30Z"));
    static final ModelInfo MODEL = new ModelInfo("yoloworldx-960.onnx", "0123456789ab", List.of("person", "car"), "1.0");

    @Test
    void detectionEventIsSnakeCaseWithIsoTimeAndSchemaVersion() throws Exception {
        Detection detection = new Detection(0, "person", 0.87654f, new BoundingBox(64, 36, 192, 360));
        DetectionEvent event = DetectionEvent.of(RUN, MODEL, 42, 1_400, 640, 360, detection,
                Instant.parse("2026-10-16T00:00:01.250Z"));

        JsonNode json = ExportJson.MAPPER.readTree(ExportJson.MAPPER.writeValueAsString(event));

        assertThat(json.get("schema_version").asInt()).isEqualTo(1);
        assertThat(json.get("run_id").asText()).isEqualTo(RUN_ID.toString());
        assertThat(json.get("video_id").asText()).isEqualTo("video-1");
        assertThat(json.get("frame_index").asLong()).isEqualTo(42);
        assertThat(json.get("timestamp_ms").asLong()).isEqualTo(1_400);
        assertThat(json.get("class_label").asText()).isEqualTo("person");
        assertThat(json.get("confidence").asDouble()).isCloseTo(0.876540, within(1e-6));
        assertThat(json.get("bbox_x").asDouble()).isCloseTo(0.1, within(1e-6));
        assertThat(json.get("bbox_y").asDouble()).isCloseTo(0.1, within(1e-6));
        assertThat(json.get("bbox_width").asDouble()).isCloseTo(0.2, within(1e-6));
        assertThat(json.get("bbox_height").asDouble()).isCloseTo(0.9, within(1e-6));
        assertThat(json.get("frame_width").asInt()).isEqualTo(640);
        assertThat(json.get("frame_height").asInt()).isEqualTo(360);
        assertThat(json.get("model_name").asText()).isEqualTo("yoloworldx-960.onnx");
        assertThat(json.get("model_version").asText()).isEqualTo("0123456789ab");
        assertThat(json.get("detected_at").asText()).isEqualTo("2026-10-16T00:00:01.250Z");
        assertThat(json.size()).isEqualTo(16);
    }

    @Test
    void boxesReachingOutsideTheFrameAreClampedToZeroOne() {
        Detection detection = new Detection(1, "car", 0.5f, new BoundingBox(-20, -10, 700, 200));
        DetectionEvent event = DetectionEvent.of(RUN, MODEL, 0, 0, 640, 360, detection, Instant.EPOCH);

        assertThat(event.bboxX()).isZero();
        assertThat(event.bboxY()).isZero();
        assertThat(event.bboxWidth()).isEqualTo(1f);
        assertThat(event.bboxX() + event.bboxWidth()).isLessThanOrEqualTo(1f);
        assertThat(event.bboxHeight()).isCloseTo(200f / 360f, within(1e-6f));
    }

    @Test
    void runSummaryUsesLowercaseEnumsAndNullsForUnknownValues() throws Exception {
        VideoRunSummary summary = new VideoRunSummary(1, RUN_ID, "live-1", VideoRunSummary.Source.LIVE_CAMERA,
                null, null, 0, 0, List.of("person"), 12, RUN.startedAt(), RUN.startedAt(), "1.0",
                VideoRunSummary.Status.CANCELLED);

        JsonNode json = ExportJson.MAPPER.readTree(ExportJson.MAPPER.writeValueAsString(summary));

        assertThat(json.get("source").asText()).isEqualTo("live_camera");
        assertThat(json.get("status").asText()).isEqualTo("cancelled");
        assertThat(json.get("duration_ms").isNull()).isTrue();
        assertThat(json.get("requested_classes").get(0).asText()).isEqualTo("person");
        assertThat(json.get("started_at").asText()).isEqualTo("2026-10-15T23:59:30Z");
    }

    @Test
    void filesArePartitionedByTheUtcStartDate() {
        assertThat(RUN.detectionsPart(3))
                .isEqualTo("detections/dt=2026-10-15/run_id=" + RUN_ID + "/part-0003.jsonl.gz");
        assertThat(RUN.summary()).isEqualTo("runs/dt=2026-10-15/run_id=" + RUN_ID + "/run.json");
    }
}
