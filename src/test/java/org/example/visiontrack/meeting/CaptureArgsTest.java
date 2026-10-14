package org.example.visiontrack.meeting;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureArgsTest {

    private static final Path DIR = Path.of("meeting", "video");

    @Test
    void ddagrabRecordsTheChosenMonitorInSegments() {
        List<String> args = ScreenCapture.args(ScreenCapture.Method.DDAGRAB, 1, 1.0, DIR, 300, 3600);

        String filter = args.get(args.indexOf("-filter_complex") + 1);
        assertThat(filter).startsWith("ddagrab=output_idx=1:framerate=1.000,hwdownload,format=bgra");
        assertThat(args).containsSubsequence("-t", "3600")
                .containsSubsequence("-f", "segment", "-segment_time", "300", "-segment_format", "matroska")
                .contains("libopenh264")
                .containsSubsequence("-force_key_frames", "expr:gte(t,n_forced*300)")
                .endsWith(DIR.resolve("screen-%03d.mkv").toString());
    }

    @Test
    void gdigrabFallbackGrabsTheDesktop() {
        List<String> args = ScreenCapture.args(ScreenCapture.Method.GDIGRAB, 0, 0.5, DIR, 300, 3600);

        assertThat(args).containsSubsequence("-f", "gdigrab", "-framerate", "0.500")
                .containsSubsequence("-i", "desktop")
                .doesNotContain("-filter_complex");
    }

    @Test
    void microphoneIsRecordedAsWhisperReadyPcm() {
        List<String> args = AudioCapture.args("Microphone (Brio 105)", DIR, "mic", 300, 3600);

        assertThat(args).containsSubsequence("-f", "dshow")
                .containsSubsequence("-i", "audio=Microphone (Brio 105)")
                .containsSubsequence("-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le")
                .endsWith(DIR.resolve("mic-%03d.mkv").toString());
    }

    @Test
    void parsesDirectShowAudioDevices() {
        String output = """
                [dshow @ 000002c8997e9a80] "Brio 105" (video)
                [dshow @ 000002c8997e9a80]   Alternative name "@device_pnp_\\\\?\\usb#vid_046d"
                [dshow @ 000002c8997e9a80] "Microphone (USB PnP Audio Device)" (audio)
                [dshow @ 000002c8997e9a80]   Alternative name "@device_cm_{33D9A762}\\wave_{56A4F310}"
                [dshow @ 000002c8997e9a80] "Headset Microphone (Realtek(R) Audio)" (audio)
                [in#0 @ 000002c8997e9700] Error opening input: Immediate exit requested
                """;

        assertThat(Ffmpeg.parseDshowAudioDevices(output))
                .containsExactly("Microphone (USB PnP Audio Device)", "Headset Microphone (Realtek(R) Audio)");
    }

    @Test
    void parsesDdagrabMonitorSize() {
        assertThat(Ffmpeg.parseDdagrabOutput("[Parsed_ddagrab_0 @ 01] Opened dxgi output 0 with dimensions 2560x1440"))
                .containsExactly(2560, 1440);
        assertThat(Ffmpeg.parseDdagrabOutput("[Parsed_ddagrab_0 @ 01] Failed to enumerate DXGI output 1")).isNull();
    }
}
