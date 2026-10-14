package org.example.visiontrack.meeting;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The instructions, the input text and the JSON schema for a notes request. */
final class NotesPrompt {

    static final String SYSTEM = """
            You write meeting notes from a speech transcript for the person who recorded the meeting. Each line \
            of the transcript is "[time] speaker: text". "Me" is the person who recorded; "Other participants" \
            are everyone else in the call.

            Rules:
            - Use only what was said. Never invent names, dates, owners, numbers or decisions. When the meeting \
            does not say something, use null or an empty list.
            - A decision is something the meeting agreed on, not an idea that was only mentioned.
            - An action item is a task someone will do. Its owner is the person named or clearly meant ("Me" \
            when the recorder takes it on). Fill in the due date only when a date, or a day relative to the \
            meeting date, was said; write it as YYYY-MM-DD.
            - Speech recognition makes mistakes. When a word is clearly misheard and the context makes the \
            meaning certain, write what was meant; otherwise keep it.
            - Write the notes in the language named in the request, also when some of the meeting was in \
            another language; keep names and technical terms as they were said.
            - Participants are the people named in the meeting, not the labels "Me" or "Other participants".
            - Be short and concrete: the summary has 3 to 6 sentences, the timeline has one entry per topic, \
            not per remark.
            - Times are HH:MM:SS from the start of the recording, taken from the transcript.
            - Answer with the JSON object only.""";

    static final String TASK = "Write the notes for this meeting as a JSON object following the schema.";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd (EEEE) HH:mm", Locale.ENGLISH);

    private NotesPrompt() {}

    /** The meeting, the part of it covered by this request, its transcript and the task. */
    static String part(NotesGenerator.Input input, NotesChunker.Part part, int index, int count) {
        StringBuilder text = new StringBuilder(header(input));
        if (count > 1) {
            text.append("This request covers part ").append(index + 1).append(" of ").append(count).append(": ")
                    .append(TranscriptWriter.clock(part.start())).append(" to ").append(TranscriptWriter.clock(part.end()))
                    .append(". Write notes for this part only; they are merged with the other parts later.\n");
        }
        return text.append('\n').append(transcript(part.segments())).append('\n').append(TASK).toString();
    }

    /** The final request for a long meeting: one set of notes from the notes of its parts. */
    static String merge(NotesGenerator.Input input, String partialNotesJson) {
        return header(input)
                + "\nThe meeting was summarised in parts, in order. Merge these partial notes into the notes of the whole "
                + "meeting: one summary of the meeting, decisions and action items without duplicates (a later part may "
                + "change or cancel an earlier one), the timeline in order, open questions that were not answered "
                + "later. Keep the times as they are.\n\nPartial notes:\n" + partialNotesJson + "\n\n" + TASK;
    }

    private static String header(NotesGenerator.Input input) {
        return "Meeting: " + input.title() + "\n"
                + "Recorded: " + DATE.format(input.startedAt()) + "\n"
                + "Length: " + TranscriptWriter.clock(input.durationSeconds()) + "\n"
                + "Write the notes in " + mainLanguage(input.segments()) + ".\n";
    }

    /**
     * The language most of the speech was in, by amount of text, as an English name ("Russian"). Models tend to
     * fall back to English when asked to pick the language themselves, so the request names it.
     */
    static String mainLanguage(List<TranscriptSegment> segments) {
        Map<String, Integer> letters = new HashMap<>();
        for (TranscriptSegment s : segments) {
            if (s.language() != null && !s.language().isBlank()) letters.merge(s.language(), s.text().length(), Integer::sum);
        }
        String code = letters.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("en");
        String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() ? "English" : name;
    }

    /** One line per speech segment: {@code [00:12:40] Me: text}. */
    static String transcript(List<TranscriptSegment> segments) {
        if (segments.isEmpty()) return "Transcript: no speech was recognised.\n";
        StringBuilder text = new StringBuilder("Transcript:\n");
        for (TranscriptSegment s : segments) {
            text.append('[').append(TranscriptWriter.clock(s.start())).append("] ")
                    .append(TranscriptWriter.speaker(s.source())).append(": ").append(s.text()).append('\n');
        }
        return text.toString();
    }

    /** JSON schema of {@link MeetingNotes}: every field required, unknown values null. */
    static Map<String, Object> schema() {
        Map<String, Object> actionItem = object(ordered(
                "task", string(false),
                "owner", string(true),
                "due", string(true),
                "sourceTime", string(true)));
        Map<String, Object> timelineEntry = object(ordered(
                "time", string(true),
                "topic", string(false),
                "details", string(true)));
        return object(ordered(
                "title", string(true),
                "type", ordered("type", List.of("string", "null"), "enum", typesWithNull()),
                "project", string(true),
                "tags", array(ordered("type", "string")),
                "participants", array(ordered("type", "string")),
                "summary", string(true),
                "decisions", array(ordered("type", "string")),
                "actionItems", array(actionItem),
                "timeline", array(timelineEntry),
                "openQuestions", array(ordered("type", "string"))));
    }

    /** A map that keeps the order of {@code keysAndValues}, so the schema is the same on every run. */
    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return map;
    }

    private static List<Object> typesWithNull() {
        List<Object> values = new ArrayList<>(MeetingNotes.TYPES);
        values.add(null);
        return values;
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        return ordered("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }

    private static Map<String, Object> string(boolean nullable) {
        return ordered("type", nullable ? List.of("string", "null") : "string");
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        return ordered("type", "array", "items", items);
    }
}
