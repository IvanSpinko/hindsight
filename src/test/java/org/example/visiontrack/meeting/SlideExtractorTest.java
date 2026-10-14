package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.visiontrack.video.VideoWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End to end on a generated "presentation" encoded like a real recording (H.264, 1 fps): eight slides of
 * different lengths, fade transitions, a participant webcam that never stops moving, and a slide whose
 * bullets appear one by one.
 */
class SlideExtractorTest {

    private static final int WIDTH = 1280, HEIGHT = 720;
    /** Seconds each slide is shown; slide 4 (index 3) builds its bullets one at a time. */
    private static final int[] DURATIONS = {8, 12, 6, 15, 9, 7, 10, 8};
    private static final int BUILD_SLIDE = 3;

    @TempDir
    Path dir;

    private final Random random = new Random(7);

    @Test
    void findsEverySlideOfAPresentationAtTheRightTime() throws Exception {
        Path video = dir.resolve("recording.mp4");
        List<Integer> starts = writePresentation(video);

        SlideExtractor extractor = new SlideExtractor(SlideChangeDetector.Settings.DEFAULT, new ObjectMapper());
        List<SlideExtractor.Slide> slides = extractor.extract(video, dir, fraction -> {});

        assertThat(slides).hasSameSizeAs(starts);
        for (int i = 0; i < slides.size(); i++) {
            // A slide is dated by the first still frame, i.e. right after its transition
            assertThat(slides.get(i).seconds()).as("slide %d", i + 1).isCloseTo(starts.get(i), within(2.0));
        }
        BufferedImage first = ImageIO.read(dir.resolve(slides.getFirst().file()).toFile());
        assertThat(first.getWidth()).isEqualTo(WIDTH);
        Color titleBar = new Color(first.getRGB(20, 20));
        assertThat(titleBar.getBlue()).as("colours keep their channel order").isGreaterThan(titleBar.getRed() + 40);
        assertThat(extractor.read(dir)).isEqualTo(slides);

        // The built-up slide is saved in its final state, with all four bullets
        BufferedImage built = ImageIO.read(dir.resolve(slides.get(BUILD_SLIDE).file()).toFile());
        assertThat(darkPixels(built, 100, 450, 600, 45)).as("last bullet present").isGreaterThan(300);
        assertThat(Files.exists(dir.resolve("slides.json"))).isTrue();
    }

    /** Writes the presentation and returns the second at which each slide starts. */
    private List<Integer> writePresentation(Path file) {
        List<Integer> starts = new ArrayList<>();
        BufferedImage previous = null;
        int t = 0;
        try (VideoWriter writer = VideoWriter.create(file, WIDTH, HEIGHT, 1)) {
            for (int s = 0; s < DURATIONS.length; s++) {
                BufferedImage slide = slide(s, 4);
                if (previous != null) {
                    writer.write(withWebcam(fade(previous, slide)));   // one frame of transition
                    t++;
                }
                starts.add(t);
                for (int i = 0; i < DURATIONS[s]; i++, t++) {
                    // On the build slide a new bullet appears every 3 seconds
                    BufferedImage frame = s == BUILD_SLIDE ? slide(s, Math.min(4, 1 + i / 3)) : slide;
                    writer.write(withWebcam(frame));
                }
                previous = slide;
            }
        }
        return starts;
    }

    private static BufferedImage slide(int index, int bullets) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(250, 250, 248));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(30, 60, 120));
        g.fillRect(0, 0, WIDTH, 110);                       // the same title bar on every slide
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
        g.drawString("Quarterly review, part " + (index + 1), 60, 75);
        g.setColor(new Color(20, 20, 20));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 34));
        Random words = new Random(index * 1000L);
        for (int b = 0; b < bullets; b++) {
            StringBuilder line = new StringBuilder("• ");
            for (int w = 0; w < 6; w++) line.append(WORDS[words.nextInt(WORDS.length)]).append(' ');
            g.drawString(line.toString(), 100, 200 + b * 95);
        }
        g.dispose();
        return image;
    }

    private static final String[] WORDS = {"revenue", "growth", "users", "release", "budget", "roadmap", "hiring",
            "latency", "costs", "partners", "risks", "metrics", "quality", "support", "design", "security"};

    private static BufferedImage fade(BufferedImage from, BufferedImage to) {
        BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.drawImage(from, 0, 0, null);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g.drawImage(to, 0, 0, null);
        g.dispose();
        return out;
    }

    /** A 240x135 "participant video" in the bottom-right corner that changes every frame. */
    private BufferedImage withWebcam(BufferedImage slide) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = frame.createGraphics();
        g.drawImage(slide, 0, 0, null);
        g.setColor(new Color(40, 40, 40));
        g.fillRect(1020, 565, 240, 135);
        g.setColor(new Color(200 - random.nextInt(60), 160, 130));
        g.fillOval(1090 + random.nextInt(40) - 20, 590 + random.nextInt(20) - 10, 90, 100);   // a moving head
        g.dispose();
        return frame;
    }

    private static int darkPixels(BufferedImage image, int x0, int y0, int w, int h) {
        int dark = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3 < 100) dark++;
            }
        }
        return dark;
    }
}
