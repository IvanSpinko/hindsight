package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.visiontrack.analysis.ProgressListener;
import org.example.visiontrack.detection.RgbImage;
import org.example.visiontrack.video.VideoReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

/** Saves the slides of a screen recording as {@code slides/slide-NN.png} plus {@code slides.json} with their times. */
public final class SlideExtractor {

    public record Slide(int number, String file, double seconds, String time) {}

    static final String SLIDES_DIR = "slides";
    static final String SLIDES_JSON = "slides.json";

    /** Screen recordings are decoded at full size so the saved slides keep readable text. */
    private static final int MAX_FRAME_DIMENSION = 8192;

    private final SlideChangeDetector.Settings settings;
    private final ObjectMapper json;

    public SlideExtractor(SlideChangeDetector.Settings settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.json = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public List<Slide> extract(Path video, Path meetingFolder, ProgressListener progress) throws IOException {
        Path dir = meetingFolder.resolve(SLIDES_DIR);
        clear(dir);
        Files.createDirectories(dir);

        SlideChangeDetector detector = new SlideChangeDetector(settings);
        List<Slide> slides = new ArrayList<>();
        try (VideoReader reader = VideoReader.open(video, MAX_FRAME_DIMENSION)) {
            long duration = reader.metadata().durationMicros();
            VideoReader.Frame frame;
            while ((frame = reader.nextFrame()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Processing interrupted");
                Optional<SlideChangeDetector.Event> event = detector.accept(frame.timestampMicros(),
                        SlideChangeDetector.thumbnail(frame.image()));
                if (event.isPresent()) save(event.get(), frame.image(), dir, slides);
                if (duration > 0) progress.onProgress(Math.min(1.0, (double) frame.timestampMicros() / duration));
            }
        }
        json.writeValue(meetingFolder.resolve(SLIDES_JSON).toFile(), slides);
        return slides;
    }

    public List<Slide> read(Path meetingFolder) throws IOException {
        Path file = meetingFolder.resolve(SLIDES_JSON);
        if (!Files.isRegularFile(file)) return List.of();
        return List.of(json.readValue(file.toFile(), Slide[].class));
    }

    private static void save(SlideChangeDetector.Event event, RgbImage image, Path dir, List<Slide> slides) throws IOException {
        String file = "slide-%02d.png".formatted(event.slideNumber());
        ImageIO.write(toBufferedImage(image), "png", dir.resolve(file).toFile());
        if (event.kind() == SlideChangeDetector.Kind.NEW) {
            double seconds = event.timestampMicros() / 1e6;
            slides.add(new Slide(event.slideNumber(), SLIDES_DIR + "/" + file, Math.round(seconds * 10) / 10.0, clock(seconds)));
        }
    }

    static String clock(double seconds) {
        long s = (long) seconds;
        return "%02d:%02d:%02d".formatted(s / 3600, s / 60 % 60, s % 60);
    }

    private static BufferedImage toBufferedImage(RgbImage image) {
        int width = image.width(), height = image.height();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] out = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
        ByteBuffer pixels = image.pixels();
        byte[] row = new byte[width * 3];
        for (int y = 0; y < height; y++) {
            pixels.get(y * image.stride(), row, 0, row.length);
            int o = y * width * 3;
            for (int i = 0; i < row.length; i += 3) {
                out[o + i] = row[i + 2];
                out[o + i + 1] = row[i + 1];
                out[o + i + 2] = row[i];
            }
        }
        return result;
    }

    private static void clear(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.getFileName().toString().matches("slide-\\d+\\.png")).toList()) {
                Files.delete(f);
            }
        }
    }
}
