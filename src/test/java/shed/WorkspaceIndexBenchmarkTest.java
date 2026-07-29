package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceIndexBenchmarkTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsLocalReproducibleIndexInputsOutputsAndCosts() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(root.resolve("one.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("two.txt"), "four", StandardCharsets.UTF_8);
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);

        WorkspaceIndexBenchmark.Report report = new WorkspaceIndexBenchmark(service).measure(root, WorkspaceIndexService.Cancellation.NONE);

        assertEquals(WorkspaceIndexService.State.READY, report.state());
        assertTrue(report.durationNanos() >= 0);
        assertEquals(2, report.inputFiles());
        assertEquals(0, report.ignoredFiles());
        assertEquals(7, report.inputBytes());
        assertEquals(2, report.outputFiles());
        assertTrue(report.cacheBytes() > 0);
        assertTrue(report.format().contains("inputBytes=7\n"));
        assertTrue(report.format().contains("cacheBytes="));
    }
}
