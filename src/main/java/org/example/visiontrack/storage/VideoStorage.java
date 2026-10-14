package org.example.visiontrack.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public interface VideoStorage {

    String store(String jobId, String originalFilename, InputStream content) throws IOException;

    void storeFile(String key, Path file) throws IOException;

    Path localPath(String key) throws IOException;

    void delete(String key);
}
