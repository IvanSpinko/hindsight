package org.example.visiontrack.storage;

import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class LocalVideoStorage implements VideoStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalVideoStorage.class);

    private final Path directory;

    public LocalVideoStorage(VisionTrackProperties properties) {
        this.directory = properties.storage().directory().toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory " + directory, e);
        }
    }

    @Override
    public String store(String jobId, String originalFilename, InputStream content) throws IOException {
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String key = extension == null ? jobId : jobId + "." + extension.toLowerCase();
        Files.copy(content, resolve(key), StandardCopyOption.REPLACE_EXISTING);
        return key;
    }

    @Override
    public void storeFile(String key, Path file) throws IOException {
        Files.move(file, resolve(key), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public Path localPath(String key) {
        return resolve(key);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("Could not delete stored video {}: {}", key, e.getMessage());
        }
    }

    private Path resolve(String key) {
        Path path = directory.resolve(key).normalize();
        if (!path.getParent().equals(directory)) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        return path;
    }
}
