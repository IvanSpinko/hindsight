package org.example.visiontrack.analysis;

import org.example.visiontrack.analysis.AnalysisResult.Box;
import org.example.visiontrack.analysis.AnalysisResult.DetectedObject;
import org.example.visiontrack.analysis.AnalysisResult.LabelCount;
import org.example.visiontrack.analysis.AnalysisResult.Position;
import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.detection.Detection;
import org.example.visiontrack.detection.Detection.BoundingBox;
import org.example.visiontrack.detection.ObjectDetectorProvider;
import org.example.visiontrack.detection.OnnxYoloDetector;
import org.example.visiontrack.tracking.ObjectTracker;
import org.example.visiontrack.tracking.ObjectTracker.TrackPoint;
import org.example.visiontrack.tracking.ObjectTracker.TrackedObject;
import org.example.visiontrack.video.VideoReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

@Service
public class VideoAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalyzer.class);

    private final ObjectDetectorProvider detectors;
    private final VisionTrackProperties.Analysis settings;

    public VideoAnalyzer(ObjectDetectorProvider detectors, VisionTrackProperties properties) {
        this.detectors = detectors;
        this.settings = properties.analysis();
    }

    /** Fails fast with the reason when object detection is unavailable (e.g. no NVIDIA GPU). */
    public void checkAvailable() {
        detectors.check();
    }

    public AnalysisResult analyze(Path video, ProgressListener progress) {
        return analyze(video, progress, FrameObserver.NONE);
    }

    public AnalysisResult analyze(Path video, ProgressListener progress, FrameObserver observer) {
        return detectors.use(detector -> analyze(detector, video, progress, observer));
    }

    private AnalysisResult analyze(OnnxYoloDetector detector, Path video, ProgressListener progress,
                                   FrameObserver observer) {
        long started = System.nanoTime();
        long sampleIntervalMicros = settings.sampleFps() > 0 ? Math.round(1_000_000 / settings.sampleFps()) : 0;
        ObjectTracker tracker = new ObjectTracker(settings.trackIouThreshold(),
                Math.round(settings.trackMaxGapSeconds() * 1_000_000), settings.trackMinHits());

        VideoReader.Metadata metadata;
        int framesAnalyzed = 0;
        long lastTimestamp = 0;
        long frameIndex = -1;
        try (VideoReader reader = VideoReader.open(video, settings.maxFrameDimension())) {
            metadata = reader.metadata();
            observer.onStart(metadata.width(), metadata.height(), metadata.frameRate(), metadata.durationMicros());
            long nextSample = 0;
            VideoReader.Frame frame;
            while ((frame = reader.nextFrame()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Analysis interrupted");
                }
                frameIndex++;
                long timestamp = frame.timestampMicros();
                lastTimestamp = Math.max(lastTimestamp, timestamp);
                if (timestamp < nextSample) continue;
                nextSample = timestamp + sampleIntervalMicros;

                List<Detection> detections = detector.detect(frame.image());
                observer.onFrame(frameIndex, timestamp, detections);
                tracker.update(detections, timestamp);
                framesAnalyzed++;

                if (metadata.durationMicros() > 0) {
                    progress.onProgress(Math.min(1.0, (double) timestamp / metadata.durationMicros()));
                }
            }
        }
        observer.onEnd(frameIndex + 1);
        progress.onProgress(1.0);

        List<TrackedObject> tracked = tracker.finish();
        double processingSeconds = (System.nanoTime() - started) / 1e9;
        log.info("Analysed {}: {} frames, {} objects in {} s", video.getFileName(), framesAnalyzed,
                tracked.size(), "%.1f".formatted(processingSeconds));

        long durationMicros = metadata.durationMicros() > 0 ? metadata.durationMicros() : lastTimestamp;
        return new AnalysisResult(
                new AnalysisResult.VideoInfo(metadata.width(), metadata.height(),
                        Math.round(metadata.frameRate() * 100) / 100.0,
                        seconds(durationMicros), timecode(durationMicros)),
                new AnalysisResult.ProcessingInfo(detector.description(), settings.sampleFps(), framesAnalyzed,
                        Math.round(processingSeconds * 10) / 10.0),
                tracked.size(),
                countByLabel(tracked),
                tracked.stream().map(VideoAnalyzer::toDetectedObject).toList());
    }

    static String timecode(long micros) {
        long totalMillis = Math.max(0, micros) / 1_000;
        long hours = totalMillis / 3_600_000;
        long minutes = (totalMillis / 60_000) % 60;
        long seconds = (totalMillis / 1_000) % 60;
        long millis = totalMillis % 1_000;
        return "%02d:%02d:%02d.%03d".formatted(hours, minutes, seconds, millis);
    }

    static double seconds(long micros) {
        return Math.round(micros / 1_000.0) / 1_000.0;
    }

    private static List<LabelCount> countByLabel(List<TrackedObject> objects) {
        Map<String, TrackedObject> first = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TrackedObject object : objects) {
            first.putIfAbsent(object.label(), object);
            counts.merge(object.label(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(e -> {
                    long firstSeen = first.get(e.getKey()).firstSeenMicros();
                    return new LabelCount(e.getKey(), e.getValue(), seconds(firstSeen), timecode(firstSeen));
                })
                .sorted(Comparator.comparingInt(LabelCount::count).reversed()
                        .thenComparingDouble(LabelCount::firstSeenSeconds))
                .toList();
    }

    private static DetectedObject toDetectedObject(TrackedObject object) {
        BoundingBox b = object.bestBox();
        return new DetectedObject(
                object.id(),
                object.label(),
                seconds(object.firstSeenMicros()),
                timecode(object.firstSeenMicros()),
                seconds(object.lastSeenMicros()),
                timecode(object.lastSeenMicros()),
                seconds(object.lastSeenMicros() - object.firstSeenMicros()),
                Math.round(object.bestConfidence() * 1000) / 1000f,
                new Box(Math.round(b.x1()), Math.round(b.y1()), Math.round(b.width()), Math.round(b.height())),
                object.path().stream().map(VideoAnalyzer::toPosition).toList());
    }

    private static Position toPosition(TrackPoint point) {
        BoundingBox b = point.box();
        return new Position(seconds(point.timestampMicros()),
                Math.round(b.x1()), Math.round(b.y1()), Math.round(b.width()), Math.round(b.height()));
    }
}
