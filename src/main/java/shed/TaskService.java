package shed;

import shed.api.RemoteCommandRequest;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class TaskService {
    private static final String TASKS_FILE_NAME = ".shedtasks";
    private static final Pattern TASK_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    enum ShellPolicy {
        LOGIN,
        DIRECT;

        static ShellPolicy parse(Object value) {
            if (value == null) return LOGIN;
            if (!(value instanceof String)) throw new IllegalArgumentException("shell must be TOML string");
            return switch (((String) value).trim().toLowerCase(Locale.ROOT)) {
                case "login" -> LOGIN;
                case "direct" -> DIRECT;
                default -> throw new IllegalArgumentException("shell must be login or direct");
            };
        }

        String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum ProblemMatcher {
        GENERIC,
        NONE;

        static ProblemMatcher parse(Object value) {
            if (value == null) return GENERIC;
            if (!(value instanceof String)) throw new IllegalArgumentException("problem_matcher must be TOML string");
            return switch (((String) value).trim().toLowerCase(Locale.ROOT)) {
                case "generic" -> GENERIC;
                case "none" -> NONE;
                default -> throw new IllegalArgumentException("problem_matcher must be generic or none");
            };
        }

        String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Presentation {
        ALWAYS,
        ON_FAILURE,
        NEVER;

        static Presentation parse(Object value) {
            if (value == null) return ON_FAILURE;
            if (!(value instanceof String)) throw new IllegalArgumentException("presentation must be TOML string");
            return switch (((String) value).trim().toLowerCase(Locale.ROOT)) {
                case "always" -> ALWAYS;
                case "on_failure" -> ON_FAILURE;
                case "never" -> NEVER;
                default -> throw new IllegalArgumentException("presentation must be always, on_failure, or never");
            };
        }

        String configValue() {
            return switch (this) {
                case ALWAYS -> "always";
                case ON_FAILURE -> "on_failure";
                case NEVER -> "never";
            };
        }
    }

    static final class WorkspaceTask {
        private final String name;
        private final String command;
        private final String cwd;
        private final Map<String, String> environment;
        private final ShellPolicy shell;
        private final ProblemMatcher problemMatcher;
        private final Presentation presentation;
        private final List<String> directArguments;

        WorkspaceTask(String name, String command, String cwd, Map<String, String> environment,
                      ShellPolicy shell, ProblemMatcher problemMatcher, Presentation presentation) {
            this(name, command, cwd, environment, shell, problemMatcher, presentation, null);
        }

        private WorkspaceTask(String name, String command, String cwd, Map<String, String> environment,
                              ShellPolicy shell, ProblemMatcher problemMatcher, Presentation presentation,
                              List<String> directArguments) {
            this.name = name;
            this.command = command;
            this.cwd = cwd;
            this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
            this.shell = shell;
            this.problemMatcher = problemMatcher;
            this.presentation = presentation;
            this.directArguments = directArguments == null ? null : Collections.unmodifiableList(new ArrayList<>(directArguments));
        }

        String name() { return name; }
        String command() { return command; }
        String cwd() { return cwd; }
        Map<String, String> environment() { return environment; }
        ShellPolicy shell() { return shell; }
        ProblemMatcher problemMatcher() { return problemMatcher; }
        Presentation presentation() { return presentation; }
        boolean hasDirectArguments() { return directArguments != null; }
        List<String> directArguments() { return directArguments == null ? List.of() : directArguments; }
    }

    static final class TaskLoadResult {
        private final Map<String, WorkspaceTask> tasks;
        private final List<String> diagnostics;

        TaskLoadResult(Map<String, WorkspaceTask> tasks, List<String> diagnostics) {
            this.tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        Map<String, WorkspaceTask> tasks() { return tasks; }
        List<String> diagnostics() { return diagnostics; }
        boolean isValid() { return diagnostics.isEmpty(); }
    }

    static final class TaskExecutionPlan {
        private final WorkspaceTask task;
        private final File workspace;
        private final String expandedCommand;
        private final List<String> processCommand;
        private final File workingDirectory;
        private final Map<String, String> environment;

        TaskExecutionPlan(WorkspaceTask task, File workspace, String expandedCommand, List<String> processCommand,
                          File workingDirectory, Map<String, String> environment) {
            this.task = task;
            this.workspace = workspace;
            this.expandedCommand = expandedCommand;
            this.processCommand = Collections.unmodifiableList(new ArrayList<>(processCommand));
            this.workingDirectory = workingDirectory;
            this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        }

        WorkspaceTask task() { return task; }
        File workspace() { return workspace; }
        String expandedCommand() { return expandedCommand; }
        List<String> processCommand() { return processCommand; }
        File workingDirectory() { return workingDirectory; }
        Map<String, String> environment() { return environment; }
    }

    public Map<String, String> loadTasks(File projectRoot) {
        Map<String, String> tasks = new LinkedHashMap<>();
        for (WorkspaceTask task : loadWorkspaceTasks(projectRoot).tasks().values()) {
            tasks.put(task.name(), task.command());
        }
        return tasks;
    }

    TaskLoadResult loadWorkspaceTasks(File projectRoot) {
        File taskFile = taskFile(projectRoot);
        if (taskFile == null || !taskFile.isFile()) return new TaskLoadResult(Map.of(), List.of());
        try {
            List<String> source = Files.readAllLines(taskFile.toPath(), StandardCharsets.UTF_8);
            TomlParseResult result = Toml.parse(taskFile.toPath());
            List<String> diagnostics = new ArrayList<>();
            for (TomlParseError error : result.errors()) {
                diagnostics.add(location(error.position()) + error.getMessage());
            }
            if (!diagnostics.isEmpty()) {
                return looksLikeLegacyTaskFile(source) ? loadLegacyLines(source) : new TaskLoadResult(Map.of(), diagnostics);
            }
            Map<String, WorkspaceTask> tasks = new LinkedHashMap<>();
            loadLegacyTasks(result, tasks, diagnostics);
            TomlTable taskTable = result.getTable("task");
            Object taskValue = result.get("task");
            if (taskValue != null && taskTable == null) {
                diagnostics.add(location(result.inputPositionOf("task")) + "task must be a TOML table");
            }
            if (taskTable != null || result.get(ConfigSchema.VERSION_KEY) != null) {
                String versionError = ConfigSchema.versionError(result);
                if (versionError != null) {
                    diagnostics.add(location(result.inputPositionOf(ConfigSchema.VERSION_KEY)) + versionError);
                } else if (taskTable != null) {
                    loadStructuredTasks(taskTable, tasks, diagnostics);
                }
            }
            return new TaskLoadResult(tasks, diagnostics);
        } catch (IOException | SecurityException error) {
            return new TaskLoadResult(Map.of(), List.of("Task configuration read failed: " + errorMessage(error)));
        }
    }

    TaskExecutionPlan buildExecutionPlan(WorkspaceTask task, File projectRoot, File activeFile) throws IOException {
        if (task == null) throw new IOException("task required");
        File workspace = canonicalDirectory(projectRoot, "workspace directory required");
        String command = task.hasDirectArguments() ? "" : expandVariables(task.command(), workspace, activeFile);
        if (!task.hasDirectArguments()) validateCommand(command);
        String cwdValue = expandVariables(task.cwd(), workspace, activeFile);
        File cwd = resolveWorkspaceDirectory(workspace, cwdValue);
        Map<String, String> environment = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : task.environment().entrySet()) {
            environment.put(entry.getKey(), expandVariables(entry.getValue(), workspace, activeFile));
        }
        Map<String, String> shellEnvironment = new HashMap<>(System.getenv());
        shellEnvironment.putAll(environment);
        List<String> processCommand = task.hasDirectArguments()
            ? expandDirectArguments(task.directArguments(), workspace, activeFile)
            : task.shell() == ShellPolicy.LOGIN
                ? ShellCommand.forCommand(command, shellEnvironment, path -> new File(path).canExecute())
                : ShellCommand.directCommand(command);
        if (processCommand.isEmpty()) throw new IOException("task command required");
        return new TaskExecutionPlan(task, workspace, task.hasDirectArguments() ? displayDirectCommand(processCommand) : command,
            processCommand, cwd, environment);
    }

    RemoteCommandRequest buildRemoteCommandRequest(TaskExecutionPlan plan, Path connectionRoot, String executionRoot,
                                                   File activeFile) throws IOException {
        if (plan == null || connectionRoot == null) throw new IOException("remote task plan and connection root are required");
        Path root = connectionRoot.toAbsolutePath().normalize();
        Path workingDirectory = plan.workingDirectory().toPath().toAbsolutePath().normalize();
        if (!workingDirectory.startsWith(root)) throw new IOException("task directory is outside the connected workspace");
        String relativeDirectory = root.relativize(workingDirectory).toString().replace(File.separatorChar, '/');
        String remoteCommand = plan.task().hasDirectArguments() ? "" : expandRemoteVariables(plan.task().command(), plan.workspace(), activeFile, root, executionRoot);
        Map<String, String> environment = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : plan.task().environment().entrySet()) {
            environment.put(entry.getKey(), expandRemoteVariables(entry.getValue(), plan.workspace(), activeFile, root, executionRoot));
        }
        List<String> command = plan.task().hasDirectArguments()
            ? expandRemoteDirectArguments(plan.task().directArguments(), plan.workspace(), activeFile, root, executionRoot)
            : plan.task().shell() == ShellPolicy.LOGIN ? List.of("sh", "-lc", remoteCommand) : ShellCommand.directCommand(remoteCommand);
        return new RemoteCommandRequest(command, relativeDirectory, environment);
    }

    public void saveTasks(File projectRoot, Map<String, String> tasks) throws IOException {
        Map<String, WorkspaceTask> workspaceTasks = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : tasks.entrySet()) {
            String name = entry.getKey();
            String command = entry.getValue();
            if (name == null || command == null || name.isBlank() || command.isBlank()) continue;
            workspaceTasks.put(name, defaultWorkspaceTask(name, command));
        }
        saveWorkspaceTasks(projectRoot, workspaceTasks);
    }

    void saveWorkspaceTasks(File projectRoot, Map<String, WorkspaceTask> tasks) throws IOException {
        File taskFile = taskFile(projectRoot);
        if (taskFile == null) throw new IOException("project root required");
        File parent = taskFile.getParentFile();
        if (parent != null && !parent.exists()) Files.createDirectories(parent.toPath());
        List<String> lines = new ArrayList<>();
        lines.add("# Shed workspace tasks");
        lines.add("schema_version = 1");
        List<String> names = new ArrayList<>(tasks.keySet());
        Collections.sort(names);
        for (String name : names) {
            WorkspaceTask task = tasks.get(name);
            if (task == null) continue;
            validateTask(task);
            lines.add("");
            lines.add("[task." + name + "]");
            lines.add("command = " + tomlString(task.command()));
            if (!"${workspaceFolder}".equals(task.cwd())) lines.add("cwd = " + tomlString(task.cwd()));
            if (task.shell() != ShellPolicy.LOGIN) lines.add("shell = " + tomlString(task.shell().configValue()));
            if (task.problemMatcher() != ProblemMatcher.GENERIC) {
                lines.add("problem_matcher = " + tomlString(task.problemMatcher().configValue()));
            }
            if (task.presentation() != Presentation.ON_FAILURE) {
                lines.add("presentation = " + tomlString(task.presentation().configValue()));
            }
            if (!task.environment().isEmpty()) {
                lines.add("");
                lines.add("[task." + name + ".env]");
                List<String> keys = new ArrayList<>(task.environment().keySet());
                Collections.sort(keys);
                for (String key : keys) lines.add(key + " = " + tomlString(task.environment().get(key)));
            }
        }
        Files.write(taskFile.toPath(), lines, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public File taskFile(File projectRoot) {
        return projectRoot == null ? null : new File(projectRoot, TASKS_FILE_NAME);
    }

    static boolean isValidTaskName(String name) {
        return name != null && TASK_NAME.matcher(name).matches();
    }

    private void loadLegacyTasks(TomlParseResult result, Map<String, WorkspaceTask> tasks, List<String> diagnostics) {
        for (String key : result.keySet()) {
            if (ConfigSchema.VERSION_KEY.equals(key) || "task".equals(key)) continue;
            Object value = result.get(key);
            if (!(value instanceof String)) {
                diagnostics.add(location(result.inputPositionOf(key)) + "legacy task " + key + " must be a TOML string");
                continue;
            }
            if (!isValidTaskName(key)) {
                diagnostics.add(location(result.inputPositionOf(key)) + "invalid task name: " + key);
                continue;
            }
            try {
                tasks.put(key, defaultWorkspaceTask(key, (String) value));
            } catch (IllegalArgumentException error) {
                diagnostics.add(location(result.inputPositionOf(key)) + error.getMessage());
            }
        }
    }

    private TaskLoadResult loadLegacyLines(List<String> lines) {
        Map<String, WorkspaceTask> tasks = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int separator = trimmed.indexOf('=');
            if (separator <= 0) continue;
            String name = trimmed.substring(0, separator).trim();
            String command = trimmed.substring(separator + 1).trim();
            if (!isValidTaskName(name) || command.isEmpty()) continue;
            try {
                tasks.put(name, defaultWorkspaceTask(name, command));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new TaskLoadResult(tasks, List.of());
    }

    private boolean looksLikeLegacyTaskFile(List<String> lines) {
        boolean hasTask = false;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("[") || trimmed.startsWith("schema_version")) return false;
            if (trimmed.indexOf('=') > 0) hasTask = true;
        }
        return hasTask;
    }

    private void loadStructuredTasks(TomlTable taskTable, Map<String, WorkspaceTask> tasks, List<String> diagnostics) {
        for (String name : taskTable.keySet()) {
            Object raw = taskTable.get(name);
            if (!(raw instanceof TomlTable)) {
                diagnostics.add("task." + name + " must be a TOML table");
                continue;
            }
            if (!isValidTaskName(name)) {
                diagnostics.add("invalid task name: " + name);
                continue;
            }
            try {
                tasks.put(name, structuredTask(name, (TomlTable) raw));
            } catch (IllegalArgumentException error) {
                diagnostics.add("task." + name + ": " + error.getMessage());
            }
        }
    }

    static WorkspaceTask defaultWorkspaceTask(String name, String command) {
        if (!isValidTaskName(name)) throw new IllegalArgumentException("invalid task name: " + name);
        validateCommand(command);
        return new WorkspaceTask(name, command.trim(), "${workspaceFolder}", Map.of(),
            ShellPolicy.LOGIN, ProblemMatcher.GENERIC, Presentation.ON_FAILURE);
    }

    /** Creates an ephemeral direct-argv task without serializing its arguments into shell syntax. */
    static WorkspaceTask directWorkspaceTask(String name, List<String> arguments, String cwd, Map<String, String> environment,
                                             ProblemMatcher problemMatcher, Presentation presentation) {
        if (!isValidTaskName(name)) throw new IllegalArgumentException("invalid task name: " + name);
        if (arguments == null || arguments.isEmpty()) throw new IllegalArgumentException("direct task arguments required");
        List<String> values = new ArrayList<>();
        for (String argument : arguments) {
            if (argument == null) throw new IllegalArgumentException("direct task argument required");
            validateSingleLine(argument, "direct task argument");
            values.add(argument);
        }
        if (cwd == null || cwd.isBlank()) throw new IllegalArgumentException("cwd must not be empty");
        validateSingleLine(cwd, "cwd");
        Map<String, String> valuesEnvironment = environment == null ? Map.of() : environment;
        for (Map.Entry<String, String> entry : valuesEnvironment.entrySet()) {
            if (!ENVIRONMENT_NAME.matcher(entry.getKey()).matches()) throw new IllegalArgumentException("invalid env name: " + entry.getKey());
            if (entry.getValue() == null) throw new IllegalArgumentException("environment value required");
            validateSingleLine(entry.getValue(), "environment value");
        }
        if (problemMatcher == null || presentation == null) throw new IllegalArgumentException("task settings required");
        return new WorkspaceTask(name, displayDirectCommand(values), cwd, valuesEnvironment, ShellPolicy.DIRECT, problemMatcher, presentation, values);
    }

    private WorkspaceTask structuredTask(String name, TomlTable table) {
        rejectUnknownFields(table, name);
        Object command = table.get("command");
        if (!(command instanceof String)) throw new IllegalArgumentException("command must be a non-empty TOML string");
        validateCommand((String) command);
        Object cwd = table.get("cwd");
        if (cwd != null && !(cwd instanceof String)) throw new IllegalArgumentException("cwd must be TOML string");
        String cwdValue = cwd == null ? "${workspaceFolder}" : ((String) cwd).trim();
        if (cwdValue.isEmpty()) throw new IllegalArgumentException("cwd must not be empty");
        validateSingleLine(cwdValue, "cwd");
        Object environmentValue = table.get("env");
        if (environmentValue != null && !(environmentValue instanceof TomlTable)) {
            throw new IllegalArgumentException("env must be a TOML table");
        }
        Map<String, String> environment = environment((TomlTable) environmentValue);
        return new WorkspaceTask(name, ((String) command).trim(), cwdValue, environment,
            ShellPolicy.parse(table.get("shell")), ProblemMatcher.parse(table.get("problem_matcher")),
            Presentation.parse(table.get("presentation")));
    }

    private void validateTask(WorkspaceTask task) {
        if (!isValidTaskName(task.name())) throw new IllegalArgumentException("invalid task name: " + task.name());
        if (task.hasDirectArguments()) throw new IllegalArgumentException("ephemeral direct-argument tasks cannot be written to .shedtasks");
        validateCommand(task.command());
        if (task.cwd() == null || task.cwd().isBlank()) throw new IllegalArgumentException("cwd must not be empty");
        validateSingleLine(task.cwd(), "cwd");
        if (task.shell() == null || task.problemMatcher() == null || task.presentation() == null) {
            throw new IllegalArgumentException("task settings required");
        }
        for (Map.Entry<String, String> entry : task.environment().entrySet()) {
            if (!ENVIRONMENT_NAME.matcher(entry.getKey()).matches()) throw new IllegalArgumentException("invalid env name: " + entry.getKey());
            validateEnvironmentValue(entry.getValue());
        }
    }

    private void rejectUnknownFields(TomlTable table, String name) {
        for (String field : table.keySet()) {
            if (!"command".equals(field) && !"cwd".equals(field) && !"env".equals(field)
                && !"shell".equals(field) && !"problem_matcher".equals(field) && !"presentation".equals(field)) {
                throw new IllegalArgumentException("unknown field " + field);
            }
        }
    }

    private Map<String, String> environment(TomlTable table) {
        if (table == null) return Map.of();
        Map<String, String> environment = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            Object value = table.get(key);
            if (!ENVIRONMENT_NAME.matcher(key).matches()) throw new IllegalArgumentException("invalid env name: " + key);
            if (!(value instanceof String)) throw new IllegalArgumentException("env." + key + " must be TOML string");
            validateEnvironmentValue((String) value);
            environment.put(key, (String) value);
        }
        return environment;
    }

    private static void validateCommand(String command) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("task command required");
        validateSingleLine(command, "task command");
    }

    private void validateEnvironmentValue(String value) {
        if (value == null) throw new IllegalArgumentException("environment value required");
        validateSingleLine(value, "environment value");
    }

    private static void validateSingleLine(String value, String label) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " must be a single line without null bytes");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) && character != '\t') {
                throw new IllegalArgumentException(label + " contains invalid control character");
            }
        }
    }

    private String expandVariables(String value, File workspace, File activeFile) throws IOException {
        if (value == null) throw new IOException("task value required");
        java.util.regex.Matcher matcher = VARIABLE.matcher(value);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "workspaceFolder" -> workspace.getPath();
                case "file" -> activeFilePath(activeFile);
                case "relativeFile" -> relativeFilePath(workspace, activeFile);
                case "fileBasename" -> activeFileName(activeFile);
                default -> throw new IOException("unsupported task variable: ${" + matcher.group(1) + "}");
            };
            matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private List<String> expandDirectArguments(List<String> values, File workspace, File activeFile) throws IOException {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String expanded = expandVariables(value, workspace, activeFile);
            validateSingleLine(expanded, "direct task argument");
            result.add(expanded);
        }
        if (result.isEmpty()) throw new IOException("task command required");
        return List.copyOf(result);
    }

    private List<String> expandRemoteDirectArguments(List<String> values, File workspace, File activeFile, Path connectionRoot,
                                                      String executionRoot) throws IOException {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String expanded = expandRemoteVariables(value, workspace, activeFile, connectionRoot, executionRoot);
            validateSingleLine(expanded, "direct task argument");
            result.add(expanded);
        }
        if (result.isEmpty()) throw new IOException("task command required");
        return List.copyOf(result);
    }

    private String expandRemoteVariables(String value, File workspace, File activeFile, Path connectionRoot,
                                         String executionRoot) throws IOException {
        if (value == null) throw new IOException("task value required");
        Path workspacePath = workspace.toPath().toAbsolutePath().normalize();
        if (!workspacePath.startsWith(connectionRoot)) {
            throw new IOException("task workspace is outside the connected workspace");
        }
        java.util.regex.Matcher matcher = VARIABLE.matcher(value);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "workspaceFolder" -> remotePath(executionRoot, connectionRoot.relativize(workspacePath));
                case "file" -> remoteFilePath(activeFile, connectionRoot, executionRoot);
                case "relativeFile" -> relativeFilePath(workspace, activeFile);
                case "fileBasename" -> activeFileName(activeFile);
                default -> throw new IOException("unsupported task variable: ${" + matcher.group(1) + "}");
            };
            matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private String remoteFilePath(File activeFile, Path connectionRoot, String executionRoot) throws IOException {
        File file = canonicalActiveFile(activeFile, "${file} requires a file-backed active buffer");
        Path path = file.toPath().toAbsolutePath().normalize();
        if (!path.startsWith(connectionRoot)) throw new IOException("${file} must be inside the connected workspace");
        return remotePath(executionRoot, connectionRoot.relativize(path));
    }

    private String remotePath(String executionRoot, Path relative) throws IOException {
        String root = executionRoot == null ? "" : executionRoot.trim().replace('\\', '/');
        if (root.isEmpty() || root.indexOf('\0') >= 0 || root.indexOf('\n') >= 0 || root.indexOf('\r') >= 0) {
            throw new IOException("remote provider does not expose an execution root for workspace variables");
        }
        String suffix = relative == null ? "" : relative.toString().replace(File.separatorChar, '/');
        return suffix.isEmpty() ? root : (root.endsWith("/") ? root + suffix : root + "/" + suffix);
    }

    private String activeFilePath(File activeFile) throws IOException {
        if (activeFile == null) throw new IOException("${file} requires a file-backed active buffer");
        return activeFile.getCanonicalPath();
    }

    private String relativeFilePath(File workspace, File activeFile) throws IOException {
        File file = canonicalActiveFile(activeFile, "${relativeFile} requires a file-backed active buffer");
        Path workspacePath = workspace.toPath();
        if (!file.toPath().startsWith(workspacePath)) throw new IOException("${relativeFile} must be inside workspace");
        return workspacePath.relativize(file.toPath()).toString();
    }

    private String activeFileName(File activeFile) throws IOException {
        File file = canonicalActiveFile(activeFile, "${fileBasename} requires a file-backed active buffer");
        return file.getName();
    }

    private File resolveWorkspaceDirectory(File workspace, String cwd) throws IOException {
        Path candidate = Path.of(cwd);
        if (!candidate.isAbsolute()) candidate = workspace.toPath().resolve(candidate);
        File directory = canonicalDirectory(candidate.toFile(), "task cwd is not a directory: " + cwd);
        if (!directory.toPath().startsWith(workspace.toPath())) throw new IOException("task cwd must remain inside workspace");
        return directory;
    }

    private File canonicalDirectory(File directory, String error) throws IOException {
        File canonical = canonicalFile(directory, error);
        if (!canonical.isDirectory()) throw new IOException(error);
        return canonical;
    }

    private File canonicalFile(File file, String error) throws IOException {
        if (file == null || !file.exists()) throw new IOException(error);
        return file.getCanonicalFile();
    }

    private File canonicalActiveFile(File file, String error) throws IOException {
        if (file == null) throw new IOException(error);
        return file.getCanonicalFile();
    }

    private String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t") + "\"";
    }

    private static String displayDirectCommand(List<String> command) {
        List<String> displayed = new ArrayList<>();
        for (String argument : command == null ? List.<String>of() : command) {
            String value = argument == null ? "" : argument;
            displayed.add('"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
        }
        return String.join(" ", displayed);
    }

    private String location(org.tomlj.TomlPosition position) {
        return position == null ? "" : "line " + position.line() + ", column " + position.column() + ": ";
    }

    private String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
