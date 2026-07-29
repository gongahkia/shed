package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RecoveryWorkspaceDialogTest {
    @TempDir
    Path tempDir;

    @Test
    void presentsCurrentDiskContentBesideRecoveryContent() throws Exception {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "current disk text");
        RecoveryJournal.Entry entry = new RecoveryJournal.Entry("file-1", "notes.txt", file.toString(), "recovery text");

        RecoveryWorkspaceDialog.EntryView view = RecoveryWorkspaceDialog.viewsFor(List.of(entry)).get(0);

        assertEquals("Current disk content", view.originalState());
        assertEquals("current disk text", view.originalContent());
        assertEquals("recovery text", view.entry().content());
    }

    @Test
    void labelsMissingAndScratchOriginalContentSafely() {
        RecoveryJournal.Entry missing = new RecoveryJournal.Entry("file-1", "missing.txt", tempDir.resolve("missing.txt").toString(), "recovery");
        RecoveryJournal.Entry scratch = new RecoveryJournal.Entry("scratch-1", "scratch", null, "recovery");
        RecoveryJournal.Entry invalid = new RecoveryJournal.Entry("file-2", "invalid.txt", "\u0000", "recovery");

        List<RecoveryWorkspaceDialog.EntryView> views = RecoveryWorkspaceDialog.viewsFor(List.of(missing, scratch, invalid));

        assertEquals("Original file missing", views.get(0).originalState());
        assertTrue(views.get(0).originalContent().startsWith("Original file is unavailable:"));
        assertEquals("Scratch document", views.get(1).originalState());
        assertEquals("No original file exists for this scratch document.", views.get(1).originalContent());
        assertEquals("Original file unavailable", views.get(2).originalState());
    }
}
