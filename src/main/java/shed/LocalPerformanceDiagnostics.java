package shed;

import java.util.Locale;
import java.util.Objects;

final class LocalPerformanceDiagnostics {
    private LocalPerformanceDiagnostics() { }

    static String overview(DiagnosticLog diagnostics, PerfService perf, boolean benchmarkReady) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(perf, "perf");
        DiagnosticLog.Snapshot log = diagnostics.inspect(1);
        StringBuilder report = new StringBuilder();
        report.append("Local performance diagnostics\n");
        report.append("=".repeat(40)).append("\n\n");
        report.append("Data scope: local only. Shed sends no diagnostic, usage, benchmark, or machine data.\n\n");
        report.append("Tool status:\n");
        report.append("  Timing recorder: active; ").append(perf.sampleCount()).append(" sample(s) across ")
            .append(perf.metricCount()).append(" metric group(s)\n");
        report.append("  Diagnostic log: ").append(statusLabel(log.status())).append("; ").append(log.entries())
            .append(" structured entr").append(log.entries() == 1 ? "y" : "ies");
        if (log.malformedEntries() > 0) {
            report.append("; ").append(log.malformedEntries()).append(" unreadable line(s)");
        }
        report.append("\n  Log path: ").append(log.path()).append("\n");
        report.append("  Workspace-index benchmark: ").append(benchmarkReady ? "ready" : "unavailable")
            .append(benchmarkReady ? " for the current workspace\n" : "; open a file-backed buffer or tree root\n");
        report.append("\nRecorded timings:\n").append(perf.report());
        report.append("\nBenchmark limits:\n");
        report.append("  Measures one fresh local persistent-index build. It can create or replace the local index cache.\n");
        report.append("  Timings and heap observations vary by JDK, storage, workspace, CPU load, and OS caches; they are not portability guarantees.\n\n");
        report.append("Controls:\n");
        report.append("  :perf diagnostics   Show latest structured local errors\n");
        report.append("  :perf benchmark     Start the cancellable local workspace-index benchmark\n");
        report.append("  :jobs, :jobcancel <id>   Check or cancel the benchmark job\n");
        return report.toString();
    }

    static String diagnostics(DiagnosticLog diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        DiagnosticLog.Snapshot log = diagnostics.inspect(20);
        StringBuilder report = new StringBuilder();
        report.append("Local diagnostic log\n");
        report.append("=".repeat(40)).append("\n\n");
        report.append("Data scope: local only. Shed does not transmit these records or telemetry.\n");
        report.append("Log status: ").append(statusLabel(log.status())).append("\n");
        report.append("Path: ").append(log.path()).append("\n");
        report.append("Structured entries: ").append(log.entries()).append("\n");
        if (log.malformedEntries() > 0) {
            report.append("Unreadable lines: ").append(log.malformedEntries()).append("\n");
        }
        if (log.status() == DiagnosticLog.Status.MISSING) {
            report.append("\nNo local diagnostic log has been written.\n");
        } else if (log.status() == DiagnosticLog.Status.UNREADABLE) {
            report.append("\nShed could not read the local diagnostic log.\n");
        } else if (log.latestEntries().isEmpty()) {
            report.append("\nNo readable diagnostic entries.\n");
        } else {
            report.append("\nLatest entries (newest first):\n");
            for (DiagnosticLog.Entry entry : log.latestEntries()) {
                report.append("\n[").append(value(entry.timestamp(), "unknown time")).append("] ")
                    .append(value(entry.severity(), "ERROR")).append(" ").append(value(entry.subsystem(), "application"))
                    .append(": ").append(value(entry.context(), "no context")).append("\n");
                report.append("  Cause: ").append(value(entry.causeType(), "unknown"));
                if (!entry.causeMessage().isBlank()) {
                    report.append(": ").append(entry.causeMessage());
                }
                report.append("\n  Remediation: ").append(value(entry.remediation(), "docs/DIAGNOSTICS.md")).append("\n");
            }
        }
        report.append("\nLimit: the local log retains the newest records within 1 MiB; this view shows at most 20 entries.\n");
        return report.toString();
    }

    private static String statusLabel(DiagnosticLog.Status status) {
        return status == null ? "unknown" : status.name().toLowerCase(Locale.ROOT);
    }

    private static String value(String source, String fallback) {
        return source == null || source.isBlank() ? fallback : source;
    }
}
