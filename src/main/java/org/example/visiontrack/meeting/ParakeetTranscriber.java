package org.example.visiontrack.meeting;

import ai.onnxruntime.OrtException;
import org.example.visiontrack.analysis.ProgressListener;
import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Speech recognition with NVIDIA Parakeet on CUDA, the most accurate model on English.
 * Pieces it writes in Cyrillic are recognised again by Whisper, the backup.
 */
public final class ParakeetTranscriber implements Transcriber {

    private static final Logger log = LoggerFactory.getLogger(ParakeetTranscriber.class);
    /** Languages written in Cyrillic; anything else Parakeet knows is written in Latin (or Greek) letters. */
    private static final Set<String> CYRILLIC = Set.of("ru", "uk", "be", "bg", "sr", "mk");
    /** A silence this long between two words starts a new segment even without a full stop. */
    static final double SEGMENT_PAUSE = 1.5;

    /** A piece of text and the time it was heard, in seconds on the meeting timeline. */
    record Timed(String text, double seconds) {}

    private final Path modelDirectory;
    private final VisionTrackProperties.Whisper vad;
    private final Runnable loadNatives;
    private final WhisperCppTranscriber cyrillicFallback;

    /**
     * @param vad              the voice activity detection tool and the languages to label segments with
     * @param loadNatives      loads the CUDA libraries before ONNX Runtime needs them
     * @param cyrillicFallback Whisper for pieces written in Cyrillic; null keeps Parakeet's text
     */
    public ParakeetTranscriber(Path modelDirectory, VisionTrackProperties.Whisper vad, Runnable loadNatives,
                               WhisperCppTranscriber cyrillicFallback) {
        this.modelDirectory = modelDirectory;
        this.vad = vad;
        this.loadNatives = loadNatives;
        this.cyrillicFallback = cyrillicFallback;
    }

    /** Whether the model files and the voice activity detection are installed. */
    public static boolean isInstalled(Path modelDirectory, VisionTrackProperties.Whisper vad) {
        return ParakeetModel.isInstalled(modelDirectory) && WhisperCppTranscriber.isVadInstalled(vad);
    }

    /** Parakeet first; the Cyrillic pieces then go to Whisper once Parakeet is freed (they never share VRAM). */
    @Override
    public Result transcribe(List<Track> tracks, ProgressListener progress) throws IOException {
        // With a fallback the first pass is shown as the first part of the progress bar
        double share = cyrillicFallback == null ? 1.0 : 0.7;
        List<TranscriptSegment> segments = new ArrayList<>();
        List<SpeechChunks.Piece> handOver = new ArrayList<>();
        loadNatives.run();
        // The model is loaded first, so a missing GPU is reported before any other work is done
        try (ParakeetModel model = ParakeetModel.load(modelDirectory)) {
            List<SpeechChunks.Piece> pieces = SpeechChunks.pieces(tracks, vad);
            log.info("Speech recognition: {} on CUDA, {} pieces of speech in {} track(s)", ParakeetModel.NAME, pieces.size(), tracks.size());
            int done = 0;
            for (SpeechChunks.Piece piece : pieces) {
                List<Timed> timed = model.recognize(piece.audio().floats(piece.span().start(), piece.span().end())).stream()
                        .map(e -> new Timed(model.text(e.token()), piece.start() + e.frame() * ParakeetModel.FRAME_SECONDS))
                        .toList();
                List<TranscriptSegment> recognised = segments(timed, piece.source(), vad.languages());
                if (cyrillicFallback != null && isMostlyCyrillic(recognised)) {
                    handOver.add(piece);
                } else {
                    segments.addAll(recognised);
                }
                progress.onProgress(share * ++done / pieces.size());
            }
        } catch (OrtException e) {
            throw new IOException("Speech recognition failed: " + e.getMessage(), e);
        }

        String model = ParakeetModel.NAME;
        if (!handOver.isEmpty()) {
            log.info("Speech recognition: {} piece(s) in Cyrillic go to Whisper", handOver.size());
            Result whisper = cyrillicFallback.transcribePieces(handOver, f -> progress.onProgress(share + (1 - share) * f));
            segments.addAll(whisper.segments());
            model = model + " + " + whisper.model();
        }
        segments.sort(Comparator.comparingDouble(TranscriptSegment::start));
        List<String> languages = segments.stream().map(TranscriptSegment::language).distinct().toList();
        return new Result(segments, model, "CUDA", languages);
    }

    /** Whether most of the letters Parakeet wrote for a piece are Cyrillic. */
    static boolean isMostlyCyrillic(List<TranscriptSegment> segments) {
        String text = String.join(" ", segments.stream().map(TranscriptSegment::text).toList());
        long letters = text.codePoints().filter(Character::isLetter).count();
        return letters > 0 && cyrillicLetters(text) * 2 > letters;
    }

    private static long cyrillicLetters(String text) {
        return text.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC).count();
    }

    /** Joins tokens into one segment per sentence, also cut at pauses of {@link #SEGMENT_PAUSE}. */
    static List<TranscriptSegment> segments(List<Timed> pieces, String source, List<String> languages) {
        List<TranscriptSegment> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        double start = 0;
        double last = 0;
        boolean sentenceEnded = false;
        for (Timed piece : pieces) {
            boolean wordStart = piece.text().startsWith(" ");
            if (!text.isEmpty() && wordStart && (sentenceEnded || piece.seconds() - last >= SEGMENT_PAUSE)) {
                add(result, text, start, last, source, languages);
            }
            if (text.isEmpty()) start = piece.seconds();
            text.append(piece.text());
            last = piece.seconds();
            sentenceEnded = endsSentence(piece.text());
        }
        add(result, text, start, last, source, languages);
        return result;
    }

    private static void add(List<TranscriptSegment> result, StringBuilder text, double start, double last,
                            String source, List<String> languages) {
        String clean = text.toString().replaceAll("\\s+", " ").strip();
        text.setLength(0);
        if (clean.isEmpty() || clean.chars().noneMatch(Character::isLetterOrDigit)) return;
        result.add(new TranscriptSegment(round(start), round(last + ParakeetModel.FRAME_SECONDS), source,
                language(clean, languages), clean));
    }

    private static boolean endsSentence(String token) {
        String t = token.strip();
        return !t.isEmpty() && ".?!\u2026".indexOf(t.charAt(t.length() - 1)) >= 0;
    }

    /** Picks an allowed language by the text's script, since Parakeet does not report one. */
    static String language(String text, List<String> allowed) {
        long cyrillic = cyrillicLetters(text);
        long letters = text.codePoints().filter(Character::isLetter).count();
        boolean isCyrillic = cyrillic * 2 > letters;
        return allowed.stream().filter(l -> CYRILLIC.contains(l) == isCyrillic).findFirst().orElse(isCyrillic ? "ru" : "en");
    }

    private static double round(double seconds) {
        return Math.round(seconds * 100) / 100.0;
    }
}
