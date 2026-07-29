package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileBufferTest {
    @TempDir
    Path tempDir;

    @Test
    void largeFileOpenKeepsBoundedPreviewAndStreamsAtomicSave() throws Exception {
        Path file = tempDir.resolve("large.txt");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 50001; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append("line ").append(i);
        }
        String original = builder.toString();
        Files.writeString(file, original, StandardCharsets.UTF_8);

        FileBuffer buffer = new FileBuffer(file.toFile());
        assertTrue(buffer.isShowingPreviewOnly());
        assertTrue(buffer.getContent().contains("shed large-file preview"));
        assertTrue(buffer.getContent().length() <= LargeFileStore.MAX_PREVIEW_CHARS + 128);
        assertEquals(50001, buffer.getLargeFileLineCount());
        assertTrue(buffer.getLargeFileStatus().contains("bounded preview"));

        buffer.save();
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, buffer::getFullContent);
    }

    @Test
    void largeFileWithMalformedUtf8PreservesUnavailableStateWithoutFallback() throws Exception {
        Path file = tempDir.resolve("malformed-large.txt");
        byte[] content = new byte[100_002];
        for (int index = 0; index < content.length; index += 2) {
            content[index] = (byte) 0xFF;
            content[index + 1] = '\n';
        }
        Files.write(file, content);

        FileBuffer buffer = new FileBuffer(file.toFile());

        assertTrue(buffer.isLargeFile());
        assertTrue(buffer.isLargeFileUnavailable());
        assertTrue(buffer.getLargeFileStatus().contains("well-formed UTF-8"));
        assertTrue(buffer.getContent().contains("large-file unavailable"));
        assertThrows(IllegalStateException.class, buffer::getFullContent);
    }

    @Test
    void openEditSaveAndReloadMaintainDirtyAndSavedSnapshots() throws Exception {
        FileBuffer namedUnsaved = new FileBuffer(tempDir.resolve("named.txt").toString());
        assertFalse(namedUnsaved.isModified());
        assertEquals("", namedUnsaved.getSavedContent());

        Path file = tempDir.resolve("lifecycle.txt");
        Files.writeString(file, "opened\n", StandardCharsets.UTF_8);
        FileBuffer buffer = new FileBuffer(file.toFile());

        assertFalse(buffer.isModified());
        assertEquals("opened\n", buffer.getSavedContent());

        buffer.setContent("draft\n");
        assertTrue(buffer.isModified());
        assertEquals("opened\n", buffer.getSavedContent());

        buffer.save();
        assertFalse(buffer.isModified());
        assertEquals("draft\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("draft\n", buffer.getSavedContent());

        Files.writeString(file, "reloaded\n", StandardCharsets.UTF_8);
        buffer.load();
        assertFalse(buffer.isModified());
        assertEquals("reloaded\n", buffer.getContent());
        assertEquals("reloaded\n", buffer.getSavedContent());
    }

    @Test
    void failedScratchSaveRemainsDirty() {
        FileBuffer scratch = FileBuffer.createScratch("[draft]", "draft\n");
        scratch.setContent("changed\n");

        assertThrows(java.io.IOException.class, scratch::save);
        assertTrue(scratch.isModified());
        assertEquals("changed\n", scratch.getContent());
    }

    @Test
    void recoveredContentRemainsDirtyUntilExplicitSave() throws Exception {
        Path file = tempDir.resolve("recovered.txt");
        Files.writeString(file, "saved\n", StandardCharsets.UTF_8);
        FileBuffer recovered = new FileBuffer(file.toFile());
        recovered.setContent("recovered\n", true);

        assertTrue(recovered.isModified());
        assertEquals("saved\n", recovered.getSavedContent());
        recovered.save();
        assertFalse(recovered.isModified());
        assertEquals("recovered\n", recovered.getSavedContent());
        assertEquals("recovered\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void externalFileStatesAreDeterministicAndPreserveBufferContent() throws Exception {
        Path file = tempDir.resolve("external.txt");
        Files.writeString(file, "source", StandardCharsets.UTF_8);
        FileBuffer buffer = new FileBuffer(file.toFile());

        assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());
        Files.writeString(file, "changed source", StandardCharsets.UTF_8);
        assertEquals(FileBuffer.ExternalFileState.EXTERNALLY_CHANGED, buffer.getExternalFileState());
        buffer.refreshExternalTimestamp();
        assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());

        buffer.setContent("dirty buffer");
        Files.delete(file);
        assertEquals(FileBuffer.ExternalFileState.DELETED, buffer.getExternalFileState());
        assertEquals("dirty buffer", buffer.getContent());
        buffer.refreshExternalTimestamp();
        assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());

        Files.writeString(file, "replacement", StandardCharsets.UTF_8);
        assertEquals(FileBuffer.ExternalFileState.REPLACED, buffer.getExternalFileState());
        buffer.refreshExternalTimestamp();
        Files.delete(file);
        Files.createDirectory(file);
        assertEquals(FileBuffer.ExternalFileState.UNSUPPORTED, buffer.getExternalFileState());
        assertEquals("dirty buffer", buffer.getContent());
    }

    @Test
    void verifiesAtomicWriteAndRestoresSourceAfterVerificationFailure() throws Exception {
        Path file = tempDir.resolve("atomic.txt");
        Files.writeString(file, "original", StandardCharsets.UTF_8);

        AtomicFileWriter.write(file, "saved".getBytes(StandardCharsets.UTF_8));
        assertEquals("saved", Files.readString(file, StandardCharsets.UTF_8));

        java.io.IOException error = assertThrows(java.io.IOException.class,
            () -> AtomicFileWriter.write(file, "unexpected".getBytes(StandardCharsets.UTF_8),
                (target, expected) -> { throw new java.io.IOException("simulated verification failure"); }));

        assertTrue(error.getMessage().contains("original source was restored"));
        assertEquals("saved", Files.readString(file, StandardCharsets.UTF_8));
        try (Stream<Path> paths = Files.list(tempDir)) {
            assertEquals(0, paths.filter(path -> path.getFileName().toString().startsWith(".shed-")).count());
        }
    }

    @Test
    void failedSaveAsPreservesSourceBindingAndDirtyContent() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "original", StandardCharsets.UTF_8);
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.writeString(parentFile, "block", StandardCharsets.UTF_8);
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.setContent("draft");

        java.io.IOException error = assertThrows(java.io.IOException.class,
            () -> buffer.saveAs(parentFile.resolve("target.txt").toFile()));

        assertTrue(error.getMessage().contains("Check disk space and permissions"));
        assertEquals(source.toFile().getAbsolutePath(), buffer.getFilePath());
        assertTrue(buffer.isModified());
        assertEquals("draft", buffer.getContent());
        assertEquals("original", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    void failedEncodingSavePreservesSourceAndReportsRemediation() throws Exception {
        Path source = tempDir.resolve("encoding.txt");
        Files.writeString(source, "original", StandardCharsets.UTF_8);
        FileBuffer buffer = new FileBuffer(source.toFile());
        buffer.setContent("draft");
        buffer.setEncoding("not-a-real-charset");

        java.io.IOException error = assertThrows(java.io.IOException.class, buffer::save);

        assertTrue(error.getMessage().contains("Check the selected encoding and retry"));
        assertTrue(buffer.isModified());
        assertEquals("original", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    void writesVersionedBackupsBeforeRetentionPruning() throws Exception {
        String originalHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        Path directory = tempDir.resolve("backups");
        System.setProperty("user.home", home.toString());
        try {
            ConfigManager config = new ConfigManager();
            config.set("backup.directory", directory.toString());
            config.set("backup.retention.count", "2");
            Path source = tempDir.resolve("backup.txt");
            Files.writeString(source, "source", StandardCharsets.UTF_8);
            FileBuffer buffer = new FileBuffer(source.toFile(), config);

            buffer.setContent("first");
            buffer.createBackup();
            Path first = buffer.getBackupFile().toPath();
            buffer.setContent("second");
            buffer.createBackup();
            Path second = buffer.getBackupFile().toPath();

            assertTrue(Files.isRegularFile(first));
            assertTrue(Files.isRegularFile(second));
            assertEquals(List.of("first", "second"), backupContents(directory));

            buffer.setContent("third");
            buffer.createBackup();

            assertEquals(List.of("second", "third"), backupContents(directory));
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    @Test
    void disabledOrFailedBackupsLeaveDirtyContentRecoverable() throws Exception {
        String originalHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home-disabled");
        System.setProperty("user.home", home.toString());
        try {
            ConfigManager config = new ConfigManager();
            Path source = tempDir.resolve("backup-failure.txt");
            Files.writeString(source, "source", StandardCharsets.UTF_8);
            FileBuffer buffer = new FileBuffer(source.toFile(), config);
            buffer.setContent("draft");

            config.set("backup.enabled", "false");
            buffer.createBackup();
            assertNull(buffer.getBackupFile());

            Path blockingFile = tempDir.resolve("not-a-directory");
            Files.writeString(blockingFile, "block", StandardCharsets.UTF_8);
            config.set("backup.enabled", "true");
            config.set("backup.directory", blockingFile.resolve("backups").toString());

            assertThrows(java.io.IOException.class, buffer::createBackup);
            assertTrue(buffer.isModified());
            assertEquals("draft", buffer.getContent());
            assertEquals("source", Files.readString(source, StandardCharsets.UTF_8));
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    private List<String> backupContents(Path directory) throws Exception {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.sorted().map(path -> {
                try {
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (java.io.IOException error) {
                    throw new RuntimeException(error);
                }
            }).toList();
        }
    }
}
