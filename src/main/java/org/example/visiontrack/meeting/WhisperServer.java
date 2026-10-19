package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A whisper-server process on 127.0.0.1, started for one meeting and stopped after. */
final class WhisperServer implements AutoCloseable {

    /** A recognised piece; {@code noSpeech} and {@code logProb} tell how sure Whisper is that it heard speech. */
    record Piece(double start, double end, String text, double noSpeech, double logProb) {

        /** Whisper's own rule for text made up from silence or noise. */
        boolean likelyHallucination() {
            return noSpeech > 0.6 && logProb < -1.0;
        }
    }

    /** Result of one request: Whisper's language guess per ISO code (e.g. "ru" -> 0.98) and the text. */
    record Inference(Map<String, Double> probabilities, List<Piece> pieces) {

        /** The most likely language among {@code allowed}, or null when Whisper gave no probabilities. */
        String mostLikely(List<String> allowed) {
            return probabilities.entrySet().stream()
                    .filter(e -> allowed.isEmpty() || allowed.contains(e.getKey()))
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }

    /** The first start on a new GPU includes a one-off compilation of the CUDA kernels, so allow plenty of time. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final Process process;
    private final URI uri;
    private final Path log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;

    private WhisperServer(Process process, int port, Path log, ObjectMapper json) {
        this.process = process;
        this.uri = URI.create("http://127.0.0.1:" + port + "/inference");
        this.log = log;
        this.json = json;
    }

    static WhisperServer start(Path executable, Path model, int threads, Path log, ObjectMapper json)
            throws IOException, InterruptedException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        List<String> command = List.of(executable.toString(), "-m", model.toString(),
                "--host", "127.0.0.1", "--port", String.valueOf(port), "-t", String.valueOf(threads), "-l", "auto");
        Files.createDirectories(log.getParent());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start();
        WhisperServer server = new WhisperServer(process, port, log, json);
        try {
            server.awaitReady();
        } catch (IOException | InterruptedException | RuntimeException e) {
            server.close();
            throw e;
        }
        return server;
    }

    private void awaitReady() throws IOException, InterruptedException {
        URI root = uri.resolve("/");
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("whisper-server exited with code " + process.exitValue() + ": " + logTail());
            }
            try {
                http.send(HttpRequest.newBuilder(root).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                return;
            } catch (ConnectException | java.net.http.HttpTimeoutException e) {
                Thread.sleep(300);
            }
        }
        throw new IOException("whisper-server did not start in " + STARTUP_TIMEOUT.toMinutes() + " min");
    }

    /** "CUDA" when the model was put on the GPU, otherwise "CPU" (read from the server's own log). */
    String backend() {
        return readLog().contains("using CUDA") ? "CUDA" : "CPU";
    }

    Inference transcribe(byte[] wav, String language) throws IOException, InterruptedException {
        String boundary = "----visiontrack" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 1024);
        field(body, boundary, "response_format", "verbose_json");
        field(body, boundary, "language", language);
        field(body, boundary, "temperature", "0.0");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("whisper-server answered " + response.statusCode());
        }
        return parse(json.readTree(response.body()));
    }

    static Inference parse(JsonNode root) throws IOException {
        if (root.has("error")) throw new IOException("whisper-server: " + root.get("error").asText());
        Map<String, Double> probabilities = new LinkedHashMap<>();
        root.path("language_probabilities").fields().forEachRemaining(e -> probabilities.put(e.getKey(), e.getValue().asDouble()));
        List<Piece> pieces = new ArrayList<>();
        for (JsonNode s : root.path("segments")) {
            pieces.add(new Piece(s.path("start").asDouble(), s.path("end").asDouble(), s.path("text").asText(),
                    s.path("no_speech_prob").asDouble(0), s.path("avg_logprob").asDouble(0)));
        }
        return new Inference(probabilities, pieces);
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
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
