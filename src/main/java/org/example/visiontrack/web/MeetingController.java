package org.example.visiontrack.web;

import org.example.visiontrack.meeting.CaptureDevices;
import org.example.visiontrack.meeting.Meeting;
import org.example.visiontrack.meeting.MeetingImporter;
import org.example.visiontrack.meeting.MeetingJobService;
import org.example.visiontrack.meeting.MeetingRecorder;
import org.example.visiontrack.meeting.MeetingStore;
import org.example.visiontrack.meeting.NotionExportService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    public record MeetingResponse(Meeting meeting, String folder) {}

    private final MeetingRecorder recorder;
    private final MeetingStore store;
    private final MeetingJobService jobs;
    private final MeetingImporter importer;
    private final NotionExportService notion;

    public MeetingController(MeetingRecorder recorder, MeetingStore store, MeetingJobService jobs, MeetingImporter importer,
                             NotionExportService notion) {
        this.recorder = recorder;
        this.store = store;
        this.jobs = jobs;
        this.importer = importer;
        this.notion = notion;
    }

    /** Whether the Notion export is set up (never the token), for the meeting cards. */
    @GetMapping("/notion")
    public NotionExportService.Status notionStatus() {
        return notion.status();
    }

    @GetMapping("/devices")
    public CaptureDevices devices(@RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        return recorder.devices(refresh);
    }

    @PostMapping(path = "/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MeetingResponse> start(@RequestBody MeetingRecorder.StartRequest request) {
        Meeting meeting = recorder.start(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response(meeting));
    }

    @PostMapping("/{id}/stop")
    public MeetingResponse stop(@PathVariable("id") String id) {
        return response(recorder.stop(id));
    }

    @GetMapping
    public List<MeetingResponse> list() {
        return store.findAll().stream().map(this::response).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<MeetingResponse> get(@PathVariable("id") String id) {
        return store.find(id).map(m -> ResponseEntity.ok(response(m))).orElse(ResponseEntity.notFound().build());
    }

    /** Runs the processing (speech, notes) again, e.g. after it failed or with new settings. */
    @PostMapping("/{id}/process")
    public ResponseEntity<MeetingResponse> process(@PathVariable("id") String id) {
        Meeting meeting = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found"));
        if (meeting.status() == Meeting.Status.RECORDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The meeting is still being recorded");
        }
        refuseWhileSending(meeting);
        jobs.submit(id);
        return ResponseEntity.accepted().body(response(store.find(id).orElseThrow()));
    }

    /** Sends the notes to Notion in the background; a page sent before is replaced. */
    @PostMapping("/{id}/notion")
    public ResponseEntity<MeetingResponse> sendToNotion(@PathVariable("id") String id) {
        try {
            return ResponseEntity.accepted().body(response(notion.submit(id)));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Deletes the meeting folder: recording, audio, transcript and notes. Not while it is recorded or processed. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        Meeting meeting = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found"));
        if (meeting.status() == Meeting.Status.RECORDING || recorder.activeId().filter(id::equals).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stop the recording first");
        }
        if (meeting.status() == Meeting.Status.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The meeting is still being processed");
        }
        refuseWhileSending(meeting);
        try {
            store.delete(id);
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Some files could not be deleted (are they open in another program?): " + e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    /** Adds an existing recording (audio or video file); it is prepared, transcribed and summarised in the background. */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MeetingResponse> importRecording(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file is empty");
        try (InputStream content = file.getInputStream()) {
            Meeting meeting = importer.importRecording(file.getOriginalFilename(), content);
            return ResponseEntity.status(HttpStatus.CREATED).body(response(meeting));
        }
    }

    /**
     * The recording to watch or listen to: the screen with sound, or an uploaded audio file (the browser plays it;
     * seeking uses range requests).
     */
    @GetMapping("/{id}/recording")
    public ResponseEntity<Resource> recording(@PathVariable("id") String id) {
        Meeting meeting = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found"));
        Meeting.Track playback = meeting.playback();
        Path file = playback == null ? null : store.folder(id).map(f -> f.resolve(playback.file())).filter(Files::isRegularFile).orElse(null);
        if (file == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recording not found");
        MediaType type = MediaTypeFactory.getMediaType(file.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
    }

    /** The readable transcript (Markdown, shown as plain text in the browser). */
    @GetMapping("/{id}/transcript")
    public ResponseEntity<Resource> transcript(@PathVariable("id") String id) {
        Path file = store.folder(id).map(f -> f.resolve("transcript.md")).filter(Files::isRegularFile)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transcript not found"));
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(new FileSystemResource(file));
    }

    /** The notes written by the local model (Markdown, shown as plain text in the browser). */
    @GetMapping("/{id}/notes")
    public ResponseEntity<Resource> notes(@PathVariable("id") String id) {
        Path file = store.folder(id).map(f -> f.resolve("notes.md")).filter(Files::isRegularFile)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notes not found"));
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(new FileSystemResource(file));
    }

    /** Opens the meeting folder in Explorer. */
    @PostMapping("/{id}/open-folder")
    public ResponseEntity<Void> openFolder(@PathVariable("id") String id) throws IOException {
        Path folder = store.folder(id).filter(Files::isDirectory)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting folder not found"));
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Opening folders is supported on Windows only");
        }
        new ProcessBuilder("explorer.exe", folder.toString()).start();
        return ResponseEntity.noContent().build();
    }

    private static void refuseWhileSending(Meeting meeting) {
        if (meeting.notion() != null && meeting.notion().state() == Meeting.NotionState.EXPORTING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The notes are being sent to Notion, try again in a moment");
        }
    }

    private MeetingResponse response(Meeting meeting) {
        return new MeetingResponse(meeting, store.folder(meeting.id()).map(Path::toString).orElse(null));
    }
}
