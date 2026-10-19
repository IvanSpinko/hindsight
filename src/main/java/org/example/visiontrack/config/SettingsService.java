package org.example.visiontrack.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-user settings in {@code %LOCALAPPDATA%\VisionTrack\settings.json}. It holds the Notion token,
 * so it is read on each use and never logged; parse errors report only the position.
 */
@Component
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    /** A Notion id: 32 hex digits, with or without dashes, also inside a copied link. */
    private static final Pattern NOTION_ID = Pattern.compile(
            "([0-9a-fA-F]{8})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{12})(?![0-9a-fA-F])");

    /** Notion export settings; {@code databaseId} and {@code dataSourceId} are filled in by the app. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NotionSettings(String token, String parentPageId, String databaseId, String dataSourceId,
                                 boolean autoExport) {

        static final NotionSettings NONE = new NotionSettings(null, null, null, null, false);

        public NotionSettings {
            token = blankToNull(token);
            parentPageId = notionId(parentPageId);
            databaseId = notionId(databaseId);
            dataSourceId = notionId(dataSourceId);
        }

        public boolean hasToken() {
            return token != null;
        }

        public boolean hasDatabase() {
            return databaseId != null || dataSourceId != null;
        }
    }

    /** Screen video compression: smaller files or sharper text. */
    public enum ScreenQuality { SMALL, STANDARD, HIGH }

    /** Meeting choices from the Settings tab; a null value falls back to application.yml. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeetingPreferences(String directory, Double screenFps, ScreenQuality screenQuality,
                                     VisionTrackProperties.SpeechEngine speechEngine, String notesLanguage,
                                     Boolean deleteSpeechAudio, String microphone, Integer monitor) {

        public static final MeetingPreferences NONE = new MeetingPreferences(null, null, null, null, null, null, null, null);

        public MeetingPreferences {
            notesLanguage = blankToNull(notesLanguage);
            if (notesLanguage != null) notesLanguage = notesLanguage.toLowerCase(Locale.ROOT);
            directory = blankToNull(directory);
            microphone = blankToNull(microphone);
        }

        public Path directory(Path fallback) {
            return directory == null ? fallback : Path.of(directory);
        }

        public double screenFps(double fallback) {
            return screenFps == null ? fallback : screenFps;
        }

        public ScreenQuality screenQualityOrDefault() {
            return screenQuality == null ? ScreenQuality.STANDARD : screenQuality;
        }

        public VisionTrackProperties.SpeechEngine speechEngine(VisionTrackProperties.SpeechEngine fallback) {
            return speechEngine == null ? fallback : speechEngine;
        }

        public boolean deletesSpeechAudio() {
            return Boolean.TRUE.equals(deleteSpeechAudio);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SettingsFile(NotionSettings notion, MeetingPreferences meetings) {}

    private final Path file;
    private final ObjectMapper json;

    public SettingsService(@Value("${visiontrack.settings-file:${LOCALAPPDATA:${user.home}}/VisionTrack/settings.json}") Path file,
                           ObjectMapper objectMapper) {
        this.file = file.toAbsolutePath().normalize();
        this.json = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path file() {
        return file;
    }

    public NotionSettings notion() {
        SettingsFile settings = read();
        return settings == null || settings.notion() == null ? NotionSettings.NONE : settings.notion();
    }

    public MeetingPreferences meetings() {
        SettingsFile settings = read();
        return settings == null || settings.meetings() == null ? MeetingPreferences.NONE : settings.meetings();
    }

    /** Remembers the database the app created or found; the rest of the file is kept. */
    public void saveNotionDatabase(String databaseId, String dataSourceId) throws IOException {
        update(root -> {
            ObjectNode notion = section(root, "notion");
            if (databaseId != null) notion.put("databaseId", databaseId);
            if (dataSourceId != null) notion.put("dataSourceId", dataSourceId);
        });
    }

    /** A null {@code token} keeps the saved one, a blank one removes it; a new page forgets the old database. */
    public void saveNotion(String token, String parentPageId, boolean autoExport) throws IOException {
        String parent = notionId(parentPageId);
        if (parentPageId != null && !parentPageId.isBlank() && parent == null) {
            throw new IllegalArgumentException("This is not a Notion page link or id");
        }
        update(root -> {
            ObjectNode notion = section(root, "notion");
            if (token != null) {
                if (token.isBlank()) notion.remove("token");
                else notion.put("token", token.strip());
            }
            if (!Objects.equals(notionId(notion.path("parentPageId").asText(null)), parent)) {
                notion.remove("databaseId");
                notion.remove("dataSourceId");
            }
            if (parent == null) notion.remove("parentPageId");
            else notion.put("parentPageId", parent);
            notion.put("autoExport", autoExport);
        });
    }

    /** Replaces the meeting choices; unknown keys are kept. */
    public void saveMeetings(MeetingPreferences preferences) throws IOException {
        JsonNode values = json.valueToTree(preferences);
        update(root -> {
            ObjectNode meetings = section(root, "meetings");
            values.properties().forEach(field -> {
                if (field.getValue().isNull()) meetings.remove(field.getKey());
                else meetings.set(field.getKey(), field.getValue());
            });
        });
    }

    private SettingsFile read() {
        if (!Files.isRegularFile(file)) return null;
        try {
            return json.readValue(file.toFile(), SettingsFile.class);
        } catch (JsonProcessingException e) {
            // The message would quote the file; only say where it is broken
            log.warn("Cannot read {}: invalid JSON near line {}, column {}", file,
                    e.getLocation() == null ? "?" : e.getLocation().getLineNr(),
                    e.getLocation() == null ? "?" : e.getLocation().getColumnNr());
            return null;
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", file, e.getClass().getSimpleName());
            return null;
        }
    }

    /** Changes the file as a JSON tree and writes it back atomically, keeping unknown keys. */
    private synchronized void update(Consumer<ObjectNode> change) throws IOException {
        ObjectNode root;
        try {
            JsonNode existing = json.readTree(file.toFile());
            root = existing instanceof ObjectNode object ? object : json.createObjectNode();
        } catch (NoSuchFileException | FileNotFoundException e) {
            root = json.createObjectNode();
        } catch (JsonProcessingException e) {
            throw new IOException("Cannot update " + file + ": it is not valid JSON");
        }
        change.accept(root);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        json.writeValue(temp.toFile(), root);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static ObjectNode section(ObjectNode root, String name) {
        return root.get(name) instanceof ObjectNode object ? object : root.putObject(name);
    }

    /** The id in a Notion id or link, as 8-4-4-4-12; null if there is none. */
    public static String notionId(String value) {
        if (value == null) return null;
        // A database link has the id of its view after "?v=": that one is not the database
        String path = value.strip().replaceFirst("[?#].*$", "");
        Matcher m = NOTION_ID.matcher(path);
        String found = null;
        // A link ends with the id, after the page title
        while (m.find()) found = String.join("-", m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)).toLowerCase(Locale.ROOT);
        return found;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
