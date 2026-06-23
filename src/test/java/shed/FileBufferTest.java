package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
