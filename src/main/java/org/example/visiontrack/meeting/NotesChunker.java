package org.example.visiontrack.meeting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a long meeting into parts of about {@code chunkSeconds} for partial summaries (spec 5.4), so each request
 * fits the local model's context. Parts are cut between speech segments, never inside one.
 */
final class NotesChunker {

    /** A meeting no longer than this many chunks is summarised in one go: a short tail is not worth its own part. */
    static final double SINGLE_PART_FACTOR = 1.5;

    record Part(double start, double end, List<TranscriptSegment> segments) {}

    private NotesChunker() {}

    static List<Part> split(List<TranscriptSegment> segments, double chunkSeconds) {
        List<TranscriptSegment> speech = segments.stream().sorted(Comparator.comparingDouble(TranscriptSegment::start)).toList();
        double end = speech.isEmpty() ? 0 : speech.getLast().end();
        if (end <= chunkSeconds * SINGLE_PART_FACTOR) {
            return List.of(new Part(0, end, speech));
        }

        // Part boundaries: the start of the first segment at least chunkSeconds after the current part's start
        List<Double> starts = new ArrayList<>(List.of(0.0));
        for (TranscriptSegment s : speech) {
            if (s.start() - starts.getLast() >= chunkSeconds) starts.add(s.start());
        }
        // A short last part is merged into the one before it
        if (starts.size() > 1 && end - starts.getLast() < chunkSeconds / 2) starts.removeLast();

        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            double from = starts.get(i);
            double to = i + 1 < starts.size() ? starts.get(i + 1) : Double.MAX_VALUE;
            parts.add(new Part(from, Math.min(to, end),
                    speech.stream().filter(s -> s.start() >= from && s.start() < to).toList()));
        }
        return parts;
    }
}
