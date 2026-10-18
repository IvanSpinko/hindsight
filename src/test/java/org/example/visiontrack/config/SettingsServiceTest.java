package org.example.visiontrack.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsServiceTest {

    @TempDir
    Path dir;

    private final ObjectMapper json = new ObjectMapper();

    private SettingsService service() {
        return new SettingsService(dir.resolve("settings.json"), json);
    }

    @Test
    void notionIdsAreTakenFromIdsAndLinks() {
        String id = "1a2b3c4d-5e6f-4a1b-8c9d-0e1f2a3b4c5d";
        assertThat(SettingsService.notionId("1a2b3c4d5e6f4a1b8c9d0e1f2a3b4c5d")).isEqualTo(id);
        assertThat(SettingsService.notionId(" " + id.toUpperCase() + " ")).isEqualTo(id);
        assertThat(SettingsService.notionId("https://www.notion.so/team/Weekly-notes-1a2b3c4d5e6f4a1b8c9d0e1f2a3b4c5d")).isEqualTo(id);
        // The id after ?v= is the view of a database, not the database
        assertThat(SettingsService.notionId("https://www.notion.so/1a2b3c4d5e6f4a1b8c9d0e1f2a3b4c5d?v=ffffffffffffffffffffffffffffffff"))
                .isEqualTo(id);
        assertThat(SettingsService.notionId("not an id")).isNull();
        assertThat(SettingsService.notionId(null)).isNull();
    }

    @Test
    void missingOrBrokenFileMeansNotConfigured() throws Exception {
        assertThat(service().notion().hasToken()).isFalse();

        Files.writeString(dir.resolve("settings.json"), "{\"notion\": {\"token\": \"secret\", ");

        assertThat(service().notion().hasToken()).isFalse();
        assertThat(service().notion().hasDatabase()).isFalse();
    }

    @Test
    void savingTheDatabaseKeepsEverythingElse() throws Exception {
        Files.writeString(dir.resolve("settings.json"), """
                {"notion": {"token": "secret", "parentPageId": "https://www.notion.so/Home-1a2b3c4d5e6f4a1b8c9d0e1f2a3b4c5d",
                            "autoExport": true, "future": 1},
                 "other": {"keep": "me"}}
                """);

        service().saveNotionDatabase("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        SettingsService.NotionSettings notion = service().notion();
        assertThat(notion.token()).isEqualTo("secret");
        assertThat(notion.parentPageId()).isEqualTo("1a2b3c4d-5e6f-4a1b-8c9d-0e1f2a3b4c5d");
        assertThat(notion.databaseId()).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(notion.dataSourceId()).isEqualTo("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        assertThat(notion.autoExport()).isTrue();
        JsonNode saved = json.readTree(dir.resolve("settings.json").toFile());
        assertThat(saved.path("other").path("keep").asText()).isEqualTo("me");
        assertThat(saved.path("notion").path("future").asInt()).isEqualTo(1);
    }

    @Test
    void savingCreatesTheFileWhenThereIsNone() throws Exception {
        service().saveNotionDatabase(null, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        assertThat(service().notion().dataSourceId()).isEqualTo("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        assertThat(service().notion().databaseId()).isNull();
    }
}
