package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingNotesTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesTheSchemaAndDropsGuesses() throws Exception {
        String answer = """
                {"title": "  Release 2.0 planning ", "type": "Call", "project": "unknown", "tags": ["release", "", "release"],
                 "participants": ["Anna", null], "summary": "We planned the release.",
                 "decisions": ["Ship on Friday"],
                 "actionItems": [
                   {"task": "Prepare the budget", "owner": "Anna", "due": "2026-10-20", "sourceTime": "00:12:40"},
                   {"task": "Book a room", "owner": null, "due": "next Friday", "sourceTime": "12:40"},
                   {"task": " ", "owner": "Me", "due": null, "sourceTime": null}],
                 "timeline": [
                   {"time": "00:03:12", "topic": "Budget", "details": null},
                   {"time": "5:00", "topic": "Dates", "details": "Friday"},
                   {"time": null, "topic": "", "details": "no topic"}],
                 "openQuestions": [], "extra": "ignored"}
                """;

        MeetingNotes notes = json.readValue(answer, MeetingNotes.class).cleaned();

        assertThat(notes.title()).isEqualTo("Release 2.0 planning");
        assertThat(notes.type()).isEqualTo("call");
        assertThat(notes.project()).isNull();
        assertThat(notes.tags()).containsExactly("release");
        assertThat(notes.participants()).containsExactly("Anna");
        assertThat(notes.actionItems()).containsExactly(
                new MeetingNotes.ActionItem("Prepare the budget", "Anna", "2026-10-20", "00:12:40"),
                new MeetingNotes.ActionItem("Book a room", null, null, null));
        assertThat(notes.timeline()).containsExactly(
                new MeetingNotes.TimelineEntry("00:03:12", "Budget", null),
                new MeetingNotes.TimelineEntry(null, "Dates", "Friday"));
        assertThat(notes.openQuestions()).isEmpty();
    }

    @Test
    void missingListsBecomeEmptyAndUnknownTypeBecomesNull() throws Exception {
        MeetingNotes notes = json.readValue("{\"type\": \"webinar\", \"summary\": null}", MeetingNotes.class).cleaned();

        assertThat(notes.type()).isNull();
        assertThat(notes.summary()).isNull();
        assertThat(notes.decisions()).isEmpty();
        assertThat(notes.actionItems()).isEmpty();
        assertThat(notes.timeline()).isEmpty();
    }

    @Test
    void markdownHasEverySectionWithCheckboxesAndTheTranscriptLink() {
        Meeting meeting = new Meeting("id", "Weekly", Meeting.Status.READY, Instant.parse("2026-10-14T10:00:00Z"), null,
                null, null, List.of(new Meeting.Track("mic", "audio/mic.wav", 0, 1830)), null, null, null, null, null, null, null);
        MeetingNotes notes = new MeetingNotes("Release planning", "call", "VisionTrack", List.of("release"), List.of("Anna"),
                "We agreed on the release date.", List.of("Ship on Friday"),
                List.of(new MeetingNotes.ActionItem("Prepare the budget", "Anna", "2026-10-20", "00:12:40"),
                        new MeetingNotes.ActionItem("Check the servers", null, null, null)),
                List.of(new MeetingNotes.TimelineEntry("00:03:12", "Budget", "Anna shows the numbers.")),
                List.of());

        String md = MarkdownExporter.markdown(meeting, notes, "gemma-4-12b-it-qat-q4_0", true);

        assertThat(md).startsWith("# Release planning\n")
                .contains("00:30:30 · call · project: VisionTrack")
                .contains("**Participants:** Anna")
                .contains("## Summary\n\nWe agreed on the release date.")
                .contains("## Decisions\n\n- Ship on Friday\n")
                .contains("- [ ] Prepare the budget — **Anna** · due 2026-10-20 · at 00:12:40\n")
                .contains("- [ ] Check the servers\n")
                .contains("### [00:03:12] Budget\n\nAnna shows the numbers.\n")
                .contains("## Open questions\n\n_None._")
                .contains("[transcript.md](transcript.md)");
    }
}
