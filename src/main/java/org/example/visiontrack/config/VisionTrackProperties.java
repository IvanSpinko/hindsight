package org.example.visiontrack.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.example.visiontrack.detection.OnnxYoloDetector;
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
        @Valid @NotNull Jobs jobs
) {

    public record Model(
            @NotNull Path path,
            Path classesPath,
            @DecimalMin("0.0") @DecimalMax("1.0") float confidenceThreshold,
            @DecimalMin("0.0") @DecimalMax("1.0") float nmsThreshold,
            @NotEmpty List<OnnxYoloDetector.Backend> backends,
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
}
