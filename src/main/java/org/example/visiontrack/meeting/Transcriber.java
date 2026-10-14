package org.example.visiontrack.meeting;

import org.example.visiontrack.analysis.ProgressListener;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Speech recognition for the audio tracks of a meeting (16 kHz mono 16-bit WAV each). */
public interface Transcriber {

    /**
     * One audio track: {@code offsetSeconds} is where it starts on the meeting timeline, {@code source} is
     * {@code mic} (the user) or {@code system} (the other participants).
     */
    record Track(Path wav, double offsetSeconds, String source) {}

    /** What a run produced, and on which hardware (shown to the user, e.g. "large-v3-turbo on CUDA"). */
    record Result(List<TranscriptSegment> segments, String model, String backend, List<String> languages) {}

    /** Recognises all tracks with one loaded model; the segments of all tracks are returned in time order. */
    Result transcribe(List<Track> tracks, ProgressListener progress) throws IOException;
}
