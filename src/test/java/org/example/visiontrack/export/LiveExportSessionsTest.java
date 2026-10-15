package org.example.visiontrack.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.Detection.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.visiontrack.export.ExportSchemaTest.MODEL;
import static org.example.visiontrack.export.JsonlDetectionSinkTest.files;
import static org.example.visiontrack.export.JsonlDetectionSinkTest.gunzipLines;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveExportSessionsTest {

    static final String SESSION = "6f1c2a1e-1111-4a7b-9c2d-3e4f5a6b7c8d";

    @TempDir
    Path dir;

    final AtomicLong clock = new AtomicLong();
    JsonlDetectionSink sink;

    LiveExportSessions sessions() {
        sink = new JsonlDetectionSink(dir);
        ExportService exports = mock(ExportService.class);
        when(exports.start(anyString(), any())).thenAnswer(call ->
                new ExportedRun(RunInfo.start(call.getArgument(0), call.getArgument(1)), MODEL, sink));
        return new LiveExportSessions(exports, Duration.ofSeconds(10), Duration.ofSeconds(60), clock::get);
    }

    @Test
    void partsAreClosedEveryBatchAndTheRunEndsOnStop() throws Exception {
        LiveExportSessions sessions = sessions();
        sessions.record(SESSION, 960, 540, List.of(cup()));
        advance(5);
        sessions.record(SESSION, 960, 540, List.of(cup()));
        sessions.tick();
        assertThat(files(sink.outbox())).isEmpty();

        advance(6); // 11 s since the session started
        sessions.tick();
        List<Path> parts = files(sink.outbox());
        assertThat(parts).hasSize(1);
        assertThat(gunzipLines(parts.getFirst())).hasSize(2);

        sessions.record(SESSION, 960, 540, List.of(cup()));
        sessions.stop(SESSION);
        assertThat(sessions.activeSessions()).isZero();

        Path summary = files(sink.outbox()).stream().filter(p -> p.endsWith("run.json")).findFirst().orElseThrow();
        JsonNode run = ExportJson.MAPPER.readTree(Files.readAllBytes(summary));
        assertThat(run.get("source").asText()).isEqualTo("live_camera");
        assertThat(run.get("video_id").asText()).isEqualTo("live-" + SESSION);
        assertThat(run.get("total_frames").asLong()).isEqualTo(3);
        assertThat(run.get("total_detections").asLong()).isEqualTo(3);
        assertThat(files(sink.outbox())).hasSize(3); // two parts and the summary
    }

    @Test
    void idleSessionsEndByThemselves() throws Exception {
        LiveExportSessions sessions = sessions();
        sessions.record(SESSION, 960, 540, List.of(cup()));
        advance(61);
        sessions.tick();

        assertThat(sessions.activeSessions()).isZero();
        assertThat(files(sink.outbox())).anyMatch(p -> p.endsWith("run.json"));
    }

    @Test
    void framesWithoutAValidSessionAreNotExported() throws Exception {
        LiveExportSessions sessions = sessions();
        sessions.record(null, 960, 540, List.of(cup()));
        sessions.record("../../etc", 960, 540, List.of(cup()));

        assertThat(sessions.activeSessions()).isZero();
        assertThat(files(dir)).isEmpty();
    }

    private void advance(int seconds) {
        clock.addAndGet(Duration.ofSeconds(seconds).toNanos());
    }

    private static Detection cup() {
        return new Detection(41, "cup", 0.7f, new BoundingBox(10, 20, 110, 220));
    }
}
