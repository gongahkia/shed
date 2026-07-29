package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ProjectFileScanBenchmarkTest {
    @TempDir
    Path tempDir;

    @Test
    void comparesLegacyAndOptimizedScanOnCurrentFixture() throws Exception {
        write("src/z.java");
        write("src/a.java");
        write("README.md");
        write(".git/config");
        write("target/out.class");

        ProjectFileScanBenchmark.Report report = new ProjectFileScanBenchmark(new ProjectFileScanner()).measure(tempDir, 100);

        assertTrue(report.equivalentFileSet());
        assertEquals(3, report.legacyFiles());
        assertEquals(3, report.optimizedFiles());
        assertTrue(report.legacyDurationNanos() >= 0);
        assertTrue(report.optimizedDurationNanos() >= 0);
        assertTrue(report.format().contains("equivalentFileSet=true\n"));
    }

    private void write(String relativePath) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, relativePath, StandardCharsets.UTF_8);
    }
}
