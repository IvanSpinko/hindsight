package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The generator against a fake model: what it asks, how it splits and merges, how it reads answers. */
class LocalNotesGeneratorTest {

    private static final String NOTES = """
            {"title": "Release planning", "type": "call", "project": null, "tags": [], "participants": ["Anna"],
             "summary": "The release moves to Friday.", "decisions": ["Release on Friday"],
             "actionItems": [{"task": "Prepare the budget", "owner": "Anna", "due": "2026-10-20", "sourceTime": "00:00:20"}],
             "timeline": [{"time": "00:00:05", "topic": "Budget", "details": null}],
             "openQuestions": []}""";

    /** Counts one token per character and answers every request with the same notes. */
    private static final class FakeModel implements LocalNotesGenerator.Model {
        final List<String> prompts = new ArrayList<>();
        final List<Map<String, Object>> schemas = new ArrayList<>();
        String answer = NOTES;
        boolean truncated;

        @Override
        public int countTokens(String text) {
            return text.length();
        }

        @Override
        public LocalNotesGenerator.Answer chat(String system, String user, Map<String, Object> schema, int maxTokens) {
            prompts.add(user);
            schemas.add(schema);
            return new LocalNotesGenerator.Answer(answer, truncated);
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final FakeModel model = new FakeModel();

    @Test
    void shortMeetingIsOneRequestWithTheTranscriptAndTheSchema() throws IOException {
        NotesGenerator.Result result = generator(1_000_000).generate(input(300), p -> {});

        assertThat(result.model()).isEqualTo("test-model");
        assertThat(result.notes().decisions()).containsExactly("Release on Friday");
        assertThat(model.prompts).singleElement().asString()
                .contains("Meeting: Weekly").contains("[00:00:10] Me: words").contains("JSON object");
        assertThat(model.schemas.getFirst()).containsEntry("additionalProperties", false);
        assertThat((List<?>) model.schemas.getFirst().get("required")).hasSize(10);
    }

    @Test
    void longMeetingIsSummarisedPerPartThenMerged() throws IOException {
        List<Double> progress = new ArrayList<>();

        generator(1_000_000).generate(input(1800), progress::add);  // 30 minutes: three parts and a merge

        assertThat(model.prompts).hasSize(4);
        assertThat(model.prompts.get(1)).contains("part 2 of 3");
        assertThat(model.prompts.get(3)).contains("Merge these partial notes").contains("Release on Friday");
        assertThat(progress).last().isEqualTo(1.0);
    }

    @Test
    void partsTooLongForTheContextAreSplitFurther() throws IOException {
        // A 10-minute part of this transcript is ~2,500 characters; with this context each request takes a few minutes
        int context = NotesPrompt.SYSTEM.length() + 512 + 1500;

        generator(context).generate(input(600), p -> {});

        assertThat(model.prompts.size()).isGreaterThan(2);
        assertThat(model.prompts.getLast()).contains("Merge these partial notes");
        for (String prompt : model.prompts.subList(0, model.prompts.size() - 1)) {
            assertThat(prompt.length()).isLessThanOrEqualTo(1500);
        }
    }

    @Test
    void requestNamesTheLanguageMostOfTheSpeechWasIn() {
        List<TranscriptSegment> mixed = List.of(
                new TranscriptSegment(0, 5, "mic", "ru", "a long sentence spoken in the main language of the meeting"),
                new TranscriptSegment(5, 7, "system", "en", "a short one"));

        assertThat(NotesPrompt.mainLanguage(mixed)).isEqualTo("Russian");
        assertThat(NotesPrompt.mainLanguage(List.of())).isEqualTo("English");
    }

    @Test
    void languageChosenInSettingsWinsOverTheSpeech() {
        List<TranscriptSegment> russian = List.of(new TranscriptSegment(0, 5, "mic", "ru", "the meeting itself"));
        NotesGenerator.Input input = new NotesGenerator.Input("Weekly", ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, ZoneOffset.UTC),
                5, russian, "en");

        assertThat(NotesPrompt.merge(input, "[]")).contains("Write the notes in English.");
        assertThat(NotesPrompt.languageName("ru")).isEqualTo("Russian");
        assertThat(NotesPrompt.languageName("zz")).isEqualTo("English");
    }

    @Test
    void speakerLabelsAreNotParticipants() throws IOException {
        model.answer = NOTES.replace("[\"Anna\"]", "[\"Me\", \"Anna\", \"Other participants\"]");

        assertThat(generator(1_000_000).generate(input(60), p -> {}).notes().participants()).containsExactly("Anna");
    }

    @Test
    void meIsNoOwnerWhenTheRecorderWasNotOnTheRecording() throws IOException {
        model.answer = NOTES.replace("\"owner\": \"Anna\"", "\"owner\": \"Me\"");
        NotesGenerator.Input uploaded = new NotesGenerator.Input("Upload", ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, ZoneOffset.UTC), 60,
                List.of(new TranscriptSegment(0, 5, RecordingImport.MIXED, "en", "I will prepare the budget")));

        assertThat(generator(1_000_000).generate(uploaded, p -> {}).notes().actionItems().getFirst().owner()).isNull();
        assertThat(generator(1_000_000).generate(input(60), p -> {}).notes().actionItems().getFirst().owner()).isEqualTo("Me");
    }

    @Test
    void truncatedOrBrokenAnswersAreClearErrors() {
        model.truncated = true;
        assertThatThrownBy(() -> generator(1_000_000).generate(input(60), p -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("did not fit");

        model.truncated = false;
        model.answer = "{\"title\": \"Rel";
        assertThatThrownBy(() -> generator(1_000_000).generate(input(60), p -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("not valid JSON");
    }

    private LocalNotesGenerator generator(int contextTokens) {
        return new LocalNotesGenerator(model, "test-model", 600, contextTokens, 512, json);
    }

    private static NotesGenerator.Input input(double seconds) {
        return new NotesGenerator.Input("Weekly", ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, ZoneOffset.UTC), seconds,
                NotesChunkerTest.speech(0, seconds - 10, 10));
    }
}
