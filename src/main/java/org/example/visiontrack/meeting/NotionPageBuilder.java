package org.example.visiontrack.meeting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the Notion database, page properties and page body from the notes,
 * within Notion's limits (2000 characters per text, 100 items per array).
 */
final class NotionPageBuilder {

    static final String DATABASE_TITLE = "Meetings";
    static final String NAME = "Name";
    static final String DATE = "Date";
    static final String DURATION = "Duration (min)";
    static final String TYPE = "Type";
    static final String PROJECT = "Project";
    static final String TAGS = "Tags";
    static final String PARTICIPANTS = "Participants";

    static final int MAX_TEXT = 2000;
    static final int MAX_ITEMS = 100;
    /** Well under Notion's 500 KB per request, so a batch of long transcript paragraphs still fits. */
    static final int MAX_REQUEST_BYTES = 300_000;
    private static final int MAX_OPTION = 100;
    private static final Map<String, String> TYPE_COLORS = Map.of("call", "blue", "lecture", "purple", "interview", "green", "other", "gray");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);

    /** A top-level block and its children; children beyond one request are appended to the block afterwards. */
    record Block(ObjectNode node, List<ObjectNode> children) {
        Block {
            children = List.copyOf(children);
        }

        Block(ObjectNode node) {
            this(node, List.of());
        }
    }

    private final ObjectMapper json;
    private final ZoneId zone;

    NotionPageBuilder(ObjectMapper json, ZoneId zone) {
        this.json = json;
        this.zone = zone;
    }

    /** {@code POST /v1/databases}: the "Meetings" database with one column per page property, inside a shared page. */
    ObjectNode databaseRequest(String parentPageId) {
        ObjectNode request = json.createObjectNode();
        request.putObject("parent").put("type", "page_id").put("page_id", parentPageId);
        request.set("title", richText(DATABASE_TITLE));
        ObjectNode properties = request.putObject("initial_data_source").putObject("properties");
        properties.putObject(NAME).putObject("title");
        properties.putObject(DATE).putObject("date");
        properties.putObject(DURATION).putObject("number").put("format", "number");
        ArrayNode types = properties.putObject(TYPE).putObject("select").putArray("options");
        for (String type : MeetingNotes.TYPES) types.addObject().put("name", type).put("color", TYPE_COLORS.get(type));
        properties.putObject(PROJECT).putObject("select");
        properties.putObject(TAGS).putObject("multi_select");
        properties.putObject(PARTICIPANTS).putObject("multi_select");
        return request;
    }

    /** {@code POST /v1/pages}: a row of the database with the meeting's properties; the body is appended separately. */
    ObjectNode pageRequest(String dataSourceId, Meeting meeting, MeetingNotes notes) {
        ObjectNode request = json.createObjectNode();
        request.putObject("parent").put("type", "data_source_id").put("data_source_id", dataSourceId);
        ObjectNode properties = request.putObject("properties");
        String title = notes.title() != null ? notes.title() : meeting.title();
        properties.putObject(NAME).set("title", richText(title == null ? MeetingStore.DEFAULT_TITLE : title));
        properties.putObject(DATE).putObject("date").put("start", DATE_TIME.format(meeting.startedAt().atZone(zone)));
        double seconds = meeting.tracks().stream().mapToDouble(Meeting.Track::durationSeconds).max().orElse(0);
        if (seconds > 0) properties.putObject(DURATION).put("number", Math.round(seconds / 6.0) / 10.0);
        if (notes.type() != null) properties.putObject(TYPE).putObject("select").put("name", option(notes.type()));
        if (notes.project() != null) properties.putObject(PROJECT).putObject("select").put("name", option(notes.project()));
        if (!notes.tags().isEmpty()) properties.putObject(TAGS).set("multi_select", options(notes.tags()));
        if (!notes.participants().isEmpty()) properties.putObject(PARTICIPANTS).set("multi_select", options(notes.participants()));
        return request;
    }

    List<Block> body(MeetingNotes notes, String model, List<TranscriptSegment> transcript) {
        List<Block> blocks = new ArrayList<>();
        if (notes.summary() != null) {
            blocks.add(new Block(heading("heading_2", "Summary")));
            for (String paragraph : notes.summary().split("\\n\\s*\\n")) {
                if (!paragraph.isBlank()) blocks.add(new Block(block("paragraph", richText(paragraph.strip()))));
            }
        }
        if (!notes.decisions().isEmpty()) {
            blocks.add(new Block(heading("heading_2", "Decisions")));
            for (String decision : notes.decisions()) blocks.add(new Block(block("bulleted_list_item", richText(decision))));
        }
        if (!notes.actionItems().isEmpty()) {
            blocks.add(new Block(heading("heading_2", "Action items")));
            for (MeetingNotes.ActionItem item : notes.actionItems()) blocks.add(new Block(actionItem(item)));
        }
        if (!notes.timeline().isEmpty()) {
            blocks.add(new Block(heading("heading_2", "Timeline")));
            for (MeetingNotes.TimelineEntry entry : notes.timeline()) {
                blocks.add(new Block(heading("heading_3", entry.time() == null ? entry.topic() : "[" + entry.time() + "] " + entry.topic())));
                if (entry.details() != null) blocks.add(new Block(block("paragraph", richText(entry.details()))));
            }
        }
        if (!notes.openQuestions().isEmpty()) {
            blocks.add(new Block(heading("heading_2", "Open questions")));
            for (String question : notes.openQuestions()) blocks.add(new Block(block("bulleted_list_item", richText(question))));
        }
        blocks.add(new Block(divider()));
        ArrayNode credit = json.createArrayNode();
        credit.add(span("Notes by " + model + ", a local model, from the recording; check names and dates before acting on them.",
                false, true, "gray"));
        blocks.add(new Block(block("paragraph", credit)));
        if (!transcript.isEmpty()) {
            ObjectNode section = heading("heading_2", "Transcript");
            ((ObjectNode) section.get("heading_2")).put("is_toggleable", true);
            blocks.add(new Block(section, transcript(transcript)));
        }
        return blocks;
    }

    /** One paragraph per speaker turn, as in transcript.md: "[00:01:02] Me (RU): text". */
    private List<ObjectNode> transcript(List<TranscriptSegment> segments) {
        boolean showLanguage = segments.stream().map(TranscriptSegment::language).distinct().count() > 1;
        List<ObjectNode> paragraphs = new ArrayList<>();
        for (List<TranscriptSegment> turn : TranscriptWriter.paragraphs(segments)) {
            TranscriptSegment first = turn.getFirst();
            StringBuilder label = new StringBuilder("[").append(TranscriptWriter.clock(first.start())).append("] ")
                    .append(TranscriptWriter.speaker(first.source()));
            if (showLanguage && first.language() != null) label.append(" (").append(first.language().toUpperCase(Locale.ROOT)).append(')');
            ArrayNode text = json.createArrayNode();
            text.add(span(label.append(": ").toString(), true, false, null));
            appendText(text, String.join(" ", turn.stream().map(TranscriptSegment::text).toList()), false, false, null);
            paragraphs.add(block("paragraph", text));
        }
        return paragraphs;
    }

    private ObjectNode actionItem(MeetingNotes.ActionItem item) {
        ArrayNode text = json.createArrayNode();
        appendText(text, item.task(), false, false, null);
        List<String> details = new ArrayList<>();
        if (item.due() != null) details.add("due " + item.due());
        if (item.sourceTime() != null) details.add("at " + item.sourceTime());
        if (item.owner() != null) {
            text.add(span(" — ", false, false, null));
            text.add(span(item.owner(), true, false, null));
        }
        if (!details.isEmpty()) text.add(span((item.owner() == null ? " — " : " · ") + String.join(" · ", details), false, false, "gray"));
        ObjectNode block = block("to_do", text);
        ((ObjectNode) block.get("to_do")).put("checked", false);
        return block;
    }

    /** Splits blocks into requests of at most {@link #MAX_ITEMS} blocks and {@code maxBytes} of JSON. */
    List<List<ObjectNode>> batches(List<ObjectNode> blocks, int maxBytes) {
        List<List<ObjectNode>> batches = new ArrayList<>();
        List<ObjectNode> current = new ArrayList<>();
        long bytes = 0;
        for (ObjectNode block : blocks) {
            long size = size(block);
            if (!current.isEmpty() && (current.size() == MAX_ITEMS || bytes + size > maxBytes)) {
                batches.add(current);
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(block);
            bytes += size;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    long size(ObjectNode node) {
        try {
            return json.writeValueAsBytes(node).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode heading(String type, String text) {
        return block(type, richText(text));
    }

    private ObjectNode divider() {
        ObjectNode block = json.createObjectNode().put("object", "block").put("type", "divider");
        block.putObject("divider");
        return block;
    }

    private ObjectNode block(String type, ArrayNode text) {
        ObjectNode block = json.createObjectNode().put("object", "block").put("type", type);
        block.putObject(type).set("rich_text", text);
        return block;
    }

    ArrayNode richText(String text) {
        ArrayNode array = json.createArrayNode();
        appendText(array, text, false, false, null);
        return array;
    }

    /** Adds the text in pieces of at most 2000 characters; whatever is beyond 100 pieces is cut (over 190k characters). */
    private void appendText(ArrayNode array, String text, boolean bold, boolean italic, String color) {
        for (String piece : split(text, MAX_TEXT)) {
            if (array.size() >= MAX_ITEMS) return;
            array.add(span(piece, bold, italic, color));
        }
    }

    private ObjectNode span(String content, boolean bold, boolean italic, String color) {
        ObjectNode span = json.createObjectNode().put("type", "text");
        span.putObject("text").put("content", content);
        if (bold || italic || color != null) {
            ObjectNode annotations = span.putObject("annotations");
            if (bold) annotations.put("bold", true);
            if (italic) annotations.put("italic", true);
            if (color != null) annotations.put("color", color);
        }
        return span;
    }

    /** Pieces of at most {@code max} UTF-16 characters, never cutting a surrogate pair (emoji) in half. */
    static List<String> split(String text, int max) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + max);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            pieces.add(text.substring(start, end));
            start = end;
        }
        return pieces;
    }

    private ArrayNode options(List<String> values) {
        ArrayNode array = json.createArrayNode();
        values.stream().map(NotionPageBuilder::option).filter(name -> !name.isEmpty()).distinct().limit(MAX_ITEMS).forEach(name -> array.addObject().put("name", name));
        return array;
    }

    /** Notion's select options may not contain commas and are at most 100 characters. */
    static String option(String value) {
        String clean = value.replace(',', ' ').replaceAll("\\s+", " ").strip();
        return clean.length() > MAX_OPTION ? clean.substring(0, MAX_OPTION).strip() : clean;
    }
}
