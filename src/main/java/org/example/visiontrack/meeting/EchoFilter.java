package org.example.visiontrack.meeting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Removes echoes from a meeting transcript. Without headphones the microphone also hears the other participants
 * from the speakers, so their words appear twice: on the system track and, a moment later, on the microphone track.
 * A microphone segment whose words were mostly said on the system track at about the same time is such an echo
 * and is dropped; the system segment keeps the words with the right speaker.
 */
final class EchoFilter {

    /** How far apart in time the two copies may be (speaker and room delay, different cut points). */
    static final double WINDOW_SECONDS = 2.0;
    /** Share of a microphone segment's words that must also be on the system track. */
    static final double SAME_WORDS = 0.6;

    private EchoFilter() {}

    static List<TranscriptSegment> removeEchoes(List<TranscriptSegment> segments) {
        List<TranscriptSegment> system = segments.stream().filter(s -> MeetingRecorder.SYSTEM.equals(s.source())).toList();
        if (system.isEmpty()) return segments;
        List<TranscriptSegment> kept = new ArrayList<>(segments.size());
        for (TranscriptSegment segment : segments) {
            if (!MeetingRecorder.MIC.equals(segment.source()) || !isEcho(segment, system)) kept.add(segment);
        }
        return kept;
    }

    private static boolean isEcho(TranscriptSegment mic, List<TranscriptSegment> system) {
        List<String> words = words(mic.text());
        if (words.isEmpty()) return false;
        Map<String, Integer> heard = new HashMap<>();
        for (TranscriptSegment s : system) {
            if (s.end() < mic.start() - WINDOW_SECONDS || s.start() > mic.end() + WINDOW_SECONDS) continue;
            for (String word : words(s.text())) heard.merge(word, 1, Integer::sum);
        }
        int same = 0;
        for (String word : words) {
            Integer count = heard.get(word);
            if (count != null && count > 0) {
                same++;
                heard.put(word, count - 1);
            }
        }
        return same >= SAME_WORDS * words.size();
    }

    /** Lower-case words without punctuation, so "Hello, Anna!" and "hello anna" match. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) words.add(word);
        }
        return words;
    }
}
