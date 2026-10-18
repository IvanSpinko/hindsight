package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.visiontrack.config.SettingsService;
import org.example.visiontrack.meeting.notion.NotionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The export against a stub of the Notion API: request sequence, headers, retries, replacing and cleaning up pages. */
class NotionExportServiceTest {

    record Call(String method, String path, String auth, String version, JsonNode body) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    @TempDir
    Path dir;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger pages = new AtomicInteger();
    private final AtomicInteger blocks = new AtomicInteger();
    private volatile int rateLimitedPages;
    private volatile boolean rejectBlocks;
    private final String databaseId = UUID.randomUUID().toString();
    private final String dataSourceId = UUID.randomUUID().toString();
    private HttpServer server;
    private MeetingStore store;
    private SettingsService settings;
    private NotionExportService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", this::handle);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        store = new MeetingStore(dir.resolve("meetings"), json);
        settings = new SettingsService(dir.resolve("settings.json"), json);
        service = new NotionExportService(store, settings, json, token -> new NotionClient(base, token, json, Duration.ZERO, 3));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath().substring("/v1/".length());
        byte[] request = exchange.getRequestBody().readAllBytes();
        JsonNode body = request.length == 0 ? null : json.readTree(request);
        calls.add(new Call(method, path, exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Notion-Version"), body));
        ObjectNode answer = json.createObjectNode();
        int status = 200;
        if (method.equals("POST") && path.equals("databases")) {
            answer.put("id", databaseId).putArray("data_sources").addObject().put("id", dataSourceId);
        } else if (method.equals("POST") && path.equals("pages")) {
            if (rateLimitedPages > 0) {
                rateLimitedPages--;
                exchange.getResponseHeaders().add("Retry-After", "0");
                status = 429;
                answer.put("object", "error").put("code", "rate_limited").put("message", "Slow down");
            } else {
                int n = pages.incrementAndGet();
                answer.put("id", "page-" + n).put("url", "https://www.notion.so/page-" + n);
            }
        } else if (method.equals("PATCH") && path.startsWith("blocks/")) {
            if (rejectBlocks) {
                status = 400;
                answer.put("object", "error").put("code", "validation_error").put("message", "body failed validation");
            } else {
                ArrayNode results = answer.putArray("results");
                for (JsonNode ignored : body.get("children")) results.addObject().put("id", "block-" + blocks.incrementAndGet());
            }
        } else if (!(method.equals("PATCH") && path.startsWith("pages/"))) {
            status = 404;
        }
        byte[] bytes = json.writeValueAsBytes(answer);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String readyMeeting(int transcriptTurns) throws IOException {
        String id = "id";
        Path folder = store.createFolder(id, "Weekly", LocalDateTime.of(2026, 10, 14, 12, 0));
        store.save(NotionPageBuilderTest.meeting());
        json.writeValue(folder.resolve(MeetingJobService.NOTES_JSON).toFile(), NotionPageBuilderTest.notes());
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int i = 0; i < transcriptTurns; i++) {
            segments.add(new TranscriptSegment(i * 4, i * 4 + 3, i % 2 == 0 ? "mic" : "system", "en", "Turn " + i));
        }
        json.writeValue(folder.resolve(TranscriptWriter.JSON_FILE).toFile(),
                new TranscriptWriter.TranscriptFile("whisper", "CUDA", List.of("en"), segments));
        return id;
    }

    private void writeSettings(String notion) throws IOException {
        Files.writeString(dir.resolve("settings.json"), "{\"notion\": " + notion + "}", StandardCharsets.UTF_8);
    }

    private Meeting.NotionExport awaitDone(String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Meeting.NotionExport export = store.find(id).orElseThrow().notion();
            if (export != null && export.state() != Meeting.NotionState.EXPORTING) return export;
            Thread.sleep(50);
        }
        throw new AssertionError("The export did not finish");
    }

    @Test
    void firstSendCreatesTheDatabaseThenThePageAndAppendsTheLongTranscriptInParts() throws Exception {
        writeSettings("{\"token\": \"secret-token\", \"parentPageId\": \"https://www.notion.so/Home-1a2b3c4d5e6f4a1b8c9d0e1f2a3b4c5d\"}");
        String id = readyMeeting(250);
        rateLimitedPages = 1;

        assertThat(service.submit(id).notion().state()).isEqualTo(Meeting.NotionState.EXPORTING);
        Meeting.NotionExport export = awaitDone(id);

        assertThat(export.state()).isEqualTo(Meeting.NotionState.EXPORTED);
        assertThat(export.pageId()).isEqualTo("page-1");
        assertThat(export.url()).isEqualTo("https://www.notion.so/page-1");
        assertThat(export.error()).isNull();
        // 14 blocks of notes with the transcript section last; its 250 paragraphs go as 100 + 100 + 50
        assertThat(calls).extracting(Call::toString).containsExactly(
                "POST databases", "POST pages", "POST pages",
                "PATCH blocks/page-1/children", "PATCH blocks/block-14/children", "PATCH blocks/block-14/children");
        assertThat(calls).allSatisfy(c -> {
            assertThat(c.auth()).isEqualTo("Bearer secret-token");
            assertThat(c.version()).isEqualTo(NotionClient.VERSION);
        });
        assertThat(calls.get(0).body().at("/parent/page_id").asText()).isEqualTo("1a2b3c4d-5e6f-4a1b-8c9d-0e1f2a3b4c5d");
        assertThat(calls.get(2).body().at("/parent/data_source_id").asText()).isEqualTo(dataSourceId);
        JsonNode top = calls.get(3).body().get("children");
        assertThat(top.size()).isEqualTo(14);
        assertThat(top.get(13).at("/heading_2/children").size()).isEqualTo(100);
        assertThat(calls.get(4).body().get("children").size()).isEqualTo(100);
        assertThat(calls.get(5).body().get("children").size()).isEqualTo(50);
        // The database is remembered, so the next meeting goes into the same one
        assertThat(settings.notion().databaseId()).isEqualTo(databaseId);
        assertThat(settings.notion().dataSourceId()).isEqualTo(dataSourceId);
    }

    @Test
    void sendingAgainReplacesThePageAndTrashesTheOldOne() throws Exception {
        writeSettings("{\"token\": \"t\", \"dataSourceId\": \"" + dataSourceId + "\"}");
        String id = readyMeeting(3);
        service.submit(id);
        awaitDone(id);
        calls.clear();

        service.submit(id);
        Meeting.NotionExport export = awaitDone(id);

        assertThat(export.pageId()).isEqualTo("page-2");
        assertThat(calls).extracting(Call::toString).containsExactly("POST pages", "PATCH blocks/page-2/children", "PATCH pages/page-1");
        assertThat(calls.getLast().body().get("in_trash").asBoolean()).isTrue();
    }

    @Test
    void aRejectedBodyTrashesTheHalfWrittenPageAndKeepsThePreviousOne() throws Exception {
        writeSettings("{\"token\": \"t\", \"dataSourceId\": \"" + dataSourceId + "\"}");
        String id = readyMeeting(3);
        service.submit(id);
        awaitDone(id);
        calls.clear();
        rejectBlocks = true;

        service.submit(id);
        Meeting.NotionExport export = awaitDone(id);

        assertThat(export.state()).isEqualTo(Meeting.NotionState.FAILED);
        assertThat(export.error()).startsWith("Notion did not accept the notes: body failed validation");
        assertThat(export.pageId()).isEqualTo("page-1");
        assertThat(export.url()).isEqualTo("https://www.notion.so/page-1");
        assertThat(calls).extracting(Call::toString).containsExactly("POST pages", "PATCH blocks/page-2/children", "PATCH pages/page-2");
    }

    @Test
    void nothingIsSentWithoutSettings() throws Exception {
        String id = readyMeeting(3);

        assertThatThrownBy(() -> service.submit(id)).isInstanceOf(IllegalStateException.class).hasMessageContaining("notion.token");

        writeSettings("{\"token\": \"t\"}");
        assertThatThrownBy(() -> service.submit(id)).isInstanceOf(IllegalStateException.class).hasMessageContaining("parentPageId");
        assertThat(calls).isEmpty();
        assertThat(service.status().tokenSet()).isTrue();
        assertThat(service.status().targetSet()).isFalse();
    }

    @Test
    void aMeetingFileWithoutTheNotionStateStillLoads() throws Exception {
        String id = readyMeeting(1);
        Path manifest = store.folder(id).orElseThrow().resolve(MeetingStore.MANIFEST);
        ObjectNode old = (ObjectNode) json.readTree(manifest.toFile());
        old.remove("notion");
        json.writeValue(manifest.toFile(), old);

        assertThat(new MeetingStore(dir.resolve("meetings"), json).find(id).orElseThrow().notion()).isNull();
    }
}
