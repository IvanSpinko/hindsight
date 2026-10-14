package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>The large model runs on CUDA; if CUDA is not available (no NVIDIA GPU, old driver, not enough VRAM)
 * the small model runs on the CPU instead.
 */
public final class WhisperCppTranscriber implements Transcriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperCppTranscriber.class);

    private final VisionTrackProperties.Whisper settings;
    private final ObjectMapper json;
    private final Path logDirectory;

    public WhisperCppTranscriber(VisionTrackProperties.Whisper settings, ObjectMapper json, Path logDirectory) {
        this.settings = settings;
        this.json = json;
        this.logDirectory = logDirectory;
    }

    /** Whether the whisper.cpp binaries and at least one model are installed. */
    public static boolean isInstalled(VisionTrackProperties.Whisper settings) {
        return Files.isRegularFile(executable(settings, "whisper-server"))
                && (Files.isRegularFile(settings.gpuModel()) || Files.isRegularFile(settings.cpuModel()));
    }

    @Override
    public Result transcribe(Path wav, double offsetSeconds, String source, ProgressListener progress) throws IOException {
        PcmWav audio = PcmWav.read(wav);
        List<SpeechChunks.Span> chunks = SpeechChunks.plan(detectSpeech(wav), audio.durationSeconds());
        if (chunks.isEmpty()) {
            return new Result(List.of(), "-", "-", List.of());
        }

        List<TranscriptSegment> segments = new ArrayList<>();
        Set<String> languages = new LinkedHashSet<>();
        try (Loaded loaded = startServer()) {
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

    private record Loaded(WhisperServer server, String model, String backend) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
        }
    }

    /** The large model on CUDA when possible, otherwise the small model on the CPU. */
    private Loaded startServer() throws IOException, InterruptedException {
        Path server = executable(settings, "whisper-server");
        if (settings.device() == VisionTrackProperties.Whisper.Device.AUTO && Files.isRegularFile(settings.gpuModel())) {
            try {
                WhisperServer gpu = WhisperServer.start(server, settings.gpuModel(), true, 4, logDirectory.resolve("whisper.log"), json);
                if (gpu.backend().equals("CUDA")) {
                    return new Loaded(gpu, modelName(settings.gpuModel()), "CUDA");
                }
                gpu.close();
                log.info("CUDA is not available for speech recognition, using the CPU");
            } catch (IOException e) {
                log.warn("Speech recognition could not start on the GPU, using the CPU: {}", e.getMessage());
            }
        }
        if (!Files.isRegularFile(settings.cpuModel())) {
            throw new IOException("CUDA is unavailable and the CPU model is missing: " + settings.cpuModel());
        }
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        WhisperServer cpu = WhisperServer.start(server, settings.cpuModel(), false, threads, logDirectory.resolve("whisper-cpu.log"), json);
        return new Loaded(cpu, modelName(settings.cpuModel()), "CPU");
    }

    /** "ggml-large-v3-turbo-q8_0.bin" -> "large-v3-turbo-q8_0". */
    static String modelName(Path model) {
        return model.getFileName().toString().replaceFirst("^ggml-", "").replaceFirst("\\.bin$", "");
    }

    private static Path executable(VisionTrackProperties.Whisper settings, String name) {
        return settings.directory().resolve(Ffmpeg.isWindows() ? name + ".exe" : name);
    }
}
