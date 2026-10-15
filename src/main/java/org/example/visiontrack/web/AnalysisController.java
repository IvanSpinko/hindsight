package org.example.visiontrack.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.visiontrack.analysis.AnalysisResult;
import org.example.visiontrack.job.AnalysisJob;
import org.example.visiontrack.job.AnalysisJob.JobStatus;
import org.example.visiontrack.job.AnalysisJobService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalysisJobResponse(
            String id,
            String fileName,
            JobStatus status,
            int progressPercent,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            String error,
            String annotatedVideoUrl,
            Summary summary,
            AnalysisResult result
    ) {

        /** What the list of past analyses shows without loading every object track. */
        public record Summary(int totalObjects, double durationSeconds, List<AnalysisResult.LabelCount> counts) {

            static Summary of(AnalysisResult result) {
                return result == null ? null
                        : new Summary(result.totalObjects(), result.video().durationSeconds(), result.counts());
            }
        }

        static AnalysisJobResponse from(AnalysisJob job, boolean includeResult) {
            return new AnalysisJobResponse(job.id(), job.fileName(), job.status(), job.progressPercent(),
                    job.createdAt(), job.startedAt(), job.finishedAt(), job.error(),
                    job.annotatedVideoKey() == null ? null : "/api/analyses/" + job.id() + "/video",
                    includeResult ? null : Summary.of(job.result()),
                    includeResult ? job.result() : null);
        }
    }

    private final AnalysisJobService service;

    public AnalysisController(AnalysisJobService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalysisJobResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file is empty");
        }
        AnalysisJob job;
        try (InputStream content = file.getInputStream()) {
            job = service.submit(file.getOriginalFilename(), content);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(job.id()).toUri();
        return ResponseEntity.accepted().location(location).body(AnalysisJobResponse.from(job, false));
    }

    @GetMapping
    public List<AnalysisJobResponse> list() {
        return service.findAll().stream().map(job -> AnalysisJobResponse.from(job, false)).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AnalysisJobResponse> get(@PathVariable("id") String id) {
        return service.find(id)
                .map(job -> ResponseEntity.ok(AnalysisJobResponse.from(job, true)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/video")
    public ResponseEntity<Resource> annotatedVideo(@PathVariable("id") String id,
                                                   @RequestParam(name = "download", defaultValue = "false") boolean download) {
        Optional<Path> video = service.annotatedVideo(id);
        if (video.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String fileName = service.find(id)
                .map(job -> job.fileName().replaceFirst("\\.[^.]+$", ""))
                .orElse(id) + "-objects.mp4";
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(video.get()));
    }
}
