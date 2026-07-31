package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandPathCompletionTest {
    @TempDir Path tempDir;

    @Test
    void suggestsRelativePathsForSupportedCommandArguments() throws Exception {
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("docs/guide.md"), "fixture\n");

        assertEquals(List.of(":tree docs/"), CommandPathCompletion.suggestions(":tree do", tempDir.toFile()));
        assertEquals(List.of(":edit docs/guide.md"), CommandPathCompletion.suggestions(":edit docs/g", tempDir.toFile()));
    }

    @Test
    void ignoresCommandsWithoutPathArguments() {
        assertTrue(CommandPathCompletion.suggestions(":git log", tempDir.toFile()).isEmpty());
        assertTrue(CommandPathCompletion.suggestions(":tree", tempDir.toFile()).isEmpty());
    }
}
