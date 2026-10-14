package org.example.visiontrack.render;

import org.example.visiontrack.analysis.AnalysisResult;
import org.example.visiontrack.analysis.AnalysisResult.DetectedObject;
import org.example.visiontrack.analysis.AnalysisResult.Position;
import org.example.visiontrack.analysis.ProgressListener;
import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.detection.RgbImage;
import org.example.visiontrack.video.VideoReader;
import org.example.visiontrack.video.VideoWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

@Component
public class AnnotatedVideoRenderer {

    private static final Logger log = LoggerFactory.getLogger(AnnotatedVideoRenderer.class);

    private static final Color[] COLORS = {
            new Color(0xff5252), new Color(0x40c4ff), new Color(0x69f0ae), new Color(0xffd740), new Color(0xe040fb),
            new Color(0xff9100), new Color(0x64ffda), new Color(0xb388ff), new Color(0xeeff41), new Color(0xff80ab)};

    private final int maxFrameDimension;

    public AnnotatedVideoRenderer(VisionTrackProperties properties) {
        this.maxFrameDimension = properties.analysis().maxFrameDimension();
    }

    public void render(Path source, AnalysisResult result, Path target, ProgressListener progress) {
        long started = System.nanoTime();
        try (VideoReader reader = VideoReader.open(source, maxFrameDimension)) {
            VideoReader.Metadata metadata = reader.metadata();
            int width = metadata.width();
            int height = metadata.height();
            double frameRate = metadata.frameRate() > 0 && metadata.frameRate() <= 240 ? metadata.frameRate() : 30;
            double sampleFps = result.processing().sampleFps() > 0 ? result.processing().sampleFps() : frameRate;
            double step = 1.0 / sampleFps;
            double sx = (double) width / result.video().width();
            double sy = (double) height / result.video().height();

            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] pixels = ((DataBufferByte) canvas.getRaster().getDataBuffer()).getData();
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int fontSize = Math.max(12, height / 40);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            float lineWidth = Math.max(2f, height / 300f);
            g.setStroke(new BasicStroke(lineWidth));

            try (VideoWriter writer = VideoWriter.create(target, width, height, frameRate)) {
                VideoReader.Frame frame;
                while ((frame = reader.nextFrame()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("Rendering interrupted");
                    }
                    copyRgbToBgr(frame.image(), pixels, width, height);
                    double t = frame.timestampMicros() / 1e6;
                    for (DetectedObject object : result.objects()) {
                        Position p = positionAt(object.track(), t, step);
                        if (p != null) drawBox(g, object, p, sx, sy, lineWidth);
                    }
                    writer.write(canvas);
                    if (metadata.durationMicros() > 0) {
                        progress.onProgress(Math.min(1.0, (double) frame.timestampMicros() / metadata.durationMicros()));
                    }
                }
            } finally {
                g.dispose();
            }
        }
        log.info("Rendered annotated video {} in {} s", target.getFileName(),
                "%.1f".formatted((System.nanoTime() - started) / 1e9));
    }

    static Position positionAt(List<Position> track, double t, double step) {
        if (track == null || track.isEmpty()) return null;
        double hold = step * 0.75;
        if (t < track.get(0).t() - hold || t > track.get(track.size() - 1).t() + hold) return null;

        int lo = 0, hi = track.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (track.get(mid).t() <= t) lo = mid; else hi = mid - 1;
        }
        Position a = track.get(lo);
        if (a.t() > t) return a;
        if (lo + 1 < track.size()) {
            Position b = track.get(lo + 1);
            if (b.t() - a.t() <= step * 2.5) {
                double f = (t - a.t()) / (b.t() - a.t());
                return new Position(t,
                        (int) Math.round(a.x() + (b.x() - a.x()) * f), (int) Math.round(a.y() + (b.y() - a.y()) * f),
                        (int) Math.round(a.w() + (b.w() - a.w()) * f), (int) Math.round(a.h() + (b.h() - a.h()) * f));
            }
        }
        return t - a.t() <= hold ? a : null;
    }

    private static void drawBox(Graphics2D g, DetectedObject object, Position p, double sx, double sy, float lineWidth) {
        int x = (int) Math.round(p.x() * sx);
        int y = (int) Math.round(p.y() * sy);
        int w = (int) Math.round(p.w() * sx);
        int h = (int) Math.round(p.h() * sy);
        Color color = colorFor(object.label());

        g.setColor(color);
        g.drawRect(x, y, w, h);

        String text = "#" + object.id() + " " + object.label();
        FontMetrics fm = g.getFontMetrics();
        int pad = Math.max(3, fm.getHeight() / 6);
        int labelW = fm.stringWidth(text) + pad * 2;
        int labelH = fm.getHeight();
        int half = Math.round(lineWidth / 2);
        int labelY = y - labelH >= 0 ? y - labelH : y;
        g.fillRect(x - half, labelY, labelW, labelH);
        g.setColor(Color.BLACK);
        g.drawString(text, x - half + pad, labelY + fm.getAscent());
    }

    static Color colorFor(String label) {
        return COLORS[(int) (Integer.toUnsignedLong(label.hashCode()) % COLORS.length)];
    }

    private static void copyRgbToBgr(RgbImage image, byte[] bgr, int width, int height) {
        if (image.width() != width || image.height() != height) {
            throw new IllegalStateException("Frame size changed mid-video: " + image.width() + "x" + image.height());
        }
        int stride = image.stride();
        byte[] row = new byte[width * 3];
        for (int y = 0; y < height; y++) {
            image.pixels().get(y * stride, row, 0, row.length);
            int o = y * width * 3;
            for (int i = 0; i < row.length; i += 3) {
                bgr[o + i] = row[i + 2];
                bgr[o + i + 1] = row[i + 1];
                bgr[o + i + 2] = row[i];
            }
        }
    }
}
