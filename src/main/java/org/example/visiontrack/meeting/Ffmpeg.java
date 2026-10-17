package org.example.visiontrack.meeting;

import org.bytedeco.javacpp.Loader;
import org.example.visiontrack.analysis.ProgressListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ffmpeg/ffprobe executables bundled with JavaCV (extracted to ~/.javacpp/cache on first use),
 * so capture works without installing FFmpeg. Recording runs in a separate ffmpeg process: if the
 * app crashes, the segments written so far stay playable.
 */
public final class Ffmpeg {

    private static final Pattern DSHOW_DEVICE = Pattern.compile("\\[dshow @ [^]]+] \"(.+)\" \\((audio|video|none)\\)");
    private static final Pattern DDAGRAB_OUTPUT = Pattern.compile("Opened dxgi output (\\d+) with dimensions (\\d+)x(\\d+)");
    /** A line of {@code -progress} output, like {@code frame=120} or {@code speed=4.1x}. */
    private static final Pattern PROGRESS_LINE = Pattern.compile("[a-z0-9_]+=\\S*");
    /** How much of the output has been written, in microseconds ({@code N/A} before the first frame). */
    private static final Pattern PROGRESS_TIME = Pattern.compile("out_time_us=(\\d+)");

    private final Path ffmpeg;
    private final Path ffprobe;

    public Ffmpeg(Path ffmpeg, Path ffprobe) {
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
    }

    /** The bundled executables, or {@code override} (a folder with ffmpeg.exe and ffprobe.exe) when set. */
    public static Ffmpeg locate(Path override) {
        if (override != null) {
            String suffix = isWindows() ? ".exe" : "";
            return new Ffmpeg(override.resolve("ffmpeg" + suffix), override.resolve("ffprobe" + suffix));
        }
        return new Ffmpeg(Path.of(Loader.load(org.bytedeco.ffmpeg.ffmpeg.class)),
                Path.of(Loader.load(org.bytedeco.ffmpeg.ffprobe.class)));
    }

    public Path executable() {
        return ffmpeg;
    }

    public record Result(int exitCode, String output) {}

    /** Runs ffmpeg to completion and returns its exit code and combined stdout/stderr. */
    public Result run(List<String> args, Duration timeout) throws IOException {
        return execute(ffmpeg, args, timeout, null);
    }

    /**
     * Runs ffmpeg like {@link #run} and reports how much of {@code durationSeconds} of input it has written, read
     * from ffmpeg's {@code -progress} output (those lines are left out of the result).
     */
    public Result run(List<String> args, Duration timeout, double durationSeconds, ProgressListener progress) throws IOException {
        List<String> withProgress = new ArrayList<>(List.of("-progress", "pipe:1", "-nostats"));
        withProgress.addAll(args);
        return execute(ffmpeg, withProgress, timeout, line -> {
            Matcher m = PROGRESS_TIME.matcher(line);
            if (m.matches() && durationSeconds > 0) {
                progress.onProgress(Math.min(1.0, Long.parseLong(m.group(1)) / 1e6 / durationSeconds));
            }
        });
    }

    public Result probe(List<String> args, Duration timeout) throws IOException {
        return execute(ffprobe, args, timeout, null);
    }

    /** Container duration in seconds, or 0 when it cannot be read. */
    public double durationSeconds(Path file) {
        try {
            Result result = probe(List.of("-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.toString()), Duration.ofSeconds(30));
            return result.exitCode() == 0 ? Double.parseDouble(result.output().strip()) : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** Lossless join (stream copy) of the segments, in order, into an MP4 or WAV file. */
    public void concat(List<Path> parts, Path output) throws IOException {
        Path list = output.resolveSibling(output.getFileName() + ".parts.txt");
        StringBuilder lines = new StringBuilder();
        for (Path part : parts) {
            lines.append("file '").append(part.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
        }
        Files.writeString(list, lines, StandardCharsets.UTF_8);
        try {
            List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y",
                    "-f", "concat", "-safe", "0", "-i", list.toString(), "-c", "copy"));
            if (output.toString().endsWith(".mp4")) args.addAll(List.of("-movflags", "+faststart"));
            args.add(output.toString());
            Result result = run(args, Duration.ofMinutes(10));
            if (result.exitCode() != 0 || !Files.isRegularFile(output)) {
                throw new IOException(result.output().strip());
            }
        } finally {
            Files.deleteIfExists(list);
        }
    }

    /** An audio track for {@link #mux}: {@code offsetSeconds} delays (or, if negative, advances) it against the video. */
    public record AudioInput(Path file, double offsetSeconds) {}

    /**
     * Puts a video and its audio tracks (microphone, system sound) into one MP4 for playback: the video is copied,
     * the audio tracks are mixed into one AAC track.
     */
    public void mux(Path video, List<AudioInput> audio, Path output) throws IOException {
        Result result = run(muxArgs(video, audio, output), Duration.ofMinutes(30));
        if (result.exitCode() != 0 || !Files.isRegularFile(output)) {
            throw new IOException("Cannot create " + output.getFileName() + ": " + result.output().strip());
        }
    }

    static List<String> muxArgs(Path video, List<AudioInput> audio, Path output) {
        List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", video.toString()));
        for (AudioInput input : audio) {
            args.addAll(List.of("-itsoffset", String.format(Locale.ROOT, "%.3f", input.offsetSeconds()), "-i", input.file().toString()));
        }
        args.addAll(List.of("-map", "0:v"));
        if (audio.size() == 1) {
            args.addAll(List.of("-map", "1:a"));
        } else if (audio.size() > 1) {
            // normalize=0 keeps each voice at its own level instead of halving both
            StringBuilder mix = new StringBuilder();
            for (int i = 1; i <= audio.size(); i++) mix.append('[').append(i).append(":a]");
            mix.append("amix=inputs=").append(audio.size()).append(":duration=longest:normalize=0[mixed]");
            args.addAll(List.of("-filter_complex", mix.toString(), "-map", "[mixed]"));
        }
        if (!audio.isEmpty()) args.addAll(List.of("-c:a", "aac", "-b:a", "96k"));
        args.addAll(List.of("-c:v", "copy", "-movflags", "+faststart", output.toString()));
        return args;
    }

    /** {@code progressLines} receives the {@code key=value} lines of {@code -progress} output; null when not asked for. */
    private static Result execute(Path executable, List<String> args, Duration timeout, Consumer<String> progressLines) throws IOException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable.toString());
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            // Drain on this thread; the commands we run print little and finish on their own
            StringBuilder output = new StringBuilder();
            for (String line; (line = out.readLine()) != null; ) {
                if (progressLines != null && PROGRESS_LINE.matcher(line).matches()) {
                    progressLines.accept(line);
                } else {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg did not finish in " + timeout.toSeconds() + " s");
            }
            return new Result(process.exitValue(), output.toString());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for ffmpeg", e);
        }
    }

    /** DirectShow audio capture devices (microphones, virtual cables, "Stereo Mix"). */
    public List<String> listAudioDevices() throws IOException {
        Result result = run(List.of("-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"), Duration.ofSeconds(15));
        return parseDshowAudioDevices(result.output());
    }

    static List<String> parseDshowAudioDevices(String output) {
        List<String> devices = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher m = DSHOW_DEVICE.matcher(line);
            if (m.find() && m.group(2).equals("audio")) devices.add(m.group(1));
        }
        return devices;
    }

    /** Size of DXGI output {@code index} as {width, height}, or null when the monitor does not exist. */
    public int[] probeDdagrabOutput(int index) throws IOException {
        Result result = run(List.of("-hide_banner", "-v", "verbose", "-filter_complex",
                "ddagrab=output_idx=" + index + ":framerate=1,hwdownload,format=bgra",
                "-frames:v", "1", "-f", "null", "-"), Duration.ofSeconds(15));
        return parseDdagrabOutput(result.output());
    }

    static int[] parseDdagrabOutput(String output) {
        Matcher m = DDAGRAB_OUTPUT.matcher(output);
        return m.find() ? new int[]{Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))} : null;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }
}
