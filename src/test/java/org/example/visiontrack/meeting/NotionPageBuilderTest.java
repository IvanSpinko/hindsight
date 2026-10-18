package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class NotionPageBuilderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final NotionPageBuilder builder = new NotionPageBuilder(json, ZoneId.of("Europe/Dublin"));

    static Meeting meeting() {
        return new Meeting("id", "Weekly", Meeting.Status.READY, Instant.parse("2026-10-14T10:00:00Z"), null,
                null, null, List.of(new Meeting.Track("mic", "audio/mic.wav", 0, 1830)), null, null, null, null, null,
                new Meeting.NotesInfo("gemma", 1, 2), null);
    }

    static MeetingNotes notes() {
        return new MeetingNotes("\u041F\u043B\u0430\u043D\u0438\u0440\u043E\u0432\u0430\u043D\u0438\u0435 \u0440\u0435\u043B\u0438\u0437\u0430", "call", "VisionTrack, v2", List.of("release"), List.of("Anna", "\u0418\u0432\u0430\u043D"),
                "We agreed on the release date.\n\nThe budget is open.", List.of("Ship on Friday"),
                List.of(new MeetingNotes.ActionItem("Prepare the budget", "Anna", "2026-10-20", "00:12:40"),
                        new MeetingNotes.ActionItem("Check the servers", null, null, null)),
                List.of(new MeetingNotes.TimelineEntry("00:03:12", "Budget", "Anna shows the numbers.")),
                List.of());
    }

    @Test
    void pageHasTheMeetingProperties() {
        JsonNode page = builder.pageRequest("ds-1", meeting(), notes());

        assertThat(page.at("/parent/type").asText()).isEqualTo("data_source_id");
        assertThat(page.at("/parent/data_source_id").asText()).isEqualTo("ds-1");
        JsonNode p = page.get("properties");
        assertThat(p.at("/Name/title/0/text/content").asText()).isEqualTo("\u041F\u043B\u0430\u043D\u0438\u0440\u043E\u0432\u0430\u043D\u0438\u0435 \u0440\u0435\u043B\u0438\u0437\u0430");
        assertThat(p.at("/Date/date/start").asText()).isEqualTo("2026-10-14T11:00:00+01:00");
        assertThat(p.at("/Duration (min)/number").asDouble()).isEqualTo(30.5);
        assertThat(p.at("/Type/select/name").asText()).isEqualTo("call");
        // Notion does not allow commas in options
        assertThat(p.at("/Project/select/name").asText()).isEqualTo("VisionTrack v2");
        assertThat(p.at("/Participants/multi_select/1/name").asText()).isEqualTo("\u0418\u0432\u0430\u043D");
        assertThat(p.at("/Tags/multi_select/0/name").asText()).isEqualTo("release");
    }

    @Test
    void unknownPropertiesAreLeftOut() {
        MeetingNotes empty = new MeetingNotes(null, null, null, null, null, null, null, null, null, null);

        JsonNode p = builder.pageRequest("ds-1", meeting(), empty).get("properties");

        assertThat(p.at("/Name/title/0/text/content").asText()).isEqualTo("Weekly");
        assertThat(p.has("Type")).isFalse();
        assertThat(p.has("Project")).isFalse();
        assertThat(p.has("Tags")).isFalse();
        assertThat(p.has("Participants")).isFalse();
    }

    @Test
    void bodyHasTheSectionsInOrderAndSkipsEmptyOnes() {
        List<NotionPageBuilder.Block> blocks = builder.body(notes(), "gemma", List.of());

        List<String> outline = blocks.stream().map(b -> {
            String type = b.node().get("type").asText();
            JsonNode text = b.node().at("/" + type + "/rich_text/0/text/content");
            return text.isMissingNode() ? type : type + ": " + text.asText();
        }).toList();
        assertThat(outline).containsExactly(
                "heading_2: Summary",
                "paragraph: We agreed on the release date.",
                "paragraph: The budget is open.",
                "heading_2: Decisions",
                "bulleted_list_item: Ship on Friday",
                "heading_2: Action items",
                "to_do: Prepare the budget",
                "to_do: Check the servers",
                "heading_2: Timeline",
                "heading_3: [00:03:12] Budget",
                "paragraph: Anna shows the numbers.",
                "divider",
                "paragraph: Notes by gemma, a local model, from the recording; check names and dates before acting on them.");

        JsonNode todo = blocks.get(6).node().get("to_do");
        assertThat(todo.get("checked").asBoolean()).isFalse();
        assertThat(todo.at("/rich_text/2/text/content").asText()).isEqualTo("Anna");
        assertThat(todo.at("/rich_text/2/annotations/bold").asBoolean()).isTrue();
        assertThat(todo.at("/rich_text/3/text/content").asText()).isEqualTo(" · due 2026-10-20 · at 00:12:40");
        assertThat(blocks.get(7).node().at("/to_do/rich_text").size()).isEqualTo(1);
    }

    @Test
    void transcriptIsAToggleSectionWithOneParagraphPerTurn() {
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment(1, 3, "mic", "ru", "\u041F\u0440\u0438\u0432\u0435\u0442."),
                new TranscriptSegment(3.5, 5, "mic", "ru", "\u041D\u0430\u0447\u043D\u0451\u043C."),
                new TranscriptSegment(6, 9, "system", "en", "Hello."));

        NotionPageBuilder.Block transcript = builder.body(notes(), "gemma", segments).getLast();

        assertThat(transcript.node().at("/heading_2/is_toggleable").asBoolean()).isTrue();
        assertThat(transcript.node().at("/heading_2/rich_text/0/text/content").asText()).isEqualTo("Transcript");
        assertThat(transcript.children()).hasSize(2);
        JsonNode first = transcript.children().getFirst().at("/paragraph/rich_text");
        assertThat(first.at("/0/text/content").asText()).isEqualTo("[00:00:01] Me (RU): ");
        assertThat(first.at("/1/text/content").asText()).isEqualTo("\u041F\u0440\u0438\u0432\u0435\u0442. \u041D\u0430\u0447\u043D\u0451\u043C.");
        assertThat(transcript.children().get(1).at("/paragraph/rich_text/0/text/content").asText())
                .isEqualTo("[00:00:06] Other participants (EN): ");
    }

    @Test
    void longTextIsSplitAt2000CharactersWithoutBreakingEmoji() {
        String text = "a".repeat(1999) + "😀" + "b".repeat(2500);

        List<String> pieces = NotionPageBuilder.split(text, 2000);

        assertThat(pieces).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(2000));
        assertThat(String.join("", pieces)).isEqualTo(text);
        assertThat(pieces.get(1)).startsWith("😀");

        JsonNode summary = builder.body(new MeetingNotes(null, null, null, null, null, text, null, null, null, null), "m", List.of())
                .get(1).node().at("/paragraph/rich_text");
        assertThat(summary.size()).isEqualTo(3);
    }

    @Test
    void batchesRespectTheBlockCountAndSize() {
        List<ObjectNode> small = new ArrayList<>();
        IntStream.range(0, 250).forEach(i -> small.add(json.createObjectNode().put("n", i)));
        assertThat(builder.batches(small, 1_000_000)).extracting(List::size).containsExactly(100, 100, 50);

        List<ObjectNode> big = new ArrayList<>();
        IntStream.range(0, 5).forEach(i -> big.add(json.createObjectNode().put("text", "x".repeat(1000))));
        assertThat(builder.batches(big, 2500)).extracting(List::size).containsExactly(2, 2, 1);
    }

    @Test
    void databaseHasAColumnForEveryProperty() {
        JsonNode request = builder.databaseRequest("page-1");

        assertThat(request.at("/parent/page_id").asText()).isEqualTo("page-1");
        assertThat(request.at("/title/0/text/content").asText()).isEqualTo("Meetings");
        JsonNode properties = request.at("/initial_data_source/properties");
        assertThat(properties.has("Name") && properties.get("Name").has("title")).isTrue();
        assertThat(properties.at("/Date").has("date")).isTrue();
        assertThat(properties.at("/Duration (min)/number/format").asText()).isEqualTo("number");
        assertThat(properties.at("/Type/select/options").findValuesAsText("name")).containsExactly("call", "lecture", "interview", "other");
        assertThat(properties.at("/Tags").has("multi_select")).isTrue();
        assertThat(properties.at("/Participants").has("multi_select")).isTrue();
        assertThat(properties.at("/Project").has("select")).isTrue();
    }
}
