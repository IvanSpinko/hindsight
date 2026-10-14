package org.example.visiontrack.meeting;

import org.example.visiontrack.detection.RgbImage;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;

/**
 * Picks the frames worth keeping as slides from a screen recording (about one frame per second).
 *
 * <p>Frames are compared as 320x180 grayscale thumbnails split into a 32x18 grid of cells:
 * <ul>
 *   <li>cells that keep changing (participant webcams, a playing video) are learned and ignored;</li>
 *   <li>a slide is kept once the picture has changed noticeably since the last slide and then stayed
 *       still for {@code stableSeconds}, so transitions and animations do not produce extra slides;</li>
 *   <li>if the change only <em>adds</em> content where the last slide was empty (a bullet appearing),
 *       the last slide is replaced by the fuller version instead of adding a new one.</li>
 * </ul>
 * A slide is dated by the first recent frame that already looked the same, so it gets the right time even
 * when the decision came later (for example while the webcam areas were still being learned).
 * Not thread-safe; one instance per video.
 */
public final class SlideChangeDetector {

    public static final int THUMB_WIDTH = 320;
    public static final int THUMB_HEIGHT = 180;

    private static final int CELL = 10;
    private static final int GRID_WIDTH = THUMB_WIDTH / CELL;
    private static final int GRID_HEIGHT = THUMB_HEIGHT / CELL;
    private static final int CELLS = GRID_WIDTH * GRID_HEIGHT;

    /** Mean gray-level difference (0-255) above which a cell counts as changed; video noise stays well below. */
    private static final double CELL_CHANGED = 6;
    /** A cell whose own gray levels vary less than this is plain background. */
    private static final double CELL_EMPTY_STDDEV = 4;
    /** Activity: moving average of "changed since the previous frame"; above this a cell is live video. */
    private static final double ACTIVITY_RATE = 0.15;
    private static final double LIVE_ACTIVITY = 0.4;
    /** How slowly a cell found to be live is forgotten (per frame), so a webcam that pauses stays ignored. */
    private static final double LIVE_MEMORY = 0.995;
    /** A frame is still when at most this share of the static cells changed since the previous frame. */
    private static final double STILL_SHARE = 0.005;
    /** Share of all-blank cells above which a frame is a blank screen, not a slide. */
    private static final double BLANK_SHARE = 0.995;
    /** An addition counts as a build step of the same slide when this share of the changed cells was empty. */
    private static final double ADDITION_SHARE = 0.9;
    /** Recent frames kept for dating slides (about a minute at one frame per second). */
    private static final int HISTORY = 60;

    private record Frame(long timestampMicros, byte[] thumb) {}

    public record Settings(double stableSeconds, double minChangedShare) {
        public static final Settings DEFAULT = new Settings(2.0, 0.03);
    }

    public enum Kind {
        /** A new slide: save it with this time. */
        NEW,
        /** The current slide gained content: replace its picture, keep its time. */
        UPDATE
    }

    public record Event(Kind kind, int slideNumber, long timestampMicros) {}

    private final Settings settings;
    private final double[] activity = new double[CELLS];
    private final double[] live = new double[CELLS];
    private byte[] previous;
    private final Deque<Frame> history = new ArrayDeque<>();
    private byte[] lastSlide;
    private long stillSince;
    private boolean decidedForThisStill;
    private int slides;

    public SlideChangeDetector(Settings settings) {
        this.settings = settings;
    }

    public int slideCount() {
        return slides;
    }

    /** Feeds the next frame's thumbnail (see {@link #thumbnail}) and reports whether it should be saved. */
    public Optional<Event> accept(long timestampMicros, byte[] thumb) {
        if (thumb.length != THUMB_WIDTH * THUMB_HEIGHT) {
            throw new IllegalArgumentException("Expected a " + THUMB_WIDTH + "x" + THUMB_HEIGHT + " thumbnail");
        }
        history.addFirst(new Frame(timestampMicros, thumb.clone()));
        if (history.size() > HISTORY) history.removeLast();
        if (previous == null) {
            previous = thumb.clone();
            stillSince = timestampMicros;
            return Optional.empty();
        }

        boolean[] changed = new boolean[CELLS];
        for (int c = 0; c < CELLS; c++) {
            changed[c] = cellDifference(previous, thumb, c) > CELL_CHANGED;
            activity[c] += ACTIVITY_RATE * ((changed[c] ? 1 : 0) - activity[c]);
            live[c] = Math.max(activity[c], live[c] * LIVE_MEMORY);
        }
        previous = thumb.clone();

        if (share(changed) > STILL_SHARE) {
            stillSince = timestampMicros;
            decidedForThisStill = false;
            return Optional.empty();
        }
        if (decidedForThisStill || timestampMicros - stillSince < settings.stableSeconds() * 1_000_000) {
            return Optional.empty();
        }
        decidedForThisStill = true;
        return decide(thumb);
    }

    private Optional<Event> decide(byte[] thumb) {
        if (isBlank(thumb)) return Optional.empty();
        if (lastSlide == null) {
            lastSlide = thumb.clone();
            return Optional.of(new Event(Kind.NEW, ++slides, firstSeen(thumb)));
        }

        boolean[] changed = new boolean[CELLS];
        int changedCount = 0, addedCount = 0;
        for (int c = 0; c < CELLS; c++) {
            if (isLive(c)) continue;
            if (cellDifference(lastSlide, thumb, c) > CELL_CHANGED) {
                changed[c] = true;
                changedCount++;
                if (cellStdDev(lastSlide, c) < CELL_EMPTY_STDDEV) addedCount++;
            }
        }
        if (share(changed) < settings.minChangedShare()) return Optional.empty();

        lastSlide = thumb.clone();
        if (addedCount >= ADDITION_SHARE * changedCount) {
            return Optional.of(new Event(Kind.UPDATE, slides, firstSeen(thumb)));
        }
        return Optional.of(new Event(Kind.NEW, ++slides, firstSeen(thumb)));
    }

    /** Time of the earliest frame in the unbroken run of recent frames that look like {@code thumb}. */
    private long firstSeen(byte[] thumb) {
        long first = stillSince;
        for (Iterator<Frame> it = history.iterator(); it.hasNext(); ) {
            Frame frame = it.next();
            boolean[] changed = new boolean[CELLS];
            for (int c = 0; c < CELLS; c++) changed[c] = cellDifference(frame.thumb(), thumb, c) > CELL_CHANGED;
            if (share(changed) > STILL_SHARE) break;
            first = Math.min(first, frame.timestampMicros());
        }
        return first;
    }

    private boolean isLive(int cell) {
        return live[cell] > LIVE_ACTIVITY;
    }

    /** Share of the static (not live) cells that are flagged. */
    private double share(boolean[] flags) {
        int total = 0, flagged = 0;
        for (int c = 0; c < CELLS; c++) {
            if (isLive(c)) continue;
            total++;
            if (flags[c]) flagged++;
        }
        return total == 0 ? 0 : (double) flagged / total;
    }

    private boolean isBlank(byte[] thumb) {
        int empty = 0;
        for (int c = 0; c < CELLS; c++) {
            if (cellStdDev(thumb, c) < CELL_EMPTY_STDDEV) empty++;
        }
        // A uniform screen (black, or a single colour while an app loads) has no content to keep
        return empty >= BLANK_SHARE * CELLS && globalStdDev(thumb) < 2 * CELL_EMPTY_STDDEV;
    }

    private static double cellDifference(byte[] a, byte[] b, int cell) {
        int x0 = cell % GRID_WIDTH * CELL, y0 = cell / GRID_WIDTH * CELL;
        long sum = 0;
        for (int y = y0; y < y0 + CELL; y++) {
            int row = y * THUMB_WIDTH;
            for (int x = x0; x < x0 + CELL; x++) {
                sum += Math.abs((a[row + x] & 0xFF) - (b[row + x] & 0xFF));
            }
        }
        return (double) sum / (CELL * CELL);
    }

    private static double cellStdDev(byte[] image, int cell) {
        int x0 = cell % GRID_WIDTH * CELL, y0 = cell / GRID_WIDTH * CELL;
        long sum = 0, squares = 0;
        for (int y = y0; y < y0 + CELL; y++) {
            int row = y * THUMB_WIDTH;
            for (int x = x0; x < x0 + CELL; x++) {
                int v = image[row + x] & 0xFF;
                sum += v;
                squares += (long) v * v;
            }
        }
        double n = CELL * CELL, mean = sum / n;
        return Math.sqrt(Math.max(0, squares / n - mean * mean));
    }

    private static double globalStdDev(byte[] image) {
        long sum = 0, squares = 0;
        for (byte b : image) {
            int v = b & 0xFF;
            sum += v;
            squares += (long) v * v;
        }
        double n = image.length, mean = sum / n;
        return Math.sqrt(Math.max(0, squares / n - mean * mean));
    }

    /** 320x180 grayscale thumbnail: every source pixel is averaged into its block, so thin text still counts. */
    public static byte[] thumbnail(RgbImage image) {
        int width = image.width(), height = image.height();
        long[] sums = new long[THUMB_WIDTH * THUMB_HEIGHT];
        int[] counts = new int[THUMB_WIDTH * THUMB_HEIGHT];
        int[] column = new int[width];
        for (int x = 0; x < width; x++) column[x] = (int) ((long) x * THUMB_WIDTH / width);

        ByteBuffer pixels = image.pixels();
        byte[] row = new byte[width * 3];
        for (int y = 0; y < height; y++) {
            pixels.get(y * image.stride(), row, 0, row.length);
            int base = (int) ((long) y * THUMB_HEIGHT / height) * THUMB_WIDTH;
            for (int x = 0, i = 0; x < width; x++, i += 3) {
                int luma = (77 * (row[i] & 0xFF) + 150 * (row[i + 1] & 0xFF) + 29 * (row[i + 2] & 0xFF)) >> 8;
                int t = base + column[x];
                sums[t] += luma;
                counts[t]++;
            }
        }
        byte[] thumb = new byte[THUMB_WIDTH * THUMB_HEIGHT];
        for (int i = 0; i < thumb.length; i++) {
            thumb[i] = (byte) (counts[i] == 0 ? 0 : sums[i] / counts[i]);
        }
        return thumb;
    }
}
