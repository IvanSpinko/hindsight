package org.example.visiontrack.export.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** S3 upload of the export outbox. Credentials come from the AWS default chain (optionally a named profile). */
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
