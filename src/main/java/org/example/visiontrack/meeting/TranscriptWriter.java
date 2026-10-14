package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Writes {@code transcript.json} (for the next steps) and {@code transcript.md} (for people). */
final class TranscriptWriter {

    static final String JSON_FILE = "transcript.json";
    static final String MARKDOWN_FILE = "transcript.md";

    /** A paragraph in transcript.md ends at a pause this long or after this many seconds. */
    private static final double PARAGRAPH_PAUSE = 2.0;
    private static final double PARAGRAPH_MAX = 60.0;

    record TranscriptFile(String model, String backend, List<String> languages, List<TranscriptSegment> segments) {}

    private final ObjectMapper json;

    TranscriptWriter(ObjectMapper json) {
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    void write(Path folder, String title, Transcriber.Result result) throws IOException {
        List<TranscriptSegment> sorted = result.segments().stream()
                .sorted(Comparator.comparingDouble(TranscriptSegment::start)).toList();
        json.writeValue(folder.resolve(JSON_FILE).toFile(),
                new TranscriptFile(result.model(), result.backend(), result.languages(), sorted));
        Files.writeString(folder.resolve(MARKDOWN_FILE), markdown(title, new Transcriber.Result(sorted, result.model(),
                result.backend(), result.languages())), StandardCharsets.UTF_8);
    }

    static String markdown(String title, Transcriber.Result result) {
        StringBuilder md = new StringBuilder("# Transcript: ").append(title).append("\n\n");
        md.append("_Speech recognition: ").append(result.model()).append(" on ").append(result.backend());
        if (!result.languages().isEmpty()) md.append(" · languages: ").append(String.join(", ", result.languages()));
        md.append("_\n");
        if (result.segments().isEmpty()) {
            return md.append("\nNo speech was recognised.\n").toString();
        }
        boolean showLanguage = result.languages().size() > 1;
        for (List<TranscriptSegment> paragraph : paragraphs(result.segments())) {
            TranscriptSegment first = paragraph.getFirst();
            md.append("\n**[").append(SlideExtractor.clock(first.start())).append("] ").append(speaker(first.source()));
            if (showLanguage) md.append(" (").append(first.language().toUpperCase(Locale.ROOT)).append(')');
            md.append(":** ");
            md.append(String.join(" ", paragraph.stream().map(TranscriptSegment::text).toList())).append('\n');
        }
        return md.toString();
    }

    static String speaker(String source) {
        return MeetingRecorder.MIC.equals(source) ? "Me" : "Other participants";
    }

    /** Consecutive segments of the same speaker and language, split at longer pauses. */
    private static List<List<TranscriptSegment>> paragraphs(List<TranscriptSegment> segments) {
        List<List<TranscriptSegment>> paragraphs = new ArrayList<>();
        List<TranscriptSegment> current = new ArrayList<>();
        for (TranscriptSegment s : segments) {
            if (!current.isEmpty()) {
                TranscriptSegment first = current.getFirst(), last = current.getLast();
                boolean sameVoice = s.source().equals(first.source()) && s.language().equals(first.language());
                if (!sameVoice || s.start() - last.end() > PARAGRAPH_PAUSE || s.end() - first.start() > PARAGRAPH_MAX) {
                    paragraphs.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(s);
        }
        if (!current.isEmpty()) paragraphs.add(current);
        return paragraphs;
    }
}
