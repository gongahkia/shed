package shed;

import javax.swing.text.BadLocationException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

final class JobQuickfixController {
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
        TaskService.TaskLoadResult loaded = editor.taskService.loadWorkspaceTasks(projectRoot);
        if (!loaded.isValid()) {
            return showTaskConfigurationDiagnostics(projectRoot, loaded.diagnostics());
        }
        if (trimmed.isEmpty() || "list".equalsIgnoreCase(trimmed)) {
            return showWorkspaceTasks(projectRoot, loaded.tasks());
        }
        List<String> args = editor.parseQuotedArguments(trimmed);
        if (args.isEmpty()) {
            return showWorkspaceTasks(projectRoot, loaded.tasks());
        }

        String sub = args.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                return showWorkspaceTasks(projectRoot, loaded.tasks());
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
                return runLoadedTask(args.get(1), projectRoot, loaded.tasks(), false);
            case "dry-run":
            case "dryrun":
                if (args.size() < 2) {
                    return "Usage: :task dry-run <name>";
                }
                return runLoadedTask(args.get(1), projectRoot, loaded.tasks(), true);
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
            start = new File(".");
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
        File fallback = cursor;
        while (cursor != null) {
            if (new File(cursor, ".shedtasks").isFile()) {
                return cursor;
            }
            if (new File(cursor, ".git").exists()) {
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
                    .append(", ").append(task.presentation().configValue()).append("]\n");
            }
        }
        editor.showScratchBuffer("[tasks]", sb.toString());
        return "Showing tasks";
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
        TaskService.WorkspaceTask task = tasks.get(normalizedName);
        if (task == null) {
            String taskCommand = inferBuiltInTaskCommand(normalizedName, projectRoot);
            if (taskCommand != null && !taskCommand.isBlank()) {
                task = TaskService.defaultWorkspaceTask(normalizedName, taskCommand);
            }
        }
        if (task == null) {
            return "Task not found: " + normalizedName + " (use :task list or :task add)";
        }
        File activeFile = activeTaskFile();
        TaskService.TaskExecutionPlan plan;
        try {
            plan = editor.taskService.buildExecutionPlan(task, projectRoot, activeFile);
        } catch (IOException | IllegalArgumentException error) {
            return "Task validation failed: " + error.getMessage();
        }
        String validationError = validateTaskPlan(plan);
        if (validationError != null) {
            return validationError;
        }
        if (dryRun) return showTaskDryRun(plan);
        int jobId = editor.asyncJobService.submit(
            "task " + normalizedName + ": " + plan.expandedCommand(),
            token -> runExternalCommand(
                plan.processCommand(),
                plan.workingDirectory(),
                null,
                token,
                editor.configManager.getProcessTimeoutMs(),
                editor.configManager.getProcessOutputMaxBytes(),
                true,
                plan.environment()
            ),
            (snapshot, result, error) -> handleTaskJobCompletion(normalizedName, plan, snapshot, result, error)
        );
        return "Task job " + jobId + " started (" + normalizedName + ")";
    }


    File activeTaskFile() {
        FileBuffer buffer = editor.getCurrentBuffer();
        return buffer != null && buffer.hasFilePath() ? new File(buffer.getFilePath()) : null;
    }


    String validateTaskPlan(TaskService.TaskExecutionPlan plan) {
        String command = plan.expandedCommand();
        if (command.length() > editor.configManager.getShellCommandMaxLength()) {
            return "Error: command length exceeds shell.command.max.length";
        }
        if (plan.task().shell() == TaskService.ShellPolicy.LOGIN && !editor.configManager.getShellCommandEnabled()) {
            return "Error: shell tasks disabled by shell.command.enabled=false";
        }
        return null;
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
            if (new File(projectRoot, "package.json").isFile()) {
                return "npm test";
            }
            if (new File(projectRoot, "Makefile").isFile()) {
                return "make test";
            }
        }
        if ("build".equals(normalized)) {
            if (new File(projectRoot, "pom.xml").isFile()) {
                return "mvn -q -DskipTests package";
            }
            if (new File(projectRoot, "package.json").isFile()) {
                return "npm run build";
            }
            if (new File(projectRoot, "Makefile").isFile()) {
                return "make build";
            }
        }
        return null;
    }


    void handleTaskJobCompletion(String taskName, AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error) {
        TaskService.WorkspaceTask task = TaskService.defaultWorkspaceTask(taskName, "true");
        TaskService.TaskExecutionPlan plan = new TaskService.TaskExecutionPlan(task, "true", List.of("true"), new File("."), Map.of());
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
            : parseTaskQuickfixEntries(output, "task:" + taskName, plan.workingDirectory());
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
        return TaskProblemParser.parseGeneric(output, source, workingDirectory);
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
            editor.animateEditorHostTint(editor.configManager.getCommandColor());
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
        editor.animateEditorHostTint(editor.configManager.getCommandColor());
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
        editor.animateEditorHostTint(editor.configManager.getCommandColor());
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
        editor.pulseCaretLine(editor.blendColor(editor.configManager.getCommandColor(), editor.configManager.getCaretColor(), 0.35));
        editor.playCue(CueType.NAVIGATE);
        return "Quickfix " + (editor.quickfixService.currentIndex() + 1) + "/" + editor.quickfixService.size();
    }


    void updateQuickfixEntries(String title, List<QuickfixService.Entry> entries) {
        if (entries == null) {
            return;
        }
        editor.quickfixService.setEntries(title, entries);
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
