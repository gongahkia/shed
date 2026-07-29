package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JOptionPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DataLossRegressionTest {
    @TempDir
    Path tempDir;

    @Test
    void cancelledDiscardRetainsDirtyContent() {
        FileBuffer buffer = FileBuffer.createScratch("[draft]", "saved");
        buffer.setContent("unsaved");

        assertFalse(DocumentLifecycle.discardConfirmed(JOptionPane.CLOSED_OPTION));
        assertTrue(DocumentLifecycle.needsDiscardConfirmation(buffer, false));
        assertTrue(buffer.isModified());
        assertEquals("unsaved", buffer.getContent());
    }

    @Test
    void simulatedWriteVerificationFailureRestoresSource() throws Exception {
        Path source = tempDir.resolve("save.txt");
        Files.writeString(source, "saved", StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () -> AtomicFileWriter.write(source,
            "replacement".getBytes(StandardCharsets.UTF_8),
            (target, expected) -> { throw new IOException("simulated verification failure"); }));

        assertTrue(error.getMessage().contains("original source was restored"));
        assertEquals("saved", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    void restartRecoveryRestoresDirtyContentWithoutWritingSourceOrUndoHistory() throws Exception {
        Path source = tempDir.resolve("recovery.txt");
        Path journalDirectory = tempDir.resolve("recovery");
        Files.writeString(source, "saved", StandardCharsets.UTF_8);
        RecoveryJournal.write(journalDirectory, new RecoveryJournal.Workspace(tempDir.toString(), source.toString(), 0),
            List.of(new RecoveryJournal.Entry("file-1", "recovery.txt", source.toString(), "recovered draft")));

        RecoveryJournal.Entry entry = RecoveryJournal.read(journalDirectory).entries().getFirst();
        FileBuffer restarted = new FileBuffer(source.toFile());
        restarted.setContent(entry.content(), true);

        assertEquals("saved", Files.readString(source, StandardCharsets.UTF_8));
        assertEquals("recovered draft", restarted.getContent());
        assertTrue(restarted.isModified());
        assertFalse(restarted.getUndoManager().canUndo());
        assertFalse(restarted.getUndoManager().canRedo());
    }

    @Test
    void externalDeletionRetainsDirtyBuffer() throws Exception {
        Path source = tempDir.resolve("external.txt");
        Files.writeString(source, "saved", StandardCharsets.UTF_8);
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.setContent("unsaved");

        Files.delete(source);

        assertEquals(FileBuffer.ExternalFileState.DELETED, buffer.getExternalFileState());
        assertTrue(buffer.isModified());
        assertEquals("unsaved", buffer.getContent());
    }

    @Test
    void backupFailureRetainsDirtySourceAndBuffer() throws Exception {
        Path source = tempDir.resolve("backup.txt");
        Path blockedParent = tempDir.resolve("blocked");
        Files.writeString(source, "saved", StandardCharsets.UTF_8);
        Files.writeString(blockedParent, "not a directory", StandardCharsets.UTF_8);
        ConfigManager config = new ConfigManager();
        config.set("backup.enabled", "true");
        config.set("backup.directory", blockedParent.resolve("backups").toString());
        FileBuffer buffer = new FileBuffer(source.toFile(), config);
        buffer.setContent("unsaved");

        assertThrows(IOException.class, buffer::createBackup);

        assertEquals("saved", Files.readString(source, StandardCharsets.UTF_8));
        assertTrue(buffer.isModified());
        assertEquals("unsaved", buffer.getContent());
    }
}
