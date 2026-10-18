package org.example.visiontrack.meeting;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Saves a meeting's notes somewhere the user reads them: a Markdown file next to the recording. Notion is separate
 * ({@link NotionExportService}): it runs on request, in the background, and keeps its own state in the meeting.
 */
public interface NotesExporter {

    /** @param model the local model that wrote the notes, shown with them */
    void export(Path folder, Meeting meeting, MeetingNotes notes, String model) throws IOException;
}
