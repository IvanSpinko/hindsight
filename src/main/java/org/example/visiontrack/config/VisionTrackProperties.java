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

    /**
     * Meeting recording. {@code ffmpegDir} overrides the ffmpeg bundled with JavaCV (a folder with
     * ffmpeg.exe and ffprobe.exe); {@code maxRecordingMinutes} also stops captures orphaned by a crash.
     * A slide is kept once the screen stays still for {@code slideStableSeconds} and at least
     * {@code slideMinChangedShare} of it differs from the previous slide.
     */
    public record Meetings(
            @NotNull Path directory,
            @DecimalMin("0.1") @DecimalMax("5.0") double screenFps,
            @Min(10) int segmentSeconds,
            @Min(1) int maxRecordingMinutes,
            Path ffmpegDir,
            @DecimalMin("0.5") double slideStableSeconds,
            @DecimalMin("0.001") @DecimalMax("1.0") double slideMinChangedShare,
            @Valid @NotNull Whisper whisper
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
