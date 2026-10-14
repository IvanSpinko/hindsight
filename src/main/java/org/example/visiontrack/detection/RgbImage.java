package org.example.visiontrack.detection;

import java.nio.ByteBuffer;

public record RgbImage(int width, int height, int stride, ByteBuffer pixels) {

    public RgbImage {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Image size must be positive: " + width + "x" + height);
        }
        if (stride < width * 3) {
            throw new IllegalArgumentException("Stride " + stride + " is smaller than row size " + width * 3);
        }
    }
}
