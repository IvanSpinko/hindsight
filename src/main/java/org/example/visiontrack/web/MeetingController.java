package org.example.visiontrack.web;

import jakarta.servlet.http.HttpServletRequest;
import org.example.visiontrack.meeting.CaptureDevices;
import org.example.visiontrack.meeting.Meeting;
import org.example.visiontrack.meeting.MeetingRecorder;
import org.example.visiontrack.meeting.MeetingStore;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    public record MeetingResponse(Meeting meeting, String folder) {}

    private final MeetingRecorder recorder;
    private final MeetingStore store;

    public MeetingController(MeetingRecorder recorder, MeetingStore store) {
        this.recorder = recorder;
        this.store = store;
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
