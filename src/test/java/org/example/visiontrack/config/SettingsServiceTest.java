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

    @Test
    void meetingChoicesAreSavedAndCleared() throws Exception {
        Files.writeString(dir.resolve("settings.json"), """
                {"notion": {"token": "secret"}, "meetings": {"future": true}}
                """);
        SettingsService.MeetingPreferences chosen = new SettingsService.MeetingPreferences("D:\\Meetings", 15.0,
                SettingsService.ScreenQuality.HIGH, VisionTrackProperties.SpeechEngine.WHISPER, "RU", true, "Mic (USB)", 1);

        service().saveMeetings(chosen);

        SettingsService.MeetingPreferences saved = service().meetings();
        assertThat(saved).isEqualTo(chosen);
        assertThat(saved.notesLanguage()).isEqualTo("ru");
        assertThat(saved.directory(Path.of("default"))).isEqualTo(Path.of("D:\\Meetings"));
        assertThat(service().notion().token()).isEqualTo("secret");

        service().saveMeetings(SettingsService.MeetingPreferences.NONE);

        assertThat(service().meetings()).isEqualTo(SettingsService.MeetingPreferences.NONE);
        assertThat(service().meetings().screenFps(30)).isEqualTo(30);
        assertThat(service().meetings().screenQualityOrDefault()).isEqualTo(SettingsService.ScreenQuality.STANDARD);
        assertThat(json.readTree(dir.resolve("settings.json").toFile()).path("meetings").path("future").asBoolean()).isTrue();
    }

    @Test
    void notionTokenIsKeptUnlessReplacedOrRemoved() throws Exception {
        String page = "https://www.notion.so/Home-1a2b3c4d5e6f4a1b8c9d0e1f2a3b4c5d";
        service().saveNotion("secret", page, true);
        service().saveNotionDatabase("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", null);

        // Same page, token left out: both stay, so does the database found there
        service().saveNotion(null, "1a2b3c4d5e6f4a1b8c9d0e1f2a3b4c5d", false);
        SettingsService.NotionSettings notion = service().notion();
        assertThat(notion.token()).isEqualTo("secret");
        assertThat(notion.databaseId()).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(notion.autoExport()).isFalse();

        // Another page: the database under the old one is forgotten
        service().saveNotion("", "ffffffffffffffffffffffffffffffff", false);
        notion = service().notion();
        assertThat(notion.hasToken()).isFalse();
        assertThat(notion.parentPageId()).isEqualTo("ffffffff-ffff-ffff-ffff-ffffffffffff");
        assertThat(notion.hasDatabase()).isFalse();
    }

    @Test
    void aNotionPageThatIsNotALinkIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().saveNotion(null, "my page", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
