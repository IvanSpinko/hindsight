package org.example.visiontrack.export.cloud;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Path;

/**
 * Conditional PUT ({@code If-None-Match: *}): S3 refuses to overwrite an existing key with 412, which here
 * means "uploaded before" (the outbox file was sent but not yet moved when the app stopped).
 */
public class S3RunUploader implements RunUploader {

    private final S3Client s3;
    private final String bucket;

    public S3RunUploader(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public Result upload(String key, Path file) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .ifNoneMatch("*")
                .contentType(key.endsWith(".json") ? "application/json" : "application/gzip")
                .build();
        try {
            s3.putObject(request, RequestBody.fromFile(file));
            return Result.UPLOADED;
        } catch (S3Exception e) {
            if (e.statusCode() == 412) return Result.ALREADY_PRESENT;
            throw e;
        }
    }

    @Override
    public String describe(String key) {
        return "s3://" + bucket + "/" + key;
    }
}
