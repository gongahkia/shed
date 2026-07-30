package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class DebugSessionController {
    private final Texteditor editor;
    private final DebugSessionService sessions;
    private final BreakpointStore breakpoints;
    private Path lastWorkspace;
    private Path lastActiveFile;

    DebugSessionController(Texteditor editor) {
        this(editor, new DebugSessionService(), new BreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")));
    }

    DebugSessionController(Texteditor editor, DebugSessionService sessions) {
        this(editor, sessions, new BreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")));
    }

    DebugSessionController(Texteditor editor, DebugSessionService sessions, BreakpointStore breakpoints) {
        this.editor = editor;
        this.sessions = sessions == null ? new DebugSessionService() : sessions;
        this.breakpoints = breakpoints == null ? new BreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")) : breakpoints;
    }

    String handle(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "help".equalsIgnoreCase(trimmed)) return "Usage: :debug status|configurations|select [name]|start [name]|stop|restart";
        int split = trimmed.indexOf(' ');
        String command = (split < 0 ? trimmed : trimmed.substring(0, split)).toLowerCase();
        String args = split < 0 ? "" : trimmed.substring(split + 1).trim();
        return switch (command) {
            case "status" -> status();
            case "configurations", "configs", "list" -> configurations();
            case "select", "configuration", "config" -> select(args);
            case "start", "launch", "attach" -> start(args);
            case "stop" -> stop();
            case "restart" -> restart(args);
            default -> "Unknown :debug subcommand: " + command;
        };
    }

    void shutdown() { sessions.stop(workspace()); }

    private String configurations() {
        Path workspace = workspace();
        DebugAdapterDetector.WorkspaceReport report = new DebugAdapterDetector(null).detect(workspace, editor.configManager.getDebugConfiguration(),
            editor.configManager.getDebugFeatureSettings());
        StringBuilder output = new StringBuilder("Debug Configurations\n\nWorkspace: ").append(workspace).append("\nEditing remains available: ")
            .append(report.normalEditingAvailable()).append("\n\nAdapters:\n");
        if (report.adapters().isEmpty()) output.append("  (none)\n");
        for (DebugAdapterDetector.AdapterReport adapter : report.adapters()) {
            output.append("  ").append(adapter.id()).append("  ").append(adapter.availability()).append("  version=")
                .append(adapter.versionState()).append("\n    ").append(adapter.remediation()).append("\n");
        }
        output.append("\nConfigurations:\n");
        if (report.configurations().isEmpty()) output.append("  (none)\n");
        for (DebugAdapterDetector.ConfigurationReport configuration : report.configurations()) {
            output.append("  ").append(configuration.name()).append("  ").append(configuration.request()).append("  ")
                .append(configuration.availability()).append("\n    ").append(configuration.remediation()).append("\n");
        }
        if (!report.validationErrors().isEmpty()) output.append("\nValidation:\n  ").append(String.join("\n  ", report.validationErrors())).append("\n");
        editor.showScratchBuffer("[debug configurations]", output.toString());
        return "Showing debug configurations";
    }

    private String select(String requested) {
        Path workspace = workspace();
        DebugAdapterRegistry.Validation validation = editor.configManager.getDebugConfiguration();
        if (validation == null || !validation.valid()) return selectResult(sessions.select(workspace, validation, ""));
        String name = requested;
        if (name == null || name.isBlank()) {
            List<String> names = validation.configurations().keySet().stream().sorted().toList();
            if (names.isEmpty()) return "No debug configurations are defined.";
            name = editor.showPaletteDialog("Debug configurations", names);
            if (name == null || name.isBlank()) return "Debug configuration selection cancelled.";
        }
        return selectResult(sessions.select(workspace, validation, name));
    }

    private String selectResult(DebugSessionService.Result result) {
        if (result.succeeded()) return result.snapshot().detail();
        return result.snapshot().detail() + diagnosticSuffix(result.snapshot());
    }

    private String start(String requested) { return submitStart(requested, false); }
    private String restart(String requested) {
        sessions.stop(workspace());
        return submitStart(requested, true);
    }

    private String submitStart(String requested, boolean restart) {
        Path workspace = workspace();
        Path activeFile = activeFile();
        String name = requested == null ? "" : requested.trim();
        int jobId = editor.asyncJobService.submit("debug " + (restart ? "restart" : "start"), token -> sessions.start(workspace, activeFile,
            editor.configManager.getDebugConfiguration(), editor.configManager.getDebugFeatureSettings(), name,
            Duration.ofMillis(Math.max(1, editor.configManager.getProcessTimeoutMs())), this::startTransport, breakpoints), (job, result, error) -> {
                if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
                    editor.showMessage("Debug " + (restart ? "restart" : "start") + " cancelled.");
                    return;
                }
                if (error != null || result == null) {
                    editor.showMessage("Debug start failed: " + (error == null ? job.getErrorMessage() : error.getMessage()));
                    return;
                }
                refreshBreakpointMarkers();
                showStatus(workspace);
                editor.showMessage(result.succeeded() ? result.snapshot().detail() : result.snapshot().detail() + diagnosticSuffix(result.snapshot()));
            });
        return "Explicit debug " + (restart ? "restart" : "start") + " requested (job " + jobId + ").";
    }

    private DebugSessionService.Connection startTransport(DebugAdapterRegistry.Plan plan, DebugFeatureSettings features,
        DebugAdapterTransport.Listener listener) throws IOException {
        DebugAdapterTransport transport = DebugAdapterTransport.start(plan, features, listener, new DiagnosticLog(editor.errorReporter.getLogPath()));
        return new DebugSessionService.Connection() {
            @Override public DebugAdapterTransport.Response request(String command, Map<String, Object> arguments, Duration timeout)
                throws IOException, java.util.concurrent.TimeoutException, InterruptedException { return transport.request(command, arguments, timeout); }
            @Override public DebugAdapterTransport.State state() { return transport.state(); }
            @Override public void close() { transport.close(); }
        };
    }

    private String stop() {
        DebugSessionService.Result result = sessions.stop(workspace());
        showStatus(workspace());
        return result.snapshot().detail();
    }

    void toggleBreakpoint(FileBuffer buffer, int zeroBasedLine) {
        if (buffer == null || !buffer.hasFilePath() || zeroBasedLine < 0) {
            editor.showMessage("Source breakpoints require a file-backed editor buffer.");
            return;
        }
        try {
            Path source = buffer.getFile().toPath().toAbsolutePath().normalize();
            Path workspace = editor.lspController.resolveWorkspaceRoot(source.getParent());
            lastWorkspace = workspace;
            lastActiveFile = source;
            BreakpointStore.Toggle result = breakpoints.toggle(workspace, source, zeroBasedLine + 1);
            refreshBreakpointMarkers();
            editor.showMessage(result.added() ? "Breakpoint added at " + source + ":" + (zeroBasedLine + 1) + "."
                : "Breakpoint removed from " + source + ":" + (zeroBasedLine + 1) + ".");
            if (sessions.snapshot(workspace).lifecycle() == DebugSessionService.Lifecycle.RUNNING) synchronizeBreakpoints(workspace);
        } catch (IOException | IllegalArgumentException error) {
            editor.showMessage("Unable to update source breakpoint: " + error.getMessage());
        }
    }

    void refreshBreakpointMarkers() {
        for (EditorPane pane : editor.editorPanes) {
            FileBuffer buffer = pane.getBuffer();
            if (buffer == null || !buffer.hasFilePath()) {
                pane.getLineNumberPanel().updateBreakpointMarkers(Map.of());
                continue;
            }
            try {
                Path source = buffer.getFile().toPath().toAbsolutePath().normalize();
                Path workspace = editor.lspController.resolveWorkspaceRoot(source.getParent());
                pane.getLineNumberPanel().updateBreakpointMarkers(breakpoints.markers(workspace, source));
            } catch (IOException | IllegalArgumentException error) {
                pane.getLineNumberPanel().updateBreakpointMarkers(Map.of());
            }
        }
    }

    private void synchronizeBreakpoints(Path workspace) {
        editor.asyncJobService.submit("debug breakpoints", token -> sessions.synchronizeBreakpoints(workspace, breakpoints,
            Duration.ofMillis(Math.max(1, editor.configManager.getProcessTimeoutMs()))), (job, result, error) -> {
                refreshBreakpointMarkers();
                if (error != null || result == null || !result.succeeded()) {
                    editor.showMessage("Source breakpoint synchronization failed.");
                } else if (!result.snapshot().diagnostics().isEmpty()) {
                    editor.showMessage("Source breakpoint synchronization completed; inspect :debug status for diagnostics.");
                }
            });
    }

    private String status() {
        showStatus(workspace());
        return "Showing debug status";
    }

    private void showStatus(Path workspace) {
        DebugSessionService.Snapshot snapshot = sessions.snapshot(workspace);
        StringBuilder output = new StringBuilder("Debug Lifecycle\n\nWorkspace: ").append(snapshot.workspace()).append("\nConfiguration: ")
            .append(snapshot.configuration().isBlank() ? "(none)" : snapshot.configuration()).append("\nState: ").append(snapshot.lifecycle())
            .append("\nDetail: ").append(snapshot.detail()).append("\n");
        if (!snapshot.diagnostics().isEmpty()) output.append("\nDiagnostics:\n  ").append(String.join("\n  ", snapshot.diagnostics())).append("\n");
        output.append("\nActions: :debug configurations | :debug select <name> | :debug start [name] | :debug stop | :debug restart [name]\n");
        editor.showScratchBuffer("[debug status]", output.toString());
    }

    private Path workspace() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer != null && buffer.hasFilePath()) {
            try {
                Path file = buffer.getFile().toPath().toAbsolutePath().normalize();
                lastActiveFile = file;
                lastWorkspace = editor.lspController.resolveWorkspaceRoot(file.getParent());
                return lastWorkspace;
            } catch (IOException ignored) { }
        }
        if (lastWorkspace != null) return lastWorkspace;
        try { return new File(".").getCanonicalFile().toPath(); }
        catch (IOException ignored) { return Path.of(".").toAbsolutePath().normalize(); }
    }

    private Path activeFile() { return lastActiveFile; }
    private static String diagnosticSuffix(DebugSessionService.Snapshot snapshot) {
        return snapshot.diagnostics().isEmpty() ? "" : " Diagnostics: " + String.join("; ", snapshot.diagnostics());
    }
}
