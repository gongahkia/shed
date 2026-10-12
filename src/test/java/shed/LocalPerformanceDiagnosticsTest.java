package shed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LocalPerformanceDiagnosticsTest {
    @TempDir
    Path tempDir;

    @Test
    void overviewLabelsLocalScopeToolStatusAndBenchmarkLimits() {
        PerfService perf = new PerfService();
        perf.recordDuration("open", System.nanoTime(), "buffer=test.txt");
        perf.recordSlowEdit("paint", 20_000_000L, 1_000_000L, 17_000_000L, 2_000_000L, 10_000, "Symbol cache");

        String report = LocalPerformanceDiagnostics.overview(new DiagnosticLog(tempDir.resolve("missing.jsonl")), perf, false);

        assertTrue(report.contains("Data scope: local only."));
        assertTrue(report.contains("Timing recorder: active; 1 sample(s) across 1 metric group(s)"));
        assertTrue(report.contains("Diagnostic log: missing; 0 structured entries"));
        assertTrue(report.contains("Workspace-index benchmark: unavailable; open a file-backed buffer or tree root"));
        assertTrue(report.contains("Timings and heap observations vary"));
        assertTrue(report.contains("edit.input-to-paint measures a key press through Swing paint completion"));
        assertTrue(report.contains("Timing p95 uses the latest 256 samples"));
        assertTrue(report.contains("Slow edits (at least 16ms"));
        assertTrue(report.contains("phase=paint"));
        assertTrue(report.contains("document-to-paint-start=17000us"));
        assertTrue(report.contains("active-jobs-at-paint=Symbol cache"));
        assertTrue(report.contains(":perf benchmark"));
    }

    @Test
    void diagnosticViewRendersLatestStructuredLocalEntry() {
        DiagnosticLog diagnostics = new DiagnosticLog(tempDir.resolve("shed-diagnostics.jsonl"));
        diagnostics.record(DiagnosticLog.Severity.ERROR, "async-jobs", "running benchmark", new IllegalStateException("local failure"),
            "docs/WORKSPACE_INDEX_BENCHMARK.md");

        String report = LocalPerformanceDiagnostics.diagnostics(diagnostics);

        assertTrue(report.contains("Data scope: local only."));
        assertTrue(report.contains("Log status: available"));
        assertTrue(report.contains("ERROR async-jobs: running benchmark"));
        assertTrue(report.contains("Cause: java.lang.IllegalStateException: local failure"));
        assertTrue(report.contains("Remediation: docs/WORKSPACE_INDEX_BENCHMARK.md"));
    }
}
