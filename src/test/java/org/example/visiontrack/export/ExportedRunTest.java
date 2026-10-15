package org.example.visiontrack.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.Detection.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.visiontrack.export.ExportSchemaTest.MODEL;
import static org.example.visiontrack.export.ExportSchemaTest.RUN;
import static org.example.visiontrack.export.JsonlDetectionSinkTest.files;
import static org.example.visiontrack.export.JsonlDetectionSinkTest.gunzipLines;

class ExportedRunTest {

    @TempDir
    Path dir;

    @Test
    void videoRunWritesFrameDetectionsAndASummaryWithSourceMetadata() throws Exception {
        JsonlDetectionSink sink = new JsonlDetectionSink(dir);
        ExportedRun run = new ExportedRun(RUN, MODEL, sink);

        run.onStart(1280, 720, 29.97, 10_000_000);
        run.onFrame(0, 0, List.of(person()));
        run.onFrame(6, 200_000, List.of());
        run.onFrame(12, 400_000, List.of(person(), person()));
        run.onEnd(300);
        run.finish(VideoRunSummary.Status.COMPLETED);
        run.finish(VideoRunSummary.Status.FAILED); // ignored

        List<String> lines = gunzipLines(sink.outbox().resolve(RUN.detectionsPart(0)));
        assertThat(lines).hasSize(3);
        JsonNode last = ExportJson.MAPPER.readTree(lines.get(2));
        assertThat(last.get("frame_index").asLong()).isEqualTo(12);
        assertThat(last.get("timestamp_ms").asLong()).isEqualTo(400);
        assertThat(last.get("frame_width").asInt()).isEqualTo(1280);

        JsonNode summary = ExportJson.MAPPER.readTree(Files.readAllBytes(sink.outbox().resolve(RUN.summary())));
        assertThat(summary.get("status").asText()).isEqualTo("completed");
        assertThat(summary.get("duration_ms").asLong()).isEqualTo(10_000);
        assertThat(summary.get("fps").asDouble()).isEqualTo(29.97);
        assertThat(summary.get("total_frames").asLong()).isEqualTo(300);
        assertThat(summary.get("total_detections").asLong()).isEqualTo(3);
    }

    @Test
    void liveRunTakesTheSizeFromEachFrameAndDerivesFps() throws Exception {
        JsonlDetectionSink sink = new JsonlDetectionSink(dir);
        ExportedRun run = new ExportedRun(RUN, MODEL, sink);

        for (int i = 0; i < 11; i++) run.record(i, i * 100L, 960, 540, List.of(person()));
        run.rollPart();
        run.finish(VideoRunSummary.Status.COMPLETED);

        JsonNode summary = ExportJson.MAPPER.readTree(Files.readAllBytes(sink.outbox().resolve(RUN.summary())));
        assertThat(summary.get("duration_ms").asLong()).isEqualTo(1_000);
        assertThat(summary.get("fps").asDouble()).isEqualTo(11.0);
        assertThat(summary.get("total_frames").asLong()).isEqualTo(11);
    }

    @Test
    void exportFailuresAreSwallowed() throws Exception {
        DetectionSink broken = run -> {
            throw new java.io.IOException("disk full");
        };
        ExportedRun run = new ExportedRun(RUN, MODEL, broken);
        run.record(0, 0, 640, 360, List.of(person()));
        run.finish(VideoRunSummary.Status.COMPLETED);

        assertThat(files(dir)).isEmpty();
    }

    private static Detection person() {
        return new Detection(0, "person", 0.8f, new BoundingBox(100, 100, 300, 500));
    }
}
