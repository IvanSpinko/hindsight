package org.example.visiontrack.export.cloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxUploaderTest {

    static final String PART = "detections/dt=2026-10-15/run_id=0f8fad5b-d9cb-469f-a165-70867728950e/part-0000.jsonl.gz";
    static final String RUN = "runs/dt=2026-10-15/run_id=0f8fad5b-d9cb-469f-a165-70867728950e/run.json";

    @TempDir
    Path dir;

    final S3Client s3 = mock(S3Client.class);
    final MutableClock clock = new MutableClock();

    OutboxUploader uploader() {
        return new OutboxUploader(dir, new S3RunUploader(s3, "lake"), "visiontrack/raw/", clock, () -> 1.0);
    }

    @Test
    void uploadsWithConditionalPutUnderRawAndMovesFilesToUploaded() throws Exception {
        outboxFile(RUN);
        outboxFile(PART);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        assertThat(uploader().uploadPending()).isEqualTo(2);

        var requests = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        var order = inOrder(s3);
        order.verify(s3, times(2)).putObject(requests.capture(), any(RequestBody.class));
        List<PutObjectRequest> sent = requests.getAllValues();
        assertThat(sent).extracting(PutObjectRequest::key)
                .containsExactly("visiontrack/raw/" + PART, "visiontrack/raw/" + RUN); // parts before summaries
        assertThat(sent).allSatisfy(r -> {
            assertThat(r.bucket()).isEqualTo("lake");
            assertThat(r.ifNoneMatch()).isEqualTo("*");
        });
        assertThat(dir.resolve("uploaded").resolve(PART)).isRegularFile();
        assertThat(dir.resolve("uploaded").resolve(RUN)).isRegularFile();
        assertThat(dir.resolve("outbox")).isEmptyDirectory();
    }

    @Test
    void existingObjectCountsAsUploaded() throws Exception {
        outboxFile(PART);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).message("PreconditionFailed").build());

        assertThat(uploader().uploadPending()).isEqualTo(1);
        assertThat(dir.resolve("uploaded").resolve(PART)).isRegularFile();
    }

    @Test
    void failuresBackOffExponentiallyAndRecover() throws Exception {
        Path file = outboxFile(PART);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"))
                .thenReturn(PutObjectResponse.builder().build());
        OutboxUploader uploader = uploader();

        assertThat(uploader.uploadPending()).isZero();
        assertThat(uploader.backoffOf(file).attempts()).isEqualTo(1);
        assertThat(uploader.backoffOf(file).nextAttempt()).isEqualTo(clock.instant().plusSeconds(5));

        clock.advance(Duration.ofSeconds(4));
        assertThat(uploader.uploadPending()).isZero(); // still waiting, no request
        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        clock.advance(Duration.ofSeconds(1));
        assertThat(uploader.uploadPending()).isZero();
        assertThat(uploader.backoffOf(file).attempts()).isEqualTo(2);
        assertThat(uploader.backoffOf(file).nextAttempt()).isEqualTo(clock.instant().plusSeconds(10));

        clock.advance(Duration.ofSeconds(10));
        assertThat(uploader.uploadPending()).isEqualTo(1);
        assertThat(uploader.backoffOf(file)).isNull();
        assertThat(file).doesNotExist();
    }

    @Test
    void backoffIsCappedAtFifteenMinutes() throws Exception {
        Path file = outboxFile(PART);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("AccessDenied").build());
        OutboxUploader uploader = uploader();

        for (int i = 0; i < 12; i++) {
            uploader.uploadPending();
            clock.advance(Duration.ofHours(1));
        }
        OutboxUploader.Backoff backoff = uploader.backoffOf(file);
        assertThat(backoff.attempts()).isEqualTo(12);
        assertThat(Duration.between(clock.instant().minus(Duration.ofHours(1)), backoff.nextAttempt()))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(file).isRegularFile();
    }

    @Test
    void rawPrefixIsNormalised() {
        assertThat(new CloudExportProperties(true, "b", "", "eu-west-1", 1).rawPrefix()).isEqualTo("raw/");
        assertThat(new CloudExportProperties(true, "b", null, "eu-west-1", 1).rawPrefix()).isEqualTo("raw/");
        assertThat(new CloudExportProperties(true, "b", "/team-a", "eu-west-1", 1).rawPrefix()).isEqualTo("team-a/raw/");
        assertThat(new CloudExportProperties(true, "b", "team-a/", "eu-west-1", 1).rawPrefix()).isEqualTo("team-a/raw/");
    }

    private Path outboxFile(String relative) throws Exception {
        Path file = dir.resolve("outbox").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "data");
        return file;
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-15T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
