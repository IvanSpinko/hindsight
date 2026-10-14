package org.example.visiontrack.meeting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Uploaded recordings made with the bundled ffmpeg: audio kept, H.264 copied, other video re-encoded by NVENC. */
class RecordingImportTest {

    private static Ffmpeg ffmpeg;

    @TempDir
    Path folder;

    @BeforeAll
    static void locateFfmpeg() {
        ffmpeg = Ffmpeg.locate(null);
    }

    @Test
    void audioFileIsKeptAndItsSoundExtracted() throws IOException {
        Meeting.Track upload = upload("mp3", List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:a", "libmp3lame"));

        List<Meeting.Track> tracks = RecordingImport.prepare(ffmpeg, folder, upload);

        assertThat(tracks).extracting(Meeting.Track::kind).containsExactly(RecordingImport.AUDIO, RecordingImport.MIXED);
        assertThat(folder.resolve("recording.mp3")).isRegularFile();
        assertThat(folder.resolve("upload")).doesNotExist();
        assertWhisperReady(tracks.get(1), 3.0);
    }

    @Test
    void h264VideoIsCopied() throws IOException {
        Meeting.Track upload = upload("mp4", List.of("-f", "lavfi", "-i", "testsrc=size=320x240:rate=10:duration=3",
                "-f", "lavfi", "-i", "sine=duration=3", "-c:v", "libopenh264", "-c:a", "aac", "-shortest"));

        List<Meeting.Track> tracks = RecordingImport.prepare(ffmpeg, folder, upload);

        assertThat(tracks).extracting(Meeting.Track::kind).containsExactly(MeetingRecorder.SCREEN, RecordingImport.MIXED);
        assertThat(RecordingImport.streams(ffmpeg, folder.resolve("recording.mp4"))).isEqualTo(new RecordingImport.Streams("h264", true));
        assertThat(tracks.getFirst().durationSeconds()).isCloseTo(3.0, within(0.3));
        assertWhisperReady(tracks.get(1), 3.0);
    }

    @Test
    void otherVideoIsReencodedSoTheBrowserCanPlayIt() throws IOException {
        Meeting.Track upload = upload("webm", List.of("-f", "lavfi", "-i", "testsrc=size=320x240:rate=10:duration=3",
                "-f", "lavfi", "-i", "sine=duration=3", "-c:v", "libvpx-vp9", "-c:a", "libopus", "-shortest"));

        RecordingImport.prepare(ffmpeg, folder, upload);

        assertThat(RecordingImport.streams(ffmpeg, folder.resolve("recording.mp4"))).isEqualTo(new RecordingImport.Streams("h264", true));
        assertThat(folder.resolve("upload/source.webm")).doesNotExist();
    }

    @Test
    void fileWithoutSoundIsRejected() throws IOException {
        Meeting.Track upload = upload("mp4", List.of("-f", "lavfi", "-i", "testsrc=size=320x240:rate=10:duration=1", "-c:v", "libopenh264"));

        assertThatThrownBy(() -> RecordingImport.prepare(ffmpeg, folder, upload)).hasMessageContaining("no sound");
    }

    @Test
    void albumCoversAreNotVideo() {
        assertThat(RecordingImport.parseStreams("mp3,audio\nmjpeg,video\n")).isEqualTo(new RecordingImport.Streams(null, true));
        assertThat(RecordingImport.parseStreams("vp9,video\nopus,audio\n")).isEqualTo(new RecordingImport.Streams("vp9", true));
    }

    @Test
    void onlyAudioAndVideoFilesAreAccepted() {
        assertThat(RecordingImport.isSupported("Weekly call.M4A")).isTrue();
        assertThat(RecordingImport.isSupported("zoom_0.mp4")).isTrue();
        assertThat(RecordingImport.isSupported("notes.docx")).isFalse();
        assertThat(RecordingImport.isSupported("no-extension")).isFalse();
    }

    @Test
    void uploadedRecordingsAreSpokenByParticipants() {
        assertThat(TranscriptWriter.speaker(RecordingImport.MIXED)).isEqualTo("Participants");
        assertThat(TranscriptWriter.speaker(MeetingRecorder.MIC)).isEqualTo("Me");
        assertThat(TranscriptWriter.speaker(MeetingRecorder.SYSTEM)).isEqualTo("Other participants");
    }

    /** Writes {@code upload/source.<extension>} the way MeetingImporter stores an upload. */
    private Meeting.Track upload(String extension, List<String> inputAndCodecs) throws IOException {
        Path file = folder.resolve("upload/source." + extension);
        Files.createDirectories(file.getParent());
        List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y"));
        args.addAll(inputAndCodecs);
        args.add(file.toString());
        Ffmpeg.Result result = ffmpeg.run(args, Duration.ofMinutes(1));
        assertThat(result.exitCode()).as(result.output()).isZero();
        return new Meeting.Track(RecordingImport.UPLOAD, "upload/source." + extension, 0, 0);
    }

    private void assertWhisperReady(Meeting.Track speech, double seconds) throws IOException {
        PcmWav wav = PcmWav.read(folder.resolve(speech.file()));
        assertThat(wav.durationSeconds()).isCloseTo(seconds, within(0.3));
        assertThat(speech.file()).isEqualTo("audio/mixed.wav");
    }
}
