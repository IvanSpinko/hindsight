package org.example.visiontrack.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoAnalyzerTest {

    @Test
    void formatsHoursMinutesSecondsAndMillis() {
        assertThat(VideoAnalyzer.timecode(0)).isEqualTo("00:00:00.000");
        assertThat(VideoAnalyzer.timecode(83_456_789)).isEqualTo("00:01:23.456");
        assertThat(VideoAnalyzer.timecode(3_723_004_000L)).isEqualTo("01:02:03.004");
    }

    @Test
    void secondsAreRoundedToMilliseconds() {
        assertThat(VideoAnalyzer.seconds(1_234_567)).isEqualTo(1.235);
    }
}
