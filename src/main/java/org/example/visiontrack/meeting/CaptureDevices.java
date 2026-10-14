package org.example.visiontrack.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Monitors and microphones available for recording. */
public record CaptureDevices(List<Monitor> monitors, List<String> microphones) {

    private static final Logger log = LoggerFactory.getLogger(CaptureDevices.class);
    private static final int MAX_MONITORS = 8;

    public record Monitor(int index, String name, int width, int height) {}

    /** Monitors are found by trying DXGI outputs 0, 1, 2... the same indices ddagrab records from. */
    static CaptureDevices detect(Ffmpeg ffmpeg) {
        List<Monitor> monitors = new ArrayList<>();
        try {
            for (int i = 0; i < MAX_MONITORS; i++) {
                int[] size = ffmpeg.probeDdagrabOutput(i);
                if (size == null) break;
                monitors.add(new Monitor(i, "Monitor " + (i + 1), size[0], size[1]));
            }
        } catch (IOException e) {
            log.warn("Cannot list monitors: {}", e.getMessage());
        }
        if (monitors.isEmpty()) {
            // ddagrab unavailable: recording falls back to gdigrab, which can always grab the whole desktop
            monitors.add(new Monitor(0, "Whole desktop", 0, 0));
        }

        List<String> microphones = List.of();
        try {
            microphones = ffmpeg.listAudioDevices();
        } catch (IOException e) {
            log.warn("Cannot list microphones: {}", e.getMessage());
        }
        return new CaptureDevices(List.copyOf(monitors), microphones);
    }
}
