package org.example.visiontrack.export.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upload of the export outbox to S3. Credentials are never configured here: the AWS default chain is used,
 * reading the named profile from {@code ~/.aws/config} when {@code profile} is set (for example
 * {@code visiontrack-uploader}), otherwise {@code AWS_PROFILE} or the default profile.
 */
@ConfigurationProperties(prefix = "visiontrack.cloud.export")
public record CloudExportProperties(
        boolean enabled,
        String bucket,
        String prefix,
        String region,
        String profile,
        long scanIntervalMs
) {

    /** {@code <prefix>raw/}: the prefix may be empty or given with or without a trailing slash. */
    public String rawPrefix() {
        String p = prefix == null ? "" : prefix.strip();
        while (p.startsWith("/")) p = p.substring(1);
        if (!p.isEmpty() && !p.endsWith("/")) p += "/";
        return p + "raw/";
    }
}
