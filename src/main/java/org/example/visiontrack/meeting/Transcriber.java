package org.example.visiontrack.meeting;

import org.example.visiontrack.analysis.ProgressListener;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Speech recognition for one audio track (16 kHz mono 16-bit WAV). */
public interface Transcriber {

    /** What a run produced, and on which hardware (shown to the user, e.g. "large-v3-turbo on CUDA"). */
    record Result(List<TranscriptSegment> segments, String model, String backend, List<String> languages) {}

    /**
     * @param offsetSeconds where the audio starts on the meeting timeline
     * @param source        {@code mic} or {@code system}
     */
    Result transcribe(Path wav, double offsetSeconds, String source, ProgressListener progress) throws IOException;
}
