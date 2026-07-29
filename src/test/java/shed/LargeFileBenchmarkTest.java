package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LargeFileBenchmarkTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsLocalOpenEditSaveMeasurementsWithoutChangingInput() throws Exception {
        Path input = tempDir.resolve("sample.txt");
        String content = "line one\nline two\n";
        Files.writeString(input, content, StandardCharsets.UTF_8);

        LargeFileBenchmark.Report report = new LargeFileBenchmark(2).measure(input);

        assertEquals(content, Files.readString(input));
        assertEquals(2, report.operations().get("open").samples());
        assertEquals(2, report.operations().get("edit").samples());
        assertEquals(2, report.operations().get("save").samples());
        assertTrue(report.operations().get("open").medianNanos() >= 0);
        assertTrue(report.operations().get("open").p95Nanos() >= report.operations().get("open").medianNanos());
        assertTrue(report.failures().isEmpty());
        assertTrue(report.format().contains("environment.javaVersion="));
        assertTrue(report.format().contains("workload.operations=open,edit,save\n"));
        assertTrue(report.format().contains("reproduction.command=java -cp target/shed-2.0.0.jar"));
    }

    @Test
    void recordsInputFailuresInsteadOfThrowing() {
        LargeFileBenchmark.Report report = new LargeFileBenchmark(1).measure(tempDir.resolve("missing.txt"));

        assertEquals(1, report.failures().size());
        assertEquals("input", report.failures().getFirst().operation());
        assertEquals(0, report.operations().get("open").samples());
        assertTrue(report.format().contains("failures=1\n"));
    }
}
