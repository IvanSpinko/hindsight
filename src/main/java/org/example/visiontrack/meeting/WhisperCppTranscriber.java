package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.visiontrack.GpuRequiredException;
import org.example.visiontrack.analysis.ProgressListener;
import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Speech recognition with whisper.cpp (option A of the spec): Silero VAD finds the speech, which is cut
 * at pauses into pieces of up to 30 s; a {@code whisper-server} process transcribes each piece with its own
 * language detection, limited to the allowed languages (Russian and English by default).
 *
 * <p>Everything runs on CUDA. Without it (no NVIDIA GPU, old driver) recognition fails with a
 * {@link GpuRequiredException}; there is no CPU fallback.
 */
public final class WhisperCppTranscriber implements Transcriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperCppTranscriber.class);
    private static final String SPEECH = "Speech recognition";

    private final VisionTrackProperties.Whisper settings;
    private final ObjectMapper json;
    private final Path logDirectory;

    public WhisperCppTranscriber(VisionTrackProperties.Whisper settings, ObjectMapper json, Path logDirectory) {
        this.settings = settings;
        this.json = json;
        this.logDirectory = logDirectory;
    }

    /** Whether the whisper.cpp binaries and both models are installed. */
    public static boolean isInstalled(VisionTrackProperties.Whisper settings) {
        return Files.isRegularFile(executable(settings, "whisper-server"))
                && Files.isRegularFile(executable(settings, "whisper-vad-speech-segments"))
                && Files.isRegularFile(settings.model()) && Files.isRegularFile(settings.vadModel());
    }

    @Override
    public Result transcribe(Path wav, double offsetSeconds, String source, ProgressListener progress) throws IOException {
        PcmWav audio = PcmWav.read(wav);
        List<TranscriptSegment> segments = new ArrayList<>();
        Set<String> languages = new LinkedHashSet<>();
        // The server starts first, so a missing GPU is reported before any other work is done
        try (Loaded loaded = startServer()) {
            List<SpeechChunks.Span> chunks = SpeechChunks.plan(detectSpeech(wav), audio.durationSeconds());
            log.info("Speech recognition: {} on {}, {} pieces of speech", loaded.model(), loaded.backend(), chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                SpeechChunks.Span chunk = chunks.get(i);
                byte[] piece = audio.slice(chunk.start(), chunk.end());
                WhisperServer.Inference inference = loaded.server().transcribe(piece, "auto");
                String language = inference.mostLikely(List.of());
                if (language == null || !settings.languages().contains(language)) {
                    // Detected a language we do not expect (Ukrainian for Russian, say): use the likeliest allowed one
                    String allowed = inference.mostLikely(settings.languages());
                    language = allowed != null ? allowed : settings.languages().getFirst();
                    inference = loaded.server().transcribe(piece, language);
                }
                languages.add(language);
                segments.addAll(toSegments(inference, offsetSeconds + chunk.start(), source, language));
                progress.onProgress((i + 1.0) / chunks.size());
            }
            return new Result(segments, loaded.model(), loaded.backend(), List.copyOf(languages));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Speech recognition interrupted", e);
        }
    }

    /**
     * Turns Whisper's pieces into segments on the meeting timeline. Whisper sometimes splits inside a word
     * (a piece that does not start with a space continues the previous one), so those are joined back.
     */
    static List<TranscriptSegment> toSegments(WhisperServer.Inference inference, double offset, String source, String language) {
        List<TranscriptSegment> result = new ArrayList<>();
        for (WhisperServer.Piece piece : inference.pieces()) {
            if (piece.likelyHallucination() || piece.text().isBlank()) continue;
            boolean continuation = !result.isEmpty() && !Character.isWhitespace(piece.text().charAt(0));
            if (continuation) {
                TranscriptSegment last = result.removeLast();
                result.add(new TranscriptSegment(last.start(), round(offset + piece.end()), source, language, last.text() + piece.text().strip()));
            } else {
                result.add(new TranscriptSegment(round(offset + piece.start()), round(offset + piece.end()), source, language, piece.text().strip()));
            }
        }
        return result;
    }

    private static double round(double seconds) {
        return Math.round(seconds * 100) / 100.0;
    }

    private List<SpeechChunks.Span> detectSpeech(Path wav) throws IOException {
        List<String> command = List.of(executable(settings, "whisper-vad-speech-segments").toString(),
                // No -ug: in whisper.cpp b5454 Silero VAD aborts on CUDA ("pre-allocated tensor in a buffer (CUDA0)
                // that cannot run the operation"), so this small model stays on the CPU (seconds per hour of audio)
                "-vm", settings.vadModel().toString(), "-f", wav.toString(), "-np", "-t", "4",
                // pauses of 0.3 s separate segments; pieces are then grouped by SpeechChunks
                "-vsd", "300", "-vp", "100");
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IOException("Voice activity detection failed (exit " + (process.isAlive() ? "timeout" : process.exitValue()) + ")");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Voice activity detection interrupted", e);
        }
        return SpeechChunks.parseVad(output);
    }

    private record Loaded(WhisperServer server, String model) implements AutoCloseable {
        String backend() {
            return "CUDA";
        }

        @Override
        public void close() {
            server.close();
        }
    }

    /** The model on CUDA; a server that came up on the CPU is stopped and reported as a missing GPU. */
    private Loaded startServer() throws IOException, InterruptedException {
        WhisperServer server;
        try {
            server = WhisperServer.start(executable(settings, "whisper-server"), settings.model(), 4,
                    logDirectory.resolve("whisper.log"), json);
        } catch (IOException e) {
            // Without the NVIDIA driver the CUDA build may not even start
            if (!nvidiaDriverInstalled()) throw new GpuRequiredException(SPEECH, e);
            throw e;
        }
        if (!server.backend().equals("CUDA")) {
            server.close();
            throw new GpuRequiredException(SPEECH);
        }
        return new Loaded(server, modelName(settings.model()));
    }

    private static boolean nvidiaDriverInstalled() {
        String windows = System.getenv("SystemRoot");
        return windows == null || Files.isRegularFile(Path.of(windows, "System32", "nvcuda.dll"));
    }

    /** "ggml-large-v3-turbo-q8_0.bin" -> "large-v3-turbo-q8_0". */
    static String modelName(Path model) {
        return model.getFileName().toString().replaceFirst("^ggml-", "").replaceFirst("\\.bin$", "");
    }

    private static Path executable(VisionTrackProperties.Whisper settings, String name) {
        return settings.directory().resolve(Ffmpeg.isWindows() ? name + ".exe" : name);
    }
}
