package org.example.visiontrack.meeting;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Screen capture arguments. The primary method is {@code ddagrab} (Desktop Duplication API: cheap, captures
 * hardware-accelerated windows); {@code gdigrab} is the fallback for systems where it fails, e.g. some hybrid-GPU laptops.
 * Video is encoded by NVENC on the NVIDIA GPU; ddagrab frames stay in video memory all the way to the encoder.
 */
final class ScreenCapture {

    enum Method { DDAGRAB, GDIGRAB }

    // The mouse pointer is left out: it would only disturb slide detection
    // H.264 needs even sizes; a slow-changing screen at 1-2 fps compresses well, so a modest bitrate keeps text sharp
    private static final String EVEN_SIZE = "scale=trunc(iw/2)*2:trunc(ih/2)*2";
    // No B-frames: they shift the first timestamps of a part by seconds at 1 fps. Forced keyframes must be IDR
    // frames, or the segment muxer cannot start a new part there.
    static final List<String> ENCODER = List.of(
            "-c:v", "h264_nvenc", "-bf", "0", "-forced-idr", "1", "-b:v", "800k", "-g", "30");

    private ScreenCapture() {}

    static List<String> args(Method method, int monitor, double fps, Path directory, int segmentSeconds, int maxSeconds) {
        List<String> args = new ArrayList<>(CaptureArgs.common(maxSeconds));
        String rate = String.format(Locale.ROOT, "%.3f", fps);
        if (method == Method.DDAGRAB) {
            args.addAll(List.of("-filter_complex",
                    "ddagrab=output_idx=" + monitor + ":framerate=" + rate + ":draw_mouse=0"));
        } else {
            args.addAll(List.of("-f", "gdigrab", "-framerate", rate, "-draw_mouse", "0"));
            Rectangle bounds = monitorBounds(monitor);
            if (bounds != null) {
                args.addAll(List.of("-offset_x", String.valueOf(bounds.x), "-offset_y", String.valueOf(bounds.y),
                        "-video_size", bounds.width + "x" + bounds.height));
            }
            args.addAll(List.of("-i", "desktop", "-vf", EVEN_SIZE));
        }
        args.addAll(ENCODER);
        args.addAll(CaptureArgs.keyframeAtEverySegment(segmentSeconds));
        args.addAll(CaptureArgs.segmentedOutput(directory, "screen", segmentSeconds));
        return args;
    }

    /** Monitor position on the virtual desktop for gdigrab; null (whole desktop) when AWT cannot tell. */
    private static Rectangle monitorBounds(int monitor) {
        if (GraphicsEnvironment.isHeadless()) return null;
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        return monitor < screens.length ? screens[monitor].getDefaultConfiguration().getBounds() : null;
    }
}
