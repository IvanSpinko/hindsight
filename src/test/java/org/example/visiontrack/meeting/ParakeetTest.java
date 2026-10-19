package org.example.visiontrack.meeting;

import org.example.visiontrack.meeting.ParakeetModel.Emission;
import org.example.visiontrack.meeting.ParakeetModel.Prediction;
import org.example.visiontrack.meeting.ParakeetTranscriber.Timed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParakeetTest {

    private static final int BLANK = 9;

    @Test
    void greedyDecodingFollowsTheTokensAndTheirJumps() throws Exception {
        // frame 0: "a" without a jump, then blank with a jump of 2; frame 2: "b" jumping 1; frame 3: blank to the end
        List<int[]> calls = new ArrayList<>();
        List<Emission> emitted = ParakeetModel.greedy(4, BLANK, 0, (frame, previous, state) -> {
            calls.add(new int[]{frame, previous, state});
            return switch (calls.size()) {
                case 1 -> new Prediction<>(1, 0, state + 1);
                case 2 -> new Prediction<>(BLANK, 2, 99);
                case 3 -> new Prediction<>(2, 1, state + 1);
                default -> new Prediction<>(BLANK, 1, 99);
            };
        });

        assertThat(emitted).containsExactly(new Emission(1, 0), new Emission(2, 2));
        // the previous token and the state only change with a real token; a blank never updates the state
        assertThat(calls).extracting(c -> c[0]).containsExactly(0, 0, 2, 3);
        assertThat(calls).extracting(c -> c[1]).containsExactly(BLANK, 1, 1, 2);
        assertThat(calls).extracting(c -> c[2]).containsExactly(0, 1, 1, 2);
    }

    @Test
    void aFrameIsLeftAfterTheMostTokensItMayEmit() throws Exception {
        List<Emission> emitted = ParakeetModel.greedy(2, BLANK, 0, (frame, previous, state) -> new Prediction<>(5, 0, state));

        assertThat(emitted).hasSize(2 * ParakeetModel.MAX_TOKENS_PER_FRAME);
        assertThat(emitted.get(ParakeetModel.MAX_TOKENS_PER_FRAME).frame()).isEqualTo(1);
    }

    @Test
    void tokensBecomeOneSegmentPerSentence() {
        List<Timed> pieces = List.of(new Timed(" Hello", 1.0), new Timed(" there", 1.4), new Timed(".", 1.6),
                new Timed(" How", 2.0), new Timed(" are", 2.2), new Timed(" you", 2.4), new Timed("?", 2.5));

        List<TranscriptSegment> segments = ParakeetTranscriber.segments(pieces, "mic", List.of("en", "ru"));

        assertThat(segments).containsExactly(
                new TranscriptSegment(1.0, 1.68, "mic", "en", "Hello there."),
                new TranscriptSegment(2.0, 2.58, "mic", "en", "How are you?"));
    }

    @Test
    void aFullStopInsideANumberDoesNotEndTheSentence() {
        List<Timed> pieces = List.of(new Timed(" version", 1.0), new Timed(" 1", 1.3), new Timed(".", 1.4),
                new Timed("2", 1.5), new Timed(" ships", 1.8), new Timed(".", 2.0), new Timed(" Done", 2.4), new Timed(".", 2.6));

        assertThat(ParakeetTranscriber.segments(pieces, "mic", List.of("en"))).extracting(TranscriptSegment::text)
                .containsExactly("version 1.2 ships.", "Done.");
    }

    @Test
    void aLongPauseStartsANewSegmentButNotInsideAWord() {
        List<Timed> pieces = List.of(new Timed(" so", 0.0), new Timed(" we", 0.3), new Timed(" sh", 4.0),
                new Timed("ip", 6.0), new Timed(" it", 6.2));

        List<TranscriptSegment> segments = ParakeetTranscriber.segments(pieces, "system", List.of("en"));

        assertThat(segments).extracting(TranscriptSegment::text).containsExactly("so we", "ship it");
        assertThat(segments.get(1).start()).isEqualTo(4.0);
    }

    @Test
    void punctuationAloneIsNotASegment() {
        assertThat(ParakeetTranscriber.segments(List.of(new Timed(".", 1.0)), "mic", List.of("en"))).isEmpty();
        assertThat(ParakeetTranscriber.segments(List.of(), "mic", List.of("en"))).isEmpty();
    }

    @Test
    void theLanguageComesFromTheLettersWithinTheAllowedOnes() {
        String russian = "\u041F\u0440\u0438\u0432\u0435\u0442, \u043A\u0430\u043A \u0434\u0435\u043B\u0430?";
        assertThat(ParakeetTranscriber.language("Let's ship the release.", List.of("ru", "en"))).isEqualTo("en");
        assertThat(ParakeetTranscriber.language(russian + " OK", List.of("ru", "en"))).isEqualTo("ru");
        assertThat(ParakeetTranscriber.language("Bun\u0103 ziua", List.of("ro", "ru"))).isEqualTo("ro");
        assertThat(ParakeetTranscriber.language(russian, List.of("en"))).isEqualTo("ru");
    }

    @Test
    void piecesWrittenMostlyInCyrillicAreHandedToWhisper() {
        String ukrainian = "\u0422\u0430\u043A \u0441\u0430\u043C\u043E \u0437 \u0446\u0438\u043C";
        TranscriptSegment cyrillic = new TranscriptSegment(0, 1, "mixed", "ru", ukrainian + " OK");
        TranscriptSegment latin = new TranscriptSegment(1, 2, "mixed", "en", "Let's ship it on Friday.");

        assertThat(ParakeetTranscriber.isMostlyCyrillic(List.of(cyrillic))).isTrue();
        assertThat(ParakeetTranscriber.isMostlyCyrillic(List.of(latin))).isFalse();
        assertThat(ParakeetTranscriber.isMostlyCyrillic(List.of(latin, cyrillic))).isFalse();
        assertThat(ParakeetTranscriber.isMostlyCyrillic(List.of())).isFalse();
    }

    @Test
    void theVocabularyIsReadByIdWithTheWordMarker(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("vocab.txt");
        Files.writeString(file, "<unk> 0\n\u2581the 2\ns 1\n<blk> 3\n", StandardCharsets.UTF_8);

        assertThat(ParakeetModel.readVocabulary(file)).containsExactly("<unk>", "s", "\u2581the", "<blk>");

        Files.writeString(file, "<unk> 0\n<blk> 2\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ParakeetModel.readVocabulary(file)).hasMessageContaining("no token 1");
    }

    @Test
    void floatSamplesAreScaledFromSixteenBits() throws Exception {
        PcmWav wav = PcmWav.parse(SpeechRecognitionTestSupport.wav(16_000, new short[]{0, 16384, -32768, 32767}));

        assertThat(wav.floats(0, 1)).containsExactly(0f, 0.5f, -1f, 32767 / 32768f);
        assertThat(wav.floats(0.5, 1)).isEmpty();
    }

    @Test
    void theModelIsInstalledOnlyWithAllItsFiles(@TempDir Path dir) throws Exception {
        for (String file : ParakeetModel.FILES) Files.writeString(dir.resolve(file), "x");
        assertThat(ParakeetModel.isInstalled(dir)).isTrue();

        Files.delete(dir.resolve(ParakeetModel.ENCODER + ".data"));
        assertThat(ParakeetModel.isInstalled(dir)).isFalse();
    }
}
