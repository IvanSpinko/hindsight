package org.example.visiontrack.export;

import java.io.IOException;
import java.util.List;

/** Where the detections of a run go. Finished files land in an outbox that mirrors the S3 key layout. */
public interface DetectionSink {

    RunRecorder open(RunInfo run) throws IOException;

    interface RunRecorder {

        void append(List<DetectionEvent> events) throws IOException;

        /** Closes the current part (if it has events) and hands it to the outbox; later events start a new part. */
        void rollPart() throws IOException;

        /** Closes the last part and writes the run summary. The recorder cannot be used afterwards. */
        void finish(VideoRunSummary summary) throws IOException;
    }
}
