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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-user settings in {@code %LOCALAPPDATA%\VisionTrack\settings.json}, edited by hand until the Settings tab
 * exists. The file holds secrets (the Notion token): it is read on each use, never logged and never committed.
 * A missing file means nothing is configured; a broken one is reported by its line and column only, so no part
 * of it ends up in the log.
 */
@Component
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    /** A Notion id: 32 hex digits, with or without dashes, also inside a copied link. */
    private static final Pattern NOTION_ID = Pattern.compile(
            "([0-9a-fA-F]{8})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{12})(?![0-9a-fA-F])");

    /**
     * The Notion export: {@code token} of an internal integration, {@code parentPageId} a page shared with it where
     * the app creates the database, {@code databaseId}/{@code dataSourceId} that database (written by the app),
     * {@code autoExport} sends the notes after every processed meeting.
     */
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SettingsFile(NotionSettings notion) {}

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
        if (!Files.isRegularFile(file)) return NotionSettings.NONE;
        try {
            SettingsFile settings = json.readValue(file.toFile(), SettingsFile.class);
            return settings == null || settings.notion() == null ? NotionSettings.NONE : settings.notion();
        } catch (JsonProcessingException e) {
            // The message would quote the file; only say where it is broken
            log.warn("Cannot read {}: invalid JSON near line {}, column {}", file,
                    e.getLocation() == null ? "?" : e.getLocation().getLineNr(),
                    e.getLocation() == null ? "?" : e.getLocation().getColumnNr());
            return NotionSettings.NONE;
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", file, e.getClass().getSimpleName());
            return NotionSettings.NONE;
        }
    }

    /** Remembers the database the app created (or found); everything else in the file is kept as it is. */
    public synchronized void saveNotionDatabase(String databaseId, String dataSourceId) throws IOException {
        ObjectNode root;
        try {
            JsonNode existing = json.readTree(file.toFile());
            root = existing instanceof ObjectNode object ? object : json.createObjectNode();
        } catch (NoSuchFileException | FileNotFoundException e) {
            root = json.createObjectNode();
        } catch (JsonProcessingException e) {
            throw new IOException("Cannot update " + file + ": it is not valid JSON");
        }
        ObjectNode notion = root.get("notion") instanceof ObjectNode object ? object : root.putObject("notion");
        if (databaseId != null) notion.put("databaseId", databaseId);
        if (dataSourceId != null) notion.put("dataSourceId", dataSourceId);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        json.writeValue(temp.toFile(), root);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
