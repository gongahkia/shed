package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileBufferTest {
    @TempDir
    Path tempDir;

    @Test
    void largeFilePreviewSavePreservesHiddenTail() throws Exception {
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

        buffer.save();

        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(original, saved);
        assertFalse(saved.contains("shed large-file preview"));
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
}
