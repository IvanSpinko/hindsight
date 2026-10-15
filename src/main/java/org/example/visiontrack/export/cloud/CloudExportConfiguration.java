package org.example.visiontrack.export.cloud;

import org.example.visiontrack.config.VisionTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Only active with {@code visiontrack.cloud.export.enabled=true}; otherwise the AWS SDK is never touched. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "visiontrack.cloud.export", name = "enabled", havingValue = "true")
public class CloudExportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudExportConfiguration.class);

    @Bean(destroyMethod = "close")
    S3Client cloudExportS3Client(CloudExportProperties cloud) {
        if (!StringUtils.hasText(cloud.bucket())) {
            throw new IllegalStateException("Cloud export is enabled but no bucket is set: "
                    + "set visiontrack.cloud.export.bucket (or VISIONTRACK_CLOUD_BUCKET with the aws profile)");
        }
        return S3Client.builder()
                .region(Region.of(cloud.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }

    @Bean
    OutboxUploader outboxUploader(S3Client s3, CloudExportProperties cloud, VisionTrackProperties properties) {
        log.info("Cloud export on: outbox uploads to s3://{}/{}", cloud.bucket(), cloud.rawPrefix());
        return new OutboxUploader(properties.export().directory(), new S3RunUploader(s3, cloud.bucket()),
                cloud.rawPrefix());
    }

    @Bean
    OutboxDrain outboxDrain(OutboxUploader uploader) {
        return new OutboxDrain(uploader);
    }

    static final class OutboxDrain {
        private final OutboxUploader uploader;

        OutboxDrain(OutboxUploader uploader) {
            this.uploader = uploader;
        }

        @Scheduled(initialDelay = 5_000, fixedDelayString = "${visiontrack.cloud.export.scan-interval-ms:15000}")
        void drain() {
            uploader.uploadPending();
        }
    }
}
