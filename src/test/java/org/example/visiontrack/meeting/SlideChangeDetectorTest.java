package org.example.visiontrack.meeting;

import org.example.visiontrack.meeting.SlideChangeDetector.Event;
import org.example.visiontrack.meeting.SlideChangeDetector.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.visiontrack.meeting.SlideChangeDetector.THUMB_HEIGHT;
import static org.example.visiontrack.meeting.SlideChangeDetector.THUMB_WIDTH;

/** Feeds synthetic 320x180 thumbnails at one frame per second, like a screen recording. */
class SlideChangeDetectorTest {

    private static final long SECOND = 1_000_000;

    private final Random random = new Random(42);

    @Test
    void findsEachSlideOnceWithTheTimeItAppeared() {
        byte[] a = slide(1), b = slide(2), c = slide(3);

        List<Event> events = run(t -> t < 10 ? a : t < 20 ? b : c, 30);

        assertThat(events).extracting(Event::kind).containsExactly(Kind.NEW, Kind.NEW, Kind.NEW);
        assertThat(events).extracting(Event::timestampMicros).containsExactly(0L, 10 * SECOND, 20 * SECOND);
    }

    @Test
    void transitionFramesDoNotBecomeSlides() {
        byte[] a = slide(1), b = slide(2);
        byte[] fade = blend(a, b);

        List<Event> events = run(t -> t < 10 ? a : t == 10 ? fade : b, 20);

        assertThat(events).hasSize(2);
        assertThat(events.get(1).timestampMicros()).isEqualTo(11 * SECOND);
    }

    @Test
    void slideShownForLessThanTheStableTimeIsSkipped() {
        byte[] a = slide(1), flash = slide(2), b = slide(3);

        List<Event> events = run(t -> t < 10 ? a : t == 10 ? flash : b, 20);

        assertThat(events).hasSize(2);
    }

    @Test
    void participantWebcamsAreIgnored() {
        byte[] a = slide(1), b = slide(2);

        List<Event> events = run(t -> withWebcam(t < 20 ? a : b), 40);

        assertThat(events).extracting(Event::kind).containsExactly(Kind.NEW, Kind.NEW);
        assertThat(events.get(1).timestampMicros()).isEqualTo(20 * SECOND);
    }

    @Test
    void bulletAppearingOnTheSameSlideUpdatesIt() {
        byte[] a = slide(1);
        byte[] withBullet = a.clone();
        text(withBullet, 40, 140, 200, 20, 99);

        List<Event> events = run(t -> t < 10 ? a : withBullet, 20);

        assertThat(events).extracting(Event::kind).containsExactly(Kind.NEW, Kind.UPDATE);
        assertThat(events).extracting(Event::slideNumber).containsExactly(1, 1);
    }

    @Test
    void nextSlideWithTheSameLayoutIsNew() {
        byte[] a = background();
        byte[] b = background();
        for (byte[] s : List.of(a, b)) text(s, 20, 10, 280, 20, 1);   // same title bar
        text(a, 40, 50, 240, 100, 2);
        text(b, 40, 50, 240, 100, 3);                                  // different body text in the same place

        List<Event> events = run(t -> t < 10 ? a : b, 20);

        assertThat(events).extracting(Event::kind).containsExactly(Kind.NEW, Kind.NEW);
    }

    @Test
    void movingPointerAndSmallChangesAreIgnored() {
        byte[] a = slide(1);

        List<Event> events = run(t -> {
            byte[] frame = a.clone();
            fill(frame, (int) (t * 7 % 300), 100, 6, 6, 0);           // a pointer-sized blob moving around
            return frame;
        }, 30);

        assertThat(events).hasSize(1);
    }

    @Test
    void blankScreenIsNotASlide() {
        byte[] black = new byte[THUMB_WIDTH * THUMB_HEIGHT];

        assertThat(run(t -> black, 10)).isEmpty();
    }

    private static List<Event> run(IntFunction<byte[]> frameAtSecond, int seconds) {
        SlideChangeDetector detector = new SlideChangeDetector(SlideChangeDetector.Settings.DEFAULT);
        List<Event> events = new ArrayList<>();
        for (int t = 0; t < seconds; t++) {
            detector.accept(t * SECOND, frameAtSecond.apply(t)).ifPresent(events::add);
        }
        return events;
    }

    // ---- synthetic frames ----

    private static byte[] background() {
        byte[] image = new byte[THUMB_WIDTH * THUMB_HEIGHT];
        Arrays.fill(image, (byte) 235);
        return image;
    }

    /** A slide: a title and a few paragraphs of "text" that differ per seed. */
    private static byte[] slide(int seed) {
        byte[] image = background();
        text(image, 20, 10, 200, 20, seed);
        text(image, 30, 50, 260, 70, seed * 31 + 7);
        return image;
    }

    /** Lines of dark "glyphs" with gaps, so cells have texture like real text. */
    private static void text(byte[] image, int x0, int y0, int w, int h, int seed) {
        Random r = new Random(seed);
        for (int y = y0; y < y0 + h; y += 6) {
            for (int x = x0; x < x0 + w; ) {
                int glyph = 2 + r.nextInt(5);
                if (r.nextInt(5) > 0) fill(image, x, y, glyph, 3, 30);
                x += glyph + 1 + r.nextInt(2);
            }
        }
    }

    private static void fill(byte[] image, int x0, int y0, int w, int h, int value) {
        for (int y = Math.max(0, y0); y < Math.min(THUMB_HEIGHT, y0 + h); y++) {
            for (int x = Math.max(0, x0); x < Math.min(THUMB_WIDTH, x0 + w); x++) {
                image[y * THUMB_WIDTH + x] = (byte) value;
            }
        }
    }

    private static byte[] blend(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (byte) (((a[i] & 0xFF) + (b[i] & 0xFF)) / 2);
        return out;
    }

    /** A 60x40 webcam tile in the corner with new content every frame. */
    private byte[] withWebcam(byte[] slide) {
        byte[] frame = slide.clone();
        for (int y = 130; y < 170; y++) {
            for (int x = 250; x < 310; x++) {
                frame[y * THUMB_WIDTH + x] = (byte) (60 + random.nextInt(140));
            }
        }
        return frame;
    }
}
