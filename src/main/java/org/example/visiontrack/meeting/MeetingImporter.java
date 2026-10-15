package org.example.visiontrack.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Adds an existing recording (audio or video file) as a meeting: the file is saved into a new meeting folder and
 * the meeting is queued; {@link MeetingJobService} prepares it ({@link RecordingImport}) and then transcribes it and
 * writes the notes like a recorded meeting.
 */
@Service
public class MeetingImporter {

    private static final Logger log = LoggerFactory.getLogger(MeetingImporter.class);

    private final MeetingStore store;
    private final MeetingJobService jobs;

    public MeetingImporter(MeetingStore store, MeetingJobService jobs) {
        this.store = store;
        this.jobs = jobs;
    }

    public Meeting importRecording(String originalFileName, InputStream content) {
        String fileName = StringUtils.getFilename(StringUtils.cleanPath(originalFileName == null ? "" : originalFileName));
        if (!StringUtils.hasText(fileName) || !RecordingImport.isSupported(fileName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload an audio file (" + String.join(", ",
                    RecordingImport.AUDIO_EXTENSIONS.stream().sorted().toList()) + ") or a video ("
                    + String.join(", ", RecordingImport.VIDEO_EXTENSIONS.stream().sorted().toList()) + ")");
        }
        String id = UUID.randomUUID().toString();
        String title = StringUtils.stripFilenameExtension(fileName).replace('_', ' ').strip();
        Path folder = null;
        try {
            folder = store.createFolder(id, title, LocalDateTime.now());
            // A neutral name inside the folder; the original name stays in the meeting as its source
            Path upload = folder.resolve("upload/source." + RecordingImport.extension(fileName));
            Files.createDirectories(upload.getParent());
            Files.copy(content, upload);
            Meeting meeting = Meeting.uploaded(id, title, fileName,
                    List.of(new Meeting.Track(RecordingImport.UPLOAD, folder.relativize(upload).toString().replace('\\', '/'), 0, 0)));
            store.save(meeting);
            log.info("Meeting {}: imported {} ({} MB)", id, fileName, Files.size(upload) / (1024 * 1024));
            jobs.submit(id);
            return store.find(id).orElse(meeting);
        } catch (IOException e) {
            if (folder != null) deleteQuietly(folder);
            throw new UncheckedIOException("Cannot save the uploaded recording: " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path folder) {
        try (var walk = Files.walk(folder)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
