package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import javax.swing.JOptionPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
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
    void failedStreamedWriteRetainsOriginalSource() throws Exception {
        Path source = tempDir.resolve("stream-save.txt");
        Files.writeString(source, "saved", StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () -> AtomicFileWriter.writeStream(source,
            output -> { throw new IOException("simulated stream failure"); }));

        assertTrue(error.getMessage().contains("target was not replaced"));
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
    void externalChangeCannotBeOverwrittenByOrdinarySave() throws Exception {
        Path source = tempDir.resolve("conflict.txt");
        Files.writeString(source, "original");
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.getDocument().insertString(buffer.getDocument().getLength(), " draft", null);
        buffer.setModified(true);
        Files.writeString(source, "external change");

        IOException error = assertThrows(IOException.class, buffer::save);

        assertTrue(error.getMessage().contains("changed on disk"));
        assertEquals("external change", Files.readString(source));
        assertTrue(buffer.isModified());
        assertTrue(buffer.getUndoManager().canUndo());
    }

    @Test
    void saveDetectsSameSizeEditWithRestoredModificationTime() throws Exception {
        Path source = tempDir.resolve("coarse-timestamp.txt");
        Files.writeString(source, "original");
        FileTime modifiedAt = Files.getLastModifiedTime(source);
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.setContent("my draft");
        Files.writeString(source, "otherone");
        Files.setLastModifiedTime(source, modifiedAt);

        assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());
        assertThrows(IOException.class, buffer::save);
        assertEquals("otherone", Files.readString(source));
        assertTrue(buffer.isModified());
    }

    @Test
    void explicitKeepMineAllowsSavingOverReviewedExternalVersion() throws Exception {
        Path source = tempDir.resolve("keep-mine.txt");
        Files.writeString(source, "original");
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.setContent("my draft");
        Files.writeString(source, "other edit");

        assertThrows(IOException.class, buffer::save);
        buffer.acceptExternalVersionForOverwrite();
        buffer.save();

        assertEquals("my draft", Files.readString(source));
        assertFalse(buffer.isModified());
    }

    @Test
    void atomicSaveKeepsSymbolicLinkAndExecutablePermission() throws Exception {
        Path source = tempDir.resolve("script.sh");
        Path link = tempDir.resolve("alias.sh");
        Files.writeString(source, "old");
        try {
            Files.setPosixFilePermissions(source, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            Files.createSymbolicLink(link, source.getFileName());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            Assumptions.assumeTrue(false, "symbolic links or POSIX permissions unavailable: " + unsupported.getMessage());
            return;
        }
        FileBuffer buffer = new FileBuffer(link.toFile());
        buffer.setContent("new");
        buffer.save();

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("new", Files.readString(source));
        assertTrue(Files.isExecutable(source));
    }

    @Test
    void atomicSavePreservesExtendedAttributesWhenSupported() throws Exception {
        Path source = tempDir.resolve("attributes.txt");
        Files.writeString(source, "old");
        UserDefinedFileAttributeView attributes = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
        Assumptions.assumeTrue(attributes != null, "extended attributes unavailable");
        try {
            attributes.write("shed.audit", ByteBuffer.wrap("retained".getBytes(StandardCharsets.UTF_8)));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            Assumptions.assumeTrue(false, "extended attributes unavailable: " + unsupported.getMessage());
            return;
        }
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.setContent("new");
        buffer.save();

        UserDefinedFileAttributeView saved = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
        ByteBuffer value = ByteBuffer.allocate(saved.size("shed.audit"));
        saved.read("shed.audit", value);
        value.flip();
        assertEquals("retained", StandardCharsets.UTF_8.decode(value).toString());
    }

    @Test
    void atomicSavePreservesDosHiddenAttributeWhenSupported() throws Exception {
        Path source = tempDir.resolve("hidden.txt");
        Files.writeString(source, "old");
        DosFileAttributeView attributes = Files.getFileAttributeView(source, DosFileAttributeView.class);
        Assumptions.assumeTrue(attributes != null, "DOS file attributes unavailable");
        attributes.setHidden(true);
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.setContent("new");
        buffer.save();

        assertTrue(Files.getFileAttributeView(source, DosFileAttributeView.class).readAttributes().isHidden());
    }

    @Test
    void atomicSaveKeepsSymbolicLinkOnPlatformsWithoutPosixPermissions() throws Exception {
        Path source = tempDir.resolve("target.txt");
        Path link = tempDir.resolve("linked.txt");
        Files.writeString(source, "old");
        try {
            Files.createSymbolicLink(link, source.getFileName());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + unsupported.getMessage());
            return;
        }
        FileBuffer buffer = new FileBuffer(link.toFile());
        buffer.setContent("new");
        buffer.save();

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("new", Files.readString(source));
    }

    @Test
    void backgroundSaveSnapshotKeepsEditsMadeWhileWritingDirty() throws Exception {
        Path source = tempDir.resolve("snapshot.txt");
        Files.writeString(source, "old");
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.getDocument().insertString(3, " first", null);
        buffer.setModified(true);
        FileBuffer.SaveSnapshot snapshot = buffer.captureSaveSnapshot(null);
        buffer.getDocument().insertString(buffer.getDocument().getLength(), " second", null);

        buffer.writeSaveSnapshot(snapshot);
        buffer.completeSaveSnapshot(snapshot);

        assertEquals("old first", Files.readString(source));
        assertEquals("old first second", buffer.getContent());
        assertTrue(buffer.isModified());
        assertTrue(buffer.getUndoManager().canUndo());
    }

    @Test
    void backgroundSaveRefusesChangesAfterSnapshotCapture() throws Exception {
        Path source = tempDir.resolve("snapshot-conflict.txt");
        Files.writeString(source, "old");
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.setContent("my draft");
        FileBuffer.SaveSnapshot snapshot = buffer.captureSaveSnapshot(null);
        Files.writeString(source, "someone else wrote this");

        assertThrows(IOException.class, () -> buffer.writeSaveSnapshot(snapshot));
        assertEquals("someone else wrote this", Files.readString(source));
        assertTrue(buffer.isModified());
    }

    @Test
    void formattedReplacementKeepsPriorUndoAndAddsOneUndoStep() throws Exception {
        Path source = tempDir.resolve("formatted.txt");
        Files.writeString(source, "old");
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.getDocument().insertString(3, " draft", null);
        buffer.replaceContentUndoably("formatted");

        assertTrue(buffer.getUndoManager().canUndo());
        buffer.getUndoManager().undo();
        assertEquals("old draft", buffer.getContent());
        assertTrue(buffer.getUndoManager().canUndo());
    }

    @Test
    void saveRefusesCharactersNotRepresentableInSelectedEncoding() throws Exception {
        Path source = tempDir.resolve("latin1.txt");
        Files.write(source, "saved".getBytes(StandardCharsets.ISO_8859_1));
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.setEncoding("ISO-8859-1");
        buffer.setContent("draft 😀");

        assertThrows(IOException.class, buffer::save);
        assertEquals("saved", Files.readString(source, StandardCharsets.ISO_8859_1));
        assertTrue(buffer.isModified());
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
