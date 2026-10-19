package org.example.visiontrack.web;

import org.example.visiontrack.config.GpuStatus;
import org.example.visiontrack.config.SettingsService;
import org.example.visiontrack.config.SettingsService.MeetingPreferences;
import org.example.visiontrack.config.SettingsService.NotionSettings;
import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.meeting.Meeting;
import org.example.visiontrack.meeting.MeetingRecorder;
import org.example.visiontrack.meeting.MeetingStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The Settings tab. Changes apply from the next recording or processing; the Notion token is never sent back.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    /** Values in use: the saved choice, else application.yml. */
    public record MeetingSettings(String directory, double screenFps, SettingsService.ScreenQuality screenQuality,
                                  VisionTrackProperties.SpeechEngine speechEngine, String notesLanguage,
                                  boolean deleteSpeechAudio, String microphone, Integer monitor) {}

    public record NotionView(boolean tokenSet, String parentPageId, boolean databaseSet, boolean autoExport) {}

    public record SettingsView(String file, MeetingSettings meetings, MeetingSettings defaults, List<String> languages,
                               NotionView notion) {}

    /** {@code token}: null keeps the saved one, empty removes it. */
    public record NotionUpdate(String token, String parentPageId, boolean autoExport) {}

    /** Languages the notes can be set to. */
    static final List<String> NOTES_LANGUAGES = List.of("en");

    private static final double MIN_FPS = 1;
    private static final double MAX_FPS = 60;

    private final SettingsService settings;
    private final VisionTrackProperties.Meetings configured;
    private final MeetingRecorder recorder;
    private final MeetingStore store;

    public SettingsController(SettingsService settings, VisionTrackProperties properties, MeetingRecorder recorder,
                              MeetingStore store) {
        this.settings = settings;
        this.configured = properties.meetings();
        this.recorder = recorder;
        this.store = store;
    }

    @GetMapping
    public SettingsView get() {
        MeetingPreferences chosen = settings.meetings();
        NotionSettings notion = settings.notion();
        MeetingSettings meetings = new MeetingSettings(
                chosen.directory(configured.directory()).toAbsolutePath().normalize().toString(),
                chosen.screenFps(configured.screenFps()), chosen.screenQualityOrDefault(),
                chosen.speechEngine(configured.speechEngine()), chosen.notesLanguage(), chosen.deletesSpeechAudio(),
                chosen.microphone(), chosen.monitor());
        MeetingSettings defaults = new MeetingSettings(configured.directory().toAbsolutePath().normalize().toString(),
                configured.screenFps(), SettingsService.ScreenQuality.STANDARD, configured.speechEngine(), null, false,
                null, null);
        return new SettingsView(settings.file().toString(), meetings, defaults, NOTES_LANGUAGES,
                new NotionView(notion.hasToken(), notion.parentPageId(), notion.hasDatabase(), notion.autoExport()));
    }

    @PutMapping(path = "/meetings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SettingsView saveMeetings(@RequestBody MeetingPreferences request) throws IOException {
        if (request.screenFps() != null && (request.screenFps() < MIN_FPS || request.screenFps() > MAX_FPS)) {
            throw badRequest("Frames per second must be between 1 and 60");
        }
        if (request.monitor() != null && request.monitor() < 0) throw badRequest("Unknown monitor");
        if (request.notesLanguage() != null && !NOTES_LANGUAGES.contains(request.notesLanguage())) {
            throw badRequest("Notes language must be one of " + NOTES_LANGUAGES);
        }
        Path directory = directory(request.directory());
        Path current = settings.meetings().directory(configured.directory()).toAbsolutePath().normalize();
        Path wanted = directory == null ? configured.directory().toAbsolutePath().normalize() : directory;
        if (!Objects.equals(current, wanted)) checkFolderCanChange(wanted);
        settings.saveMeetings(request);
        return get();
    }

    @PutMapping(path = "/notion", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SettingsView saveNotion(@RequestBody NotionUpdate request) throws IOException {
        try {
            settings.saveNotion(request.token(), request.parentPageId(), request.autoExport());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
        return get();
    }

    /** Separate from {@link #get()}: nvidia-smi takes a moment. */
    @GetMapping("/gpu")
    public GpuStatus gpu() {
        return GpuStatus.query();
    }

    /** Not while a meeting is recorded or processed; the folder must be writable. */
    private void checkFolderCanChange(Path wanted) {
        if (recorder.activeId().isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stop the recording before changing the meetings folder");
        }
        if (store.findAll().stream().anyMatch(m -> m.status() == Meeting.Status.PROCESSING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait until the meetings are processed before changing the folder");
        }
        try {
            Files.createDirectories(wanted);
        } catch (IOException e) {
            throw badRequest("Cannot create the folder " + wanted + ": " + e.getMessage());
        }
        if (!Files.isWritable(wanted)) throw badRequest("The folder " + wanted + " is not writable");
    }

    private static Path directory(String value) {
        if (value == null) return null;
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) throw badRequest("Give the full path of the meetings folder, e.g. D:\\Meetings");
            return path.normalize();
        } catch (InvalidPathException e) {
            throw badRequest("Not a valid folder: " + value);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
