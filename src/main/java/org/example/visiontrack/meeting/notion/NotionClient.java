package org.example.visiontrack.meeting.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The few Notion API calls the export needs, with plain {@link HttpClient} and Jackson (no SDK).
 * Requests are spaced to stay under Notion's average of three per second; answers 429 (rate limited) and 529
 * (overloaded) are retried after {@code Retry-After} or an exponential backoff. Server errors are retried only
 * for reads: a failed write may still have been saved, and writing it twice would duplicate the content.
 * The token is sent only in the {@code Authorization} header and never logged.
 */
public final class NotionClient {

    public static final String VERSION = "2026-03-11";
    public static final URI API = URI.create("https://api.notion.com/v1/");

    private static final Logger log = LoggerFactory.getLogger(NotionClient.class);
    private static final Set<Integer> READ_RETRY = Set.of(500, 502, 503, 504);
    /** Waits longer than this are not worth it for a button the user just pressed. */
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);

    private final HttpClient http;
    private final URI base;
    private final String token;
    private final ObjectMapper json;
    private final Duration spacing;
    private final int maxRetries;
    private long nextRequestNanos = System.nanoTime();

    public NotionClient(String token, ObjectMapper json) {
        this(API, token, json, Duration.ofMillis(350), 5);
    }

    /** For a stub server in tests: another address, shorter spacing between requests, fewer retries. */
    public NotionClient(URI base, String token, ObjectMapper json, Duration spacing, int maxRetries) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.base = base;
        this.token = token;
        this.json = json;
        this.spacing = spacing;
        this.maxRetries = maxRetries;
    }

    public JsonNode get(String path) throws IOException {
        return send("GET", path, null);
    }

    public JsonNode post(String path, Object body) throws IOException {
        return send("POST", path, body);
    }

    public JsonNode patch(String path, Object body) throws IOException {
        return send("PATCH", path, body);
    }

    private JsonNode send(String method, String path, Object body) throws IOException {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", VERSION)
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response;
            try {
                pace();
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("The request to Notion was interrupted");
            }
            int status = response.statusCode();
            if (status / 100 == 2) {
                String text = response.body();
                return text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text);
            }
            NotionException error = error(status, response.body());
            boolean busy = (status == 429 && !"public_api_request_blocked".equals(error.code())) || status == 529;
            boolean retry = attempt < maxRetries && (busy || ("GET".equals(method) && READ_RETRY.contains(status)));
            if (!retry) throw error;
            Duration wait = retryAfter(response).orElse(backoff(attempt));
            if (wait.compareTo(MAX_WAIT) > 0) throw error;
            log.info("Notion answered {} to {} {}, retrying in {} ms", status, method, path, wait.toMillis());
            sleep(wait);
        }
    }

    private NotionException error(int status, String body) {
        String code = null;
        String message = "HTTP " + status;
        try {
            JsonNode node = body == null || body.isBlank() ? null : json.readTree(body);
            if (node != null) {
                code = node.path("code").asText(null);
                message = node.path("message").asText(message);
            }
        } catch (IOException e) {
            // Not JSON (e.g. a proxy page): the status says enough
        }
        return new NotionException(status, code, message);
    }

    private static Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip()))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    /** 1 s, 2 s, 4 s ... with up to 25% jitter, so parallel clients do not retry in step. */
    private static Duration backoff(int attempt) {
        long millis = 1000L << Math.min(attempt, 5);
        return Duration.ofMillis(millis + ThreadLocalRandom.current().nextLong(millis / 4 + 1));
    }

    private synchronized void pace() throws InterruptedException {
        long wait = nextRequestNanos - System.nanoTime();
        if (wait > 0) Thread.sleep(Duration.ofNanos(wait));
        nextRequestNanos = System.nanoTime() + spacing.toNanos();
    }

    private static void sleep(Duration duration) throws InterruptedIOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("The request to Notion was interrupted");
        }
    }
}
