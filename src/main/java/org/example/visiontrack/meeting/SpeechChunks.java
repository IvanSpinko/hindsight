package org.example.visiontrack.meeting;

import org.example.visiontrack.config.VisionTrackProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits speech into the pieces sent to Whisper. Whisper picks one language per request, so pieces are
 * kept short (at most {@code maxSeconds}) and cut at pauses: a meeting that switches between Russian and
 * English then gets the right language for each part. Silence is never sent, which also avoids Whisper's
 * habit of inventing text in silent audio.
 */
final class SpeechChunks {

    record Span(double start, double end) {
        double length() {
            return end - start;
        }
    }

    /** Typical Whisper window; longer speech without pauses is still sent whole rather than cut mid-word. */
    static final double MAX_SECONDS = 30;
    /** Pieces are not closed at a pause before they are this long, so short phrases are grouped. */
    static final double MIN_SECONDS = 10;
    /** A pause at least this long ends a piece once it has MIN_SECONDS of speech. */
    static final double SPLIT_PAUSE = 0.8;
    /** A pause at least this long always ends a piece: the speaker or the language often changes there. */
    static final double LONG_PAUSE = 2.0;
    /** Audio kept around the speech so first and last syllables are not clipped. */
    static final double PADDING = 0.2;

    /** A piece to recognise: its span in the track's audio, and where that track sits on the meeting timeline. */
    record Piece(PcmWav audio, Span span, double offsetSeconds, String source) {
        /** Where the piece starts on the meeting timeline. */
        double start() {
            return offsetSeconds + span.start();
        }
    }

    private static final Pattern VAD_SEGMENT = Pattern.compile("start = ([\\d.]+), end = ([\\d.]+)");

    private SpeechChunks() {}

    /** Finds the speech in every track (Silero VAD) and cuts it into pieces, track by track. */
    static List<Piece> pieces(List<Transcriber.Track> tracks, VisionTrackProperties.Whisper vad) throws IOException {
        List<Piece> pieces = new ArrayList<>();
        for (Transcriber.Track track : tracks) {
            PcmWav wav = PcmWav.read(track.wav());
            for (Span span : plan(WhisperCppTranscriber.detectSpeech(vad, track.wav()), wav.durationSeconds())) {
                pieces.add(new Piece(wav, span, track.offsetSeconds(), track.source()));
            }
        }
        return pieces;
    }

    /** Parses whisper-vad-speech-segments output (times in centiseconds). */
    static List<Span> parseVad(String output) {
        List<Span> spans = new ArrayList<>();
        Matcher m = VAD_SEGMENT.matcher(output);
        while (m.find()) {
            spans.add(new Span(Double.parseDouble(m.group(1)) / 100, Double.parseDouble(m.group(2)) / 100));
        }
        return spans;
    }

    static List<Span> plan(List<Span> speech, double totalSeconds) {
        List<Span> chunks = new ArrayList<>();
        Span current = null;
        for (Span s : speech) {
            if (current == null) {
                current = s;
                continue;
            }
            double pause = s.start() - current.end();
            boolean tooLong = s.end() - current.start() > MAX_SECONDS;
            boolean naturalBreak = pause >= LONG_PAUSE || (pause >= SPLIT_PAUSE && current.length() >= MIN_SECONDS);
            if (tooLong || naturalBreak) {
                chunks.add(current);
                current = s;
            } else {
                current = new Span(current.start(), s.end());
            }
        }
        if (current != null) chunks.add(current);
        return chunks.stream()
                .map(c -> new Span(Math.max(0, c.start() - PADDING), Math.min(totalSeconds, c.end() + PADDING)))
                .toList();
    }
}
