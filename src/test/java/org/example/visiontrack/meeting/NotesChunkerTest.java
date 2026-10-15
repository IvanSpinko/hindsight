package org.example.visiontrack.meeting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotesChunkerTest {

    private static final double TEN_MINUTES = 600;

    @Test
    void shortMeetingIsOnePart() {
        List<NotesChunker.Part> parts = NotesChunker.split(speech(0, 840, 20), TEN_MINUTES);

        assertThat(parts).hasSize(1);
        assertThat(parts.getFirst().segments()).hasSize(43);
    }

    @Test
    void longMeetingIsCutBetweenSegments() {
        List<TranscriptSegment> segments = speech(0, 3600, 25);  // one hour, a segment every 25 s

        List<NotesChunker.Part> parts = NotesChunker.split(segments, TEN_MINUTES);

        assertThat(parts).hasSize(6);
        assertThat(parts.stream().mapToInt(p -> p.segments().size()).sum()).isEqualTo(segments.size());
        for (NotesChunker.Part part : parts) {
            assertThat(part.segments()).allMatch(s -> s.start() >= part.start() && s.start() < part.end() + 25);
        }
        assertThat(parts.get(1).start()).isEqualTo(600);
    }

    @Test
    void shortTailIsMergedIntoTheLastPart() {
        List<NotesChunker.Part> parts = NotesChunker.split(speech(0, 1260, 30), TEN_MINUTES);

        assertThat(parts).hasSize(2);
        assertThat(parts.getLast().segments().getLast().start()).isEqualTo(1260);
    }

    @Test
    void noSpeechIsOneEmptyPart() {
        assertThat(NotesChunker.split(List.of(), TEN_MINUTES)).singleElement()
                .satisfies(part -> assertThat(part.segments()).isEmpty());
    }

    static List<TranscriptSegment> speech(double from, double to, double step) {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (double t = from; t <= to; t += step) segments.add(new TranscriptSegment(t, t + step - 1, "mic", "en", "words"));
        return segments;
    }
}
