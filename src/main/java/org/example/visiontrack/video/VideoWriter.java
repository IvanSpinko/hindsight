package org.example.visiontrack.video;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameRecorder;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public final class VideoWriter implements AutoCloseable {

    private static final double BITS_PER_PIXEL = 0.1;

    private final FFmpegFrameRecorder recorder;
    private final int width;
    private final int height;
    private final Frame frame;

    public static VideoWriter create(Path file, int width, int height, double frameRate) {
        if (width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("H.264 needs even frame sizes, got " + width + "x" + height);
        }
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file.toFile(), width, height, 0);
        recorder.setFormat("mp4");
        // Encoded by NVENC on the NVIDIA GPU; no B-frames, NVENC refuses them with the short GOP of low frame rates
        recorder.setVideoCodecName("h264_nvenc");
        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        recorder.setFrameRate(frameRate);
        recorder.setGopSize((int) Math.max(1, Math.round(frameRate * 2)));
        recorder.setMaxBFrames(0);
        recorder.setVideoBitrate((int) (width * height * frameRate * BITS_PER_PIXEL));
        recorder.setOption("movflags", "+faststart");
        try {
            recorder.start();
        } catch (FrameRecorder.Exception e) {
            try {
                recorder.release();
            } catch (FrameRecorder.Exception ignored) {
            }
            throw new UncheckedIOException("Cannot create video " + file.getFileName() + ": " + e.getMessage(), e);
        }
        return new VideoWriter(recorder, width, height);
    }

    private VideoWriter(FFmpegFrameRecorder recorder, int width, int height) {
        this.recorder = recorder;
        this.width = width;
        this.height = height;
        this.frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
    }

    public void write(BufferedImage image) {
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR || image.getWidth() != width || image.getHeight() != height) {
            throw new IllegalArgumentException("Expected a " + width + "x" + height + " TYPE_3BYTE_BGR image");
        }
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        ByteBuffer buffer = (ByteBuffer) frame.image[0];
        int rowBytes = width * 3;
        for (int y = 0; y < height; y++) {
            buffer.put(y * frame.imageStride, pixels, y * rowBytes, rowBytes);
        }
        try {
            recorder.record(frame, avutil.AV_PIX_FMT_BGR24);
        } catch (FrameRecorder.Exception e) {
            throw new UncheckedIOException("Error while encoding video: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            recorder.stop();
            recorder.release();
        } catch (FrameRecorder.Exception e) {
            throw new UncheckedIOException("Error finishing video: " + e.getMessage(), e);
        }
    }
}
