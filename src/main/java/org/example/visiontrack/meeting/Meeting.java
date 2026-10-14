package org.example.visiontrack.meeting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A recorded meeting, stored as {@code meeting.json} in the meeting's folder.
 * Track offsets put all tracks on one timeline: media time 0 of a track is {@code startOffsetMillis}
 * after {@link #startedAt}. {@code capturePids} are the ffmpeg processes of a running recording, kept so
 * they can be stopped if the app crashes (on Windows child processes outlive their parent).
 */
public record Meeting(
        String id,
        String title,
        Status status,
        Instant startedAt,
        Instant stoppedAt,
        String screenCapture,
        String microphone,
        List<Track> tracks,
        List<Long> capturePids,
        List<String> warnings,
        String error
) {

    public enum Status { RECORDING, RECORDED, FAILED }

    public record Track(String kind, String file, long startOffsetMillis, double durationSeconds) {}

    public Meeting {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
        capturePids = capturePids == null ? List.of() : List.copyOf(capturePids);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    static Meeting recording(String id, String title, String screenCapture, String microphone, List<Track> tracks,
                             List<Long> capturePids) {
        return new Meeting(id, title, Status.RECORDING, Instant.now(), null, screenCapture, microphone, tracks, capturePids, null, null);
    }

    Meeting withWarning(String warning) {
        List<String> all = new ArrayList<>(warnings);
        if (!all.contains(warning)) all.add(warning);
        return new Meeting(id, title, status, startedAt, stoppedAt, screenCapture, microphone, tracks, capturePids, all, error);
    }

    Meeting recorded(Instant stoppedAt, List<Track> finalTracks) {
        return new Meeting(id, title, Status.RECORDED, startedAt, stoppedAt, screenCapture, microphone, finalTracks, null, warnings, null);
    }

    Meeting failed(String error) {
        return new Meeting(id, title, Status.FAILED, startedAt, Instant.now(), screenCapture, microphone, tracks, null, warnings, error);
    }
}
