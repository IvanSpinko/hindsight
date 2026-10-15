package org.example.visiontrack.analysis;

import org.example.visiontrack.detection.Detection;

import java.util.List;

/** Sees the raw detections of every analysed frame, before tracking (used for the detection export). */
public interface FrameObserver {

    FrameObserver NONE = new FrameObserver() {};

    /** Size of the analysed frames (after downscaling), source frame rate and duration (0 if unknown). */
    default void onStart(int width, int height, double frameRate, long durationMicros) {}

    /** {@code frameIndex} counts every decoded frame, so skipped (not sampled) frames leave gaps. */
    default void onFrame(long frameIndex, long timestampMicros, List<Detection> detections) {}

    default void onEnd(long decodedFrames) {}
}
