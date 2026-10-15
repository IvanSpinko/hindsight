package org.example.visiontrack.meeting;

import java.io.IOException;
import java.nio.file.Path;

/** Saves a meeting's notes somewhere the user reads them: a Markdown file now, Notion in stage 7. */
public interface NotesExporter {

    /** @param model the local model that wrote the notes, shown with them */
    void export(Path folder, Meeting meeting, MeetingNotes notes, String model) throws IOException;
}
