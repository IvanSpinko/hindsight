package org.example.visiontrack.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingStoreTest {

    @TempDir
    Path root;

    private MeetingStore store() {
        return new MeetingStore(root, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void folderTitleIsSafeForWindows() {
        assertThat(MeetingStore.folderTitle("Release 2.0: plan/budget?")).isEqualTo("Release 2.0 plan budget");
        assertThat(MeetingStore.folderTitle("  wrap-up...  ")).isEqualTo("wrap-up");
        assertThat(MeetingStore.folderTitle("a".repeat(100))).hasSize(60);
        assertThat(MeetingStore.folderTitle(" <>|* ")).isEqualTo(MeetingStore.DEFAULT_TITLE);
        assertThat(MeetingStore.folderTitle(null)).isEqualTo(MeetingStore.DEFAULT_TITLE);
    }

    @Test
    void folderIsNamedByDateTimeAndTitleWithoutClashes() throws Exception {
        MeetingStore store = store();
        LocalDateTime time = LocalDateTime.of(2026, 10, 14, 9, 5);

        Path first = store.createFolder("a", "Standup", time);
        Path second = store.createFolder("b", "Standup", time);

        assertThat(first.getFileName().toString()).isEqualTo("2026-10-14_09-05_Standup");
        assertThat(second.getFileName().toString()).isEqualTo("2026-10-14_09-05_Standup-2");
    }

    @Test
    void meetingsAreReadBackFromTheirFolders() throws Exception {
        MeetingStore store = store();
        store.createFolder("id-1", "Sync", LocalDateTime.now());
        Meeting meeting = Meeting.recording("id-1", "Sync", "ddagrab · Monitor 1", "Mic",
                List.of(new Meeting.Track("mic", "audio/mic.wav", 120, 0)), List.of(4242L));
        store.save(meeting);

        MeetingStore reopened = store();
        assertThat(reopened.find("id-1")).contains(meeting);
        assertThat(reopened.findAll()).containsExactly(meeting);
        assertThat(Files.isRegularFile(reopened.folder("id-1").orElseThrow().resolve("meeting.json"))).isTrue();
    }
}
