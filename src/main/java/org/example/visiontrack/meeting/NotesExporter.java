package org.example.visiontrack.meeting;

import java.io.IOException;
import java.nio.file.Path;

/** Saves notes where the user reads them (Markdown next to the recording). */
public interface NotesExporter {

    /** @param model the local model that wrote the notes, shown with them */
    void export(Path folder, Meeting meeting, MeetingNotes notes, String model) throws IOException;
}
