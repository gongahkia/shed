package shed;

import shed.api.RemoteCommandRequest;
import shed.api.RemoteCommandResult;
import javax.swing.text.BadLocationException;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

final class JobQuickfixController {
    private record VsCodeTaskReports(VsCodeTaskImporter.Report folder, VsCodeTaskImporter.Report workspace) {
        VsCodeTaskReports {
            folder = folder == null ? new VsCodeTaskImporter.Report(null, Map.of(), Map.of(), List.of(), List.of(), "") : folder;
            workspace = workspace == null ? new VsCodeTaskImporter.Report(null, Map.of(), Map.of(), List.of(), List.of(), "") : workspace;
        }
    }

    private final Texteditor editor;
    private final Map<Integer, String> taskOutputs = new LinkedHashMap<>();

    JobQuickfixController(Texteditor editor) {
        this.editor = editor;
    }

    String taskOutputForPanel(int jobId) {
        return taskOutputs.getOrDefault(jobId, "");
    }

    public String deleteLineRange(int startLine, int endLine) {
        try {
            int safeStart = Math.max(1, startLine);
            int safeEnd = Math.max(safeStart, endLine);
            int maxLines = editor.writingArea.getLineCount();
            if (safeStart > maxLines) {
                return "Invalid range";
            }
            safeEnd = Math.min(safeEnd, maxLines);

            int startOffset = editor.writingArea.getLineStartOffset(safeStart - 1);
            int endOffset = editor.writingArea.getLineEndOffset(safeEnd - 1);
            if (endOffset < editor.writingArea.getText().length()) {
                endOffset = Math.min(endOffset + 1, editor.writingArea.getText().length());
            }
            editor.writingArea.replaceRange("", startOffset, endOffset);
            editor.writingArea.setCaretPosition(Math.min(startOffset, editor.writingArea.getText().length()));
            editor.markModified();
            int deleted = safeEnd - safeStart + 1;
            return deleted + " line" + (deleted == 1 ? "" : "s") + " deleted";
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }


    public String substituteRange(String pattern, String replacement, int startLine, int endLine, boolean replaceAll) {
        try {
            int maxLines = editor.writingArea.getLineCount();
            int safeStart = Math.max(1, Math.min(startLine, maxLines));
            int safeEnd = Math.max(safeStart, Math.min(endLine, maxLines));
            int startOffset = editor.writingArea.getLineStartOffset(safeStart - 1);
            int endOffset = editor.writingArea.getLineEndOffset(safeEnd - 1);
            String rangeText = editor.writingArea.getText().substring(startOffset, endOffset);
            ReplacementResult result = editor.replaceLiteral(rangeText, pattern, replacement, replaceAll);
            if (result.matchCount == 0) {
                return "Pattern not found: " + pattern;
            }
            editor.writingArea.replaceRange(result.updatedText, startOffset, endOffset);
            editor.writingArea.setCaretPosition(Math.min(startOffset + Math.max(0, result.firstMatchOffset), editor.writingArea.getText().length()));
            editor.markModified();
            return "Replaced " + result.matchCount + " occurrence" + (result.matchCount == 1 ? "" : "s");
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }


    public String runShellCommand(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            return "Error: :! requires command";
        }
        String validationError = validateShellCommand(trimmed);
        if (validationError != null) {
            return validationError;
        }
        int jobId = editor.asyncJobService.submit(
            "shell: " + trimmed,
            token -> runShellProcess(trimmed, null, token),
            this::handleShellJobCompletion
        );
        return "Shell job " + jobId + " started";
    }


    public String runDropCommand(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            return "Usage: :drop <command>";
        }
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return "Drop runner requires a file-backed buffer";
        }
        String filePath = buffer.getFilePath();
        String quotedPath = "'" + filePath.replace("'", "'\"'\"'") + "'";
        String expanded = trimmed.contains("%") ? trimmed.replace("%", quotedPath) : trimmed + " " + quotedPath;
        String validationError = validateShellCommand(expanded);
        if (validationError != null) {
            return validationError;
        }
        int jobId = editor.asyncJobService.submit(
            "drop: " + expanded,
            token -> runShellProcess(expanded, null, token),
            this::handleShellJobCompletion
        );
        return "Drop job " + jobId + " started";
    }


    public String handleTaskCommand(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        File projectRoot = resolveTaskProjectRoot();
        List<String> args = editor.parseQuotedArguments(trimmed);
        if (!args.isEmpty() && "cmake".equalsIgnoreCase(args.getFirst())) {
            return runCmakePreset(args, projectRoot, Map.of());
        }
        TaskService.TaskLoadResult loaded = editor.taskService.loadWorkspaceTasks(projectRoot);
        if (!loaded.isValid()) {
            return showTaskConfigurationDiagnostics(projectRoot, loaded.diagnostics());
        }
        VsCodeTaskReports vsCodeTasks = vsCodeTaskReports(projectRoot, loaded.tasks());
        Map<String, TaskService.WorkspaceTask> tasks = effectiveWorkspaceTasks(loaded.tasks(), vsCodeTasks);
        if (trimmed.isEmpty() || "list".equalsIgnoreCase(trimmed)) {
            return showWorkspaceTasks(projectRoot, tasks, vsCodeTasks);
        }
        if (args.isEmpty()) {
            return showWorkspaceTasks(projectRoot, tasks, vsCodeTasks);
        }

        String sub = args.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                return showWorkspaceTasks(projectRoot, tasks, vsCodeTasks);
            case "vscode":
            case "tasks-json":
            case "tasksjson":
                return showVsCodeTasks(projectRoot, vsCodeTasks);
            case "add":
                if (args.size() < 3) {
                    return "Usage: :task add <name> <command>";
                }
                StringBuilder commandBuilder = new StringBuilder();
                for (int i = 2; i < args.size(); i++) {
                    if (i > 2) {
                        commandBuilder.append(" ");
                    }
                    commandBuilder.append(args.get(i));
                }
                return saveProjectTask(projectRoot, args.get(1), commandBuilder.toString());
            case "remove":
            case "rm":
            case "delete":
                if (args.size() < 2) {
                    return "Usage: :task remove <name>";
                }
                return removeProjectTask(projectRoot, args.get(1));
            case "run":
                if (args.size() < 2) {
                    return "Usage: :task run <name>";
                }
                return runLoadedTask(args.get(1), projectRoot, tasks, false);
            case "dry-run":
            case "dryrun":
                if (args.size() < 2) {
                    return "Usage: :task dry-run <name>";
                }
                return runLoadedTask(args.get(1), projectRoot, tasks, true);
            case "remote":
            case "run-remote":
                if (args.size() < 3) {
                    return "Usage: :task remote <connection-id> <name>";
                }
                return runRemoteTask(args.get(1), args.get(2), projectRoot, tasks, false);
            case "remote-dry-run":
                if (args.size() < 3) {
                    return "Usage: :task remote-dry-run <connection-id> <name>";
                }
                return runRemoteTask(args.get(1), args.get(2), projectRoot, tasks, true);
            case "container":
            case "devcontainer":
                if (args.size() < 2) {
                    return "Usage: :task container <name>";
                }
                return runContainerTask(args.get(1), projectRoot, tasks, false);
            case "container-dry-run":
            case "devcontainer-dry-run":
                if (args.size() < 2) {
                    return "Usage: :task container-dry-run <name>";
                }
                return runContainerTask(args.get(1), projectRoot, tasks, true);
            case "cancel":
                if (args.size() < 2) {
                    return "Usage: :task cancel <job-id>";
                }
                return cancelTaskJob(args.get(1));
            default:
                return "Usage: :task run <name> (use :task list)";
        }
    }


    File resolveTaskProjectRoot() {
        FileBuffer buffer = editor.getCurrentBuffer();
        File start = null;
        if (buffer != null && buffer.hasFilePath()) {
            start = new File(buffer.getFilePath());
        } else {
            Path activeWorkspace = editor.workspaceController == null ? null : editor.workspaceController.activeRoot();
            start = activeWorkspace == null ? new File(".") : activeWorkspace.toFile();
        }
        File root = detectTaskProjectRoot(start);
        if (root == null) {
            root = new File(".");
        }
        try {
            return root.getCanonicalFile();
        } catch (IOException e) {
            return root.getAbsoluteFile();
        }
    }


    File detectTaskProjectRoot(File file) {
        if (file == null) {
            return null;
        }
        File cursor = file.isDirectory() ? file : file.getParentFile();
        Path configuredRoot = null;
        if (cursor != null && editor.workspaceController != null) {
            configuredRoot = editor.workspaceController.rootFor(cursor.toPath());
        }
        File fallback = cursor;
        while (cursor != null) {
            if (new File(cursor, ".shedtasks").isFile() || new File(cursor, ".vscode/tasks.json").isFile()) {
                return cursor;
            }
            if (new File(cursor, ".git").exists()) {
                return cursor;
            }
            if (configuredRoot != null && configuredRoot.equals(cursor.toPath().toAbsolutePath().normalize())) {
                return cursor;
            }
            fallback = cursor;
            cursor = cursor.getParentFile();
        }
        return fallback;
    }


    String showProjectTasks(File projectRoot, Map<String, String> tasks) {
        Map<String, TaskService.WorkspaceTask> workspaceTasks = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : tasks.entrySet()) {
            workspaceTasks.put(entry.getKey(), TaskService.defaultWorkspaceTask(entry.getKey(), entry.getValue()));
        }
        return showWorkspaceTasks(projectRoot, workspaceTasks);
    }

    String showWorkspaceTasks(File projectRoot, Map<String, TaskService.WorkspaceTask> tasks) {
        return showWorkspaceTasks(projectRoot, tasks, null);
    }

    String showWorkspaceTasks(File projectRoot, Map<String, TaskService.WorkspaceTask> tasks, VsCodeTaskReports vsCodeTasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Workspace tasks\n\n");
        sb.append("root: ").append(projectRoot.getAbsolutePath()).append("\n");
        sb.append("file: ").append(editor.taskService.taskFile(projectRoot).getAbsolutePath()).append("\n\n");
        if (tasks.isEmpty()) {
            sb.append("No saved tasks.\n");
            sb.append("Use :task add <name> <command>\n");
            sb.append("Run only with :task run <name>.\n");
        } else {
            List<String> names = new ArrayList<>(tasks.keySet());
            Collections.sort(names);
            for (String name : names) {
                TaskService.WorkspaceTask task = tasks.get(name);
                sb.append("  ").append(name).append(" = ").append(task.command())
                    .append("  [").append(task.shell().configValue())
                    .append(", ").append(task.problemMatcher().configValue())
                    .append(", ").append(task.presentation().configValue()).append("]")
                    .append(task.sessionOnly() ? "  [VS Code session only]" : "").append("\n");
            }
        }
        appendVsCodeTaskReports(sb, vsCodeTasks);
        editor.showScratchBuffer("[tasks]", sb.toString());
        return "Showing tasks";
    }

    Map<String, TaskService.WorkspaceTask> effectiveWorkspaceTasks(File projectRoot, TaskService.TaskLoadResult loaded) {
        if (loaded == null || !loaded.isValid()) return Map.of();
        return effectiveWorkspaceTasks(loaded.tasks(), vsCodeTaskReports(projectRoot, loaded.tasks()));
    }

    Map<String, String> acceptedVsCodeTaskNames(File projectRoot, TaskService.TaskLoadResult loaded) {
        if (loaded == null || !loaded.isValid()) return Map.of();
        VsCodeTaskReports reports = vsCodeTaskReports(projectRoot, loaded.tasks());
        return VsCodeTaskImporter.uniqueTaskNamesByLabel(reports.folder(), reports.workspace());
    }

    private Map<String, TaskService.WorkspaceTask> effectiveWorkspaceTasks(Map<String, TaskService.WorkspaceTask> local,
                                                                            VsCodeTaskReports imported) {
        Map<String, TaskService.WorkspaceTask> result = new LinkedHashMap<>(local == null ? Map.of() : local);
        if (imported != null) {
            result.putAll(imported.folder().tasks());
            result.putAll(imported.workspace().tasks());
        }
        return Map.copyOf(result);
    }

    private VsCodeTaskReports vsCodeTaskReports(File projectRoot, Map<String, TaskService.WorkspaceTask> localTasks) {
        Path root = projectRoot == null ? null : projectRoot.toPath();
        Set<String> names = new LinkedHashSet<>(localTasks == null ? Set.of() : localTasks.keySet());
        VsCodeTaskImporter.Report folder = VsCodeTaskImporter.read(root, names);
        names.addAll(folder.tasks().keySet());
        return new VsCodeTaskReports(folder, workspaceTaskReport(root, names));
    }

    private VsCodeTaskImporter.Report workspaceTaskReport(Path root, Set<String> reservedNames) {
        if (editor.workspaceController == null || root == null || !editor.workspaceController.roots().contains(root.toAbsolutePath().normalize())) {
            return new VsCodeTaskImporter.Report(null, Map.of(), Map.of(), List.of(), List.of(), "");
        }
        WorkspaceManifest.ImportedConfiguration manifest = editor.workspaceController.manifestConfiguration();
        if (!manifest.present() || !manifest.usable() || !manifest.hasTasks()) {
            return manifest.present() && !manifest.usable()
                ? new VsCodeTaskImporter.Report(manifest.source(), Map.of(), Map.of(), List.of(), List.of(), manifest.failure())
                : new VsCodeTaskImporter.Report(null, Map.of(), Map.of(), List.of(), List.of(), "");
        }
        return VsCodeTaskImporter.readWorkspaceConfiguration(manifest.source(), manifest.tasks(), reservedNames);
    }

    private String showVsCodeTasks(File projectRoot, VsCodeTaskReports reports) {
        StringBuilder output = new StringBuilder("VS Code task compatibility\n\nWorkspace: ").append(projectRoot.getAbsolutePath()).append('\n');
        appendVsCodeTaskReports(output, reports);
        editor.showScratchBuffer("[VS Code tasks.json]", output.toString());
        return reports != null && (reports.folder().present() || reports.workspace().present())
            ? "Showing VS Code task compatibility" : "No .vscode/tasks.json or imported workspace tasks were found for this workspace.";
    }

    private static void appendVsCodeTaskReports(StringBuilder output, VsCodeTaskReports reports) {
        if (reports == null) return;
        appendVsCodeTaskReport(output, "VS Code tasks.json", reports.folder());
        appendVsCodeTaskReport(output, "Imported VS Code workspace tasks", reports.workspace());
    }

    private static void appendVsCodeTaskReport(StringBuilder output, String title, VsCodeTaskImporter.Report report) {
        if (report == null) return;
        output.append("\n").append(title).append(":\n");
        if (!report.present()) {
            output.append("  (not found; no VS Code tasks were imported)\n");
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
            output.append("  Run with :task run <name>; start remains explicit.\n");
        }
        if (!report.skipped().isEmpty()) {
            output.append("  Skipped:\n");
            for (String detail : report.skipped()) output.append("    ").append(detail).append("\n");
        }
    }


    String showTaskConfigurationDiagnostics(File projectRoot, List<String> diagnostics) {
        StringBuilder output = new StringBuilder("Task configuration invalid\n\n");
        output.append("file: ").append(editor.taskService.taskFile(projectRoot).getAbsolutePath()).append("\n\n");
        for (String diagnostic : diagnostics) output.append("- ").append(diagnostic).append("\n");
        editor.showScratchBuffer("[tasks]", output.toString());
        return "Task configuration invalid";
    }


    String saveProjectTask(File projectRoot, String name, String command) {
        String normalizedName = name == null ? "" : name.trim();
        String normalizedCommand = command == null ? "" : command.trim();
        if (normalizedName.isEmpty()) return "Task name required";
        if (!TaskService.isValidTaskName(normalizedName)) return "Invalid task name: " + normalizedName;
        if (normalizedCommand.isEmpty()) {
            return "Task command required";
        }
        TaskService.TaskLoadResult loaded = editor.taskService.loadWorkspaceTasks(projectRoot);
        if (!loaded.isValid()) return showTaskConfigurationDiagnostics(projectRoot, loaded.diagnostics());
        Map<String, TaskService.WorkspaceTask> tasks = new LinkedHashMap<>(loaded.tasks());
        try {
            tasks.put(normalizedName, TaskService.defaultWorkspaceTask(normalizedName, normalizedCommand));
            editor.taskService.saveWorkspaceTasks(projectRoot, tasks);
            return "Saved task '" + normalizedName + "'";
        } catch (IOException | IllegalArgumentException e) {
            return "Task save failed: " + e.getMessage();
        }
    }


    String removeProjectTask(File projectRoot, String name) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty()) {
            return "Task name required";
        }
        TaskService.TaskLoadResult loaded = editor.taskService.loadWorkspaceTasks(projectRoot);
        if (!loaded.isValid()) return showTaskConfigurationDiagnostics(projectRoot, loaded.diagnostics());
        Map<String, TaskService.WorkspaceTask> tasks = new LinkedHashMap<>(loaded.tasks());
        if (!tasks.containsKey(normalizedName)) {
            return "Task not found: " + normalizedName;
        }
        tasks.remove(normalizedName);
        try {
            editor.taskService.saveWorkspaceTasks(projectRoot, tasks);
            return "Removed task '" + normalizedName + "'";
        } catch (IOException e) {
            return "Task remove failed: " + e.getMessage();
        }
    }


    String runNamedTask(String taskName, File projectRoot, Map<String, String> tasks) {
        Map<String, TaskService.WorkspaceTask> workspaceTasks = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : tasks.entrySet()) {
            workspaceTasks.put(entry.getKey(), TaskService.defaultWorkspaceTask(entry.getKey(), entry.getValue()));
        }
        return runLoadedTask(taskName, projectRoot, workspaceTasks, false);
    }


    String runLoadedTask(String taskName, File projectRoot, Map<String, TaskService.WorkspaceTask> tasks, boolean dryRun) {
        String normalizedName = taskName == null ? "" : taskName.trim();
        if (normalizedName.isEmpty()) return "Task name required";
        TaskService.WorkspaceTask task = resolveTask(normalizedName, projectRoot, tasks);
        if (task == null) {
            return "Task not found: " + normalizedName + " (use :task list or :task add)";
        }
        File activeFile = activeTaskFile();
        List<TaskService.TaskExecutionPlan> plans;
        try {
            plans = taskExecutionPlans(normalizedName, task, tasks, projectRoot, activeFile);
        } catch (IOException | IllegalArgumentException error) {
            return "Task validation failed: " + error.getMessage();
        }
        String validationError = validateTaskPlans(plans);
        if (validationError != null) {
            return validationError;
        }
        TaskService.TaskExecutionPlan plan = plans.get(plans.size() - 1);
        RemoteWorkspaceSessionService.Connection remoteConnection = editor.remoteWorkspaceSessions == null
            ? null : editor.remoteWorkspaceSessions.connectionFor(projectRoot.toPath());
        if (remoteConnection != null) {
            return runActivatedRemoteTask(normalizedName, plans, activeFile, remoteConnection, dryRun);
        }
        DevContainerSessionService.Connection connection = editor.devContainerSessions == null
            ? null : editor.devContainerSessions.connectionFor(projectRoot.toPath());
        if (connection != null) {
            return runConnectedContainerTask(normalizedName, plans, activeFile, connection, dryRun, false);
        }
        if (dryRun) return showTaskDryRun(plans);
        int jobId = editor.asyncJobService.submit(
            "task " + normalizedName + ": " + taskPlanDescription(plans),
            token -> runLocalTaskPlans(plans, token),
            (snapshot, result, error) -> handleTaskJobCompletion(normalizedName, plan, snapshot, result, error)
        );
        return "Task job " + jobId + " started (" + normalizedName + ")";
    }

    DebugSessionService.PreLaunchResult runDebugPreLaunchTask(DebugAdapterRegistry.Plan debugPlan, AsyncJobService.JobToken token) {
        if (debugPlan == null || debugPlan.configuration() == null || debugPlan.configuration().prelaunchTask().isBlank()) {
            return new DebugSessionService.PreLaunchResult(true, List.of());
        }
        String taskName = debugPlan.configuration().prelaunchTask();
        File workspace = debugPlan.workspace().toFile();
        TaskService.TaskLoadResult loaded = editor.taskService.loadWorkspaceTasks(workspace);
        if (!loaded.isValid()) return new DebugSessionService.PreLaunchResult(false, loaded.diagnostics());
        Map<String, TaskService.WorkspaceTask> tasks = effectiveWorkspaceTasks(workspace, loaded);
        TaskService.WorkspaceTask task = tasks.get(taskName);
        if (task == null) {
            return new DebugSessionService.PreLaunchResult(false, List.of("Debug pre-launch task is not defined in "
                + editor.taskService.taskFile(workspace).getAbsolutePath() + ": " + taskName));
        }
        List<TaskService.TaskExecutionPlan> plans;
        File activeFile = debugPlan.program() == null ? null : debugPlan.program().toFile();
        try {
            plans = taskExecutionPlans(taskName, task, tasks, workspace, activeFile);
        } catch (IOException | IllegalArgumentException error) {
            return new DebugSessionService.PreLaunchResult(false, List.of("Debug pre-launch task validation failed: " + error.getMessage()));
        }
        String validationError = validateTaskPlans(plans);
        if (validationError != null) return new DebugSessionService.PreLaunchResult(false, List.of(validationError));
        TaskService.TaskExecutionPlan plan = plans.get(plans.size() - 1);
        CommandResult result;
        RemoteWorkspaceTaskTargets.Target remote = editor.remoteWorkspaceTaskTargets == null ? null
            : editor.remoteWorkspaceTaskTargets.targetForPath(debugPlan.workspace());
        if (remote != null && remote.workspace().executionRoot() != null
            && !remote.workspace().executionRoot().trim().equals(remote.localRoot().toString())) {
            try {
                String remoteValidation = validateRemoteTaskPlans(plans, remote.localRoot(), remote.workspace().executionRoot(), activeFile);
                if (remoteValidation != null) return new DebugSessionService.PreLaunchResult(false, List.of(remoteValidation));
                result = runTaskPlans(plans, token, stage -> remoteCommandResult(remote.workspace().execute(
                    editor.taskService.buildRemoteCommandRequest(stage, remote.localRoot(), remote.workspace().executionRoot(), activeFile)
                )));
            } catch (IOException | IllegalArgumentException error) {
                return new DebugSessionService.PreLaunchResult(false, List.of("Remote debug pre-launch task validation failed: " + error.getMessage()));
            } catch (Exception error) {
                String message = error.getMessage();
                return new DebugSessionService.PreLaunchResult(false, List.of("Remote debug pre-launch task failed: "
                    + (message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' '))));
            }
        } else if (editor.devContainerSessions != null && editor.devContainerSessions.connectionFor(debugPlan.workspace()) != null) {
            try {
                DevContainerSessionService.Connection connection = editor.devContainerSessions.connectionFor(debugPlan.workspace());
                String containerValidation = validateContainerTaskPlans(plans, connection.workspace(), connection.remoteWorkingDirectory(), activeFile);
                if (containerValidation != null) return new DebugSessionService.PreLaunchResult(false, List.of(containerValidation));
                result = runConnectedContainerTaskPlans(plans, activeFile, connection, token);
            } catch (Exception error) {
                String message = error.getMessage();
                return new DebugSessionService.PreLaunchResult(false, List.of("Dev Container debug pre-launch task failed: "
                    + (message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' '))));
            }
        } else if (editor.devContainerController != null && editor.devContainerController.hasConfiguration(debugPlan.workspace())) {
            try {
                String containerValidation = validateContainerTaskPlans(plans, workspace.toPath(), "/<remote-workspace>", activeFile);
                if (containerValidation != null) return new DebugSessionService.PreLaunchResult(false, List.of(containerValidation));
                result = runContainerTaskPlans(plans, workspace, activeFile, token);
            } catch (Exception error) {
                String message = error.getMessage();
                return new DebugSessionService.PreLaunchResult(false, List.of("Dev Container debug pre-launch task failed: "
                    + (message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' '))));
            }
        } else {
            try {
                result = runLocalTaskPlans(plans, token);
            } catch (Exception error) {
                String message = error.getMessage();
                return new DebugSessionService.PreLaunchResult(false, List.of("Debug pre-launch task failed: "
                    + (message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' '))));
            }
        }
        if (result.exitCode != 0) {
            String detail = taskOutput(result);
            return new DebugSessionService.PreLaunchResult(false, List.of("Debug pre-launch task '" + taskName + "' failed"
                + (detail.isBlank() ? "." : ": " + detail)));
        }
        return new DebugSessionService.PreLaunchResult(true, List.of("Debug pre-launch task '" + taskName + "' completed."));
    }

    private String runRemoteTask(String connectionId, String taskName, File projectRoot,
                                 Map<String, TaskService.WorkspaceTask> tasks, boolean dryRun) {
        String normalizedName = taskName == null ? "" : taskName.trim();
        if (normalizedName.isEmpty()) return "Task name required";
        TaskService.WorkspaceTask task = resolveTask(normalizedName, projectRoot, tasks);
        if (task == null) return "Task not found: " + normalizedName + " (use :task list or :task add)";
        File activeFile = activeTaskFile();
        List<TaskService.TaskExecutionPlan> plans;
        try {
            plans = taskExecutionPlans(normalizedName, task, tasks, projectRoot, activeFile);
        } catch (IOException | IllegalArgumentException error) {
            return "Task validation failed: " + error.getMessage();
        }
        String validationError = validateTaskPlans(plans);
        if (validationError != null) return validationError;
        RemoteWorkspaceTaskTargets.Target target = editor.remoteWorkspaceTaskTargets == null ? null
            : editor.remoteWorkspaceTaskTargets.targetFor(connectionId, projectRoot.toPath());
        if (target == null) return "Remote task requires a connected workspace containing: " + projectRoot.getAbsolutePath();
        String remoteValidation = validateRemoteTaskPlans(plans, target.localRoot(), target.workspace().executionRoot(), activeFile);
        if (remoteValidation != null) return remoteValidation;
        TaskService.TaskExecutionPlan plan = plans.get(plans.size() - 1);
        if (dryRun) return showRemoteTaskDryRun(target, plans, activeFile);
        int jobId = editor.asyncJobService.submit(
            "task remote " + target.id() + " " + normalizedName,
            token -> runTaskPlans(plans, token, stage -> remoteCommandResult(target.workspace().execute(
                editor.taskService.buildRemoteCommandRequest(stage, target.localRoot(), target.workspace().executionRoot(), activeFile)
            ))),
            (snapshot, result, error) -> handleTaskJobCompletion(normalizedName, plan, snapshot, result, error)
        );
        return "Remote task job " + jobId + " started (" + target.id() + ":" + normalizedName + ")";
    }

    private String runActivatedRemoteTask(String taskName, List<TaskService.TaskExecutionPlan> plans, File activeFile,
                                          RemoteWorkspaceSessionService.Connection connection, boolean dryRun) {
        RemoteWorkspaceTaskTargets.Target target = new RemoteWorkspaceTaskTargets.Target(connection.id(), connection.workspace(), connection.localRoot());
        String remoteValidation = validateRemoteTaskPlans(plans, connection.localRoot(), connection.workspace().executionRoot(), activeFile);
        if (remoteValidation != null) return remoteValidation;
        TaskService.TaskExecutionPlan plan = plans.get(plans.size() - 1);
        if (dryRun) return showRemoteTaskDryRun(target, plans, activeFile);
        int jobId = editor.asyncJobService.submit(
            "task remote " + connection.id() + " " + taskName,
            token -> runTaskPlans(plans, token, stage -> remoteCommandResult(connection.workspace().execute(
                editor.taskService.buildRemoteCommandRequest(stage, connection.localRoot(), connection.workspace().executionRoot(), activeFile)
            ))),
            (snapshot, result, error) -> handleTaskJobCompletion(taskName, plan, snapshot, result, error)
        );
        return "Task job " + jobId + " started remotely (" + connection.id() + ":" + taskName + ")";
    }

    private String runContainerTask(String taskName, File projectRoot,
                                    Map<String, TaskService.WorkspaceTask> tasks, boolean dryRun) {
        String normalizedName = taskName == null ? "" : taskName.trim();
        if (normalizedName.isEmpty()) return "Task name required";
        if (!new File(projectRoot, ".devcontainer/devcontainer.json").isFile()) {
            return "Dev Container task requires .devcontainer/devcontainer.json in: " + projectRoot.getAbsolutePath();
        }
        TaskService.WorkspaceTask task = resolveTask(normalizedName, projectRoot, tasks);
        if (task == null) return "Task not found: " + normalizedName + " (use :task list or :task add)";
        File activeFile = activeTaskFile();
        List<TaskService.TaskExecutionPlan> plans;
        try {
            plans = taskExecutionPlans(normalizedName, task, tasks, projectRoot, activeFile);
        } catch (IOException | IllegalArgumentException error) {
            return "Task validation failed: " + error.getMessage();
        }
        String validationError = validateTaskPlans(plans);
        if (validationError != null) return validationError;
        DevContainerSessionService.Connection connection = editor.devContainerSessions == null
            ? null : editor.devContainerSessions.connectionFor(projectRoot.toPath());
        if (connection != null) {
            return runConnectedContainerTask(normalizedName, plans, activeFile, connection, dryRun, true);
        }
        TaskService.TaskExecutionPlan plan = plans.get(plans.size() - 1);
        String containerValidation = validateContainerTaskPlans(plans, projectRoot.toPath(), "/<remote-workspace>", activeFile);
        if (containerValidation != null) return containerValidation;
        if (dryRun) return showContainerTaskDryRun(plans, activeFile);
        int jobId = editor.asyncJobService.submit(
            "task container " + normalizedName,
            token -> runContainerTaskPlans(plans, projectRoot, activeFile, token),
            (snapshot, result, error) -> handleTaskJobCompletion(normalizedName, plan, snapshot, result, error)
        );
        return "Dev Container task job " + jobId + " started (" + normalizedName + ")";
    }

    /** Runs a task through an already verified, explicitly connected Dev Container without re-probing it. */
    private String runConnectedContainerTask(String taskName, List<TaskService.TaskExecutionPlan> plans, File activeFile,
                                             DevContainerSessionService.Connection connection, boolean dryRun,
                                             boolean explicitlyRequested) {
        String containerValidation = validateContainerTaskPlans(plans, connection.workspace(), connection.remoteWorkingDirectory(), activeFile);
        if (containerValidation != null) return containerValidation;
        TaskService.TaskExecutionPlan plan = plans.get(plans.size() - 1);
        if (dryRun) return showConnectedContainerTaskDryRun(plans, activeFile, connection);
        int jobId = editor.asyncJobService.submit(
            "task container " + taskName,
            token -> runConnectedContainerTaskPlans(plans, activeFile, connection, token),
            (snapshot, result, error) -> handleTaskJobCompletion(taskName, plan, snapshot, result, error)
        );
        return explicitlyRequested ? "Dev Container task job " + jobId + " started (" + taskName + ")"
            : "Task job " + jobId + " started in Dev Container (" + taskName + ")";
    }

    private CommandResult runContainerTaskProcess(TaskService.TaskExecutionPlan plan, File projectRoot, File activeFile,
                                                  AsyncJobService.JobToken token) throws Exception {
        int timeout = editor.configManager.getProcessTimeoutMs();
        int outputLimit = editor.configManager.getProcessOutputMaxBytes();
        CommandResult rootProbe = runExternalCommand(devContainerPrefix(projectRoot.toPath()), projectRoot, null, token,
            timeout, outputLimit, true);
        if (rootProbe.exitCode != 0) return rootProbe;
        String remoteRoot;
        try {
            remoteRoot = DevContainerWorkspace.remoteWorkingDirectory(rootProbe.stdout);
        } catch (IllegalArgumentException error) {
            return new CommandResult(-1, "", "Dev Container workspace probe failed: " + error.getMessage());
        }
        RemoteCommandRequest request = editor.taskService.buildRemoteCommandRequest(plan, projectRoot.toPath(), remoteRoot, activeFile);
        List<String> invocation = devContainerInvocation(plan.workspace().toPath(), remoteRoot, request, plan.task().shell());
        return runExternalCommand(invocation, projectRoot, null, token, timeout, outputLimit, true);
    }

    private CommandResult runContainerTaskPlans(List<TaskService.TaskExecutionPlan> plans, File projectRoot, File activeFile,
                                                AsyncJobService.JobToken token) throws Exception {
        int timeout = editor.configManager.getProcessTimeoutMs();
        int outputLimit = editor.configManager.getProcessOutputMaxBytes();
        CommandResult rootProbe = runExternalCommand(devContainerPrefix(projectRoot.toPath()), projectRoot, null, token,
            timeout, outputLimit, true);
        if (rootProbe.exitCode != 0) return rootProbe;
        String remoteRoot;
        try {
            remoteRoot = DevContainerWorkspace.remoteWorkingDirectory(rootProbe.stdout);
        } catch (IllegalArgumentException error) {
            return new CommandResult(-1, "", "Dev Container workspace probe failed: " + error.getMessage());
        }
        return runTaskPlans(plans, token, plan -> {
            RemoteCommandRequest request = editor.taskService.buildRemoteCommandRequest(plan, projectRoot.toPath(), remoteRoot, activeFile);
            List<String> invocation = devContainerInvocation(plan.workspace().toPath(), remoteRoot, request, plan.task().shell());
            return runExternalCommand(invocation, projectRoot, null, token, timeout, outputLimit, true);
        });
    }

    private CommandResult runConnectedContainerTaskProcess(TaskService.TaskExecutionPlan plan, File activeFile,
                                                           DevContainerSessionService.Connection connection,
                                                           AsyncJobService.JobToken token) throws Exception {
        if (connection == null) throw new IOException("Dev Container session is unavailable");
        RemoteCommandRequest request = editor.taskService.buildRemoteCommandRequest(plan, connection.workspace(),
            connection.remoteWorkingDirectory(), activeFile);
        List<String> invocation = devContainerInvocation(connection.workspace(), connection.remoteWorkingDirectory(), request, plan.task().shell());
        return runExternalCommand(invocation, connection.workspace().toFile(), null, token,
            editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true);
    }

    private CommandResult runConnectedContainerTaskPlans(List<TaskService.TaskExecutionPlan> plans, File activeFile,
                                                         DevContainerSessionService.Connection connection,
                                                         AsyncJobService.JobToken token) throws Exception {
        if (connection == null) throw new IOException("Dev Container session is unavailable");
        return runTaskPlans(plans, token, plan -> {
            RemoteCommandRequest request = editor.taskService.buildRemoteCommandRequest(plan, connection.workspace(),
                connection.remoteWorkingDirectory(), activeFile);
            List<String> invocation = devContainerInvocation(connection.workspace(), connection.remoteWorkingDirectory(), request, plan.task().shell());
            return runExternalCommand(invocation, connection.workspace().toFile(), null, token,
                editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true);
        });
    }

    private List<String> devContainerPrefix(Path workspace) {
        List<String> command = new ArrayList<>(List.of("devcontainer", "exec", "--workspace-folder", workspace.toString()));
        command.add("pwd");
        return command;
    }

    static List<String> devContainerInvocation(Path workspace, String remoteRoot, RemoteCommandRequest request,
                                                TaskService.ShellPolicy shell) throws IOException {
        if (workspace == null || remoteRoot == null || remoteRoot.isBlank() || request == null || shell == null) {
            throw new IOException("Dev Container task requires workspace, remote root, command, and shell policy");
        }
        List<String> command = new ArrayList<>(List.of("devcontainer", "exec", "--workspace-folder", workspace.toString()));
        for (Map.Entry<String, String> entry : request.environment().entrySet()) {
            command.add("--remote-env");
            command.add(entry.getKey() + "=" + entry.getValue());
        }
        if (request.relativeWorkingDirectory().isEmpty()) {
            command.addAll(request.command());
            return List.copyOf(command);
        }
        if (shell == TaskService.ShellPolicy.DIRECT) {
            throw new IOException("direct Dev Container tasks require cwd to be the workspace root; use shell=login or shell=shell for a subdirectory");
        }
        String directory = remoteDirectory(remoteRoot, request.relativeWorkingDirectory());
        command.add("/bin/sh");
        command.add(shell == TaskService.ShellPolicy.LOGIN ? "-lc" : "-c");
        command.add("cd -- " + posixQuote(directory) + " && exec " + posixCommand(request.command()));
        return List.copyOf(command);
    }

    private static String remoteDirectory(String root, String relative) throws IOException {
        String normalizedRoot = root.replace('\\', '/');
        if (!normalizedRoot.startsWith("/") || normalizedRoot.indexOf('\n') >= 0 || normalizedRoot.indexOf('\r') >= 0 || normalizedRoot.indexOf('\u0000') >= 0) {
            throw new IOException("Dev Container workspace root must be an absolute POSIX path");
        }
        return normalizedRoot.endsWith("/") ? normalizedRoot + relative : normalizedRoot + "/" + relative;
    }

    private static String posixCommand(List<String> command) {
        return command.stream().map(JobQuickfixController::posixQuote).collect(java.util.stream.Collectors.joining(" "));
    }

    private static String posixQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    TaskService.WorkspaceTask resolveTask(String name, File projectRoot, Map<String, TaskService.WorkspaceTask> tasks) {
        TaskService.WorkspaceTask task = tasks.get(name);
        if (task != null) return task;
        TaskService.WorkspaceTask grouped = TaskService.defaultGroupTask(name, tasks);
        if (grouped != null) return grouped;
        return inferBuiltInTask(name, projectRoot);
    }

    TaskService.WorkspaceTask inferBuiltInTask(String taskName, File projectRoot) {
        String command = inferBuiltInTaskCommand(taskName, projectRoot);
        if (command != null && !command.isBlank()) return TaskService.defaultWorkspaceTask(taskName, command);
        if (projectRoot == null) return null;
        String normalized = taskName == null ? "" : taskName.trim().toLowerCase(Locale.ROOT);
        if ("test".equals(normalized)) {
            if (hasSoleDotnetTarget(projectRoot)) return directDotnetTask(taskName, "test");
            Path testDirectory = TestAdapterRegistry.ctestTestDirectory(projectRoot.toPath());
            return testDirectory == null ? null : directCmakeTask(taskName,
                List.of("ctest", "--test-dir", testDirectory.toString(), "--output-on-failure"));
        }
        if ("build".equals(normalized)) {
            if (hasSoleDotnetTarget(projectRoot)) return directDotnetTask(taskName, "build");
            Path buildDirectory = TestAdapterRegistry.cmakeBuildDirectory(projectRoot.toPath());
            return buildDirectory == null ? null : directCmakeTask(taskName, List.of("cmake", "--build", buildDirectory.toString()));
        }
        return null;
    }

    private TaskService.WorkspaceTask directCmakeTask(String name, List<String> arguments) {
        return TaskService.directWorkspaceTask(name, arguments, "${workspaceFolder}", Map.of(),
            TaskService.ProblemMatcher.GENERIC, TaskService.Presentation.ON_FAILURE);
    }

    private String runCmakePreset(List<String> arguments, File projectRoot, Map<String, TaskService.WorkspaceTask> tasks) {
        boolean dryRun = arguments.size() > 1 && "dry-run".equalsIgnoreCase(arguments.get(1));
        int operationIndex = dryRun ? 2 : 1;
        int presetIndex = dryRun ? 3 : 2;
        if (arguments.size() != presetIndex + 1) {
            return "Usage: :task cmake [dry-run] <configure|build|test|package|workflow> <preset>";
        }
        if (!hasCmakePresetFile(projectRoot == null ? null : projectRoot.toPath())) {
            return "CMake preset command requires CMakePresets.json or CMakeUserPresets.json at the workspace root";
        }
        String operation = arguments.get(operationIndex);
        String preset = arguments.get(presetIndex);
        List<String> command;
        try {
            command = cmakePresetArguments(operation, preset);
        } catch (IllegalArgumentException error) {
            return "CMake preset command invalid: " + error.getMessage();
        }
        String taskName = "cmake-preset-" + operation.trim().toLowerCase(Locale.ROOT);
        TaskService.WorkspaceTask task = directCmakeTask(taskName, command);
        Map<String, TaskService.WorkspaceTask> available = new LinkedHashMap<>(tasks == null ? Map.of() : tasks);
        available.put(taskName, task);
        return runLoadedTask(taskName, projectRoot, available, dryRun);
    }

    static List<String> cmakePresetArguments(String operation, String preset) {
        String normalizedOperation = operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
        if (!"configure".equals(normalizedOperation) && !"build".equals(normalizedOperation) && !"test".equals(normalizedOperation)
            && !"package".equals(normalizedOperation) && !"workflow".equals(normalizedOperation)) {
            throw new IllegalArgumentException("operation must be configure, build, test, package, or workflow");
        }
        if (!CmakePresetSupport.isSafeName(preset)) throw new IllegalArgumentException("preset name is invalid");
        return switch (normalizedOperation) {
            case "configure" -> List.of("cmake", "--preset", preset.trim());
            case "build" -> List.of("cmake", "--build", "--preset", preset.trim());
            case "test" -> List.of("ctest", "--preset", preset.trim());
            case "package" -> List.of("cpack", "--preset", preset.trim());
            case "workflow" -> List.of("cmake", "--workflow", "--preset", preset.trim());
            default -> throw new IllegalStateException("unreachable CMake preset operation");
        };
    }

    static boolean hasCmakePresetFile(Path root) {
        return CmakePresetSupport.hasPresetFile(root);
    }

    static boolean isSafeCmakePresetName(String name) {
        return CmakePresetSupport.isSafeName(name);
    }

    private TaskService.WorkspaceTask directDotnetTask(String name, String operation) {
        return TaskService.directWorkspaceTask(name, List.of("dotnet", operation), "${workspaceFolder}", Map.of(),
            TaskService.ProblemMatcher.GENERIC, TaskService.Presentation.ON_FAILURE);
    }

    private CommandResult remoteCommandResult(RemoteCommandResult result) {
        if (result == null) return new CommandResult(-1, "", "remote workspace returned no result");
        return new CommandResult(result.exitCode(), result.output(), "");
    }


    File activeTaskFile() {
        FileBuffer buffer = editor.getCurrentBuffer();
        return buffer != null && buffer.hasFilePath() ? new File(buffer.getFilePath()) : null;
    }

    private List<TaskService.TaskExecutionPlan> taskExecutionPlans(String taskName, TaskService.WorkspaceTask task,
                                                                    Map<String, TaskService.WorkspaceTask> tasks,
                                                                    File projectRoot, File activeFile) throws IOException {
        Map<String, TaskService.WorkspaceTask> available = new LinkedHashMap<>(tasks == null ? Map.of() : tasks);
        available.putIfAbsent(taskName, task);
        return editor.taskService.buildExecutionPlans(taskName, available, projectRoot, activeFile);
    }

    private String validateRemoteTaskPlans(List<TaskService.TaskExecutionPlan> plans, Path localRoot, String executionRoot,
                                            File activeFile) {
        try {
            for (TaskService.TaskExecutionPlan plan : plans) {
                editor.taskService.buildRemoteCommandRequest(plan, localRoot, executionRoot, activeFile);
            }
            return null;
        } catch (IOException | IllegalArgumentException error) {
            return "Remote task validation failed: " + error.getMessage();
        }
    }

    private String validateContainerTaskPlans(List<TaskService.TaskExecutionPlan> plans, Path localRoot, String executionRoot,
                                              File activeFile) {
        try {
            for (TaskService.TaskExecutionPlan plan : plans) {
                RemoteCommandRequest request = editor.taskService.buildRemoteCommandRequest(plan, localRoot, executionRoot, activeFile);
                devContainerInvocation(plan.workspace().toPath(), executionRoot, request, plan.task().shell());
            }
            return null;
        } catch (IOException | IllegalArgumentException error) {
            return "Dev Container task validation failed: " + error.getMessage();
        }
    }

    private String validateTaskPlans(List<TaskService.TaskExecutionPlan> plans) {
        if (plans == null || plans.isEmpty()) return "Task validation failed: no execution plans";
        for (TaskService.TaskExecutionPlan plan : plans) {
            String error = validateTaskPlan(plan);
            if (error != null) return error + " (task " + plan.task().name() + ")";
        }
        return null;
    }

    private String taskPlanDescription(List<TaskService.TaskExecutionPlan> plans) {
        if (plans == null || plans.isEmpty()) return "(no task)";
        return plans.stream().map(plan -> plan.task().name()).collect(java.util.stream.Collectors.joining(" -> "));
    }

    private CommandResult runLocalTaskPlans(List<TaskService.TaskExecutionPlan> plans, AsyncJobService.JobToken token) throws Exception {
        return runTaskPlans(plans, token, plan -> runExternalCommand(plan.processCommand(), plan.workingDirectory(), null, token,
            editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true, plan.environment()));
    }

    private CommandResult runTaskPlans(List<TaskService.TaskExecutionPlan> plans, AsyncJobService.JobToken token,
                                       TaskPlanRunner runner) throws Exception {
        if (plans == null || plans.isEmpty()) return new CommandResult(-1, "", "Task execution plan is empty");
        int limit = Math.max(1024, editor.configManager.getProcessOutputMaxBytes());
        byte[] truncationMarker = "[shed: task sequence output truncated]\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int contentLimit = Math.max(0, limit - truncationMarker.length);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        boolean[] truncated = new boolean[] {false};
        for (TaskService.TaskExecutionPlan plan : plans) {
            if (token != null && token.isCancelled()) return new CommandResult(-1, output.toString(java.nio.charset.StandardCharsets.UTF_8), "Task cancelled");
            appendTaskOutput(output, "==> task " + plan.task().name() + "\n", contentLimit, truncated);
            CommandResult result = runner.run(plan);
            String stageOutput = taskOutput(result);
            if (!stageOutput.isBlank()) appendTaskOutput(output, stageOutput + "\n", contentLimit, truncated);
            if (result.exitCode != 0) {
                String detail = result.stderr == null || result.stderr.isBlank() ? "Task '" + plan.task().name() + "' failed" : result.stderr;
                if (truncated[0]) output.writeBytes(truncationMarker);
                return new CommandResult(result.exitCode, output.toString(java.nio.charset.StandardCharsets.UTF_8), detail);
            }
        }
        if (truncated[0]) output.writeBytes(truncationMarker);
        return new CommandResult(0, output.toString(java.nio.charset.StandardCharsets.UTF_8), "");
    }

    private void appendTaskOutput(java.io.ByteArrayOutputStream output, String value, int limit, boolean[] truncated) {
        if (output == null || value == null || value.isEmpty() || truncated == null || truncated.length == 0 || truncated[0]) return;
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int remaining = limit - output.size();
        if (remaining <= 0) {
            truncated[0] = true;
            return;
        }
        int count = Math.min(remaining, bytes.length);
        output.write(bytes, 0, count);
        if (count < bytes.length) truncated[0] = true;
    }

    @FunctionalInterface
    private interface TaskPlanRunner {
        CommandResult run(TaskService.TaskExecutionPlan plan) throws Exception;
    }


    String validateTaskPlan(TaskService.TaskExecutionPlan plan) {
        String command = plan.expandedCommand();
        if (command.length() > editor.configManager.getShellCommandMaxLength()) {
            return "Error: command length exceeds shell.command.max.length";
        }
        if (plan.task().shell() != TaskService.ShellPolicy.DIRECT && !editor.configManager.getShellCommandEnabled()) {
            return "Error: shell tasks disabled by shell.command.enabled=false";
        }
        return null;
    }

    String showTaskDryRun(List<TaskService.TaskExecutionPlan> plans) {
        if (plans == null || plans.isEmpty()) return "Task dry run unavailable: no execution plans";
        if (plans.size() == 1) return showTaskDryRun(plans.get(0));
        StringBuilder output = new StringBuilder("Task dependency dry run: ")
            .append(plans.get(plans.size() - 1).task().name()).append("\n\n");
        for (int index = 0; index < plans.size(); index++) {
            TaskService.TaskExecutionPlan plan = plans.get(index);
            output.append(index + 1).append(". ").append(plan.task().name()).append("\n");
            output.append("   command: ").append(plan.expandedCommand()).append("\n");
            output.append("   shell: ").append(plan.task().shell().configValue()).append("\n");
            output.append("   cwd: ").append(plan.workingDirectory().getAbsolutePath()).append("\n");
        }
        output.append("\nTasks run in this exact order; this dry run starts nothing.\n");
        editor.showScratchBuffer("[task dry-run " + plans.get(plans.size() - 1).task().name() + "]", output.toString());
        return "Task dependency dry run shown (not started)";
    }


    String showTaskDryRun(TaskService.TaskExecutionPlan plan) {
        StringBuilder output = new StringBuilder("Task dry run: ").append(plan.task().name()).append("\n\n");
        output.append("command: ").append(plan.expandedCommand()).append("\n");
        output.append("shell: ").append(plan.task().shell().configValue()).append("\n");
        output.append("cwd: ").append(plan.workingDirectory().getAbsolutePath()).append("\n");
        output.append("problem_matcher: ").append(plan.task().problemMatcher().configValue()).append("\n");
        output.append("presentation: ").append(plan.task().presentation().configValue()).append("\n");
        output.append("env keys: ").append(plan.environment().isEmpty() ? "(none)" : String.join(", ", plan.environment().keySet())).append("\n");
        editor.showScratchBuffer("[task dry-run " + plan.task().name() + "]", output.toString());
        return "Task dry run shown (not started)";
    }

    private String showRemoteTaskDryRun(RemoteWorkspaceTaskTargets.Target target, List<TaskService.TaskExecutionPlan> plans,
                                        File activeFile) {
        String validation = validateRemoteTaskPlans(plans, target.localRoot(), target.workspace().executionRoot(), activeFile);
        if (validation != null) return validation;
        if (plans.size() == 1) {
            try {
                TaskService.TaskExecutionPlan plan = plans.get(0);
                return showRemoteTaskDryRun(target, plan, editor.taskService.buildRemoteCommandRequest(plan,
                    target.localRoot(), target.workspace().executionRoot(), activeFile));
            } catch (IOException | IllegalArgumentException error) {
                return "Remote task validation failed: " + error.getMessage();
            }
        }
        StringBuilder output = new StringBuilder("Remote task dependency dry run: ")
            .append(plans.get(plans.size() - 1).task().name()).append("\n\nconnection: ").append(target.id()).append("\n\n");
        try {
            for (int index = 0; index < plans.size(); index++) {
                TaskService.TaskExecutionPlan plan = plans.get(index);
                RemoteCommandRequest request = editor.taskService.buildRemoteCommandRequest(plan, target.localRoot(), target.workspace().executionRoot(), activeFile);
                output.append(index + 1).append(". ").append(plan.task().name()).append("\n")
                    .append("   remote cwd: /").append(request.relativeWorkingDirectory()).append(" (relative to connection root)\n")
                    .append("   command: ").append(String.join(" ", request.command())).append("\n");
            }
        } catch (IOException | IllegalArgumentException error) {
            return "Remote task validation failed: " + error.getMessage();
        }
        output.append("\nTasks run in this exact order; this dry run starts nothing.\n");
        editor.showScratchBuffer("[remote task dry-run " + plans.get(plans.size() - 1).task().name() + "]", output.toString());
        return "Remote task dependency dry run shown (not started)";
    }

    private String showRemoteTaskDryRun(RemoteWorkspaceTaskTargets.Target target, TaskService.TaskExecutionPlan plan,
                                        RemoteCommandRequest request) {
        StringBuilder output = new StringBuilder("Remote task dry run: ").append(plan.task().name()).append("\n\n");
        output.append("connection: ").append(target.id()).append("\n");
        output.append("remote cwd: /").append(request.relativeWorkingDirectory()).append(" (relative to connection root)\n");
        output.append("command: ").append(String.join(" ", request.command())).append("\n");
        output.append("shell: ").append(plan.task().shell().configValue()).append("\n");
        output.append("problem_matcher: ").append(plan.task().problemMatcher().configValue()).append("\n");
        output.append("presentation: ").append(plan.task().presentation().configValue()).append("\n");
        output.append("env keys: ").append(request.environment().isEmpty() ? "(none)" : String.join(", ", request.environment().keySet())).append("\n\n");
        output.append("This dry run starts nothing.\n");
        editor.showScratchBuffer("[remote task dry-run " + plan.task().name() + "]", output.toString());
        return "Remote task dry run shown (not started)";
    }

    private String showContainerTaskDryRun(List<TaskService.TaskExecutionPlan> plans, File activeFile) {
        if (plans.size() == 1) {
            try {
                TaskService.TaskExecutionPlan plan = plans.get(0);
                RemoteCommandRequest request = editor.taskService.buildRemoteCommandRequest(plan, plan.workspace().toPath(), "/<remote-workspace>", activeFile);
                return showContainerTaskDryRun(plan, request);
            } catch (IOException | IllegalArgumentException error) {
                return "Dev Container task validation failed: " + error.getMessage();
            }
        }
        StringBuilder output = new StringBuilder("Dev Container task dependency dry run: ")
            .append(plans.get(plans.size() - 1).task().name()).append("\n\n");
        for (int index = 0; index < plans.size(); index++) {
            TaskService.TaskExecutionPlan plan = plans.get(index);
            output.append(index + 1).append(". ").append(plan.task().name()).append("\n")
                .append("   command: ").append(plan.expandedCommand()).append("\n")
                .append("   cwd: ").append(plan.workingDirectory().getAbsolutePath()).append("\n");
        }
        output.append("\nThe Dev Container workspace path is resolved once when this sequence runs; this dry run starts nothing.\n");
        editor.showScratchBuffer("[container task dry-run " + plans.get(plans.size() - 1).task().name() + "]", output.toString());
        return "Dev Container task dependency dry run shown (not started)";
    }

    private String showContainerTaskDryRun(TaskService.TaskExecutionPlan plan, RemoteCommandRequest request) {
        StringBuilder output = new StringBuilder("Dev Container task dry run: ").append(plan.task().name()).append("\n\n");
        output.append("host workspace: ").append(plan.workspace().getAbsolutePath()).append("\n");
        output.append("remote cwd: ").append(request.relativeWorkingDirectory().isEmpty() ? "(workspace root)" : request.relativeWorkingDirectory()).append("\n");
        output.append("command: ").append(String.join(" ", request.command())).append("\n");
        output.append("shell: ").append(plan.task().shell().configValue()).append("\n");
        output.append("env keys: ").append(request.environment().isEmpty() ? "(none)" : String.join(", ", request.environment().keySet())).append("\n\n");
        output.append("The remote workspace path is resolved by an explicit devcontainer exec call when this task runs.\n");
        editor.showScratchBuffer("[container task dry-run " + plan.task().name() + "]", output.toString());
        return "Dev Container task dry run shown (not started)";
    }

    private String showConnectedContainerTaskDryRun(List<TaskService.TaskExecutionPlan> plans, File activeFile,
                                                    DevContainerSessionService.Connection connection) {
        if (plans.size() == 1) {
            try {
                TaskService.TaskExecutionPlan plan = plans.get(0);
                RemoteCommandRequest request = editor.taskService.buildRemoteCommandRequest(plan, connection.workspace(),
                    connection.remoteWorkingDirectory(), activeFile);
                return showConnectedContainerTaskDryRun(plan, request, connection);
            } catch (IOException | IllegalArgumentException error) {
                return "Dev Container task validation failed: " + error.getMessage();
            }
        }
        StringBuilder output = new StringBuilder("Connected Dev Container task dependency dry run: ")
            .append(plans.get(plans.size() - 1).task().name()).append("\n\nhost workspace: ")
            .append(connection.workspace()).append("\ncontainer workspace: ").append(connection.remoteWorkingDirectory()).append("\n\n");
        for (int index = 0; index < plans.size(); index++) {
            TaskService.TaskExecutionPlan plan = plans.get(index);
            output.append(index + 1).append(". ").append(plan.task().name()).append("\n")
                .append("   command: ").append(plan.expandedCommand()).append("\n")
                .append("   cwd: ").append(plan.workingDirectory().getAbsolutePath()).append("\n");
        }
        output.append("\nTasks run in this exact order in the explicitly connected container; this dry run starts nothing.\n");
        editor.showScratchBuffer("[container task dry-run " + plans.get(plans.size() - 1).task().name() + "]", output.toString());
        return "Connected Dev Container task dependency dry run shown (not started)";
    }

    private String showConnectedContainerTaskDryRun(TaskService.TaskExecutionPlan plan, RemoteCommandRequest request,
                                                    DevContainerSessionService.Connection connection) {
        StringBuilder output = new StringBuilder("Connected Dev Container task dry run: ").append(plan.task().name()).append("\n\n");
        output.append("host workspace: ").append(connection.workspace()).append("\n");
        output.append("container workspace: ").append(connection.remoteWorkingDirectory()).append("\n");
        output.append("remote cwd: ").append(request.relativeWorkingDirectory().isEmpty() ? "(workspace root)" : request.relativeWorkingDirectory()).append("\n");
        output.append("command: ").append(String.join(" ", request.command())).append("\n");
        output.append("shell: ").append(plan.task().shell().configValue()).append("\n");
        output.append("env keys: ").append(request.environment().isEmpty() ? "(none)" : String.join(", ", request.environment().keySet())).append("\n\n");
        output.append("The container was explicitly connected earlier in this application session; this dry run starts nothing.\n");
        editor.showScratchBuffer("[container task dry-run " + plan.task().name() + "]", output.toString());
        return "Dev Container task dry run shown (not started)";
    }


    String cancelTaskJob(String jobIdArgument) {
        try {
            int jobId = Integer.parseInt(jobIdArgument.trim());
            AsyncJobService.JobSnapshot job = editor.asyncJobService.get(jobId);
            if (job == null || !job.getDescription().startsWith("task ")) return "Task job not running: " + jobId;
            boolean cancelled = editor.asyncJobService.cancel(jobId);
            return cancelled ? "Task job " + jobId + " cancellation sent" : "Task job not running: " + jobId;
        } catch (NumberFormatException error) {
            return "Invalid job id: " + jobIdArgument;
        }
    }


    String inferBuiltInTaskCommand(String taskName, File projectRoot) {
        String normalized = taskName == null ? "" : taskName.trim().toLowerCase(Locale.ROOT);
        if ("test".equals(normalized)) {
            if (new File(projectRoot, "pom.xml").isFile()) {
                return "mvn -q test";
            }
            String gradle = gradleWrapperCommand(projectRoot, "test");
            if (gradle != null) return gradle;
            if (new File(projectRoot, "package.json").isFile()) {
                return "npm test";
            }
            if (new File(projectRoot, "Makefile").isFile()) {
                return "make test";
            }
            if (new File(projectRoot, "Cargo.toml").isFile()) {
                return "cargo test";
            }
            if (new File(projectRoot, "go.mod").isFile()) {
                return "go test ./...";
            }
        }
        if ("build".equals(normalized)) {
            if (new File(projectRoot, "pom.xml").isFile()) {
                return "mvn -q -DskipTests package";
            }
            String gradle = gradleWrapperCommand(projectRoot, "build");
            if (gradle != null) return gradle;
            if (new File(projectRoot, "package.json").isFile()) {
                return "npm run build";
            }
            if (new File(projectRoot, "Makefile").isFile()) {
                return "make build";
            }
            if (new File(projectRoot, "Cargo.toml").isFile()) {
                return "cargo build";
            }
            if (new File(projectRoot, "go.mod").isFile()) {
                return "go build ./...";
            }
        }
        return null;
    }

    private static String gradleWrapperCommand(File projectRoot, String task) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
            && new File(projectRoot, "gradlew.bat").isFile()) return "gradlew.bat " + task;
        if (new File(projectRoot, "gradlew").isFile()) return "./gradlew " + task;
        return null;
    }

    private static boolean hasSoleDotnetTarget(File projectRoot) {
        if (projectRoot == null) return false;
        File[] entries = projectRoot.listFiles(file -> file.isFile() && isDotnetTargetName(file.getName()));
        return entries != null && entries.length == 1;
    }

    private static boolean isDotnetTargetName(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".sln") || normalized.endsWith(".slnx") || normalized.endsWith(".csproj")
            || normalized.endsWith(".fsproj") || normalized.endsWith(".vbproj");
    }


    void handleTaskJobCompletion(String taskName, AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error) {
        TaskService.WorkspaceTask task = TaskService.defaultWorkspaceTask(taskName, "true");
        TaskService.TaskExecutionPlan plan = new TaskService.TaskExecutionPlan(task, new File("."), "true", List.of("true"), new File("."), Map.of());
        handleTaskJobCompletion(taskName, plan, snapshot, result, error);
    }


    void handleTaskJobCompletion(String taskName, TaskService.TaskExecutionPlan plan,
                                 AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error) {
        if (editor.closingDown) {
            return;
        }
        int jobId = snapshot == null ? -1 : snapshot.getId();
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED) {
            editor.showMessage("Task job " + jobId + " cancelled");
            return;
        }
        if (error != null || result == null) {
            String message = error == null ? "unknown error" : error.getMessage();
            editor.showMessage("Task job " + jobId + " failed: " + (message == null ? "" : message));
            return;
        }
        String output = taskOutput(result);
        if (jobId >= 0) {
            taskOutputs.put(jobId, output);
            while (taskOutputs.size() > 100) taskOutputs.remove(taskOutputs.keySet().iterator().next());
        }
        List<QuickfixService.Entry> parsedEntries = plan.task().problemMatcher() == TaskService.ProblemMatcher.NONE
            ? List.of()
            : parseTaskQuickfixEntries(output, "task:" + taskName, plan.workingDirectory(), plan.task().problemMatcher());
        if (parsedEntries.isEmpty()) editor.problemsController.clearQuickfixSource("task:" + taskName);
        if (!parsedEntries.isEmpty()) {
            updateQuickfixEntries("task " + taskName + " #" + jobId, parsedEntries);
        }

        if (result.exitCode != 0) {
            if (shouldPresentTaskOutput(plan.task().presentation(), false) && !output.isEmpty()) {
                if (editor.toolWindowHost == null || !editor.toolWindowHost.isSelected(ToolWindowHost.Tab.TASKS)) {
                    editor.showScratchBuffer("[task " + taskName + " #" + jobId + "]", output + "\n");
                }
            }
            String failure = result.stderr == null || result.stderr.isBlank() ? "exit " + result.exitCode : result.stderr.strip();
            editor.showMessage(parsedEntries.isEmpty()
                ? "Task '" + taskName + "' failed (" + failure + ")"
                : "Task '" + taskName + "' failed (" + failure + ", quickfix updated)");
            return;
        }

        if (shouldPresentTaskOutput(plan.task().presentation(), true) && !output.isEmpty()) {
            if (editor.toolWindowHost == null || !editor.toolWindowHost.isSelected(ToolWindowHost.Tab.TASKS)) {
                editor.showScratchBuffer("[task " + taskName + " #" + jobId + "]", output + "\n");
            }
        }
        editor.showMessage(parsedEntries.isEmpty()
            ? "Task '" + taskName + "' complete"
            : "Task '" + taskName + "' complete (quickfix updated)");
        if (editor.toolWindowHost != null) editor.toolWindowHost.refresh(ToolWindowHost.Tab.TASKS);
    }


    boolean shouldPresentTaskOutput(TaskService.Presentation presentation, boolean succeeded) {
        return presentation == TaskService.Presentation.ALWAYS
            || (!succeeded && presentation == TaskService.Presentation.ON_FAILURE);
    }


    String taskOutput(CommandResult result) {
        String stdout = result.stdout == null ? "" : result.stdout.stripTrailing();
        String stderr = result.stderr == null ? "" : result.stderr.stripTrailing();
        if (stderr.isEmpty() || stderr.equals(stdout)) return stdout;
        return stdout.isEmpty() ? stderr : stdout + "\n" + stderr;
    }


    List<QuickfixService.Entry> parseTaskQuickfixEntries(String output, String source, File workingDirectory) {
        return parseTaskQuickfixEntries(output, source, workingDirectory, TaskService.ProblemMatcher.GENERIC);
    }

    List<QuickfixService.Entry> parseTaskQuickfixEntries(String output, String source, File workingDirectory,
                                                          TaskService.ProblemMatcher matcher) {
        return TaskProblemParser.parse(output, source, workingDirectory, matcher);
    }


    public String filterRangeWithCommand(int startLine, int endLine, String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            return "Error: :! requires command";
        }
        String validationError = validateShellCommand(trimmed);
        if (validationError != null) {
            return validationError;
        }
        try {
            int safeStart = Math.max(1, Math.min(startLine, editor.writingArea.getLineCount()));
            int safeEnd = Math.max(safeStart, Math.min(endLine, editor.writingArea.getLineCount()));
            int startOffset = editor.writingArea.getLineStartOffset(safeStart - 1);
            int endOffset = editor.writingArea.getLineEndOffset(safeEnd - 1);
            String input = editor.writingArea.getText().substring(startOffset, endOffset);
            FileBuffer targetBuffer = editor.getCurrentBuffer();

            int jobId = editor.asyncJobService.submit(
                "filter " + safeStart + "," + safeEnd + ": " + trimmed,
                token -> runShellProcess(trimmed, input, token),
                (snapshot, result, error) -> handleFilterJobCompletion(
                    snapshot,
                    result,
                    error,
                    targetBuffer,
                    startOffset,
                    endOffset,
                    input,
                    safeStart,
                    safeEnd
                )
            );
            return "Filter job " + jobId + " started";
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }


    public String showJobs() {
        List<AsyncJobService.JobSnapshot> jobs = editor.asyncJobService.list();
        if (jobs.isEmpty()) {
            return "No jobs";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Jobs\n\n");
        for (AsyncJobService.JobSnapshot job : jobs) {
            builder.append(job.getId())
                .append("  ")
                .append(job.getStatus().name().toLowerCase())
                .append("  ")
                .append(job.getDescription());
            Long finished = job.getFinishedAtMillis();
            if (finished != null) {
                long duration = Math.max(0L, finished - job.getStartedAtMillis());
                builder.append("  (").append(duration).append(" ms)");
            }
            if (job.getErrorMessage() != null && !job.getErrorMessage().isBlank()) {
                builder.append("  ").append(job.getErrorMessage().strip());
            }
            builder.append("\n");
        }
        editor.showScratchBuffer("[jobs]", builder.toString());
        return "Showing jobs";
    }


    public String cancelJob(String jobIdArgument) {
        if (jobIdArgument == null || jobIdArgument.isBlank()) {
            return "Usage: :jobcancel <id>";
        }
        try {
            int jobId = Integer.parseInt(jobIdArgument.trim());
            boolean cancelled = editor.asyncJobService.cancel(jobId);
            return cancelled ? "Cancellation sent for job " + jobId : "Job not running: " + jobId;
        } catch (NumberFormatException e) {
            return "Invalid job id: " + jobIdArgument;
        }
    }


    public String openQuickfixList() {
        if (!editor.quickfixService.hasEntries()) {
            return "Quickfix is empty";
        }
        String content = editor.quickfixService.render();
        if (content.isBlank()) {
            return "Quickfix is empty";
        }

        if (editor.quickfixBuffer != null && editor.buffers.contains(editor.quickfixBuffer)) {
            editor.quickfixBuffer.setContent(content, false);
            editor.loadBufferIntoEditor(editor.quickfixBuffer);
            editor.writingArea.setCaretPosition(Math.min(Math.max(0, editor.quickfixService.currentIndex()), Math.max(0, editor.writingArea.getDocument().getLength() - 1)));
            return "Quickfix updated";
        }

        editor.persistCurrentBufferState();
        FileBuffer returnBuffer = editor.getCurrentBuffer();
        int returnCaretPosition = editor.writingArea.getCaretPosition();
        editor.quickfixBuffer = FileBuffer.createScratch("[quickfix]", content);
        editor.buffers.add(editor.quickfixBuffer);
        if (returnBuffer != null) {
            editor.specialBufferReturns.push(new SpecialBufferReturnState(editor.quickfixBuffer, returnBuffer, returnCaretPosition));
        }
        editor.loadBufferIntoEditor(editor.quickfixBuffer);
        return "Quickfix opened";
    }


    public String quickfixNext() {
        return jumpToQuickfixEntry(editor.quickfixService.next());
    }


    public String quickfixPrev() {
        return jumpToQuickfixEntry(editor.quickfixService.previous());
    }


    public String quickfixFirst() {
        return jumpToQuickfixEntry(editor.quickfixService.first());
    }


    public String quickfixLast() {
        return jumpToQuickfixEntry(editor.quickfixService.last());
    }


    public String quickfixCurrent(String argument) {
        if (argument == null || argument.isBlank()) {
            return jumpToQuickfixEntry(editor.quickfixService.current());
        }
        try {
            int index = Integer.parseInt(argument.trim());
            QuickfixService.Entry selected = editor.quickfixService.select(index);
            if (selected == null) {
                return "Quickfix index out of range: " + index;
            }
            return jumpToQuickfixEntry(selected);
        } catch (NumberFormatException e) {
            return "Usage: :cc [index]";
        }
    }


    public String closeQuickfixList() {
        if (editor.quickfixBuffer == null || !editor.buffers.contains(editor.quickfixBuffer)) {
            return "Quickfix not open";
        }
        if (editor.getCurrentBuffer() == editor.quickfixBuffer) {
            return editor.requestQuit(true);
        }
        FileBuffer replacement = null;
        for (FileBuffer candidate : editor.buffers) {
            if (candidate != null && candidate != editor.quickfixBuffer) {
                replacement = candidate;
                break;
            }
        }
        if (replacement == null) {
            editor.openLandingPage();
            replacement = editor.getCurrentBuffer();
        }
        for (EditorPane pane : editor.editorPanes) {
            if (pane != null && pane.getBuffer() == editor.quickfixBuffer) {
                editor.loadBufferIntoPane(pane, replacement, 0);
            }
        }
        pruneSpecialBufferReturns(editor.quickfixBuffer);
        editor.buffers.remove(editor.quickfixBuffer);
        editor.quickfixBuffer = null;
        return "Quickfix closed";
    }


    void pruneSpecialBufferReturns(FileBuffer scratchBuffer) {
        if (scratchBuffer == null || editor.specialBufferReturns.isEmpty()) {
            return;
        }
        Deque<SpecialBufferReturnState> rebuilt = new ArrayDeque<>();
        for (SpecialBufferReturnState state : editor.specialBufferReturns) {
            if (state == null || state.scratchBuffer == scratchBuffer) {
                continue;
            }
            rebuilt.addLast(state);
        }
        editor.specialBufferReturns = rebuilt;
    }


    boolean isQuickfixBufferActive() {
        FileBuffer current = editor.getCurrentBuffer();
        return current != null && current == editor.quickfixBuffer;
    }


    String openQuickfixSelection() {
        if (!isQuickfixBufferActive()) {
            return "Quickfix buffer not active";
        }
        int index = editor.getCurrentCaretLine() + 1;
        QuickfixService.Entry entry = editor.quickfixService.atLine(index);
        if (entry == null) {
            return "No quickfix entry on this line";
        }
        return jumpToQuickfixEntry(entry);
    }


    String jumpToQuickfixEntry(QuickfixService.Entry entry) {
        if (entry == null) {
            return "Quickfix is empty";
        }

        if (entry.getFilePath() != null && !entry.getFilePath().isBlank()) {
            try {
                editor.openFile(new File(entry.getFilePath()));
            } catch (IOException e) {
                return "Quickfix open failed: " + e.getMessage();
            }
        }

        String lineResult = editor.gotoLine(entry.getLine());
        if (lineResult.startsWith("Error") || lineResult.startsWith("Invalid")) {
            return lineResult;
        }
        try {
            int lineStart = editor.writingArea.getLineStartOffset(Math.max(0, entry.getLine() - 1));
            int target = Math.min(lineStart + Math.max(0, entry.getColumn() - 1), editor.writingArea.getText().length());
            editor.writingArea.setCaretPosition(target);
        } catch (BadLocationException ignored) {
        }
        return "Quickfix " + (editor.quickfixService.currentIndex() + 1) + "/" + editor.quickfixService.size();
    }


    void updateQuickfixEntries(String title, List<QuickfixService.Entry> entries) {
        if (entries == null) {
            return;
        }
        editor.quickfixService.setEntries(title, entries);
        editor.problemsController.recordQuickfixEntries(entries);
        if (editor.quickfixBuffer != null && editor.buffers.contains(editor.quickfixBuffer)) {
            editor.quickfixBuffer.setContent(editor.quickfixService.render(), false);
            if (editor.getCurrentBuffer() == editor.quickfixBuffer) {
                editor.loadBufferIntoEditor(editor.quickfixBuffer);
            }
        }
    }


    List<QuickfixService.Entry> parseQuickfixEntries(String output, String defaultSource) {
        List<QuickfixService.Entry> entries = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return entries;
        }
        String source = defaultSource == null ? "" : defaultSource;
        for (String line : output.split("\n")) {
            Matcher matcher = editor.QUICKFIX_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String path = matcher.group(1).trim();
            int lineNumber;
            int columnNumber = 1;
            try {
                lineNumber = Integer.parseInt(matcher.group(2));
                String col = matcher.group(3);
                if (col != null && !col.isBlank()) {
                    columnNumber = Integer.parseInt(col);
                }
            } catch (NumberFormatException ignored) {
                continue;
            }
            String message = matcher.group(4) == null ? "" : matcher.group(4).trim();
            entries.add(new QuickfixService.Entry(path, lineNumber, columnNumber, message, source));
        }
        return entries;
    }


    String validateShellCommand(String command) {
        if (command == null || command.isBlank()) {
            return "Error: command is empty";
        }
        if (command.indexOf('\0') >= 0) {
            return "Error: command contains invalid null byte";
        }
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return "Error: command must be a single line";
        }
        if (!editor.configManager.getShellCommandEnabled()) {
            return "Error: shell commands disabled by shell.command.enabled=false";
        }
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (Character.isISOControl(ch) && ch != '\t') {
                return "Error: command contains invalid control character";
            }
        }
        if (command.length() > editor.configManager.getShellCommandMaxLength()) {
            return "Error: command length exceeds shell.command.max.length";
        }
        return null;
    }


    CommandResult runShellProcess(String command, String input, AsyncJobService.JobToken token) throws Exception {
        return runExternalCommand(
            ShellCommand.forCommand(command),
            new File("."),
            input,
            token,
            editor.configManager.getProcessTimeoutMs(),
            editor.configManager.getProcessOutputMaxBytes(),
            true
        );
    }


    CommandResult runExternalCommand(
        List<String> command,
        File workingDirectory,
        String input,
        AsyncJobService.JobToken token,
        int timeoutMs,
        int outputLimitBytes,
        boolean redirectErrorStream
    ) {
        return runExternalCommand(command, workingDirectory, input, token, timeoutMs, outputLimitBytes,
            redirectErrorStream, Map.of());
    }


    CommandResult runExternalCommand(
        List<String> command,
        File workingDirectory,
        String input,
        AsyncJobService.JobToken token,
        int timeoutMs,
        int outputLimitBytes,
        boolean redirectErrorStream,
        Map<String, String> environment
    ) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory == null ? new File(".") : workingDirectory);
            builder.redirectErrorStream(redirectErrorStream);
            if (environment != null && !environment.isEmpty()) {
                builder.environment().putAll(environment);
            }
            process = builder.start();
            Process runningProcess = process;
            if (token != null) {
                token.onCancel(() -> {
                    if (runningProcess.isAlive()) {
                        runningProcess.destroyForcibly();
                    }
                });
            }

            if (input != null) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(input.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }

            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            boolean[] truncated = new boolean[] {false};
            Thread outputReader = new Thread(() -> readInputStreamCapped(runningProcess.getInputStream(), outputBuffer, outputLimitBytes, truncated), "shed-process-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished = runningProcess.waitFor(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
            if (!finished) {
                runningProcess.destroyForcibly();
                outputReader.join(500);
                return new CommandResult(-1, "", "Process timed out after " + timeoutMs + "ms");
            }
            outputReader.join(1000);
            if (token != null && token.isCancelled()) {
                return new CommandResult(-1, "", "Process cancelled");
            }
            String output = outputBuffer.toString(StandardCharsets.UTF_8);
            if (truncated[0]) {
                output = output + "\n[shed: output truncated]";
            }
            return new CommandResult(runningProcess.exitValue(), output, "");
        } catch (InterruptedException e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", "Process interrupted");
        } catch (Exception e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return new CommandResult(-1, "", e.getMessage());
        }
    }


    void readInputStreamCapped(InputStream stream, ByteArrayOutputStream out, int maxBytes, boolean[] truncated) {
        byte[] buffer = new byte[8192];
        int total = 0;
        int limit = Math.max(1024, maxBytes);
        try (InputStream input = stream) {
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                int remaining = limit - total;
                if (remaining <= 0) {
                    truncated[0] = true;
                    continue;
                }
                int toWrite = Math.min(read, remaining);
                out.write(buffer, 0, toWrite);
                total += toWrite;
                if (toWrite < read) {
                    truncated[0] = true;
                }
            }
        } catch (IOException ignored) {
        }
    }


    void handleShellJobCompletion(AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error) {
        if (editor.closingDown) {
            return;
        }
        int jobId = snapshot == null ? -1 : snapshot.getId();
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED) {
            editor.showMessage("Shell job " + jobId + " cancelled");
            return;
        }
        if (error != null || result == null) {
            String message = error == null ? "unknown error" : error.getMessage();
            editor.showMessage("Shell job " + jobId + " failed: " + (message == null ? "" : message));
            return;
        }

        String output = result.stdout == null ? "" : result.stdout.stripTrailing();
        List<QuickfixService.Entry> parsedEntries = parseQuickfixEntries(output, "shell");
        if (parsedEntries.isEmpty()) editor.problemsController.clearQuickfixSource("shell");
        if (!parsedEntries.isEmpty()) {
            updateQuickfixEntries("shell job " + jobId, parsedEntries);
        }
        if (result.exitCode != 0) {
            if (output.isEmpty()) {
                editor.showMessage("Shell job " + jobId + " failed (exit " + result.exitCode + ")");
            } else {
                editor.showScratchBuffer("[shell job " + jobId + "]", output + "\n");
                editor.showMessage(parsedEntries.isEmpty()
                    ? "Shell job " + jobId + " failed (exit " + result.exitCode + ")"
                    : "Shell job " + jobId + " failed (exit " + result.exitCode + ", quickfix updated)");
            }
            return;
        }

        if (output.isEmpty()) {
            editor.showMessage("Shell job " + jobId + " exited 0");
            return;
        }
        if (output.lines().count() <= 1) {
            editor.showMessage(output);
            return;
        }
        editor.showScratchBuffer("[shell job " + jobId + "]", output + "\n");
        editor.showMessage(parsedEntries.isEmpty()
            ? "Shell job " + jobId + " complete"
            : "Shell job " + jobId + " complete (quickfix updated)");
    }


    void handleFilterJobCompletion(
        AsyncJobService.JobSnapshot snapshot,
        CommandResult result,
        Exception error,
        FileBuffer targetBuffer,
        int startOffset,
        int endOffset,
        String originalInput,
        int startLine,
        int endLine
    ) {
        if (editor.closingDown) {
            return;
        }
        int jobId = snapshot == null ? -1 : snapshot.getId();
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED) {
            editor.showMessage("Filter job " + jobId + " cancelled");
            return;
        }
        if (error != null || result == null) {
            String message = error == null ? "unknown error" : error.getMessage();
            editor.showMessage("Filter job " + jobId + " failed: " + (message == null ? "" : message));
            return;
        }
        if (result.exitCode != 0) {
            String output = result.stdout == null ? "" : result.stdout.strip();
            if (output.isEmpty()) {
                editor.showMessage("Filter job " + jobId + " failed (exit " + result.exitCode + ")");
            } else {
                editor.showScratchBuffer("[filter job " + jobId + "]", output + "\n");
                editor.showMessage("Filter job " + jobId + " failed (exit " + result.exitCode + ")");
            }
            return;
        }
        if (targetBuffer == null || editor.getCurrentBuffer() != targetBuffer) {
            editor.showMessage("Filter job " + jobId + " complete (target buffer not active)");
            return;
        }
        String text = editor.writingArea.getText();
        if (startOffset < 0 || endOffset > text.length() || startOffset > endOffset) {
            editor.showMessage("Filter job " + jobId + " skipped (buffer changed)");
            return;
        }
        String currentSlice = text.substring(startOffset, endOffset);
        if (!currentSlice.equals(originalInput)) {
            editor.showMessage("Filter job " + jobId + " skipped (range changed)");
            return;
        }
        editor.writingArea.replaceRange(result.stdout == null ? "" : result.stdout, startOffset, endOffset);
        editor.writingArea.setCaretPosition(Math.min(startOffset, editor.writingArea.getText().length()));
        editor.markModified();
        editor.showMessage((endLine - startLine + 1) + " line filter applied");
    }

}
