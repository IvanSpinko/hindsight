package org.example.visiontrack.web;

import jakarta.servlet.http.HttpServletRequest;
import org.example.visiontrack.meeting.CaptureDevices;
import org.example.visiontrack.meeting.Meeting;
import org.example.visiontrack.meeting.MeetingJobService;
import org.example.visiontrack.meeting.MeetingRecorder;
import org.example.visiontrack.meeting.MeetingStore;
import org.example.visiontrack.meeting.SlideExtractor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    public record MeetingResponse(Meeting meeting, String folder) {}

    private final MeetingRecorder recorder;
    private final MeetingStore store;
    private final MeetingJobService jobs;

    public MeetingController(MeetingRecorder recorder, MeetingStore store, MeetingJobService jobs) {
        this.recorder = recorder;
        this.store = store;
        this.jobs = jobs;
    }

    /** Screen and microphone recording is only ever controlled from this computer. */
    @ModelAttribute
    void requireLocalRequest(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Meetings can only be controlled from this computer");
            }
        } catch (UnknownHostException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
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

    /** Runs the processing (slides, ...) again, e.g. after it failed or with new settings. */
    @PostMapping("/{id}/process")
    public ResponseEntity<MeetingResponse> process(@PathVariable("id") String id) {
        Meeting meeting = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found"));
        if (meeting.status() == Meeting.Status.RECORDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The meeting is still being recorded");
        }
        jobs.submit(id);
        return ResponseEntity.accepted().body(response(store.find(id).orElseThrow()));
    }

    @GetMapping("/{id}/slides")
    public List<SlideExtractor.Slide> slides(@PathVariable("id") String id) throws IOException {
        if (store.folder(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found");
        return jobs.slides(id);
    }

    @GetMapping("/{id}/slides/{file:slide-\\d+\\.png}")
    public ResponseEntity<Resource> slideImage(@PathVariable("id") String id, @PathVariable("file") String file) {
        Path image = store.folder(id).map(f -> f.resolve("slides").resolve(file)).filter(Files::isRegularFile)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slide not found"));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(new FileSystemResource(image));
    }

    /** The readable transcript (Markdown, shown as plain text in the browser). */
    @GetMapping("/{id}/transcript")
    public ResponseEntity<Resource> transcript(@PathVariable("id") String id) {
        Path file = store.folder(id).map(f -> f.resolve("transcript.md")).filter(Files::isRegularFile)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transcript not found"));
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

    private MeetingResponse response(Meeting meeting) {
        return new MeetingResponse(meeting, store.folder(meeting.id()).map(Path::toString).orElse(null));
    }
}
