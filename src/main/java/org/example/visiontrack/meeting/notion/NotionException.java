package org.example.visiontrack.meeting.notion;

import java.io.IOException;

/** A request Notion answered with an error: the HTTP status, Notion's error code (e.g. {@code validation_error}) and message. */
public class NotionException extends IOException {

    private final int status;
    private final String code;

    public NotionException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    /** Notion's error code, or null when the answer had none. */
    public String code() {
        return code;
    }
}
