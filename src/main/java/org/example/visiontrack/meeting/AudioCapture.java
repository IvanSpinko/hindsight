package org.example.visiontrack.meeting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Microphone capture via DirectShow, stored the way Whisper wants it: 16 kHz mono 16-bit PCM. */
final class AudioCapture {

    static final int SAMPLE_RATE = 16_000;

    private AudioCapture() {}

    static List<String> args(String device, Path directory, String prefix, int segmentSeconds, int maxSeconds) {
        List<String> args = new ArrayList<>(CaptureArgs.common(maxSeconds));
        // A small buffer (ms) keeps the audio clock close to the screen clock
        args.addAll(List.of("-f", "dshow", "-audio_buffer_size", "50", "-i", "audio=" + device,
                "-ac", "1", "-ar", String.valueOf(SAMPLE_RATE), "-c:a", "pcm_s16le"));
        args.addAll(CaptureArgs.segmentedOutput(directory, prefix, segmentSeconds));
        return args;
    }
}
