package org.example.visiontrack.meeting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Records what the computer plays (the other participants): {@code audio-loopback.exe} piped into ffmpeg. */
final class SystemAudioCapture {

    /** The default output device and the format it plays in. */
    record Device(int sampleRate, int channels, String name) {}

    private SystemAudioCapture() {}

    static boolean isInstalled(Path helper) {
        return helper != null && Files.isRegularFile(helper);
    }

    /** Asks the helper which device is the default output and in which format it plays. */
    static Device defaultDevice(Path helper) throws IOException {
        Process process = new ProcessBuilder(helper.toString(), "--info").redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("audio-loopback did not answer");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while asking for the output device", e);
        }
        if (process.exitValue() != 0) throw new IOException(output.isEmpty() ? "audio-loopback failed" : output);
        return parseInfo(output);
    }

    /** {@code "44100 2 Speakers (JBL Charge 5)"}. */
    static Device parseInfo(String line) throws IOException {
        String[] parts = line.strip().split(" ", 3);
        try {
            return new Device(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), parts.length > 2 ? parts[2] : "Output device");
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new IOException("Unexpected answer from audio-loopback: " + line);
        }
    }

    /** The helper's command; it stops by itself after {@code maxSeconds}, like the ffmpeg captures. */
    static List<String> source(Path helper, int maxSeconds) {
        return List.of(helper.toString(), "--seconds", String.valueOf(maxSeconds));
    }

    /** ffmpeg reading the helper's float PCM from stdin, writing 16 kHz mono 16-bit parts. */
    static List<String> args(Device device, Path directory, String prefix, int segmentSeconds, int maxSeconds) {
        List<String> args = new ArrayList<>(CaptureArgs.common(maxSeconds));
        args.addAll(List.of("-f", "f32le", "-ar", String.valueOf(device.sampleRate()), "-ac", String.valueOf(device.channels()),
                "-i", "pipe:0", "-ac", "1", "-ar", String.valueOf(AudioCapture.SAMPLE_RATE), "-c:a", "pcm_s16le"));
        args.addAll(CaptureArgs.segmentedOutput(directory, prefix, segmentSeconds));
        return args;
    }
}
