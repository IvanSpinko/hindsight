package org.example.visiontrack.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.Detection.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.visiontrack.export.ExportSchemaTest.MODEL;
import static org.example.visiontrack.export.ExportSchemaTest.RUN;

class JsonlDetectionSinkTest {

    @TempDir
    Path dir;

    @Test
    void finishedRunLandsInTheOutboxAsGzippedJsonLinesAndSummary() throws Exception {
        JsonlDetectionSink sink = new JsonlDetectionSink(dir);
        DetectionSink.RunRecorder recorder = sink.open(RUN);
        recorder.append(List.of(event(0, "person"), event(0, "car")));
        recorder.append(List.of(event(5, "person")));

        assertThat(files(sink.outbox())).isEmpty();
        recorder.finish(summary(3));

        Path part = sink.outbox().resolve(RUN.detectionsPart(0));
        List<String> lines = gunzipLines(part);
        assertThat(lines).hasSize(3);
        assertThat(ExportJson.MAPPER.readTree(lines.get(1)).get("class_label").asText()).isEqualTo("car");
        assertThat(ExportJson.MAPPER.readTree(lines.get(2)).get("frame_index").asLong()).isEqualTo(5);

        JsonNode run = ExportJson.MAPPER.readTree(sink.outbox().resolve(RUN.summary()).toFile());
        assertThat(run.get("total_detections").asLong()).isEqualTo(3);
        assertThat(files(sink.outbox())).hasSize(2);
        assertThat(files(dir.resolve("in-progress"))).isEmpty();
    }

    @Test
    void rollingPartsGivesOneFileEachAndSkipsEmptyParts() throws Exception {
        JsonlDetectionSink sink = new JsonlDetectionSink(dir);
        DetectionSink.RunRecorder recorder = sink.open(RUN);
        recorder.append(List.of(event(0, "person")));
        recorder.rollPart();
        assertThat(sink.outbox().resolve(RUN.detectionsPart(0))).isRegularFile();

        recorder.rollPart(); // nothing written since the last roll
        recorder.append(List.of(event(10, "car"), event(11, "car")));
        recorder.finish(summary(3));

        assertThat(gunzipLines(sink.outbox().resolve(RUN.detectionsPart(1)))).hasSize(2);
        assertThat(sink.outbox().resolve(RUN.detectionsPart(2))).doesNotExist();
    }

    @Test
    void runWithoutDetectionsStillWritesItsSummary() throws Exception {
        JsonlDetectionSink sink = new JsonlDetectionSink(dir);
        sink.open(RUN).finish(summary(0));

        assertThat(files(sink.outbox())).containsExactly(sink.outbox().resolve(RUN.summary()));
    }

    static DetectionEvent event(long frame, String label) {
        return DetectionEvent.of(RUN, MODEL, frame, frame * 200, 640, 360,
                new Detection(0, label, 0.9f, new BoundingBox(10, 10, 100, 100)), Instant.EPOCH);
    }

    static VideoRunSummary summary(long detections) {
        return new VideoRunSummary(1, RUN.runId(), RUN.videoId(), RUN.source(), 1_000L, 25.0, 25, detections,
                MODEL.classes(), 10, RUN.startedAt(), RUN.startedAt(), "1.0", VideoRunSummary.Status.COMPLETED);
    }

    static List<String> gunzipLines(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    static List<Path> files(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }
}
