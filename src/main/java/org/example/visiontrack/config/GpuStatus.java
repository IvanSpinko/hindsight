package org.example.visiontrack.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** NVIDIA GPUs and their free video memory, from {@code nvidia-smi}. */
public record GpuStatus(List<Gpu> gpus, String problem) {

    public record Gpu(String name, String driver, long totalMib, long freeMib) {}

    private static final long TIMEOUT_SECONDS = 5;

    public static GpuStatus query() {
        Process process;
        try {
            process = new ProcessBuilder("nvidia-smi", "--query-gpu=name,driver_version,memory.total,memory.free",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
        } catch (IOException e) {
            return new GpuStatus(List.of(), "No NVIDIA driver found (nvidia-smi is missing)");
        }
        try (InputStream out = process.getInputStream()) {
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new GpuStatus(List.of(), "nvidia-smi did not answer");
            }
            String text = new String(out.readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) return new GpuStatus(List.of(), "nvidia-smi failed: " + text.strip());
            List<Gpu> gpus = parse(text);
            return new GpuStatus(gpus, gpus.isEmpty() ? "No NVIDIA GPU found" : null);
        } catch (IOException e) {
            return new GpuStatus(List.of(), "Cannot run nvidia-smi: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GpuStatus(List.of(), "Interrupted");
        }
    }

    /** Lines like {@code NVIDIA GeForce RTX 4070, 566.36, 12282, 10915}; others are skipped. */
    static List<Gpu> parse(String csv) {
        List<Gpu> gpus = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            String[] fields = line.split(",");
            if (fields.length != 4) continue;
            try {
                gpus.add(new Gpu(fields[0].strip(), fields[1].strip(),
                        Long.parseLong(fields[2].strip()), Long.parseLong(fields[3].strip())));
            } catch (NumberFormatException ignored) {
            }
        }
        return gpus;
    }
}
