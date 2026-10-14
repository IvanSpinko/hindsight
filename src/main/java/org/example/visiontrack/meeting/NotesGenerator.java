package org.example.visiontrack.meeting;

import org.example.visiontrack.analysis.ProgressListener;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

/** Writes {@link MeetingNotes} from what was said in a meeting. */
public interface NotesGenerator {

    /** What the notes are written from. Segment times are seconds on the meeting timeline. */
    record Input(String title, ZonedDateTime startedAt, double durationSeconds, List<TranscriptSegment> segments) {

        public Input {
            segments = List.copyOf(segments);
        }
    }

    /** The notes and the model that wrote them. */
    record Result(MeetingNotes notes, String model) {}

    Result generate(Input input, ProgressListener progress) throws IOException;
}
