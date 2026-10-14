package org.example.visiontrack;

import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.detection.ObjectDetectorProvider;
import org.example.visiontrack.web.GpuErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GpuRequiredTest {

    @Test
    void messageNamesTheFeatureTheCardAndTheDriver() {
        assertThat(new GpuRequiredException("Speech recognition").getMessage())
                .startsWith("Speech recognition needs an NVIDIA graphics card with CUDA (driver 528 or newer).")
                .contains("No compatible GPU was found");
    }

    @Test
    void pageGetsServiceUnavailableWithTheMessageAsDetail() {
        ProblemDetail problem = new GpuErrorHandler().gpuRequired(new GpuRequiredException("Object detection"));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).startsWith("Object detection needs an NVIDIA graphics card");
    }

    @Test
    void appStartsWithoutTheDetectorAndReportsWhyOnUse() {
        VisionTrackProperties.Model model = new VisionTrackProperties.Model(
                Path.of("missing", "model.onnx"), null, 0.35f, 0.45f, List.of(), null);
        VisionTrackProperties properties = new VisionTrackProperties(model, null, null, null, null);

        ObjectDetectorProvider provider = new ObjectDetectorProvider(properties);

        assertThatThrownBy(provider::check).hasMessageContaining("Model file not found");
    }
}
