package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.visiontrack.meeting.SpeechChunks.Span;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SpeechRecognitionTest {

    /** Real whisper-vad-speech-segments output for 40 s of Russian, a 3 s pause, English, a 3 s pause, Russian. */
    private static final String VAD_OUTPUT = """
            Detected 10 speech segments:
            Speech segment 0: start = 7.00, end = 102.00
            Speech segment 1: start = 131.00, end = 208.00
            Speech segment 2: start = 295.00, end = 617.00
            Speech segment 3: start = 704.00, end = 1136.00
            Speech segment 4: start = 1213.00, end = 1536.00
            Speech segment 5: start = 1911.00, end = 2163.00
            Speech segment 6: start = 2237.00, end = 2598.00
            Speech segment 7: start = 2688.00, end = 2880.00
            Speech segment 8: start = 3261.00, end = 3795.00
            Speech segment 9: start = 3875.00, end = 3993.00
            """;

    @Test
    void speechIsCutAtTheLongPausesWhereTheLanguageChanges() {
        List<Span> speech = SpeechChunks.parseVad(VAD_OUTPUT);
        assertThat(speech).hasSize(10);
        assertThat(speech.getFirst()).isEqualTo(new Span(0.07, 1.02));

        List<Span> chunks = SpeechChunks.plan(speech, 40.64);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).end()).isCloseTo(15.56, within(0.01));    // Russian, ends before the first pause
        assertThat(chunks.get(1).start()).isCloseTo(18.91, within(0.01));  // English
        assertThat(chunks.get(2).start()).isCloseTo(32.41, within(0.01));  // Russian again
        assertThat(chunks.get(2).end()).isCloseTo(40.13, within(0.01));
    }

    @Test
    void continuousSpeechIsSentInPiecesOfAtMostThirtySeconds() {
        List<Span> speech = List.of(new Span(0, 9), new Span(9.3, 18), new Span(18.3, 27), new Span(27.3, 36), new Span(36.3, 45));

        List<Span> chunks = SpeechChunks.plan(speech, 45);

        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(30.4));
        assertThat(chunks).hasSize(2);
    }

    @Test
    void shortPhrasesAreGroupedAndSilenceGivesNoPieces() {
        assertThat(SpeechChunks.plan(List.of(), 60)).isEmpty();
        List<Span> chunks = SpeechChunks.plan(List.of(new Span(1, 2), new Span(3, 4), new Span(4.5, 6)), 60);
        assertThat(chunks).containsExactly(new Span(0.8, 6.2));
    }

    @Test
    void parsesServerResponseAndPicksTheLikeliestAllowedLanguage() throws Exception {
        String response = """
                {"task":"transcribe","language":"ukrainian","duration":14.0,"text":"...",
                 "detected_language":"ukrainian","detected_language_probability":0.55,
                 "language_probabilities":{"uk":0.55,"ru":0.40,"en":0.05},
                 "segments":[
                   {"id":0,"text":" Good afternoon","start":0.0,"end":1.5,"no_speech_prob":0.01,"avg_logprob":-0.2},
                   {"id":1,"text":" Thanks for watching!","start":1.5,"end":3.0,"no_speech_prob":0.9,"avg_logprob":-1.4}]}
                """;

        WhisperServer.Inference inference = WhisperServer.parse(new ObjectMapper().readTree(response));

        assertThat(inference.mostLikely(List.of())).isEqualTo("uk");
        assertThat(inference.mostLikely(List.of("ru", "en"))).isEqualTo("ru");
        assertThat(inference.pieces()).hasSize(2);
        assertThat(inference.pieces().get(1).likelyHallucination()).isTrue();
    }

    @Test
    void segmentsAreMovedToTheMeetingTimelineAndSplitWordsAreJoined() {
        WhisperServer.Inference inference = new WhisperServer.Inference(java.util.Map.of("ru", 1.0), List.of(
                new WhisperServer.Piece(0.0, 4.0, " we need to check the server lo", 0.01, -0.2),
                new WhisperServer.Piece(4.0, 5.0, "ad this week", 0.01, -0.2),
                new WhisperServer.Piece(5.0, 6.0, " Any questions?", 0.02, -0.3),
                new WhisperServer.Piece(6.0, 9.0, " Subtitles by the community", 0.8, -1.5)));

        List<TranscriptSegment> segments = WhisperCppTranscriber.toSegments(inference, 30.0, "mic", "ru");

        assertThat(segments).containsExactly(
                new TranscriptSegment(30.0, 35.0, "mic", "ru", "we need to check the server load this week"),
                new TranscriptSegment(35.0, 36.0, "mic", "ru", "Any questions?"));
    }

    @Test
    void wavPiecesAreStandaloneFiles() throws Exception {
        byte[] wav = wav(16_000, 32_000);                      // 2 s
        PcmWav audio = PcmWav.parse(wav);
        assertThat(audio.durationSeconds()).isEqualTo(2.0);

        PcmWav piece = PcmWav.parse(audio.slice(0.5, 1.5));
        assertThat(piece.sampleRate()).isEqualTo(16_000);
        assertThat(piece.durationSeconds()).isEqualTo(1.0);

        // An interrupted recording has a data size of 0 in its header: everything present is used
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 0);
        assertThat(PcmWav.parse(wav).durationSeconds()).isEqualTo(2.0);
    }

    @Test
    void transcriptMarkdownGroupsParagraphsAndMarksLanguages() {
        Transcriber.Result result = new Transcriber.Result(List.of(
                new TranscriptSegment(0, 4, "mic", "ru", "Good afternoon, colleagues."),
                new TranscriptSegment(4.5, 8, "mic", "ru", "Let us start with the release."),
                new TranscriptSegment(19, 25, "mic", "en", "Now in English for our partners.")),
                "large-v3-turbo-q8_0", "CUDA", List.of("ru", "en"));

        String md = TranscriptWriter.markdown("Release sync", result);

        assertThat(md).startsWith("# Transcript: Release sync")
                .contains("large-v3-turbo-q8_0 on CUDA · languages: ru, en")
                .contains("**[00:00:00] Me (RU):** Good afternoon, colleagues. Let us start with the release.")
                .contains("**[00:00:19] Me (EN):** Now in English for our partners.");
        assertThat(WhisperCppTranscriber.modelName(Path.of("models/ggml-large-v3-turbo-q8_0.bin"))).isEqualTo("large-v3-turbo-q8_0");
    }

    private static byte[] wav(int sampleRate, int samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + samples * 2).putInt(0x45564157);
        b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(samples * 2);
        return b.array();
    }
}
