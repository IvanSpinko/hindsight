package org.example.visiontrack.meeting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.visiontrack.analysis.ProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes {@link MeetingNotes} with a local model. Long meetings are summarised in parts, then merged. */
final class LocalNotesGenerator implements NotesGenerator {

    /** The model's answer; {@code truncated} when it hit the token limit before the JSON was complete. */
    record Answer(String content, boolean truncated) {}

    /** What the generator needs from a model server (a running {@link LlamaServer} in the app). */
    interface Model {
        int countTokens(String text) throws IOException, InterruptedException;

        Answer chat(String system, String user, Map<String, Object> schema, int maxTokens) throws IOException, InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(LocalNotesGenerator.class);

    private final Model model;
    private final String modelName;
    private final double chunkSeconds;
    private final int contextTokens;
    private final int maxTokens;
    private final ObjectMapper json;

    LocalNotesGenerator(Model model, String modelName, double chunkSeconds, int contextTokens, int maxTokens, ObjectMapper json) {
        this.model = model;
        this.modelName = modelName;
        this.chunkSeconds = chunkSeconds;
        this.contextTokens = contextTokens;
        this.maxTokens = maxTokens;
        this.json = json;
    }

    @Override
    public Result generate(Input input, ProgressListener progress) throws IOException {
        try {
            List<NotesChunker.Part> parts = fitToContext(input, NotesChunker.split(input.segments(), chunkSeconds));
            int requests = parts.size() == 1 ? 1 : parts.size() + 1;
            log.info("Writing notes with {}: {} part(s)", modelName, parts.size());

            List<MeetingNotes> partial = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                partial.add(ask(NotesPrompt.part(input, parts.get(i), i, parts.size())));
                progress.onProgress((i + 1.0) / requests);
            }
            MeetingNotes notes = parts.size() == 1 ? partial.getFirst() : merge(input, partial);
            progress.onProgress(1.0);
            return new Result(withoutUnknownMe(notes, input), modelName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Writing notes was interrupted", e);
        }
    }

    /** Uploads have no microphone track, so "Me" is unknown there: a "Me" owner is dropped, not guessed. */
    static MeetingNotes withoutUnknownMe(MeetingNotes notes, Input input) {
        boolean recorderSpoke = input.segments().stream().anyMatch(s -> MeetingRecorder.MIC.equals(s.source()));
        if (recorderSpoke) return notes;
        List<MeetingNotes.ActionItem> items = notes.actionItems().stream()
                .map(i -> "Me".equalsIgnoreCase(i.owner()) ? new MeetingNotes.ActionItem(i.task(), null, i.due(), i.sourceTime()) : i)
                .toList();
        return new MeetingNotes(notes.title(), notes.type(), notes.project(), notes.tags(), notes.participants(),
                notes.summary(), notes.decisions(), items, notes.timeline(), notes.openQuestions());
    }

    /** One set of notes from partial ones; when they do not fit the context together, each half is merged first. */
    private MeetingNotes merge(Input input, List<MeetingNotes> partial) throws IOException, InterruptedException {
        String prompt = NotesPrompt.merge(input, json.writerWithDefaultPrettyPrinter().writeValueAsString(partial));
        if (partial.size() > 2 && model.countTokens(prompt) > budget()) {
            int middle = partial.size() / 2;
            return merge(input, List.of(merge(input, partial.subList(0, middle)), merge(input, partial.subList(middle, partial.size()))));
        }
        return ask(prompt);
    }

    /** Tokens left for the user prompt: the context minus the system prompt and the longest answer. */
    private int budget() throws IOException, InterruptedException {
        return contextTokens - maxTokens - model.countTokens(NotesPrompt.SYSTEM);
    }

    /** Halves every part whose prompt and answer would not fit the context, until all fit. */
    private List<NotesChunker.Part> fitToContext(Input input, List<NotesChunker.Part> parts) throws IOException, InterruptedException {
        int budget = budget();
        List<NotesChunker.Part> fitted = new ArrayList<>();
        for (NotesChunker.Part part : parts) {
            if (part.segments().size() < 2 || model.countTokens(NotesPrompt.part(input, part, 0, 2)) <= budget) {
                fitted.add(part);
                continue;
            }
            int middle = part.segments().size() / 2;
            double cut = part.segments().get(middle).start();
            fitted.addAll(fitToContext(input, List.of(
                    new NotesChunker.Part(part.start(), cut, part.segments().subList(0, middle)),
                    new NotesChunker.Part(cut, part.end(), part.segments().subList(middle, part.segments().size())))));
        }
        return fitted;
    }

    private MeetingNotes ask(String prompt) throws IOException, InterruptedException {
        Answer answer = model.chat(NotesPrompt.SYSTEM, prompt, NotesPrompt.schema(), maxTokens);
        if (answer.truncated()) {
            throw new IOException("The notes did not fit into " + maxTokens + " tokens (raise visiontrack.meetings.notes.max-tokens)");
        }
        try {
            return json.readValue(answer.content(), MeetingNotes.class).cleaned();
        } catch (JsonProcessingException e) {
            // The answer is meeting content: report its size only
            throw new IOException("The model returned notes that are not valid JSON (" + answer.content().length() + " characters)", e);
        }
    }
}
