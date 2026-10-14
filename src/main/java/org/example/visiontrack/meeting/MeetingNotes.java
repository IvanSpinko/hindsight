package org.example.visiontrack.meeting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Meeting notes as written by the model (spec section 6). Unknown values are {@code null} or empty lists, never
 * guesses; {@link #cleaned} enforces that on whatever the model returned. Times are {@code HH:MM:SS} on the
 * meeting timeline and {@code due} is an ISO date.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeetingNotes(
        String title,
        String type,
        String project,
        List<String> tags,
        List<String> participants,
        String summary,
        List<String> decisions,
        List<ActionItem> actionItems,
        List<TimelineEntry> timeline,
        List<String> openQuestions
) {

    public static final List<String> TYPES = List.of("call", "lecture", "interview", "other");

    private static final Pattern TIME = Pattern.compile("\\d{2}:[0-5]\\d:[0-5]\\d");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionItem(String task, String owner, String due, String sourceTime) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimelineEntry(String time, String topic, String details) {}

    public MeetingNotes {
        tags = nonNull(tags);
        participants = nonNull(participants);
        decisions = nonNull(decisions);
        actionItems = nonNull(actionItems);
        timeline = nonNull(timeline);
        openQuestions = nonNull(openQuestions);
    }

    /**
     * Drops what does not follow the schema instead of keeping a guess: blank text becomes null, a type outside
     * {@link #TYPES} and a malformed time or date become null, empty and repeated list entries are removed,
     * an action item without a task and a timeline entry without a topic are dropped.
     */
    public MeetingNotes cleaned() {
        String cleanType = text(type) == null ? null : type.strip().toLowerCase(Locale.ROOT);
        List<ActionItem> items = new ArrayList<>();
        for (ActionItem item : actionItems) {
            if (item == null || text(item.task()) == null) continue;
            items.add(new ActionItem(text(item.task()), text(item.owner()), date(item.due()), time(item.sourceTime())));
        }
        List<TimelineEntry> entries = new ArrayList<>();
        for (TimelineEntry entry : timeline) {
            if (entry == null || text(entry.topic()) == null) continue;
            entries.add(new TimelineEntry(time(entry.time()), text(entry.topic()), text(entry.details())));
        }
        // The speaker labels of the transcript are not people
        List<String> people = texts(participants).stream()
                .filter(p -> !p.equalsIgnoreCase("Me") && !p.equalsIgnoreCase("Other participants")).toList();
        return new MeetingNotes(text(title), cleanType != null && TYPES.contains(cleanType) ? cleanType : null, text(project),
                texts(tags), people, text(summary), texts(decisions), items, entries, texts(openQuestions));
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }

    private static String text(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() || stripped.equalsIgnoreCase("null") || stripped.equalsIgnoreCase("unknown") ? null : stripped;
    }

    private static List<String> texts(List<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String clean = text(value);
            if (clean != null) unique.add(clean);
        }
        return List.copyOf(unique);
    }

    static String time(String value) {
        String clean = text(value);
        return clean != null && TIME.matcher(clean).matches() ? clean : null;
    }

    static String date(String value) {
        String clean = text(value);
        if (clean == null) return null;
        try {
            return LocalDate.parse(clean).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
