package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TaskProblemParserTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativeGenericProblemsFromTaskCwd() throws Exception {
        Path cwd = tempDir.resolve("tools");
        Files.createDirectories(cwd.resolve("src"));

        List<QuickfixService.Entry> entries = TaskProblemParser.parseGeneric(
            "src/Main.java:12:4: missing semicolon\nnot a problem", "task:check", cwd.toFile());

        assertEquals(1, entries.size());
        QuickfixService.Entry entry = entries.get(0);
        assertEquals(cwd.resolve("src/Main.java").toFile().getCanonicalPath(), entry.getFilePath());
        assertEquals(12, entry.getLine());
        assertEquals(4, entry.getColumn());
        assertEquals("missing semicolon", entry.getMessage());
        assertEquals("task:check", entry.getSource());
    }

    @Test
    void ignoresMalformedProblemOutput() {
        assertTrue(TaskProblemParser.parseGeneric("src/Main.java:line: bad", "task", tempDir.toFile()).isEmpty());
    }
}
