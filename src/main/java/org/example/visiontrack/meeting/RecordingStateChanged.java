package org.example.visiontrack.meeting;

import java.time.Instant;

/** Published when a recording starts or stops, e.g. for the REC indicator in the tray. */
public record RecordingStateChanged(boolean recording, Instant startedAt) {}
