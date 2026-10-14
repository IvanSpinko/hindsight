package org.example.visiontrack.analysis;

import java.util.List;

public record AnalysisResult(
        VideoInfo video,
        ProcessingInfo processing,
        int totalObjects,
        List<LabelCount> counts,
        List<DetectedObject> objects
) {

    public record VideoInfo(int width, int height, double frameRate,
                            double durationSeconds, String duration) {}

    public record ProcessingInfo(String model, double sampleFps, int framesAnalyzed,
                                 double processingSeconds) {}

    public record LabelCount(String label, int count, double firstSeenSeconds, String firstSeen) {}

    public record DetectedObject(
            int id,
            String label,
            double firstSeenSeconds,
            String firstSeen,
            double lastSeenSeconds,
            String lastSeen,
            double visibleSeconds,
            float confidence,
            Box box,
            List<Position> track
    ) {}

    public record Box(int x, int y, int width, int height) {}

    public record Position(double t, int x, int y, int w, int h) {}
}
