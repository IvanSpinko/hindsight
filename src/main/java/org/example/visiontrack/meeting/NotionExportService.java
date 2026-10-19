package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.example.visiontrack.config.SettingsService;
import org.example.visiontrack.config.SettingsService.NotionSettings;
import org.example.visiontrack.meeting.notion.NotionClient;
import org.example.visiontrack.meeting.notion.NotionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Sends meeting notes to Notion as a page in a "Meetings" database, in the background.
 * Sending again writes the new page first, then moves the old one to Notion's trash.
 */
@Service
public class NotionExportService {

    /** What the UI needs to know about the settings; never the token itself. */
    public record Status(boolean tokenSet, boolean targetSet, boolean databaseSet, boolean autoExport, String settingsFile) {}

    private static final Logger log = LoggerFactory.getLogger(NotionExportService.class);

    private final MeetingStore store;
    private final SettingsService settings;
    private final ObjectMapper json;
    private final Function<String, NotionClient> clients;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "notion-export");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public NotionExportService(MeetingStore store, SettingsService settings, ObjectMapper objectMapper) {
        this(store, settings, objectMapper, token -> new NotionClient(token, objectMapper));
    }

    NotionExportService(MeetingStore store, SettingsService settings, ObjectMapper objectMapper,
                        Function<String, NotionClient> clients) {
        this.store = store;
        this.settings = settings;
        this.json = objectMapper;
        this.clients = clients;
    }

    public Status status() {
        NotionSettings notion = settings.notion();
        return new Status(notion.hasToken(), notion.hasDatabase() || notion.parentPageId() != null, notion.hasDatabase(),
                notion.autoExport(), settings.file().toString());
    }

    /**
     * Starts sending a meeting's notes; returns the meeting in state EXPORTING.
     *
     * @throws NoSuchElementException if there is no such meeting
     * @throws IllegalStateException  if it cannot be sent (no notes, Notion not set up)
     */
    public Meeting submit(String id) {
        Meeting meeting = store.find(id).orElseThrow(() -> new NoSuchElementException("Meeting not found"));
        if (meeting.status() != Meeting.Status.READY) throw new IllegalStateException("Only processed meetings can be sent to Notion");
        Path folder = store.folder(id).orElseThrow(() -> new NoSuchElementException("Meeting not found"));
        if (meeting.notes() == null || !Files.isRegularFile(folder.resolve(MeetingJobService.NOTES_JSON))) {
            throw new IllegalStateException("This meeting has no notes to send");
        }
        NotionSettings notion = settings.notion();
        if (!notion.hasToken()) {
            throw new IllegalStateException("Notion is not set up: put the integration token into " + settings.file() + " (notion.token)");
        }
        if (!notion.hasDatabase() && notion.parentPageId() == null) {
            throw new IllegalStateException("Notion is not set up: put the link of a page shared with the integration into "
                    + settings.file() + " (notion.parentPageId); the Meetings database is created there");
        }
        if (!queued.add(id)) return meeting;
        Meeting.NotionExport previous = meeting.notion();
        Meeting exporting = meeting.withNotion(new Meeting.NotionExport(Meeting.NotionState.EXPORTING,
                previous == null ? null : previous.pageId(), previous == null ? null : previous.url(),
                previous == null ? null : previous.exportedAt(), null));
        store.save(exporting);
        executor.execute(() -> {
            try {
                run(id);
            } finally {
                queued.remove(id);
            }
        });
        return exporting;
    }

    /** Sends the notes after processing when the user turned that on; problems are logged, never thrown. */
    public void afterProcessing(String id) {
        NotionSettings notion = settings.notion();
        if (!notion.autoExport()) return;
        try {
            submit(id);
        } catch (RuntimeException e) {
            log.warn("Meeting {}: not sent to Notion automatically: {}", id, e.getMessage());
        }
    }

    private void run(String id) {
        Meeting meeting = store.find(id).orElse(null);
        Path folder = store.folder(id).orElse(null);
        if (meeting == null || folder == null) return;
        Meeting.NotionExport previous = meeting.notion();
        try {
            MeetingNotes notes = json.readValue(folder.resolve(MeetingJobService.NOTES_JSON).toFile(), MeetingNotes.class);
            String model = meeting.notes() != null && meeting.notes().model() != null ? meeting.notes().model() : "a local model";
            NotionSettings notion = settings.notion();
            NotionClient client = clients.apply(notion.token());
            NotionPageBuilder builder = new NotionPageBuilder(json, ZoneId.systemDefault());
            String dataSource = dataSource(client, builder, notion);

            JsonNode page = client.post("pages", builder.pageRequest(dataSource, meeting, notes));
            String pageId = page.path("id").asText(null);
            if (pageId == null) throw new IOException("Notion did not return the new page");
            try {
                appendBody(client, builder, pageId, builder.body(notes, model, readTranscript(folder)));
            } catch (IOException | RuntimeException e) {
                trashQuietly(client, pageId);
                throw e;
            }
            if (previous != null && previous.pageId() != null && !previous.pageId().equals(pageId)) {
                trashQuietly(client, previous.pageId());
            }
            save(id, new Meeting.NotionExport(Meeting.NotionState.EXPORTED, pageId, page.path("url").asText(null), Instant.now(), null));
            log.info("Meeting {} sent to Notion", id);
        } catch (Exception e) {
            String message = message(e);
            log.warn("Meeting {}: sending to Notion failed: {}", id, message);
            save(id, new Meeting.NotionExport(Meeting.NotionState.FAILED, previous == null ? null : previous.pageId(),
                    previous == null ? null : previous.url(), previous == null ? null : previous.exportedAt(), message));
        }
    }

    /** The data source pages are created in: from the settings, from the database id, or a new "Meetings" database. */
    private String dataSource(NotionClient client, NotionPageBuilder builder, NotionSettings notion) throws IOException {
        if (notion.dataSourceId() != null) return notion.dataSourceId();
        if (notion.databaseId() != null) {
            String dataSource = firstDataSource(client.get("databases/" + notion.databaseId()));
            settings.saveNotionDatabase(null, dataSource);
            return dataSource;
        }
        JsonNode database = client.post("databases", builder.databaseRequest(notion.parentPageId()));
        String databaseId = database.path("id").asText(null);
        String dataSource = firstDataSource(database);
        settings.saveNotionDatabase(databaseId, dataSource);
        log.info("Created the Notion database {} for meeting notes", databaseId);
        return dataSource;
    }

    private static String firstDataSource(JsonNode database) throws IOException {
        String id = database.path("data_sources").path(0).path("id").asText(null);
        if (id == null) throw new IOException("The Notion database has no data source");
        return id;
    }

    /** Appends the body in as few requests as Notion's limits allow. */
    private void appendBody(NotionClient client, NotionPageBuilder builder, String pageId, List<NotionPageBuilder.Block> blocks)
            throws IOException {
        int i = 0;
        while (i < blocks.size()) {
            List<ObjectNode> batch = new ArrayList<>();
            List<ObjectNode> leftover = List.of();
            long bytes = 0;
            while (i < blocks.size() && batch.size() < NotionPageBuilder.MAX_ITEMS) {
                NotionPageBuilder.Block block = blocks.get(i);
                ObjectNode node = block.node().deepCopy();
                List<ObjectNode> inline = List.of();
                if (!block.children().isEmpty()) {
                    inline = builder.batches(block.children(), NotionPageBuilder.MAX_REQUEST_BYTES / 2).getFirst();
                    ((ObjectNode) node.get(node.get("type").asText())).set("children", json.valueToTree(inline));
                }
                long size = builder.size(node);
                if (!batch.isEmpty() && bytes + size > NotionPageBuilder.MAX_REQUEST_BYTES) break;
                batch.add(node);
                bytes += size;
                i++;
                if (inline.size() < block.children().size()) {
                    leftover = block.children().subList(inline.size(), block.children().size());
                    break;
                }
            }
            JsonNode added = client.patch("blocks/" + pageId + "/children", children(batch));
            if (!leftover.isEmpty()) {
                String parent = added.path("results").path(batch.size() - 1).path("id").asText(null);
                if (parent == null) throw new IOException("Notion did not return the transcript section");
                for (List<ObjectNode> part : builder.batches(leftover, NotionPageBuilder.MAX_REQUEST_BYTES)) {
                    client.patch("blocks/" + parent + "/children", children(part));
                }
            }
        }
    }

    private ObjectNode children(List<ObjectNode> blocks) {
        ObjectNode body = json.createObjectNode();
        body.set("children", json.valueToTree(blocks));
        return body;
    }

    private void trashQuietly(NotionClient client, String pageId) {
        try {
            client.patch("pages/" + pageId, json.createObjectNode().put("in_trash", true));
        } catch (IOException e) {
            log.warn("Could not move the Notion page {} to the trash: {}", pageId, e.getMessage());
        }
    }

    private List<TranscriptSegment> readTranscript(Path folder) throws IOException {
        Path file = folder.resolve(TranscriptWriter.JSON_FILE);
        if (!Files.isRegularFile(file)) return List.of();
        return json.readValue(file.toFile(), TranscriptWriter.TranscriptFile.class).segments();
    }

    private void save(String id, Meeting.NotionExport export) {
        store.find(id).ifPresent(m -> store.save(m.withNotion(export)));
    }

    /** What the user can do about the error; Notion's own message where it is specific. */
    String message(Exception e) {
        if (e instanceof NotionException n) {
            return switch (n.status()) {
                case 401 -> "Notion rejected the token: check notion.token in " + settings.file();
                case 403, 404 -> "Notion cannot open the page or database: share it with the integration "
                        + "(... menu > Connections in Notion) and check the link in " + settings.file();
                case 400 -> "Notion did not accept the notes: " + n.getMessage();
                case 429, 529 -> "Notion is busy, try again in a minute";
                default -> "Notion answered " + n.status() + ": " + n.getMessage();
            };
        }
        if (e instanceof ConnectException || e instanceof HttpTimeoutException || e instanceof java.nio.channels.UnresolvedAddressException) {
            return "Cannot reach Notion: check the internet connection";
        }
        if (e instanceof java.nio.file.NoSuchFileException) return "The notes file is missing: process the meeting again";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** A send cut short by a restart is shown as failed, so the user can send it again. */
    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        for (Meeting meeting : store.findAll()) {
            Meeting.NotionExport export = meeting.notion();
            if (export != null && export.state() == Meeting.NotionState.EXPORTING) {
                store.save(meeting.withNotion(new Meeting.NotionExport(Meeting.NotionState.FAILED, export.pageId(), export.url(),
                        export.exportedAt(), "Interrupted by a restart: send it again")));
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
