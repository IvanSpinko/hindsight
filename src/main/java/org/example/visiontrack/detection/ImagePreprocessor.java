package org.example.visiontrack.detection;

import java.util.Arrays;

public final class ImagePreprocessor {

    static final float PAD_VALUE = 114f / 255f;

    private ImagePreprocessor() {}

    public record Letterbox(int inputWidth, int inputHeight,
                            double scale, int resizedWidth, int resizedHeight,
                            int padX, int padY) {

        public static Letterbox fit(int sourceWidth, int sourceHeight, int inputWidth, int inputHeight) {
            double scale = Math.min((double) inputWidth / sourceWidth, (double) inputHeight / sourceHeight);
            int resizedWidth = Math.max(1, Math.min(inputWidth, (int) Math.round(sourceWidth * scale)));
            int resizedHeight = Math.max(1, Math.min(inputHeight, (int) Math.round(sourceHeight * scale)));
            return new Letterbox(inputWidth, inputHeight, scale, resizedWidth, resizedHeight,
                    (inputWidth - resizedWidth) / 2, (inputHeight - resizedHeight) / 2);
        }

        public float toSourceX(float x) {
            return (float) ((x - padX) / scale);
        }

        public float toSourceY(float y) {
            return (float) ((y - padY) / scale);
        }
    }

    public static void toChwTensor(RgbImage image, Letterbox letterbox, float[] out) {
        int inW = letterbox.inputWidth();
        int inH = letterbox.inputHeight();
        int plane = inW * inH;
        if (out.length < 3 * plane) {
            throw new IllegalArgumentException("Output buffer too small: " + out.length + " < " + 3 * plane);
        }
        Arrays.fill(out, 0, 3 * plane, PAD_VALUE);

        int srcW = image.width();
        int srcH = image.height();
        int stride = image.stride();
        byte[] src = new byte[(srcH - 1) * stride + srcW * 3];
        image.pixels().get(0, src, 0, src.length);

        int rw = letterbox.resizedWidth();
        int rh = letterbox.resizedHeight();

        int[] x0 = new int[rw];
        int[] x1 = new int[rw];
        float[] wx = new float[rw];
        double fx = (double) srcW / rw;
        for (int x = 0; x < rw; x++) {
            double sx = Math.max(0.0, (x + 0.5) * fx - 0.5);
            int ix = Math.min((int) sx, srcW - 1);
            x0[x] = ix * 3;
            x1[x] = Math.min(ix + 1, srcW - 1) * 3;
            wx[x] = (float) (sx - ix);
        }

        double fy = (double) srcH / rh;
        final float inv255 = 1f / 255f;
        for (int y = 0; y < rh; y++) {
            double sy = Math.max(0.0, (y + 0.5) * fy - 0.5);
            int iy = Math.min((int) sy, srcH - 1);
            int row0 = iy * stride;
            int row1 = Math.min(iy + 1, srcH - 1) * stride;
            float wy = (float) (sy - iy);
            int outRow = (letterbox.padY() + y) * inW + letterbox.padX();

            for (int x = 0; x < rw; x++) {
                int a = row0 + x0[x], b = row0 + x1[x], c = row1 + x0[x], d = row1 + x1[x];
                float w = wx[x];
                int o = outRow + x;
                for (int ch = 0; ch < 3; ch++) {
                    float top = (src[a + ch] & 0xFF) + w * ((src[b + ch] & 0xFF) - (src[a + ch] & 0xFF));
                    float bottom = (src[c + ch] & 0xFF) + w * ((src[d + ch] & 0xFF) - (src[c + ch] & 0xFF));
                    out[ch * plane + o] = (top + wy * (bottom - top)) * inv255;
                }
            }
        }
    }
}
