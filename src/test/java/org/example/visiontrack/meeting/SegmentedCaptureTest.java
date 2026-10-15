package org.example.visiontrack.meeting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Runs the real bundled ffmpeg with synthetic sources (no screen or microphone needed): records in
 * 1-second segments, stops with 'q' like the app does, and joins the parts losslessly.
 */
class SegmentedCaptureTest {

    private static Ffmpeg ffmpeg;

    @TempDir
    Path dir;

    @BeforeAll
    static void locateFfmpeg() {
        ffmpeg = Ffmpeg.locate(null);
    }

    @Test
    void screenSegmentsJoinIntoOneMp4() throws Exception {
        List<String> args = new ArrayList<>(CaptureArgs.common(60));
        // The real screen encoder (NVENC), so the test also checks that it starts a part at every forced keyframe
        args.addAll(List.of("-re", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=2"));
        args.addAll(ScreenCapture.ENCODER);
        args.addAll(CaptureArgs.keyframeAtEverySegment(1));
        args.addAll(CaptureArgs.segmentedOutput(dir, "screen", 1));

        double seconds = record(args);

        List<Path> parts = parts("screen");
        assertThat(parts).hasSizeGreaterThanOrEqualTo(2);
        Path output = dir.resolve("screen.mp4");
        ffmpeg.concat(parts, output);
        assertThat(ffmpeg.durationSeconds(output)).isCloseTo(seconds, within(1.5));
    }

    @Test
    void audioSegmentsJoinIntoOneWav() throws Exception {
        List<String> args = new ArrayList<>(CaptureArgs.common(60));
        // -re paces the synthetic source like a live microphone
        args.addAll(List.of("-re", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-ac", "1", "-ar", String.valueOf(AudioCapture.SAMPLE_RATE), "-c:a", "pcm_s16le"));
        args.addAll(CaptureArgs.segmentedOutput(dir, "mic", 1));

        double seconds = record(args);

        Path output = dir.resolve("mic.wav");
        ffmpeg.concat(parts("mic"), output);
        assertThat(ffmpeg.durationSeconds(output)).isCloseTo(seconds, within(1.0));
        // 16-bit mono at 16 kHz plus a small header
        assertThat(Files.size(output)).isCloseTo((long) (ffmpeg.durationSeconds(output) * 32_000), within(4_000L));
    }

    @Test
    void pipedSystemSoundStopsWithItsSourceAndKeepsEveryPart() throws Exception {
        // A stand-in for audio-loopback: ffmpeg writing float stereo PCM to stdout in real time
        List<String> source = List.of(ffmpeg.executable().toString(), "-hide_banner", "-loglevel", "error", "-re",
                "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=44100", "-ac", "2", "-f", "f32le", "-");
        List<String> args = SystemAudioCapture.args(new SystemAudioCapture.Device(44100, 2, "test"), dir, "system", 1, 60);
        CaptureProcess process = CaptureProcess.startPiped("system", source, ffmpeg.executable(), args, dir.resolve("logs/system.log"));
        assertThat(process.pids()).hasSize(2);
        assertThat(process.survives(Duration.ofMillis(3000))).as(process.errorTail()).isTrue();
        double seconds = (System.nanoTime() - process.startedAtNanos()) / 1e9;

        process.stop(Duration.ofSeconds(10));

        assertThat(process.isAlive()).isFalse();
        Path output = dir.resolve("system.wav");
        ffmpeg.concat(parts("system"), output);
        assertThat(ffmpeg.durationSeconds(output)).isCloseTo(seconds, within(1.0));
    }

    /** Captures for ~3 s, stops gracefully and returns the wall-clock duration. */
    private double record(List<String> args) throws Exception {
        CaptureProcess process = CaptureProcess.start("test", ffmpeg.executable(), args, dir.resolve("logs/test.log"));
        assertThat(process.survives(Duration.ofMillis(3000))).as(process.errorTail()).isTrue();
        double seconds = (System.nanoTime() - process.startedAtNanos()) / 1e9;
        process.stop(Duration.ofSeconds(10));
        assertThat(process.isAlive()).isFalse();
        assertThat(process.diedUnexpectedly()).isFalse();
        return seconds;
    }

    private List<Path> parts(String prefix) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().matches(prefix + "-\\d{3}\\.mkv")).sorted().toList();
        }
    }
}
