package org.example.visiontrack.video;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.example.visiontrack.detection.RgbImage;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VideoReader implements AutoCloseable {

    static {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
    }

    public record Metadata(int width, int height, double frameRate, long durationMicros) {}

    public record Frame(long timestampMicros, RgbImage image) {}

    private final FFmpegFrameGrabber grabber;
    private final Metadata metadata;
    private final long startOffsetMicros;

    public static VideoReader open(Path file, int maxFrameDimension) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Video file not found: " + file);
        }
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile());
        try {
            grabber.setPixelFormat(avutil.AV_PIX_FMT_RGB24);
            grabber.start();
            if (!grabber.hasVideo() || grabber.getImageWidth() <= 0) {
                throw new IllegalArgumentException("File contains no video stream");
            }
            return new VideoReader(grabber, maxFrameDimension);
        } catch (Exception e) {
            try {
                grabber.close();
            } catch (Exception ignored) {
            }
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("Cannot open video: " + e.getMessage(), e);
        }
    }

    private VideoReader(FFmpegFrameGrabber grabber, int maxFrameDimension) {
        this.grabber = grabber;

        int sourceWidth = grabber.getImageWidth();
        int sourceHeight = grabber.getImageHeight();
        double scale = Math.min(1.0, (double) maxFrameDimension / Math.max(sourceWidth, sourceHeight));
        int width = Math.max(2, (int) Math.round(sourceWidth * scale) & ~1);
        int height = Math.max(2, (int) Math.round(sourceHeight * scale) & ~1);
        if (width != sourceWidth || height != sourceHeight) {
            grabber.setImageWidth(width);
            grabber.setImageHeight(height);
        }

        long start = grabber.getFormatContext().start_time();
        this.startOffsetMicros = start == avutil.AV_NOPTS_VALUE ? 0 : start;
        this.metadata = new Metadata(width, height, grabber.getVideoFrameRate(), Math.max(0, grabber.getLengthInTime()));
    }

    public Metadata metadata() {
        return metadata;
    }

    public Frame nextFrame() {
        try {
            org.bytedeco.javacv.Frame frame = grabber.grabImage();
            if (frame == null) return null;
            RgbImage image = new RgbImage(frame.imageWidth, frame.imageHeight, frame.imageStride,
                    (ByteBuffer) frame.image[0]);
            return new Frame(Math.max(0, frame.timestamp - startOffsetMicros), image);
        } catch (FrameGrabber.Exception e) {
            throw new UncheckedIOException("Error while decoding video: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            grabber.close();
        } catch (FrameGrabber.Exception e) {
            throw new UncheckedIOException("Error closing video", e);
        }
    }
}
