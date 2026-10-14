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
 * One long-running ffmpeg capture (screen or a microphone) writing numbered segments into a folder.
 * ffmpeg's messages go to {@code logFile} so the pipe never fills up and errors can be shown later.
 */
final class CaptureProcess {

    private final String name;
    private final Process process;
    private final Path logFile;
    private final long startedAtNanos;
    private volatile boolean stopping;

    private CaptureProcess(String name, Process process, Path logFile) {
        this.name = name;
        this.process = process;
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
        return new CaptureProcess(name, process, logFile);
    }

    String name() {
        return name;
    }

    long pid() {
        return process.pid();
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

    /** Asks ffmpeg to finish ('q' on stdin) so the last segment is closed properly, then kills it if needed. */
    void stop(Duration timeout) {
        stopping = true;
        if (!process.isAlive()) return;
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write('q');
            stdin.flush();
        } catch (IOException ignored) {
            // ffmpeg already closed stdin; fall through to waiting/killing
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
