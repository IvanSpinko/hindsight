package org.example.visiontrack.meeting;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Screen capture with {@code ddagrab} (or {@code gdigrab} where it fails), encoded by NVENC. */
final class ScreenCapture {

    enum Method { DDAGRAB, GDIGRAB }

    // The recording is for people to rewatch, so the mouse pointer is kept: it shows what was pointed at
    // H.264 needs even sizes
    private static final String EVEN_SIZE = "scale=trunc(iw/2)*2:trunc(ih/2)*2";
    // Constant quality keeps text sharp at a low bitrate. No B-frames and IDR keyframes,
    // so each part starts cleanly; a keyframe every 2 s keeps seeking quick.
    static final List<String> ENCODER = List.of(
            "-c:v", "h264_nvenc", "-bf", "0", "-forced-idr", "1",
            "-rc", "vbr", "-cq", "28", "-b:v", "0", "-maxrate", "8M", "-bufsize", "16M", "-g", "60");

    private ScreenCapture() {}

    static List<String> args(Method method, int monitor, double fps, Path directory, int segmentSeconds, int maxSeconds) {
        List<String> args = new ArrayList<>(CaptureArgs.common(maxSeconds));
        String rate = String.format(Locale.ROOT, "%.3f", fps);
        if (method == Method.DDAGRAB) {
            args.addAll(List.of("-filter_complex",
                    "ddagrab=output_idx=" + monitor + ":framerate=" + rate + ":draw_mouse=1"));
        } else {
            args.addAll(List.of("-f", "gdigrab", "-framerate", rate, "-draw_mouse", "1"));
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
