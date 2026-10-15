package org.example.visiontrack.export;

import java.util.List;

/** What produced the detections: written into every event and run summary. */
public record ModelInfo(String name, String version, List<String> classes, String appVersion) {

    public ModelInfo {
        classes = List.copyOf(classes);
    }
}
