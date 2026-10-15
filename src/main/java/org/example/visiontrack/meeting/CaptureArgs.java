package org.example.visiontrack.meeting;

import java.nio.file.Path;
import java.util.List;

/** ffmpeg arguments shared by all capture tracks. */
final class CaptureArgs {

    /** Matroska stays readable when ffmpeg is killed mid-write, unlike MP4 or WAV headers. */
    static final String SEGMENT_EXTENSION = "mkv";

    private CaptureArgs() {}

    static List<String> common(int maxSeconds) {
        // -t is a safety net: a capture orphaned by a crashed app stops by itself
        return List.of("-hide_banner", "-nostats", "-loglevel", "warning", "-y", "-t", String.valueOf(maxSeconds));
    }

    /** Video can only be split at keyframes, so force one at every segment boundary. */
    static List<String> keyframeAtEverySegment(int segmentSeconds) {
        return List.of("-force_key_frames", "expr:gte(t,n_forced*" + segmentSeconds + ")");
    }

    /** Writes {@code <prefix>-000.mkv, -001.mkv, ...}, starting a new file every {@code segmentSeconds}. */
    static List<String> segmentedOutput(Path directory, String prefix, int segmentSeconds) {
        return List.of("-f", "segment", "-segment_time", String.valueOf(segmentSeconds),
                "-segment_format", "matroska", "-reset_timestamps", "1",
                directory.resolve(prefix + "-%03d." + SEGMENT_EXTENSION).toString());
    }
}
