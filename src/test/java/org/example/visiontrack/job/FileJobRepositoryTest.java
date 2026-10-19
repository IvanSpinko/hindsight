package org.example.visiontrack.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.visiontrack.analysis.AnalysisResult;
import org.example.visiontrack.export.RunInfo;
import org.example.visiontrack.export.VideoRunSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileJobRepositoryTest {

    @TempDir
    Path dir;

    private FileJobRepository repository() {
        return new FileJobRepository(dir, new ObjectMapper().findAndRegisterModules());
    }

    private static AnalysisResult result() {
        return new AnalysisResult(
                new AnalysisResult.VideoInfo(1280, 720, 30, 12.5, "00:12"),
                new AnalysisResult.ProcessingInfo("yolo", 5, 62, 3.2),
                1,
                List.of(new AnalysisResult.LabelCount("person", 1, 0.4, "00:00")),
                List.of(new AnalysisResult.DetectedObject(1, "person", 0.4, "00:00", 10, "00:10", 9.6, 0.91f,
                        new AnalysisResult.Box(10, 20, 30, 40),
                        List.of(new AnalysisResult.Position(0.4, 10, 20, 30, 40)))));
    }

    @Test
    void finishedAnalysesAreLoadedAfterARestart() {
        String id = UUID.randomUUID().toString();
        FileJobRepository repository = repository();
        repository.save(AnalysisJob.queued(id, "street.mp4", id + ".mp4"));
        repository.update(id, j -> j.started().withExportRun(RunInfo.start(id, VideoRunSummary.Source.UPLOAD)));
        repository.update(id, j -> j.completed(result(), id + "-annotated.mp4"));
        AnalysisJob saved = repository.findById(id).orElseThrow();

        assertThat(repository().findAll()).containsExactly(saved);
    }

    @Test
    void anAnalysisRunningAtShutdownIsLoadedAsFailed() {
        String id = UUID.randomUUID().toString();
        FileJobRepository repository = repository();
        repository.save(AnalysisJob.queued(id, "street.mp4", id + ".mp4"));
        repository.update(id, j -> j.started().withProgress(40));

        AnalysisJob loaded = repository().findById(id).orElseThrow();

        assertThat(loaded.status()).isEqualTo(AnalysisJob.JobStatus.FAILED);
        assertThat(loaded.error()).isEqualTo(FileJobRepository.INTERRUPTED);
        assertThat(repository().findById(id).orElseThrow().status()).isEqualTo(AnalysisJob.JobStatus.FAILED);
    }

    @Test
    void deletedAnalysesAreGoneFromDisk() throws Exception {
        String id = UUID.randomUUID().toString();
        FileJobRepository repository = repository();
        repository.save(AnalysisJob.queued(id, "street.mp4", id + ".mp4"));
        repository.delete(id);
        repository.update(id, j -> j.withProgress(50));

        assertThat(repository().findAll()).isEmpty();
        try (var files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void brokenFilesAreSkipped() throws Exception {
        Files.writeString(dir.resolve(UUID.randomUUID() + ".json"), "{not json");

        assertThat(repository().findAll()).isEmpty();
    }
}
