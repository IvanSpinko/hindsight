package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Meetings live in folders under {@code Documents\VisionTrack\Meetings}, one per meeting
 * ({@code YYYY-MM-DD_HH-MM_<title>}); its {@code meeting.json} is the only record, so the list
 * survives restarts and the user can move or delete folders freely.
 */
@Component
public class MeetingStore {

    static final String MANIFEST = "meeting.json";
    static final String DEFAULT_TITLE = "Meeting";

    private static final Logger log = LoggerFactory.getLogger(MeetingStore.class);
    private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    private static final int MAX_TITLE_LENGTH = 60;

    private final Path root;
    private final ObjectMapper json;
    private final Map<String, Path> folders = new ConcurrentHashMap<>();

    @Autowired
    public MeetingStore(VisionTrackProperties properties, ObjectMapper objectMapper) {
        this(properties.meetings().directory(), objectMapper);
    }

    MeetingStore(Path root, ObjectMapper objectMapper) {
        this.root = root.toAbsolutePath().normalize();
        this.json = objectMapper.copy()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path root() {
        return root;
    }

    /** Creates an empty folder for a new meeting; a number is appended if the name is taken. */
    Path createFolder(String id, String title, LocalDateTime startedAt) throws IOException {
        Files.createDirectories(root);
        String base = startedAt.format(FOLDER_TIME) + "_" + folderTitle(title);
        Path folder = root.resolve(base);
        for (int n = 2; Files.exists(folder); n++) {
            folder = root.resolve(base + "-" + n);
        }
        Files.createDirectories(folder);
        folders.put(id, folder);
        return folder;
    }

    /** A title made safe for a Windows folder name: no reserved characters, no trailing dots, bounded length. */
    static String folderTitle(String title) {
        String clean = title == null ? "" : title
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (clean.length() > MAX_TITLE_LENGTH) clean = clean.substring(0, MAX_TITLE_LENGTH).strip();
        clean = clean.replaceAll("[. ]+$", "");
        return clean.isEmpty() ? DEFAULT_TITLE : clean;
    }

    void save(Meeting meeting) {
        Path folder = folder(meeting.id()).orElseThrow(() -> new IllegalStateException("Unknown meeting " + meeting.id()));
        try {
            Path temp = folder.resolve(MANIFEST + ".tmp");
            json.writeValue(temp.toFile(), meeting);
            Files.move(temp, folder.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save " + folder.resolve(MANIFEST), e);
        }
    }

    public Optional<Path> folder(String id) {
        Path known = folders.get(id);
        if (known != null && Files.isDirectory(known)) return Optional.of(known);
        findAll();
        return Optional.ofNullable(folders.get(id));
    }

    public Optional<Meeting> find(String id) {
        return folder(id).flatMap(this::read);
    }

    /** All meetings, newest first. Re-scans the folder, so moved or deleted meetings disappear. */
    public List<Meeting> findAll() {
        if (!Files.isDirectory(root)) return List.of();
        List<Meeting> meetings = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> read(dir).ifPresent(meeting -> {
                folders.put(meeting.id(), dir);
                meetings.add(meeting);
            }));
        } catch (IOException e) {
            log.warn("Cannot list meetings in {}: {}", root, e.getMessage());
        }
        meetings.sort(Comparator.comparing(Meeting::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return meetings;
    }

    private Optional<Meeting> read(Path folder) {
        Path manifest = folder.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) return Optional.empty();
        try {
            return Optional.of(json.readValue(manifest.toFile(), Meeting.class));
        } catch (IOException e) {
            log.warn("Skipping {}: {}", manifest, e.getMessage());
            return Optional.empty();
        }
    }
}
