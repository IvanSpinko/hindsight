package org.example.visiontrack.meeting;

/** One piece of recognised speech: seconds on the meeting timeline, the track it came from, its language. */
public record TranscriptSegment(double start, double end, String source, String language, String text) {}
