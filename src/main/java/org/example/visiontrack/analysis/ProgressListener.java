package org.example.visiontrack.analysis;

@FunctionalInterface
public interface ProgressListener {

    void onProgress(double fraction);

    default ProgressListener range(double from, double to) {
        return fraction -> onProgress(from + (to - from) * fraction);
    }
}
