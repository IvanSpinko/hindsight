package org.example.visiontrack.tracking;

import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.Detection.BoundingBox;
import org.example.visiontrack.tracking.ObjectTracker.TrackPoint;
import org.example.visiontrack.tracking.ObjectTracker.TrackedObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectTrackerTest {

    private static final long SECOND = 1_000_000;

    private final ObjectTracker tracker = new ObjectTracker(0.3f, SECOND, 2);

    private static Detection person(float x) {
        return new Detection(0, "person", 0.9f, new BoundingBox(x, 100, x + 100, 300));
    }

    private static Detection car(float x) {
        return new Detection(2, "car", 0.8f, new BoundingBox(x, 400, x + 200, 500));
    }

    @Test
    void movingObjectIsCountedOnceWithFirstAndLastTimestamps() {
        tracker.update(List.of(person(0)), 0);
        tracker.update(List.of(person(10)), SECOND / 5);
        tracker.update(List.of(person(20)), 2 * SECOND / 5);

        List<TrackedObject> objects = tracker.finish();

        assertThat(objects).hasSize(1);
        assertThat(objects.get(0).label()).isEqualTo("person");
        assertThat(objects.get(0).firstSeenMicros()).isZero();
        assertThat(objects.get(0).lastSeenMicros()).isEqualTo(2 * SECOND / 5);
        assertThat(objects.get(0).detections()).isEqualTo(3);
        assertThat(objects.get(0).path()).extracting(TrackPoint::timestampMicros)
                .containsExactly(0L, SECOND / 5, 2 * SECOND / 5);
        assertThat(objects.get(0).path().get(2).box().x1()).isEqualTo(20f);
    }

    @Test
    void twoSeparateObjectsOfTheSameClassAreCountedTwice() {
        tracker.update(List.of(person(0), person(500)), 0);
        tracker.update(List.of(person(5), person(505)), SECOND / 5);

        assertThat(tracker.finish()).hasSize(2);
    }

    @Test
    void differentClassesNeverShareATrack() {
        Detection p = new Detection(0, "person", 0.9f, new BoundingBox(0, 0, 100, 100));
        Detection d = new Detection(16, "dog", 0.9f, new BoundingBox(0, 0, 100, 100));
        tracker.update(List.of(p, d), 0);
        tracker.update(List.of(p, d), SECOND / 5);

        assertThat(tracker.finish()).extracting(TrackedObject::label).containsExactlyInAnyOrder("person", "dog");
    }

    @Test
    void singleFrameFlickerIsIgnored() {
        tracker.update(List.of(person(0), car(0)), 0);
        tracker.update(List.of(person(0)), SECOND / 5);

        assertThat(tracker.finish()).extracting(TrackedObject::label).containsExactly("person");
    }

    @Test
    void shortGapContinuesTheTrackButLongGapStartsANewObject() {
        tracker.update(List.of(person(0)), 0);
        tracker.update(List.of(person(0)), SECOND / 5);
        tracker.update(List.of(person(0)), SECOND);
        tracker.update(List.of(person(0)), 4 * SECOND);
        tracker.update(List.of(person(0)), 4 * SECOND + SECOND / 5);

        List<TrackedObject> objects = tracker.finish();

        assertThat(objects).hasSize(2);
        assertThat(objects.get(0).lastSeenMicros()).isEqualTo(SECOND);
        assertThat(objects.get(1).firstSeenMicros()).isEqualTo(4 * SECOND);
    }

    @Test
    void objectsAreOrderedByFirstAppearanceAndNumberedSequentially() {
        tracker.update(List.of(car(0)), 0);
        tracker.update(List.of(car(0), person(600)), SECOND / 5);
        tracker.update(List.of(person(600)), 2 * SECOND / 5);

        List<TrackedObject> objects = tracker.finish();

        assertThat(objects).extracting(TrackedObject::label).containsExactly("car", "person");
        assertThat(objects).extracting(TrackedObject::id).containsExactly(1, 2);
    }
}
