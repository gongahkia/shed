package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LargeFileBenchmarkTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsColdWarmOpenEditAndSaveWithoutChangingStandardInput() throws Exception {
        Path input = tempDir.resolve("sample.txt");
        String content = "line one\nline two\n";
        Files.writeString(input, content, StandardCharsets.UTF_8);

        LargeFileBenchmark.Report report = new LargeFileBenchmark(2).measure(input);

        assertEquals(content, Files.readString(input));
        assertEquals(LargeFileBenchmark.ResultState.PASS, report.state());
        assertEquals(2, report.operations().get("coldOpen").samples());
        assertEquals(2, report.operations().get("warmOpen").samples());
        assertEquals(LargeFileBenchmark.OperationState.UNSUPPORTED, report.operations().get("scroll").state());
        assertEquals(2, report.operations().get("edit").samples());
        assertEquals(2, report.operations().get("save").samples());
        assertTrue(report.operations().get("coldOpen").medianNanos() >= 0L);
        assertTrue(report.operations().get("coldOpen").p95Nanos() >= report.operations().get("coldOpen").medianNanos());
        assertTrue(report.failures().isEmpty());
        assertTrue(report.format().contains("result.state=PASS\n"));
        assertTrue(report.format().contains("workload.operations=coldOpen,warmOpen,scroll,edit,save\n"));
        assertTrue(report.format().contains("reproduction.command=java -cp target/shed-2.0.0.jar"));
    }

    @Test
    void recordsLargeFileScrollAndStreamedSaveWithExplicitUnavailableEdit() throws Exception {
        Path input = tempDir.resolve("large.txt");
        Files.writeString(input, "line\n".repeat(500_001), StandardCharsets.UTF_8);

        LargeFileBenchmark.Report report = new LargeFileBenchmark(1).measure(input);

        assertEquals(LargeFileBenchmark.ResultState.PASS, report.state());
        assertEquals(LargeFileBenchmark.OperationState.PASS, report.operations().get("coldOpen").state());
        assertEquals(LargeFileBenchmark.OperationState.PASS, report.operations().get("warmOpen").state());
        assertEquals(LargeFileBenchmark.OperationState.PASS, report.operations().get("scroll").state());
        assertEquals(LargeFileBenchmark.OperationState.UNSUPPORTED, report.operations().get("edit").state());
        assertEquals("large-file bounded editing is unavailable", report.operations().get("edit").reason());
        assertEquals(LargeFileBenchmark.OperationState.PASS, report.operations().get("save").state());
        assertTrue(report.failures().isEmpty());
    }

    @Test
    void reportsFailWhenCompletedMeasurementsExceedConfiguredP95Limit() throws Exception {
        Path input = tempDir.resolve("threshold.txt");
        Files.writeString(input, "line\n", StandardCharsets.UTF_8);

        LargeFileBenchmark.Report report = new LargeFileBenchmark(1, 1L).measure(input);

        assertEquals(LargeFileBenchmark.ResultState.FAIL, report.state());
        assertTrue(report.format().contains("workload.maxP95Nanos=1\n"));
    }

    @Test
    void recordsInputFailuresInsteadOfThrowing() {
        LargeFileBenchmark.Report report = new LargeFileBenchmark(1).measure(tempDir.resolve("missing.txt"));

        assertEquals(LargeFileBenchmark.ResultState.ERROR, report.state());
        assertEquals(1, report.failures().size());
        assertEquals("input", report.failures().getFirst().operation());
        assertEquals(LargeFileBenchmark.OperationState.ERROR, report.operations().get("coldOpen").state());
        assertTrue(report.format().contains("failures=1\n"));
    }

    @Test
    void commandReturnsMachineReadableErrorExitCode() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = LargeFileBenchmark.run(new String[] {tempDir.resolve("missing.txt").toString()},
            new PrintStream(output), new PrintStream(error));

        assertEquals(2, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("result.state=ERROR\n"));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }
}
