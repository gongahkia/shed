package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ProjectFileScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesExclusionsLimitAndDeterministicOrdering() throws Exception {
        write("z.txt");
        write("a.txt");
        write("dir/b.txt");
        write(".hidden.txt");
        write("node_modules/package.js");
        write("target/output.class");
        ProjectFileScanner scanner = new ProjectFileScanner();

        ProjectFileScanner.ScanResult full = scanner.scan(tempDir, tempDir, 10, ProjectFileScanner.Cancellation.NONE);
        ProjectFileScanner.ScanResult limited = scanner.scan(tempDir, tempDir, 2, ProjectFileScanner.Cancellation.NONE);

        assertEquals(List.of("a.txt", "z.txt", "dir/b.txt"), full.files());
        assertFalse(full.cancelled());
        assertEquals(List.of("a.txt", "z.txt"), limited.files());
    }

    @Test
    void returnsDeterministicPartialResultWhenCancelled() throws Exception {
        write("a.txt");
        write("b.txt");
        ProjectFileScanner.CancellationSource cancellation = new ProjectFileScanner.CancellationSource();
        ProjectFileScanner scanner = new ProjectFileScanner();
        cancellation.cancel();

        ProjectFileScanner.ScanResult result = scanner.scan(tempDir, tempDir, 10, cancellation);

        assertTrue(result.cancelled());
        assertTrue(result.files().isEmpty());
        assertEquals(0, result.scannedDirectories());
    }

    private void write(String relativePath) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, relativePath, StandardCharsets.UTF_8);
    }
}
