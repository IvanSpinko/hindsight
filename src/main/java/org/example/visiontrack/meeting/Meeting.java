package org.example.visiontrack.meeting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A recorded meeting, stored as {@code meeting.json} in the meeting's folder.
 * Track offsets put all tracks on one timeline: media time 0 of a track is {@code startOffsetMillis}
 * after {@link #startedAt}. {@code capturePids} are the ffmpeg processes of a running recording, kept so
 * they can be stopped if the app crashes (on Windows child processes outlive their parent).
 *
 * <p>Lifecycle: RECORDING → RECORDED (files joined) → PROCESSING (slides, later speech and notes) → READY.
 * FAILED keeps whatever was recorded; processing can be started again.
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
        String error,
        Processing processing,
        int slideCount
) {

    public enum Status { RECORDING, RECORDED, PROCESSING, READY, FAILED }

    /** The processing step that is running and how far it got (0-100). */
    public record Processing(String step, int percent) {}

    public record Track(String kind, String file, long startOffsetMillis, double durationSeconds) {}

    public Meeting {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
        capturePids = capturePids == null ? List.of() : List.copyOf(capturePids);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    static Meeting recording(String id, String title, String screenCapture, String microphone, List<Track> tracks,
                             List<Long> capturePids) {
        return new Meeting(id, title, Status.RECORDING, Instant.now(), null, screenCapture, microphone, tracks, capturePids,
                null, null, null, 0);
    }

    Meeting withWarning(String warning) {
        List<String> all = new ArrayList<>(warnings);
        if (!all.contains(warning)) all.add(warning);
        return new Meeting(id, title, status, startedAt, stoppedAt, screenCapture, microphone, tracks, capturePids, all,
                error, processing, slideCount);
    }

    Meeting recorded(Instant stoppedAt, List<Track> finalTracks) {
        return new Meeting(id, title, Status.RECORDED, startedAt, stoppedAt, screenCapture, microphone, finalTracks, null,
                warnings, null, null, slideCount);
    }

    Meeting processing(String step, int percent) {
        return new Meeting(id, title, Status.PROCESSING, startedAt, stoppedAt, screenCapture, microphone, tracks, null,
                warnings, null, new Processing(step, percent), slideCount);
    }

    Meeting ready(int slides) {
        return new Meeting(id, title, Status.READY, startedAt, stoppedAt, screenCapture, microphone, tracks, null,
                warnings, null, null, slides);
    }

    Meeting failed(String error) {
        Instant end = stoppedAt == null ? Instant.now() : stoppedAt;
        return new Meeting(id, title, Status.FAILED, startedAt, end, screenCapture, microphone, tracks, null,
                warnings, error, null, slideCount);
    }

    /** The recording file to process: the combined recording, or the screen video if combining failed. */
    Track screenTrack() {
        return tracks.stream().filter(t -> t.kind().equals(MeetingRecorder.SCREEN)).findFirst().orElse(null);
    }
}
