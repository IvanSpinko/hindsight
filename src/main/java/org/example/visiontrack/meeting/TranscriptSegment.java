package org.example.visiontrack.meeting;

/**
 * One piece of recognised speech. Times are seconds on the meeting timeline; {@code source} is
 * {@code mic} (the user, "Me") or {@code system} (the other participants); {@code language} is the
 * ISO code Whisper used for this piece, e.g. {@code ru} or {@code en}.
 */
public record TranscriptSegment(double start, double end, String source, String language, String text) {}
