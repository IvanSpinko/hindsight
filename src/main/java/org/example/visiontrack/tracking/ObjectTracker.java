package org.example.visiontrack.tracking;

import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.Detection.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ObjectTracker {

    public record TrackedObject(int id, int classId, String label,
                                long firstSeenMicros, long lastSeenMicros,
                                int detections, float bestConfidence, BoundingBox bestBox,
                                List<TrackPoint> path) {}

    public record TrackPoint(long timestampMicros, BoundingBox box) {}

    private final float iouThreshold;
    private final long maxGapMicros;
    private final int minHits;

    private final List<Track> active = new ArrayList<>();
    private final List<TrackedObject> finished = new ArrayList<>();
    private int nextId = 1;

    public ObjectTracker(float iouThreshold, long maxGapMicros, int minHits) {
        this.iouThreshold = iouThreshold;
        this.maxGapMicros = maxGapMicros;
        this.minHits = minHits;
    }

    public void update(List<Detection> detections, long timestampMicros) {
        closeStaleTracks(timestampMicros);

        record Match(Track track, int detection, float iou) {}
        List<Match> matches = new ArrayList<>();
        for (Track track : active) {
            for (int d = 0; d < detections.size(); d++) {
                Detection detection = detections.get(d);
                if (detection.classId() != track.classId) continue;
                float iou = track.lastBox.iou(detection.box());
                if (iou >= iouThreshold) matches.add(new Match(track, d, iou));
            }
        }
        matches.sort(Comparator.comparingDouble(Match::iou).reversed());

        boolean[] detectionUsed = new boolean[detections.size()];
        List<Track> updated = new ArrayList<>();
        for (Match match : matches) {
            if (detectionUsed[match.detection()] || updated.contains(match.track())) continue;
            detectionUsed[match.detection()] = true;
            updated.add(match.track());
            match.track().add(detections.get(match.detection()), timestampMicros);
        }

        for (int d = 0; d < detections.size(); d++) {
            if (!detectionUsed[d]) active.add(new Track(detections.get(d), timestampMicros));
        }
    }

    public List<TrackedObject> finish() {
        closeStaleTracks(Long.MAX_VALUE);
        List<TrackedObject> result = new ArrayList<>(finished);
        result.sort(Comparator.comparingLong(TrackedObject::firstSeenMicros)
                .thenComparingInt(TrackedObject::id));
        return result;
    }

    private void closeStaleTracks(long now) {
        active.removeIf(track -> {
            boolean stale = now == Long.MAX_VALUE || now - track.lastSeen > maxGapMicros;
            if (stale && track.id > 0) finished.add(track.toObject());
            return stale;
        });
    }

    private final class Track {
        final int classId;
        final String label;
        final long firstSeen;
        long lastSeen;
        int hits;
        BoundingBox lastBox;
        float bestConfidence;
        BoundingBox bestBox;
        final List<TrackPoint> path = new ArrayList<>();
        int id;

        Track(Detection detection, long timestamp) {
            this.classId = detection.classId();
            this.label = detection.label();
            this.firstSeen = timestamp;
            add(detection, timestamp);
        }

        void add(Detection detection, long timestamp) {
            lastSeen = timestamp;
            lastBox = detection.box();
            path.add(new TrackPoint(timestamp, detection.box()));
            hits++;
            if (detection.confidence() > bestConfidence) {
                bestConfidence = detection.confidence();
                bestBox = detection.box();
            }
            if (id == 0 && hits >= minHits) id = nextId++;
        }

        TrackedObject toObject() {
            return new TrackedObject(id, classId, label, firstSeen, lastSeen, hits, bestConfidence, bestBox,
                    List.copyOf(path));
        }
    }
}
