package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class DebugSessionController {
    private record VsCodeLaunchReports(VsCodeLaunchConfigurationImporter.Report folder,
                                       VsCodeLaunchConfigurationImporter.Report workspace) {
        VsCodeLaunchReports {
            folder = folder == null ? new VsCodeLaunchConfigurationImporter.Report(null, Map.of(), List.of(), List.of(), "") : folder;
            workspace = workspace == null ? new VsCodeLaunchConfigurationImporter.Report(null, Map.of(), List.of(), List.of(), "") : workspace;
        }
    }

    record ExceptionBreakpointView(DebugSessionService.ExceptionFilter filter, boolean enabled, boolean adapterDefault) {
        ExceptionBreakpointView {
            if (filter == null) throw new IllegalArgumentException("exception breakpoint filter is required");
        }

        @Override public String toString() {
            return (enabled ? "enabled  " : "disabled ") + filter.label() + " (" + filter.id() + ")"
                + (adapterDefault ? "  [adapter default]" : "");
        }
    }

    private final Texteditor editor;
    private final DebugSessionService sessions;
    private final BreakpointStore breakpoints;
    private final ExceptionBreakpointStore exceptionBreakpoints;
    private final Map<Path, Boolean> openSourceOnStop = new ConcurrentHashMap<>();
    private Path lastWorkspace;
    private Path lastActiveFile;

    DebugSessionController(Texteditor editor) {
        this(editor, new DebugSessionService(), new BreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")),
            new ExceptionBreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")));
    }

    DebugSessionController(Texteditor editor, DebugSessionService sessions) {
        this(editor, sessions, new BreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")),
            new ExceptionBreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")));
    }

    DebugSessionController(Texteditor editor, DebugSessionService sessions, BreakpointStore breakpoints) {
        this(editor, sessions, breakpoints, new ExceptionBreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")));
    }

    DebugSessionController(Texteditor editor, DebugSessionService sessions, BreakpointStore breakpoints, ExceptionBreakpointStore exceptionBreakpoints) {
        this.editor = editor;
        this.sessions = sessions == null ? new DebugSessionService() : sessions;
        this.breakpoints = breakpoints == null ? new BreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")) : breakpoints;
        this.exceptionBreakpoints = exceptionBreakpoints == null
            ? new ExceptionBreakpointStore(Path.of(editor.configManager.getSessionDirectory(), "breakpoints")) : exceptionBreakpoints;
    }

    String handle(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "help".equalsIgnoreCase(trimmed)) {
            return "Usage: :debug status|configurations|vscode|select [name]|start [name]|stop|restart|continue|next|stepin|stepout|pause|goto [line]|breakpoint list|enable|disable|remove|condition|hit|log|clear-*|exception list|enable|disable|console [clear]|stack|variables|frame <id>|watch add|remove|list|clear";
        }
        int split = trimmed.indexOf(' ');
        String command = (split < 0 ? trimmed : trimmed.substring(0, split)).toLowerCase();
        String args = split < 0 ? "" : trimmed.substring(split + 1).trim();
        return switch (command) {
            case "status" -> status();
            case "configurations", "configs", "list" -> configurations();
            case "vscode", "launch-json", "launchjson" -> showVsCodeLaunchConfigurations();
            case "select", "configuration", "config" -> select(args);
            case "start", "launch", "attach" -> start(args);
            case "stop" -> stop();
            case "restart" -> restart(args);
            case "continue", "cont", "c" -> submitControl(DebugSessionService.Control.CONTINUE);
            case "next", "stepover", "step-over" -> submitControl(DebugSessionService.Control.NEXT);
            case "stepin", "step-in" -> submitControl(DebugSessionService.Control.STEP_IN);
            case "stepout", "step-out" -> submitControl(DebugSessionService.Control.STEP_OUT);
            case "pause" -> submitControl(DebugSessionService.Control.PAUSE);
            case "goto", "run-to-cursor", "runtocursor" -> runToCursor(args);
            case "breakpoint", "breakpoints", "bp" -> breakpoint(args);
            case "exception", "exceptions" -> exceptionBreakpoint(args);
            case "console", "output" -> console(args);
            case "stack", "frames", "variables", "inspect", "refresh" -> submitInspection();
            case "frame" -> selectFrame(args);
            case "watch", "watches" -> watch(args);
            default -> "Unknown :debug subcommand: " + command;
        };
    }

    void shutdown() {
        Path workspace = workspace();
        sessions.stop(workspace);
        openSourceOnStop.remove(workspace);
    }

    List<String> configurationNamesForPanel() {
        DebugAdapterRegistry.Validation validation = validation();
        return validation == null || !validation.valid() ? List.of() : validation.configurations().keySet().stream().sorted().toList();
    }

    DebugSessionService.Snapshot snapshotForPanel() { return sessions.snapshot(workspace()); }

    DebugInspection.Snapshot inspectionForPanel() { return sessions.inspection(workspace()); }

    DebugConsole.Snapshot consoleForPanel() { return sessions.console(workspace()); }

    String selectForPanel(String name) { return select(name); }

    String startForPanel() { return start(""); }

    String startTest(Path root, TestService.TestCase test, String configuration) {
        if (root == null || test == null || configuration == null || configuration.isBlank()) return "Test debug configuration is required";
        if (test.file() == null) return "Test source location is unavailable";
        Path workspace = root.toAbsolutePath().normalize();
        Path file = test.file().toAbsolutePath().normalize();
        if (!file.startsWith(workspace)) return "Test source escapes the selected workspace";
        return submitStart(workspace, new DebugAdapterRegistry.LaunchContext(file, test.id(), file), configuration, false, "test debug");
    }

    String stopForPanel() { return stop(); }

    String restartForPanel() { return restart(""); }

    String continueForPanel() { return submitControl(DebugSessionService.Control.CONTINUE); }
    String nextForPanel() { return submitControl(DebugSessionService.Control.NEXT); }
    String stepInForPanel() { return submitControl(DebugSessionService.Control.STEP_IN); }
    String stepOutForPanel() { return submitControl(DebugSessionService.Control.STEP_OUT); }
    String pauseForPanel() { return submitControl(DebugSessionService.Control.PAUSE); }
    String runToCursorForPanel() { return runToCursor(""); }

    List<BreakpointStore.Breakpoint> breakpointsForPanel() {
        try {
            List<BreakpointStore.Breakpoint> result = new ArrayList<>();
            for (List<BreakpointStore.Breakpoint> values : breakpoints.sources(workspace()).values()) result.addAll(values);
            result.sort(Comparator.comparing((BreakpointStore.Breakpoint value) -> value.source().toString()).thenComparingInt(BreakpointStore.Breakpoint::line));
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException error) {
            return List.of();
        }
    }

    String configureBreakpointForPanel(BreakpointStore.Breakpoint breakpoint, boolean enabled, String condition, String hitCondition, String logMessage) {
        if (breakpoint == null) return "Select a source breakpoint.";
        return configureBreakpoint(breakpoint.source(), breakpoint.line(), enabled, condition, hitCondition, logMessage);
    }

    List<ExceptionBreakpointView> exceptionBreakpointsForPanel() {
        Path workspace = workspace();
        try {
            Map<String, ExceptionBreakpointStore.Setting> settings = exceptionBreakpoints.settings(workspace);
            List<ExceptionBreakpointView> result = new ArrayList<>();
            for (DebugSessionService.ExceptionFilter filter : sessions.exceptionFilters(workspace)) {
                ExceptionBreakpointStore.Setting setting = settings.get(filter.id());
                result.add(new ExceptionBreakpointView(filter, setting == null ? filter.defaultEnabled() : setting.enabled(), setting == null));
            }
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException error) {
            return List.of();
        }
    }

    String configureExceptionBreakpointForPanel(ExceptionBreakpointView breakpoint, boolean enabled) {
        if (breakpoint == null) return "Select an exception breakpoint.";
        return configureExceptionBreakpoint(breakpoint.filter(), enabled);
    }

    String removeBreakpointForPanel(BreakpointStore.Breakpoint breakpoint) {
        if (breakpoint == null) return "Select a source breakpoint.";
        try {
            if (!breakpoints.remove(workspace(), breakpoint.source(), breakpoint.line())) return "Source breakpoint is unavailable.";
            refreshBreakpointMarkers();
            if (sessions.snapshot(workspace()).lifecycle() == DebugSessionService.Lifecycle.RUNNING) synchronizeBreakpoints(workspace());
            return "Source breakpoint removed.";
        } catch (IOException | IllegalArgumentException error) {
            return "Unable to remove source breakpoint: " + error.getMessage();
        }
    }

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

    String openFrameSourceForPanel(DebugInspection.Frame frame) {
        Path source = DebugFrameLocation.sourcePath(frame);
        if (source == null) return "The selected debug frame has no local source file.";
        try {
            editor.recordJumpPosition();
            editor.openFile(source.toFile());
            String result = editor.gotoLine(DebugFrameLocation.line(frame));
            if (result.startsWith("Error") || result.startsWith("Invalid")) return result;
            int lineIndex = Math.max(0, DebugFrameLocation.line(frame) - 1);
            int lineStart = editor.writingArea.getLineStartOffset(lineIndex);
            int target = Math.min(lineStart + DebugFrameLocation.column(frame) - 1, editor.writingArea.getText().length());
            editor.writingArea.setCaretPosition(target);
            return "Opened debug frame source.";
        } catch (Exception error) {
            String detail = error.getMessage();
            return "Could not open debug frame source: " + (detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail);
        }
    }

    private String configurations() {
        Path workspace = workspace();
        DebugAdapterRegistry.Validation validation = validation();
        VsCodeLaunchReports vsCode = vsCodeLaunchReports(workspace, baseValidation(workspace));
        java.util.Set<String> remote = new java.util.LinkedHashSet<>();
        if (validation != null) for (DebugAdapterRegistry.Adapter adapter : validation.registry().adapters().values()) {
            if (adapter.transport() != DebugAdapterRegistry.Transport.STDIO) continue;
            try {
                List<String> command = new ArrayList<>();
                command.add(adapter.command());
                command.addAll(adapter.args());
                if (remoteDebugAdapterAvailable(workspace, command)) remote.add(adapter.id());
            } catch (IOException ignored) { }
        }
        DebugAdapterDetector.WorkspaceReport report = new DebugAdapterDetector(null).detect(workspace, validation,
            editor.configManager.getDebugFeatureSettingsForWorkspace(workspace), remote);
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
        appendVsCodeLaunchReports(output, vsCode);
        editor.showScratchBuffer("[debug configurations]", output.toString());
        return "Showing debug configurations";
    }

    private String showVsCodeLaunchConfigurations() {
        Path workspace = workspace();
        VsCodeLaunchReports reports = vsCodeLaunchReports(workspace, baseValidation(workspace));
        StringBuilder output = new StringBuilder("VS Code launch compatibility\n\nWorkspace: ").append(workspace).append('\n');
        appendVsCodeLaunchReports(output, reports);
        editor.showScratchBuffer("[VS Code launch.json]", output.toString());
        return reports.folder().present() || reports.workspace().present()
            ? "Showing VS Code launch compatibility" : "No .vscode/launch.json or imported workspace launch configuration was found for this workspace.";
    }

    private static void appendVsCodeLaunchReports(StringBuilder output, VsCodeLaunchReports reports) {
        if (reports == null) return;
        appendVsCodeLaunchReport(output, "VS Code launch.json", reports.folder());
        appendVsCodeLaunchReport(output, "Imported VS Code workspace launch", reports.workspace());
    }

    private static void appendVsCodeLaunchReport(StringBuilder output, String title, VsCodeLaunchConfigurationImporter.Report report) {
        output.append("\n").append(title).append(":\n");
        if (report == null || !report.present()) {
            output.append("  (not found; no VS Code profiles were imported)\n");
            return;
        }
        output.append("  ").append(report.source()).append("\n");
        if (!report.failure().isEmpty()) {
            output.append("  Import unavailable: ").append(report.failure()).append("\n");
            return;
        }
        if (report.accepted().isEmpty()) output.append("  Accepted: (none)\n");
        else {
            output.append("  Accepted for this session only:\n");
            for (String name : report.accepted()) output.append("    ").append(name).append("\n");
            output.append("  Select with :debug select <name>; start remains explicit.\n");
        }
        if (!report.skipped().isEmpty()) {
            output.append("  Skipped:\n");
            for (String detail : report.skipped()) output.append("    ").append(detail).append("\n");
        }
    }

    private String select(String requested) {
        Path workspace = workspace();
        DebugAdapterRegistry.Validation validation = validation();
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
        return submitStart(workspace, new DebugAdapterRegistry.LaunchContext(activeFile, "", null), requested, restart, restart ? "restart" : "start");
    }

    private String submitStart(Path workspace, DebugAdapterRegistry.LaunchContext context, String requested, boolean restart, String operation) {
        if (workspace != null) lastWorkspace = workspace.toAbsolutePath().normalize();
        if (context != null && context.activeFile() != null) lastActiveFile = context.activeFile();
        String name = requested == null ? "" : requested.trim();
        DebugAdapterRegistry.Validation configuration = validation(workspace);
        DebugFeatureSettings features = editor.configManager.getDebugFeatureSettingsForWorkspace(workspace);
        openSourceOnStop.put(workspace.toAbsolutePath().normalize(), editor.configManager.getDebugOpenSourceOnStopForWorkspace(workspace));
        int jobId = editor.asyncJobService.submit("debug " + operation, token -> sessions.start(workspace, context,
            configuration, features, name,
            Duration.ofMillis(Math.max(1, editor.configManager.getProcessTimeoutMs())), this::startTransport, breakpoints, exceptionBreakpoints,
            plan -> editor.jobQuickfixController.runDebugPreLaunchTask(plan, token)), (job, result, error) -> {
                if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
                    editor.showMessage("Debug " + operation + " cancelled.");
                    return;
                }
                if (error != null || result == null) {
                    editor.showMessage("Debug start failed: " + (error == null ? job.getErrorMessage() : error.getMessage()));
                    return;
                }
                refreshBreakpointMarkers();
                if (result.succeeded() && sessions.inspection(workspace).paused()) {
                    scheduleStoppedInspection(workspace);
                }
                if (editor.toolWindowHost != null && editor.toolWindowHost.isSelected(ToolWindowHost.Tab.DEBUG)) {
                    editor.toolWindowHost.refresh(ToolWindowHost.Tab.DEBUG);
                } else {
                    showStatus(workspace);
                }
                editor.showMessage(result.succeeded() ? result.snapshot().detail() : result.snapshot().detail() + diagnosticSuffix(result.snapshot()));
            });
        return "Explicit debug " + operation + " requested (job " + jobId + ").";
    }

    private DebugSessionService.Connection startTransport(DebugAdapterRegistry.Plan plan, DebugFeatureSettings features,
        DebugAdapterTransport.Listener listener) throws IOException {
        DebugAdapterTransport.Listener combinedListener = new DebugAdapterTransport.Listener() {
            @Override public void onEvent(DebugAdapterTransport.Event event) {
                listener.onEvent(event);
                if (event != null && "stopped".equals(event.event())) scheduleStoppedInspection(plan.workspace());
            }

            @Override public void onDiagnostic(DebugAdapterTransport.Diagnostic diagnostic) {
                listener.onDiagnostic(diagnostic);
            }
        };
        List<String> adapterCommand = new ArrayList<>();
        adapterCommand.add(plan.adapter().command());
        adapterCommand.addAll(plan.adapter().args());
        RemoteDebugEndpoint remote = plan.adapter().transport() != DebugAdapterRegistry.Transport.STDIO ? null
            : remoteDebugAdapterEndpoint(plan.workspace(), adapterCommand);
        DebugAdapterTransport transport = remote == null
            ? DebugAdapterTransport.start(plan, features, combinedListener, new DiagnosticLog(editor.errorReporter.getLogPath()))
            : DebugAdapterTransport.startRemote(plan, remote.command(), features, combinedListener, new DiagnosticLog(editor.errorReporter.getLogPath()));
        return new DebugSessionService.Connection() {
            @Override public DebugAdapterTransport.Response request(String command, Map<String, Object> arguments, Duration timeout)
                throws IOException, java.util.concurrent.TimeoutException, InterruptedException { return transport.request(command, arguments, timeout); }
            @Override public DebugAdapterTransport.State state() { return transport.state(); }
            @Override public String adapterPath(Path localPath) {
                return remote == null ? DebugSessionService.Connection.super.adapterPath(localPath) : remote.remotePathFor(localPath);
            }
            @Override public Path localPath(String adapterPath) { return remote == null ? null : remote.localPathFor(adapterPath); }
            @Override public void close() { transport.close(); }
        };
    }

    private void scheduleStoppedInspection(Path workspace) {
        if (workspace == null || !opensSourceOnStop(workspace)) return;
        Duration timeout = Duration.ofMillis(Math.max(1, editor.configManager.getProcessTimeoutMs()));
        editor.asyncJobService.submit("debug stopped inspection", token -> sessions.refreshInspection(workspace, timeout), (job, result, error) -> {
            if (error != null || result == null || !result.succeeded() || !opensSourceOnStop(workspace)) {
                refreshDebugPanel();
                return;
            }
            DebugInspection.Frame frame = selectedStoppedFrame(sessions.inspection(workspace));
            if (frame != null) {
                String message = openFrameSourceForPanel(frame);
                if (!"Opened debug frame source.".equals(message)) editor.showMessage(message);
            }
            refreshDebugPanel();
        });
    }

    static DebugInspection.Frame selectedStoppedFrame(DebugInspection.Snapshot snapshot) {
        if (snapshot == null || !snapshot.paused()) return null;
        for (DebugInspection.Frame frame : snapshot.frames()) {
            if (frame.id() == snapshot.frameId()) return frame;
        }
        return snapshot.frames().isEmpty() ? null : snapshot.frames().getFirst();
    }

    private void refreshDebugPanel() {
        if (editor.toolWindowHost != null && editor.toolWindowHost.isSelected(ToolWindowHost.Tab.DEBUG)) {
            editor.toolWindowHost.refresh(ToolWindowHost.Tab.DEBUG);
        }
    }

    private DebugAdapterRegistry.Validation validation() {
        return validation(workspace());
    }

    private DebugAdapterRegistry.Validation validation(Path workspace) {
        DebugAdapterRegistry.Validation base = baseValidation(workspace);
        VsCodeLaunchReports imported = vsCodeLaunchReports(workspace, base);
        Map<String, DebugAdapterRegistry.Configuration> configurations = new LinkedHashMap<>(imported.folder().configurations());
        configurations.putAll(imported.workspace().configurations());
        return DebugAdapterRegistry.withExternalConfigurations(base, configurations);
    }

    private DebugAdapterRegistry.Validation baseValidation(Path workspace) {
        return ExtensionDebugAdapterSupport.effective(BuiltInDebugAdapterSupport.effective(editor.configManager.getDebugConfigurationForWorkspace(workspace)),
            editor.extensionRegistry);
    }

    private VsCodeLaunchReports vsCodeLaunchReports(Path workspace, DebugAdapterRegistry.Validation base) {
        Map<String, String> taskNames = vsCodeTaskNames(workspace);
        VsCodeLaunchConfigurationImporter.Report folder = VsCodeLaunchConfigurationImporter.read(workspace, base, taskNames);
        DebugAdapterRegistry.Validation withFolder = DebugAdapterRegistry.withExternalConfigurations(base, folder.configurations());
        return new VsCodeLaunchReports(folder, workspaceLaunchReport(workspace, withFolder, taskNames));
    }

    private VsCodeLaunchConfigurationImporter.Report workspaceLaunchReport(Path workspace, DebugAdapterRegistry.Validation base,
                                                                             Map<String, String> taskNames) {
        if (editor.workspaceController == null || workspace == null
            || !editor.workspaceController.roots().contains(workspace.toAbsolutePath().normalize())) {
            return new VsCodeLaunchConfigurationImporter.Report(null, Map.of(), List.of(), List.of(), "");
        }
        WorkspaceManifest.ImportedConfiguration manifest = editor.workspaceController.manifestConfiguration();
        if (!manifest.present() || !manifest.usable() || !manifest.hasLaunch()) {
            return manifest.present() && !manifest.usable()
                ? new VsCodeLaunchConfigurationImporter.Report(manifest.source(), Map.of(), List.of(), List.of(), manifest.failure())
                : new VsCodeLaunchConfigurationImporter.Report(null, Map.of(), List.of(), List.of(), "");
        }
        return VsCodeLaunchConfigurationImporter.readWorkspaceConfiguration(manifest.source(), manifest.launch(), base, taskNames);
    }

    private Map<String, String> vsCodeTaskNames(Path workspace) {
        if (workspace == null) return Map.of();
        TaskService.TaskLoadResult local = editor.taskService.loadWorkspaceTasks(workspace.toFile());
        return editor.jobQuickfixController.acceptedVsCodeTaskNames(workspace.toFile(), local);
    }

    private boolean remoteDebugAdapterAvailable(Path workspace, List<String> command) throws IOException {
        if (editor.remoteWorkspaceController != null && editor.remoteWorkspaceController.debugAdapterEndpoint(workspace, command) != null) return true;
        return editor.devContainerController != null && editor.devContainerController.supportsDebugAdapter(workspace, command);
    }

    private RemoteDebugEndpoint remoteDebugAdapterEndpoint(Path workspace, List<String> command) throws IOException {
        if (editor.remoteWorkspaceController != null) {
            RemoteDebugEndpoint endpoint = editor.remoteWorkspaceController.debugAdapterEndpoint(workspace, command);
            if (endpoint != null) return endpoint;
        }
        if (editor.devContainerController != null && editor.devContainerController.hasConfiguration(workspace)) {
            return editor.devContainerController.debugAdapterEndpoint(workspace, command);
        }
        return null;
    }

    private String stop() {
        Path workspace = workspace();
        DebugSessionService.Result result = sessions.stop(workspace);
        openSourceOnStop.remove(workspace);
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

    private String submitControl(DebugSessionService.Control control) {
        Path workspace = workspace();
        String operation = control == null ? "control" : control.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        int jobId = editor.asyncJobService.submit("debug " + operation, token -> sessions.control(workspace, control,
            Duration.ofMillis(Math.max(1, editor.configManager.getProcessTimeoutMs()))), (job, result, error) -> {
                if (editor.toolWindowHost != null && editor.toolWindowHost.isSelected(ToolWindowHost.Tab.DEBUG)) {
                    editor.toolWindowHost.refresh(ToolWindowHost.Tab.DEBUG);
                } else {
                    showStatus(workspace);
                }
                if (error != null || result == null || !result.succeeded()) {
                    editor.showMessage("Debug " + operation + " failed; inspect :debug status.");
                } else editor.showMessage(result.snapshot().detail());
            });
        return "Debug " + operation + " requested (job " + jobId + ").";
    }

    private String runToCursor(String argument) {
        Path source = activeFile();
        if (source == null) return "Run to cursor requires an active file-backed editor buffer.";
        int line;
        String value = argument == null ? "" : argument.trim();
        try {
            line = value.isBlank() ? editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition()) + 1 : Integer.parseInt(value);
        } catch (Exception error) {
            return "Usage: :debug goto [positive-line]";
        }
        if (line < 1) return "Usage: :debug goto [positive-line]";
        int column = 1;
        if (value.isBlank()) {
            try {
                int lineStart = editor.writingArea.getLineStartOffset(line - 1);
                column = editor.writingArea.getCaretPosition() - lineStart + 1;
            } catch (Exception error) {
                return "Run to cursor could not resolve the active caret location.";
            }
        }
        Path workspace = workspace();
        int requestedLine = line;
        int requestedColumn = column;
        int jobId = editor.asyncJobService.submit("debug run to cursor", token -> sessions.runToCursor(workspace, source, requestedLine, requestedColumn,
            Duration.ofMillis(Math.max(1, editor.configManager.getProcessTimeoutMs()))), (job, result, error) -> {
                refreshDebugPanel();
                if (error != null || result == null || !result.succeeded()) editor.showMessage("Debug run to cursor failed; inspect :debug status.");
                else editor.showMessage(result.snapshot().detail());
            });
        return "Debug run to cursor requested (job " + jobId + ").";
    }

    private String breakpoint(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "list".equalsIgnoreCase(trimmed)) {
            showBreakpoints();
            return "Showing source breakpoints.";
        }
        int split = trimmed.indexOf(' ');
        String command = (split < 0 ? trimmed : trimmed.substring(0, split)).toLowerCase(java.util.Locale.ROOT);
        String rest = split < 0 ? "" : trimmed.substring(split + 1).trim();
        if ("enable".equals(command) || "disable".equals(command) || "remove".equals(command) || "delete".equals(command)
            || "clear-condition".equals(command) || "clear-hit".equals(command) || "clear-log".equals(command)) {
            BreakpointStore.Breakpoint breakpoint = breakpointAtActiveFile(rest);
            if (breakpoint == null) return "Usage: :debug breakpoint " + command + " <positive-line>";
            if ("remove".equals(command) || "delete".equals(command)) return removeBreakpointForPanel(breakpoint);
            String condition = "clear-condition".equals(command) ? "" : breakpoint.condition();
            String hit = "clear-hit".equals(command) ? "" : breakpoint.hitCondition();
            String log = "clear-log".equals(command) ? "" : breakpoint.logMessage();
            return configureBreakpoint(breakpoint.source(), breakpoint.line(), "enable".equals(command) || (!"disable".equals(command) && breakpoint.enabled()), condition, hit, log);
        }
        if ("condition".equals(command) || "hit".equals(command) || "log".equals(command)) {
            int valueStart = rest.indexOf(' ');
            if (valueStart < 1) return "Usage: :debug breakpoint " + command + " <positive-line> <value>";
            BreakpointStore.Breakpoint breakpoint = breakpointAtActiveFile(rest.substring(0, valueStart));
            String value = rest.substring(valueStart + 1).trim();
            if (breakpoint == null || value.isEmpty()) return "Usage: :debug breakpoint " + command + " <positive-line> <value>";
            String condition = "condition".equals(command) ? value : breakpoint.condition();
            String hit = "hit".equals(command) ? value : breakpoint.hitCondition();
            String log = "log".equals(command) ? value : breakpoint.logMessage();
            return configureBreakpoint(breakpoint.source(), breakpoint.line(), breakpoint.enabled(), condition, hit, log);
        }
        return "Usage: :debug breakpoint list|enable <line>|disable <line>|remove <line>|condition <line> <value>|hit <line> <value>|log <line> <value>|clear-condition|clear-hit|clear-log <line>";
    }

    private String exceptionBreakpoint(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "list".equalsIgnoreCase(trimmed)) {
            showExceptionBreakpoints();
            return "Showing exception breakpoints.";
        }
        int split = trimmed.indexOf(' ');
        String command = (split < 0 ? trimmed : trimmed.substring(0, split)).toLowerCase(java.util.Locale.ROOT);
        String filterId = split < 0 ? "" : trimmed.substring(split + 1).trim();
        if (!"enable".equals(command) && !"disable".equals(command)) return "Usage: :debug exception list|enable <filter>|disable <filter>";
        DebugSessionService.ExceptionFilter filter = sessions.exceptionFilters(workspace()).stream()
            .filter(value -> value.id().equals(filterId)).findFirst().orElse(null);
        if (filter == null) return "Exception breakpoint filter is unavailable; start a compatible debug session and use :debug exception list.";
        return configureExceptionBreakpoint(filter, "enable".equals(command));
    }

    private String configureExceptionBreakpoint(DebugSessionService.ExceptionFilter filter, boolean enabled) {
        if (filter == null) return "Select an exception breakpoint.";
        try {
            exceptionBreakpoints.configure(workspace(), filter.id(), enabled);
            if (sessions.snapshot(workspace()).lifecycle() == DebugSessionService.Lifecycle.RUNNING) synchronizeBreakpoints(workspace());
            return "Exception breakpoint '" + filter.label() + "' " + (enabled ? "enabled." : "disabled.");
        } catch (IOException | IllegalArgumentException error) {
            return "Unable to update exception breakpoint: " + error.getMessage();
        }
    }

    private void showExceptionBreakpoints() {
        Path workspace = workspace();
        StringBuilder text = new StringBuilder("Exception Breakpoints\n\n");
        List<DebugSessionService.ExceptionFilter> filters = sessions.exceptionFilters(workspace);
        if (filters.isEmpty()) {
            text.append("No exception breakpoint filters are available. Start a compatible debug session first.\n");
        } else {
            try {
                Map<String, ExceptionBreakpointStore.Setting> settings = exceptionBreakpoints.settings(workspace);
                for (DebugSessionService.ExceptionFilter filter : filters) {
                    ExceptionBreakpointStore.Setting setting = settings.get(filter.id());
                    boolean enabled = setting == null ? filter.defaultEnabled() : setting.enabled();
                    text.append(enabled ? "enabled  " : "disabled ").append(filter.id()).append("  ").append(filter.label());
                    if (setting == null) text.append("  (adapter default)");
                    text.append("\n");
                }
            } catch (IOException | IllegalArgumentException error) {
                text.append("Unable to load exception breakpoint settings: ").append(error.getMessage()).append("\n");
            }
        }
        text.append("\nActions: :debug exception enable <filter> | :debug exception disable <filter>\n");
        editor.showScratchBuffer("[debug exception breakpoints]", text.toString());
    }

    private BreakpointStore.Breakpoint breakpointAtActiveFile(String value) {
        int line;
        try { line = Integer.parseInt(value == null ? "" : value.trim()); }
        catch (NumberFormatException error) { return null; }
        if (line < 1) return null;
        Path source = activeFile();
        if (source == null) return null;
        try {
            List<BreakpointStore.Breakpoint> values = breakpoints.sources(workspace()).get(source.toAbsolutePath().normalize());
            if (values == null) return null;
            return values.stream().filter(breakpoint -> breakpoint.line() == line || breakpoint.displayLine() == line).findFirst().orElse(null);
        } catch (IOException | IllegalArgumentException error) {
            return null;
        }
    }

    private String configureBreakpoint(Path source, int line, boolean enabled, String condition, String hitCondition, String logMessage) {
        try {
            breakpoints.configure(workspace(), source, line, enabled, condition, hitCondition, logMessage);
            refreshBreakpointMarkers();
            if (sessions.snapshot(workspace()).lifecycle() == DebugSessionService.Lifecycle.RUNNING) synchronizeBreakpoints(workspace());
            return "Source breakpoint updated.";
        } catch (IOException | IllegalArgumentException error) {
            return "Unable to update source breakpoint: " + error.getMessage();
        }
    }

    private void showBreakpoints() {
        StringBuilder text = new StringBuilder("Source Breakpoints\n\n");
        List<BreakpointStore.Breakpoint> values = breakpointsForPanel();
        if (values.isEmpty()) text.append("(none)\n");
        for (BreakpointStore.Breakpoint breakpoint : values) {
            text.append(breakpoint.enabled() ? "enabled " : "disabled ").append(breakpoint.state()).append("  ")
                .append(breakpoint.source()).append(":").append(breakpoint.line());
            if (!breakpoint.condition().isBlank()) text.append("  condition=").append(breakpoint.condition());
            if (!breakpoint.hitCondition().isBlank()) text.append("  hit=").append(breakpoint.hitCondition());
            if (!breakpoint.logMessage().isBlank()) text.append("  log=").append(breakpoint.logMessage());
            if (!breakpoint.message().isBlank()) text.append("  ").append(breakpoint.message());
            text.append("\n");
        }
        editor.showScratchBuffer("[debug breakpoints]", text.toString());
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
        editor.asyncJobService.submit("debug breakpoints", token -> sessions.synchronizeBreakpoints(workspace, breakpoints, exceptionBreakpoints,
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

    private Path activeFile() {
        workspace();
        return lastActiveFile;
    }

    private boolean opensSourceOnStop(Path workspace) {
        if (workspace == null) return false;
        Boolean configured = openSourceOnStop.get(workspace.toAbsolutePath().normalize());
        return configured == null ? editor.configManager.getDebugOpenSourceOnStopForWorkspace(workspace) : configured;
    }
    private static String diagnosticSuffix(DebugSessionService.Snapshot snapshot) {
        return snapshot.diagnostics().isEmpty() ? "" : " Diagnostics: " + String.join("; ", snapshot.diagnostics());
    }
}
