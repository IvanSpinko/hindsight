package org.example.visiontrack.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "visiontrack")
public record VisionTrackProperties(
        @Valid @NotNull Model model,
        @Valid @NotNull Analysis analysis,
        @Valid @NotNull Storage storage,
        @Valid @NotNull Jobs jobs,
        @Valid @NotNull Export export,
        @Valid @NotNull Meetings meetings
) {

    public record Model(
            @NotNull Path path,
            Path classesPath,
            @DecimalMin("0.0") @DecimalMax("1.0") float confidenceThreshold,
            @DecimalMin("0.0") @DecimalMax("1.0") float nmsThreshold,
            List<Path> nativePreload,
            Path nativeDir
    ) {
        public Model {
            nativePreload = nativePreload == null ? List.of() : List.copyOf(nativePreload);
        }
    }

    public record Analysis(
            @DecimalMin("0.0") double sampleFps,
            @Min(64) int maxFrameDimension,
            @DecimalMin("0.0") @DecimalMax("1.0") float trackIouThreshold,
            @DecimalMin("0.0") double trackMaxGapSeconds,
            @Min(1) int trackMinHits,
            boolean annotatedVideo
    ) {}

    public record Storage(
            @NotNull Path directory,
            boolean deleteAfterProcessing,
            @NotEmpty List<String> allowedExtensions
    ) {}

    public record Jobs(
            @Min(1) int concurrency,
            @Min(0) int queueCapacity
    ) {}

    /** Detections written as JSON Lines for every run (always on); see the export package. */
    public record Export(
            @NotNull Path directory,
            @Min(1) int liveBatchSeconds,
            @Min(5) int liveIdleTimeoutSeconds
    ) {}

    /**
     * Meeting recording. {@code ffmpegDir} overrides the ffmpeg bundled with JavaCV (a folder with
     * ffmpeg.exe and ffprobe.exe); {@code maxRecordingMinutes} also stops captures orphaned by a crash.
     * The screen is recorded for people to watch later; only the speech is analysed. {@code systemAudioHelper}
     * is audio-loopback.exe, which captures what the computer plays (the other participants).
     */
    public record Meetings(
            @NotNull Path directory,
            @DecimalMin("0.1") @DecimalMax("60.0") double screenFps,
            @Min(10) int segmentSeconds,
            @Min(1) int maxRecordingMinutes,
            Path ffmpegDir,
            Path systemAudioHelper,
            @NotNull SpeechEngine speechEngine,
            @Valid @NotNull Parakeet parakeet,
            @Valid @NotNull Whisper whisper,
            @Valid @NotNull Notes notes
    ) {}

    /** Which model turns meeting speech into text; Whisper is also the fallback when Parakeet is not installed. */
    public enum SpeechEngine { PARAKEET, WHISPER }

    /**
     * NVIDIA Parakeet TDT 0.6B v3 in ONNX Runtime on CUDA: {@code directory} holds the ONNX export (nemo128.onnx,
     * encoder-model.onnx with its .data file, decoder_joint-model.onnx, vocab.txt). Speech is found with the
     * Whisper section's voice activity detection, and its {@code languages} label the segments.
     */
    public record Parakeet(@NotNull Path directory) {}

    /**
     * Meeting notes written by a local model with llama.cpp on CUDA: {@code directory} holds llama-server.exe and
     * its DLLs, {@code model} is a GGUF file. Meetings longer than {@code chunkMinutes} are summarised in parts of
     * that length first, then merged. {@code contextTokens} is the model's context window (prompt and answer),
     * {@code maxTokens} the longest answer.
     */
    public record Notes(
            @NotNull Path directory,
            @NotNull Path model,
            @Min(1) int chunkMinutes,
            @Min(2048) int contextTokens,
            @Min(256) int maxTokens
    ) {}

    /**
     * Speech recognition with whisper.cpp on CUDA (no CPU fallback): {@code directory} holds whisper-server.exe
     * and its DLLs. {@code languages} are the ISO codes speech may be in; the first one is the fallback.
     */
    public record Whisper(
            @NotNull Path directory,
            @NotNull Path model,
            @NotNull Path vadModel,
            @NotEmpty List<String> languages
    ) {}
}
