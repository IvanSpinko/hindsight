package org.example.visiontrack.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GpuStatusTest {

    @Test
    void nvidiaSmiLinesAreParsed() {
        String csv = "NVIDIA GeForce RTX 4070, 566.36, 12282, 10915\r\nNVIDIA T400, 566.36, [N/A], 1\n\n";

        assertThat(GpuStatus.parse(csv)).containsExactly(new GpuStatus.Gpu("NVIDIA GeForce RTX 4070", "566.36", 12282, 10915));
    }
}
