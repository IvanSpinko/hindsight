package org.example.visiontrack.meeting;

import org.example.visiontrack.analysis.ProgressListener;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

/** Writes {@link MeetingNotes} from what was said in a meeting. */
public interface NotesGenerator {

    /** What the notes are written from. Segment times are seconds on the meeting timeline. */
    /** {@code language}: ISO code of the notes; null for the main language of the speech. */
    record Input(String title, ZonedDateTime startedAt, double durationSeconds, List<TranscriptSegment> segments,
                 String language) {

        public Input {
            segments = List.copyOf(segments);
        }

        Input(String title, ZonedDateTime startedAt, double durationSeconds, List<TranscriptSegment> segments) {
            this(title, startedAt, durationSeconds, segments, null);
        }
    }

    /** The notes and the model that wrote them. */
    record Result(MeetingNotes notes, String model) {}

    Result generate(Input input, ProgressListener progress) throws IOException;
}
