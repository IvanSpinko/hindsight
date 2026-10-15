package org.example.visiontrack.meeting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** System sound: the helper's answers, the capture arguments, mixing into the recording and echo removal. */
class SystemAudioTest {

    private static final Path DIR = Path.of("meeting", "audio");

    @Test
    void readsTheOutputDeviceFormat() throws IOException {
        assertThat(SystemAudioCapture.parseInfo("44100 2 Speakers (JBL Charge 5)\r\n"))
                .isEqualTo(new SystemAudioCapture.Device(44100, 2, "Speakers (JBL Charge 5)"));
        assertThatThrownBy(() -> SystemAudioCapture.parseInfo("audio-loopback: no device"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void ffmpegStoresThePipedSoundLikeTheMicrophone() {
        List<String> args = SystemAudioCapture.args(new SystemAudioCapture.Device(48000, 2, "Speakers"), DIR, "system", 300, 3600);

        assertThat(args).containsSubsequence("-f", "f32le", "-ar", "48000", "-ac", "2", "-i", "pipe:0")
                .containsSubsequence("-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le")
                .endsWith(DIR.resolve("system-%03d.mkv").toString());
    }

    @Test
    void recordingMixesBothSoundsWithTheirOffsets() {
        List<String> args = Ffmpeg.muxArgs(Path.of("screen.mp4"), List.of(
                new Ffmpeg.AudioInput(Path.of("mic.wav"), -0.85), new Ffmpeg.AudioInput(Path.of("system.wav"), -0.6)), Path.of("out.mp4"));

        assertThat(args).containsSubsequence("-itsoffset", "-0.850", "-i", "mic.wav", "-itsoffset", "-0.600", "-i", "system.wav")
                .containsSubsequence("-filter_complex", "[1:a][2:a]amix=inputs=2:duration=longest:normalize=0[mixed]", "-map", "[mixed]")
                .containsSubsequence("-c:v", "copy");
    }

    @Test
    void recordingWithOneSoundMapsIt() {
        List<String> args = Ffmpeg.muxArgs(Path.of("screen.mp4"), List.of(new Ffmpeg.AudioInput(Path.of("mic.wav"), 0)), Path.of("out.mp4"));

        assertThat(args).containsSubsequence("-map", "0:v", "-map", "1:a").doesNotContain("-filter_complex");
    }

    @Test
    void echoOfTheOtherParticipantsIsRemovedFromTheMicrophone() {
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment(10.0, 13.0, "system", "en", "Are we still targeting the release on Friday?"),
                // the speakers' sound picked up by the microphone, a little later and cut differently
                new TranscriptSegment(10.4, 12.8, "mic", "en", "are we still targeting the release"),
                // the user's own answer
                new TranscriptSegment(13.5, 15.0, "mic", "en", "Yes, Friday stays."),
                // the same words much later are not an echo
                new TranscriptSegment(60.0, 62.0, "mic", "en", "Are we still targeting the release on Friday?"));

        assertThat(EchoFilter.removeEchoes(segments)).extracting(TranscriptSegment::start).containsExactly(10.0, 13.5, 60.0);
    }

    @Test
    void withoutTheSystemTrackNothingIsRemoved() {
        List<TranscriptSegment> segments = List.of(new TranscriptSegment(0, 2, "mic", "ru", "words"));

        assertThat(EchoFilter.removeEchoes(segments)).isEqualTo(segments);
    }

    @Test
    void notesStartOnlyWithEnoughFreeVideoMemory() {
        long gemma = 6_975_879_296L;
        LlamaServer.CudaDevice busy = new LlamaServer.CudaDevice("RTX 5070", 12226, 5200);
        LlamaServer.CudaDevice free = new LlamaServer.CudaDevice("RTX 5070", 12226, 11035);

        assertThat(MeetingJobService.notesMemoryProblem(free, gemma)).isNull();
        assertThat(MeetingJobService.notesMemoryProblem(busy, gemma))
                .contains("5.1 GB free of 11.9 GB").contains("about 7.5 GB needed").contains("Process again");
    }

    @Test
    void readsLlamaDevices() {
        String output = """
                load_backend: loaded CUDA backend from tools\\llama\\ggml-cuda.dll
                Available devices:
                  CUDA0: NVIDIA GeForce RTX 5070 (12226 MiB, 11035 MiB free)
                """;

        assertThat(LlamaServer.parseDevices(output)).containsExactly(new LlamaServer.CudaDevice("NVIDIA GeForce RTX 5070", 12226, 11035));
        assertThat(LlamaServer.parseDevices("Available devices:\n  (none)\n")).isEmpty();
    }
}
