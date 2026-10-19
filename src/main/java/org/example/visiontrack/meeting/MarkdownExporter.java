package org.example.visiontrack.meeting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Writes {@code notes.md} next to the recording, with a relative link to the transcript. */
public final class MarkdownExporter implements NotesExporter {

    static final String FILE = "notes.md";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH);

    @Override
    public void export(Path folder, Meeting meeting, MeetingNotes notes, String model) throws IOException {
        Files.writeString(folder.resolve(FILE), markdown(meeting, notes, model, Files.isRegularFile(folder.resolve(TranscriptWriter.MARKDOWN_FILE))),
                StandardCharsets.UTF_8);
    }

    static String markdown(Meeting meeting, MeetingNotes notes, String model, boolean hasTranscript) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(notes.title() != null ? notes.title() : meeting.title()).append("\n\n");

        List<String> facts = new ArrayList<>();
        facts.add(DATE.format(meeting.startedAt().atZone(ZoneId.systemDefault())));
        double duration = meeting.tracks().stream().mapToDouble(Meeting.Track::durationSeconds).max().orElse(0);
        if (duration > 0) facts.add(TranscriptWriter.clock(duration));
        if (notes.type() != null) facts.add(notes.type());
        if (notes.project() != null) facts.add("project: " + notes.project());
        md.append('_').append(String.join(" · ", facts)).append("_\n");
        if (!notes.participants().isEmpty()) md.append("\n**Participants:** ").append(String.join(", ", notes.participants())).append('\n');
        if (!notes.tags().isEmpty()) {
            md.append("\n**Tags:** ").append(String.join(", ", notes.tags().stream().map(t -> "`" + t + "`").toList())).append('\n');
        }

        md.append("\n## Summary\n\n").append(notes.summary() != null ? notes.summary() : "_No summary._").append('\n');

        md.append("\n## Decisions\n\n");
        list(md, notes.decisions());

        md.append("\n## Action items\n\n");
        if (notes.actionItems().isEmpty()) md.append("_None._\n");
        for (MeetingNotes.ActionItem item : notes.actionItems()) {
            md.append("- [ ] ").append(item.task());
            List<String> details = new ArrayList<>();
            if (item.owner() != null) details.add("**" + item.owner() + "**");
            if (item.due() != null) details.add("due " + item.due());
            if (item.sourceTime() != null) details.add("at " + item.sourceTime());
            if (!details.isEmpty()) md.append(" — ").append(String.join(" · ", details));
            md.append('\n');
        }

        md.append("\n## Timeline\n");
        if (notes.timeline().isEmpty()) md.append("\n_None._\n");
        for (MeetingNotes.TimelineEntry entry : notes.timeline()) {
            md.append("\n### ");
            if (entry.time() != null) md.append('[').append(entry.time()).append("] ");
            md.append(entry.topic()).append('\n');
            if (entry.details() != null) md.append('\n').append(entry.details()).append('\n');
        }

        md.append("\n## Open questions\n\n");
        list(md, notes.openQuestions());

        md.append("\n---\n\n_Notes by ").append(model).append(", a local model, from the recording; check names and dates before acting on them._");
        if (hasTranscript) md.append(" Full transcript: [").append(TranscriptWriter.MARKDOWN_FILE).append("](")
                .append(TranscriptWriter.MARKDOWN_FILE).append(").");
        md.append('\n');
        return md.toString();
    }

    private static void list(StringBuilder md, List<String> items) {
        if (items.isEmpty()) md.append("_None._\n");
        for (String item : items) md.append("- ").append(item).append('\n');
    }
}
