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
        if (trimmed.isEmpty() || "help".equalsIgnoreCase(trimmed)) {
            return "Usage: :debug status|configurations|select [name]|start [name]|stop|restart|console [clear]|stack|variables|frame <id>|watch add|remove|list|clear";
        }
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
            case "console", "output" -> console(args);
            case "stack", "frames", "variables", "inspect", "refresh" -> submitInspection();
            case "frame" -> selectFrame(args);
            case "watch", "watches" -> watch(args);
            default -> "Unknown :debug subcommand: " + command;
        };
    }

    void shutdown() { sessions.stop(workspace()); }

    List<String> configurationNamesForPanel() {
        DebugAdapterRegistry.Validation validation = editor.configManager.getDebugConfiguration();
        return validation == null || !validation.valid() ? List.of() : validation.configurations().keySet().stream().sorted().toList();
    }

    DebugSessionService.Snapshot snapshotForPanel() { return sessions.snapshot(workspace()); }

    DebugInspection.Snapshot inspectionForPanel() { return sessions.inspection(workspace()); }

    DebugConsole.Snapshot consoleForPanel() { return sessions.console(workspace()); }

    String selectForPanel(String name) { return select(name); }

    String startForPanel() { return start(""); }

    String stopForPanel() { return stop(); }

    String restartForPanel() { return restart(""); }

    String refreshInspectionForPanel() {
        Path workspace = workspace();
        int jobId = editor.asyncJobService.submit("debug inspect", token -> sessions.refreshInspection(workspace,
            Duration.ofMillis(Math.max(1, editor.configManager.getProcessTimeoutMs()))), (job, result, error) -> {
                if (editor.toolWindowHost != null) editor.toolWindowHost.refresh(ToolWindowHost.Tab.DEBUG);
            });
        return "Debug inspection requested (job " + jobId + ").";
    }

    String addWatchForPanel(String expression) {
        DebugSessionService.InspectionResult result = sessions.addWatch(workspace(), expression);
        return result.succeeded() ? "Watch added" : "Watch expression is empty, invalid, too long, or already exists.";
    }

    String removeWatchForPanel(String expression) {
        return sessions.removeWatch(workspace(), expression).succeeded() ? "Watch removed" : "Watch was not found.";
    }

    String selectFrameForPanel(int id) {
        return sessions.selectFrame(workspace(), id).succeeded() ? refreshInspectionForPanel() : "Debug frame is unavailable.";
    }

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
                if (editor.toolWindowHost != null && editor.toolWindowHost.isSelected(ToolWindowHost.Tab.DEBUG)) {
                    editor.toolWindowHost.refresh(ToolWindowHost.Tab.DEBUG);
                } else {
                    showStatus(workspace);
                }
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
        if (editor.toolWindowHost != null && editor.toolWindowHost.isSelected(ToolWindowHost.Tab.DEBUG)) {
            editor.toolWindowHost.refresh(ToolWindowHost.Tab.DEBUG);
        } else {
            showStatus(workspace());
        }
        return result.snapshot().detail();
    }

    private String console(String argument) {
        Path workspace = workspace();
        String command = argument == null ? "" : argument.trim();
        if (command.isEmpty() || "show".equalsIgnoreCase(command)) {
            showConsole(workspace);
            return "Showing debug console";
        }
        if ("clear".equalsIgnoreCase(command)) {
            sessions.clearConsole(workspace);
            showConsole(workspace);
            return "Debug console cleared";
        }
        return "Usage: :debug console [clear]";
    }

    private String selectFrame(String argument) {
        int frameId;
        try { frameId = Integer.parseInt(argument); }
        catch (NumberFormatException error) { return "Usage: :debug frame <positive-id>"; }
        if (frameId < 1) return "Usage: :debug frame <positive-id>";
        DebugSessionService.InspectionResult result = sessions.selectFrame(workspace(), frameId);
        if (!result.succeeded()) return "Debug frame " + frameId + " is unavailable for the active paused session.";
        return submitInspection();
    }

    private String watch(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "list".equalsIgnoreCase(trimmed)) {
            showInspection(workspace());
            return "Showing debug watches";
        }
        int split = trimmed.indexOf(' ');
        String command = (split < 0 ? trimmed : trimmed.substring(0, split)).toLowerCase();
        String expression = split < 0 ? "" : trimmed.substring(split + 1).trim();
        Path workspace = workspace();
        return switch (command) {
            case "add" -> {
                DebugSessionService.InspectionResult result = sessions.addWatch(workspace, expression);
                if (!result.succeeded()) yield "Watch expression is empty, invalid, too long, or already exists.";
                yield result.snapshot().paused() ? submitInspection() : "Watch added. It will evaluate when debugging pauses.";
            }
            case "remove", "delete" -> sessions.removeWatch(workspace, expression).succeeded() ? "Watch removed." : "Watch was not found.";
            case "clear" -> sessions.clearWatches(workspace).succeeded() ? "Debug watches cleared." : "No debug watches are defined.";
            default -> "Usage: :debug watch add <expression>|remove <expression>|list|clear";
        };
    }

    private String submitInspection() {
        Path workspace = workspace();
        int jobId = editor.asyncJobService.submit("debug inspect", token -> sessions.refreshInspection(workspace,
            Duration.ofMillis(Math.max(1, editor.configManager.getProcessTimeoutMs()))), (job, result, error) -> {
                showInspection(workspace);
                if (error != null || result == null || !result.succeeded()) editor.showMessage("Debug inspection is unavailable; inspect [debug inspector].");
            });
        showInspection(workspace);
        return "Debug inspection requested (job " + jobId + ").";
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

    private void showInspection(Path workspace) {
        DebugInspection.Snapshot snapshot = sessions.inspection(workspace);
        StringBuilder output = new StringBuilder("Debug Inspector\n\nState: ").append(snapshot.state()).append("\nDetail: ")
            .append(snapshot.detail()).append("\nPaused: ").append(snapshot.paused()).append("\nThread: ")
            .append(snapshot.threadId() == 0 ? "(none)" : snapshot.threadId()).append("\nActive frame: ")
            .append(snapshot.frameId() == 0 ? "(none)" : snapshot.frameId()).append("\n\nThreads:\n");
        if (snapshot.threads().isEmpty()) output.append("  (none or unavailable)\n");
        for (DebugInspection.ThreadInfo thread : snapshot.threads()) output.append("  ").append(thread.id()).append("  ").append(thread.name()).append("\n");
        output.append("\nCall Stack:\n");
        if (snapshot.frames().isEmpty()) output.append("  (none or unavailable)\n");
        for (DebugInspection.Frame frame : snapshot.frames()) {
            output.append(frame.id() == snapshot.frameId() ? "* " : "  ").append(frame.id()).append("  ").append(frame.name());
            if (!frame.source().isBlank()) output.append("  ").append(frame.source()).append(":").append(frame.line());
            output.append("\n");
        }
        output.append("\nScopes and Variables:\n");
        if (snapshot.scopes().isEmpty()) output.append("  (none or unavailable)\n");
        for (DebugInspection.Scope scope : snapshot.scopes()) {
            output.append("  ").append(scope.name()).append(scope.expensive() ? " (expensive)" : "").append("\n");
            if (scope.variables().isEmpty()) output.append("    (none or unavailable)\n");
            for (DebugInspection.Variable variable : scope.variables()) {
                output.append("    ").append(variable.name()).append(" = ").append(variable.value());
                if (!variable.type().isBlank()) output.append("  : ").append(variable.type());
                output.append("\n");
            }
        }
        output.append("\nWatches:\n");
        if (snapshot.watches().isEmpty()) output.append("  (none)\n");
        for (DebugInspection.Watch watch : snapshot.watches()) {
            output.append("  ").append(watch.expression()).append("  ").append(watch.state());
            if (!watch.value().isBlank()) output.append("  = ").append(watch.value());
            if (!watch.type().isBlank()) output.append("  : ").append(watch.type());
            if (!watch.message().isBlank()) output.append("  ").append(watch.message());
            output.append("\n");
        }
        output.append("\nActions: :debug stack | :debug variables | :debug frame <id> | :debug watch add <expression> | :debug watch remove <expression>\n");
        editor.showScratchBuffer("[debug inspector]", output.toString());
    }

    private void showConsole(Path workspace) {
        DebugConsole.Snapshot snapshot = sessions.console(workspace);
        StringBuilder output = new StringBuilder("Debug Console\n\nState: ").append(snapshot.state()).append("\nDetail: ")
            .append(snapshot.detail()).append("\nEvents retained: ").append(snapshot.events()).append("\n");
        if (snapshot.truncated()) output.append("Recovery buffer truncated to the most recent ").append(DebugConsole.MAX_CHARACTERS).append(" characters.\n");
        output.append("\nOutput:\n");
        if (snapshot.output().isEmpty()) output.append("  (none)\n");
        else output.append(snapshot.output());
        if (!snapshot.output().isEmpty() && !snapshot.output().endsWith("\n")) output.append("\n");
        output.append("\nActions: :debug console clear\n");
        editor.showScratchBuffer("[debug console]", output.toString());
    }

    private void showStatus(Path workspace) {
        DebugSessionService.Snapshot snapshot = sessions.snapshot(workspace);
        StringBuilder output = new StringBuilder("Debug Lifecycle\n\nWorkspace: ").append(snapshot.workspace()).append("\nConfiguration: ")
            .append(snapshot.configuration().isBlank() ? "(none)" : snapshot.configuration()).append("\nState: ").append(snapshot.lifecycle())
            .append("\nDetail: ").append(snapshot.detail()).append("\n");
        DebugConsole.Snapshot console = sessions.console(workspace);
        output.append("Console: ").append(console.state()).append(" — ").append(console.detail()).append("\n");
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
