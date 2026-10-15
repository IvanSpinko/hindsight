package org.example.visiontrack.export.cloud;

import java.nio.file.Path;

/** Puts one finished export file into cloud storage under a fixed key. */
public interface RunUploader {

    enum Result { UPLOADED, ALREADY_PRESENT }

    /** Must be idempotent: uploading the same key twice never creates a second copy of the data. */
    Result upload(String key, Path file);

    String describe(String key);
}
