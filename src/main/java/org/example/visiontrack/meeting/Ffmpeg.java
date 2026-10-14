package org.example.visiontrack.meeting;

import org.bytedeco.javacpp.Loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
        return execute(ffmpeg, args, timeout);
    }

    public Result probe(List<String> args, Duration timeout) throws IOException {
        return execute(ffprobe, args, timeout);
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

    private static Result execute(Path executable, List<String> args, Duration timeout) throws IOException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable.toString());
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        try (InputStream out = process.getInputStream()) {
            // Drain on this thread; the commands we run print little and finish on their own
            byte[] bytes = out.readAllBytes();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg did not finish in " + timeout.toSeconds() + " s");
            }
            return new Result(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
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
