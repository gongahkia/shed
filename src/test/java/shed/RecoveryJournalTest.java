package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RecoveryJournalTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyWritesAndReadsVersionedBoundedJournal() throws Exception {
        List<RecoveryJournal.Entry> entries = new ArrayList<>();
        for (int index = 0; index < RecoveryJournal.MAX_ENTRIES + 2; index++) {
            entries.add(new RecoveryJournal.Entry("scratch-" + index, "scratch " + index, null, "content " + index));
        }
        RecoveryJournal.Workspace workspace = new RecoveryJournal.Workspace("/work/project", "/work/project/a.txt", 7);

        RecoveryJournal.write(tempDir, workspace, entries);

        Path journalPath = tempDir.resolve(RecoveryJournal.FILE_NAME);
        assertTrue(Files.isRegularFile(journalPath));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
        }
        assertTrue(Files.readString(journalPath).contains("\"version\":1"));
        RecoveryJournal.Journal journal = RecoveryJournal.read(tempDir);
        assertEquals(workspace, journal.workspace());
        assertEquals(RecoveryJournal.MAX_ENTRIES, journal.entries().size());
        assertEquals(2, journal.retention().droppedEntries());
        assertEquals(journal.entries().size(), journal.retention().retainedEntries());
        assertTrue(journal.retention().retainedContentBytes() > 0);

        RecoveryJournal.write(tempDir, workspace, List.of(new RecoveryJournal.Entry("file-1", "a.txt", "/work/project/a.txt", "updated")));
        assertEquals(List.of("file-1"), RecoveryJournal.read(tempDir).entries().stream().map(RecoveryJournal.Entry::id).toList());
    }

    @Test
    void rejectsTamperedOrMissingJournal() throws Exception {
        assertNull(RecoveryJournal.read(tempDir));
        RecoveryJournal.write(tempDir, new RecoveryJournal.Workspace("/work", null, 0),
            List.of(new RecoveryJournal.Entry("scratch-1", "scratch", null, "draft")));

        Path journalPath = tempDir.resolve(RecoveryJournal.FILE_NAME);
        String original = Files.readString(journalPath);
        Files.writeString(journalPath, original.replace("draft", "tampered"));

        IOException error = assertThrows(IOException.class, () -> RecoveryJournal.read(tempDir));
        assertTrue(error.getMessage().contains("integrity"));
        RecoveryJournal.clear(tempDir);
        assertFalse(Files.exists(journalPath));
    }

    @Test
    void dropsOversizedEntriesWithoutWritingPartialContent() throws Exception {
        String oversized = "x".repeat(RecoveryJournal.MAX_CONTENT_BYTES + 1);
        RecoveryJournal.write(tempDir, new RecoveryJournal.Workspace("/work", null, 0), List.of(
            new RecoveryJournal.Entry("large", "large", null, oversized),
            new RecoveryJournal.Entry("small", "small", null, "kept")
        ));

        RecoveryJournal.Journal journal = RecoveryJournal.read(tempDir);
        assertEquals(List.of("small"), journal.entries().stream().map(RecoveryJournal.Entry::id).toList());
        assertEquals(1, journal.retention().droppedEntries());
        assertEquals(4, journal.retention().retainedContentBytes());
    }
}
