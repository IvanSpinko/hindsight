package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A llama.cpp {@code llama-server} process on 127.0.0.1 with the notes model fully on the GPU. It is started for
 * one meeting and stopped afterwards, which frees its VRAM for speech recognition and object detection.
 */
final class LlamaServer implements LocalNotesGenerator.Model, AutoCloseable {

    /** Loading a 7-9 GB model, plus a one-off CUDA kernel compilation on a new GPU. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(20);

    private final Process process;
    private final URI base;
    private final Path log;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private LlamaServer(Process process, int port, Path log, ObjectMapper json) {
        this.process = process;
        this.base = URI.create("http://127.0.0.1:" + port + "/");
        this.log = log;
        this.json = json;
    }

    /**
     * One slot with the whole context, all layers on the GPU, flash attention, no "thinking" (the answer is a JSON
     * object constrained by a grammar, there is nothing to think aloud into).
     */
    static LlamaServer start(Path executable, Path model, int contextTokens, Path log, ObjectMapper json)
            throws IOException, InterruptedException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        List<String> command = List.of(executable.toString(), "-m", model.toString(),
                "--host", "127.0.0.1", "--port", String.valueOf(port),
                "-c", String.valueOf(contextTokens), "-np", "1", "-ngl", "all", "-fa", "on",
                "--reasoning", "off", "--no-webui");
        Files.createDirectories(log.getParent());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start();
        LlamaServer server = new LlamaServer(process, port, log, json);
        try {
            server.awaitReady();
        } catch (IOException | InterruptedException | RuntimeException e) {
            server.close();
            throw e;
        }
        return server;
    }

    /** {@code /health} answers 503 while the model loads and 200 once it can take requests. */
    private void awaitReady() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("llama-server exited with code " + process.exitValue() + ": " + logTail());
            }
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(base.resolve("health"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) return;
            } catch (ConnectException | HttpTimeoutException e) {
                // not listening yet
            }
            Thread.sleep(500);
        }
        throw new IOException("llama-server did not start in " + STARTUP_TIMEOUT.toMinutes() + " min");
    }

    /** Whether llama.cpp sees a CUDA device ({@code --list-devices} prints e.g. "CUDA0: NVIDIA GeForce RTX 5070"). */
    static boolean hasCudaDevice(Path executable) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable.toString(), "--list-devices").redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(1, TimeUnit.MINUTES)) process.destroyForcibly();
        return listsCudaDevice(output);
    }

    static boolean listsCudaDevice(String listDevicesOutput) {
        int devices = listDevicesOutput.indexOf("Available devices:");
        return devices >= 0 && listDevicesOutput.substring(devices).lines().anyMatch(line -> line.strip().startsWith("CUDA"));
    }

    @Override
    public int countTokens(String text) throws IOException, InterruptedException {
        JsonNode answer = post("tokenize", Map.of("content", text), Duration.ofMinutes(1));
        return answer.path("tokens").size();
    }

    @Override
    public LocalNotesGenerator.Answer chat(String system, String user, Map<String, Object> schema, int maxTokens) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)),
                "response_format", Map.of("type", "json_schema", "json_schema", Map.of("name", "meeting_notes", "schema", schema)),
                "max_tokens", maxTokens,
                // Low temperature: notes should repeat what was said, not vary it
                "temperature", 0.2);
        JsonNode choice = post("v1/chat/completions", body, REQUEST_TIMEOUT).path("choices").path(0);
        return new LocalNotesGenerator.Answer(choice.path("message").path("content").asText(""), "length".equals(choice.path("finish_reason").asText()));
    }

    private JsonNode post(String path, Object body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            // The error text is the server's own message (e.g. context exceeded), not meeting content
            String message = json.readTree(response.body()).path("error").path("message").asText(response.body());
            throw new IOException("llama-server answered " + response.statusCode() + ": " + message);
        }
        return json.readTree(response.body());
    }

    private String readLog() {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private String logTail() {
        String text = readLog().strip();
        return text.substring(Math.max(0, text.length() - 300));
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
