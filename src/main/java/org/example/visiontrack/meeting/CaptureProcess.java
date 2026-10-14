package org.example.visiontrack.meeting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One long-running ffmpeg capture (screen, microphone or system sound) writing numbered segments into a folder.
 * The system sound comes from a {@code source} process piped into ffmpeg's stdin. Messages go to {@code logFile}
 * so no pipe fills up and errors can be shown later.
 */
final class CaptureProcess {

    private final String name;
    private final Process process;
    private final Process source;
    private final Path logFile;
    private final long startedAtNanos;
    private volatile boolean stopping;

    private CaptureProcess(String name, Process process, Process source, Path logFile) {
        this.name = name;
        this.process = process;
        this.source = source;
        this.logFile = logFile;
        this.startedAtNanos = System.nanoTime();
    }

    static CaptureProcess start(String name, Path ffmpeg, List<String> args, Path logFile) throws IOException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(ffmpeg.toString());
        command.addAll(args);
        Files.createDirectories(logFile.getParent());
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();
        return new CaptureProcess(name, process, null, logFile);
    }

    /** {@code source}'s stdout piped into ffmpeg's stdin; both write their messages to {@code logFile}. */
    static CaptureProcess startPiped(String name, List<String> source, Path ffmpeg, List<String> args, Path logFile) throws IOException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(ffmpeg.toString());
        command.addAll(args);
        Files.createDirectories(logFile.getParent());
        List<Process> pipeline = ProcessBuilder.startPipeline(List.of(
                new ProcessBuilder(source).redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile())),
                new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))));
        return new CaptureProcess(name, pipeline.get(1), pipeline.get(0), logFile);
    }

    String name() {
        return name;
    }

    long pid() {
        return process.pid();
    }

    /** All processes of this capture, to stop them after a crash. */
    List<Long> pids() {
        return source == null ? List.of(process.pid()) : List.of(process.pid(), source.pid());
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** True when the process ended although nobody asked it to stop. */
    boolean diedUnexpectedly() {
        return !stopping && !process.isAlive();
    }

    long startedAtNanos() {
        return startedAtNanos;
    }

    /**
     * Waits up to {@code timeout} and reports whether the capture is still running, i.e. the device opened.
     * Devices that fail (busy, missing, unsupported) make ffmpeg exit within a second or two.
     */
    boolean survives(Duration timeout) throws InterruptedException {
        return !process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Asks ffmpeg to finish so the last segment is closed properly, then kills it if needed: 'q' on stdin, or for
     * a piped capture the end of its input, by stopping the source.
     */
    void stop(Duration timeout) {
        stopping = true;
        if (source != null) source.destroy();
        if (!process.isAlive()) return;
        if (source == null) {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write('q');
                stdin.flush();
            } catch (IOException ignored) {
                // ffmpeg already closed stdin; fall through to waiting/killing
            }
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** The last few non-progress lines ffmpeg printed, for error messages. */
    String errorTail() {
        try (Stream<String> lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            List<String> all = lines.map(String::strip).filter(l -> !l.isEmpty()).toList();
            return all.subList(Math.max(0, all.size() - 3), all.size()).stream().collect(Collectors.joining(" | "));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
