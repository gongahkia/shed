package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LargeFileProjectionTest {
    @TempDir
    Path tempDir;

    @Test
    void movesBoundedWindowWithoutMaterializingAllLines() throws Exception {
        Path file = tempDir.resolve("large.txt");
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= 500_001; line++) {
            content.append("line-").append(line).append('\n');
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        FileBuffer buffer = new FileBuffer(file.toFile());
        LargeFileProjection projection = new LargeFileProjection(buffer);

        projection.render(20);
        assertTrue(buffer.getContent().contains("line-1"));
        assertTrue(projection.moveForward(20));
        assertEquals(33L, projection.firstLine());
        assertTrue(buffer.getContent().contains("line-33"));
        assertTrue(projection.moveBackward(20));
        assertEquals(1L, projection.firstLine());
    }
}
