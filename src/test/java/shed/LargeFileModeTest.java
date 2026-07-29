package shed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LargeFileModeTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsActiveModeOperationsAndRemediation() throws Exception {
        Path file = tempDir.resolve("large.txt");
        Files.writeString(file, "line\n".repeat(50_001), StandardCharsets.UTF_8);

        String report = LargeFileMode.report(new FileBuffer(file.toFile()));

        assertTrue(report.contains("State: active"));
        assertTrue(report.contains("streamed atomic save"));
        assertTrue(report.contains("editing, undo/redo, save-as"));
        assertTrue(report.contains("Remediation:"));
    }
}
