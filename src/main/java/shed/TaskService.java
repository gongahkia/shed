package shed;

import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import org.tomlj.TomlArray;

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
    private static final Pattern TASK_INPUT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_DEPENDENCIES = 100;
    private static final int MAX_TASK_INPUTS = 32;
    private static final int MAX_INPUT_OPTIONS = 100;
    private static final int MAX_INPUT_VALUE_LENGTH = 256;

    enum ShellPolicy {
        LOGIN,
        SHELL,
        DIRECT;

        static ShellPolicy parse(Object value) {
            if (value == null) return LOGIN;
            if (!(value instanceof String)) throw new IllegalArgumentException("shell must be TOML string");
            return switch (((String) value).trim().toLowerCase(Locale.ROOT)) {
                case "login" -> LOGIN;
                case "shell" -> SHELL;
                case "direct" -> DIRECT;
                default -> throw new IllegalArgumentException("shell must be login, shell, or direct");
            };
        }

        String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum ProblemMatcher {
        GENERIC,
        TYPESCRIPT,
        ESLINT,
        MSCOMPILE,
        CUSTOM,
        NONE;

        static ProblemMatcher parse(Object value) {
            if (value == null) return GENERIC;
            if (!(value instanceof String)) throw new IllegalArgumentException("problem_matcher must be TOML string");
            return switch (((String) value).trim().toLowerCase(Locale.ROOT)) {
                case "generic" -> GENERIC;
                case "typescript", "tsc" -> TYPESCRIPT;
                case "eslint" -> ESLINT;
                case "mscompile", "msvc" -> MSCOMPILE;
                case "custom" -> CUSTOM;
                case "none" -> NONE;
                default -> throw new IllegalArgumentException("problem_matcher must be generic, typescript, eslint, mscompile, custom, or none");
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

    /** Controls whether a requested task's independent dependency stages may run concurrently. */
    enum DependencyOrder {
        SEQUENTIAL,
        PARALLEL;

        static DependencyOrder parse(Object value) {
            if (value == null) return SEQUENTIAL;
            if (!(value instanceof String)) throw new IllegalArgumentException("depends_order must be TOML string");
            return switch (((String) value).trim().toLowerCase(Locale.ROOT)) {
                case "sequential" -> SEQUENTIAL;
                case "parallel" -> PARALLEL;
                default -> throw new IllegalArgumentException("depends_order must be sequential or parallel");
            };
        }

        String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A bounded, task-local value selected explicitly on the command line. */
    static final class TaskInput {
        private final String name;
        private final String defaultValue;
        private final List<String> options;

        TaskInput(String name, String defaultValue, List<String> options) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.options = Collections.unmodifiableList(new ArrayList<>(options == null ? List.of() : options));
        }

        String name() { return name; }
        String defaultValue() { return defaultValue; }
        List<String> options() { return options; }
    }

    /** The only VS Code task groups Shed can use as an explicit build/test entry point. */
    enum TaskGroup {
        NONE,
        BUILD,
        TEST;

        static TaskGroup requestedBy(String taskName) {
            if (taskName == null) return NONE;
            return switch (taskName.trim().toLowerCase(Locale.ROOT)) {
                case "build" -> BUILD;
                case "test" -> TEST;
                default -> NONE;
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
        private final List<String> shellArguments;
        private final boolean sessionOnly;
        private final List<String> dependencies;
        private final DependencyOrder dependencyOrder;
        private final TaskGroup group;
        private final boolean defaultGroup;
        private final boolean background;
        private final String readyWhen;
        private final Map<String, TaskInput> inputs;
        private final TaskDiagnosticTemplate customProblemMatcher;

        WorkspaceTask(String name, String command, String cwd, Map<String, String> environment,
                      ShellPolicy shell, ProblemMatcher problemMatcher, Presentation presentation) {
            this(name, command, cwd, environment, shell, problemMatcher, presentation, null, null, false, List.of(), DependencyOrder.SEQUENTIAL,
                TaskGroup.NONE, false, false, "", Map.of(), null);
        }

        private WorkspaceTask(String name, String command, String cwd, Map<String, String> environment,
                              ShellPolicy shell, ProblemMatcher problemMatcher, Presentation presentation,
                              List<String> directArguments, List<String> shellArguments, boolean sessionOnly,
                              List<String> dependencies, DependencyOrder dependencyOrder, TaskGroup group, boolean defaultGroup, boolean background, String readyWhen,
                              Map<String, TaskInput> inputs, TaskDiagnosticTemplate customProblemMatcher) {
            this.name = name;
            this.command = command;
            this.cwd = cwd;
            this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
            this.shell = shell;
            this.problemMatcher = problemMatcher;
            this.presentation = presentation;
            this.directArguments = directArguments == null ? null : Collections.unmodifiableList(new ArrayList<>(directArguments));
            this.shellArguments = shellArguments == null ? null : Collections.unmodifiableList(new ArrayList<>(shellArguments));
            this.sessionOnly = sessionOnly;
            this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies == null ? List.of() : dependencies));
            this.dependencyOrder = dependencyOrder == null ? DependencyOrder.SEQUENTIAL : dependencyOrder;
            this.group = group == null ? TaskGroup.NONE : group;
            this.defaultGroup = defaultGroup && this.group != TaskGroup.NONE;
            this.background = background;
            this.readyWhen = readyWhen == null ? "" : readyWhen;
            this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs == null ? Map.of() : inputs));
            this.customProblemMatcher = customProblemMatcher;
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
        boolean hasShellArguments() { return shellArguments != null; }
        List<String> shellArguments() { return shellArguments == null ? List.of() : shellArguments; }
        boolean sessionOnly() { return sessionOnly; }
        List<String> dependencies() { return dependencies; }
        DependencyOrder dependencyOrder() { return dependencyOrder; }
        TaskGroup group() { return group; }
        boolean defaultGroup() { return defaultGroup; }
        boolean background() { return background; }
        String readyWhen() { return readyWhen; }
        Map<String, TaskInput> inputs() { return inputs; }
        TaskDiagnosticTemplate customProblemMatcher() { return customProblemMatcher; }
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
        private final Map<String, String> inputValues;

        TaskExecutionPlan(WorkspaceTask task, File workspace, String expandedCommand, List<String> processCommand,
                          File workingDirectory, Map<String, String> environment, Map<String, String> inputValues) {
            this.task = task;
            this.workspace = workspace;
            this.expandedCommand = expandedCommand;
            this.processCommand = Collections.unmodifiableList(new ArrayList<>(processCommand));
            this.workingDirectory = workingDirectory;
            this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
            this.inputValues = Collections.unmodifiableMap(new LinkedHashMap<>(inputValues));
        }

        WorkspaceTask task() { return task; }
        File workspace() { return workspace; }
        String expandedCommand() { return expandedCommand; }
        List<String> processCommand() { return processCommand; }
        File workingDirectory() { return workingDirectory; }
        Map<String, String> environment() { return environment; }
        Map<String, String> inputValues() { return inputValues; }
    }

    /** Builds a dependency-first, sequential execution plan without starting any task. */
    List<TaskExecutionPlan> buildExecutionPlans(String taskName, Map<String, WorkspaceTask> tasks,
                                                File projectRoot, File activeFile) throws IOException {
        return buildExecutionPlans(taskName, tasks, projectRoot, activeFile, Map.of());
    }

    List<TaskExecutionPlan> buildExecutionPlans(String taskName, Map<String, WorkspaceTask> tasks,
                                                File projectRoot, File activeFile, Map<String, String> suppliedInputs) throws IOException {
        if (taskName == null || taskName.isBlank()) throw new IOException("task name required");
        Map<String, WorkspaceTask> available = tasks == null ? Map.of() : tasks;
        List<WorkspaceTask> ordered = new ArrayList<>();
        List<String> path = new ArrayList<>();
        java.util.HashSet<String> completed = new java.util.HashSet<>();
        resolveTaskDependencies(taskName, available, path, completed, ordered);
        Map<String, String> inputs = validatedInputAssignments(suppliedInputs);
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (WorkspaceTask task : ordered) declared.addAll(task.inputs().keySet());
        for (String name : inputs.keySet()) {
            if (!declared.contains(name)) throw new IOException("task input is not declared in this task plan: " + name);
        }
        List<TaskExecutionPlan> plans = new ArrayList<>(ordered.size());
        for (WorkspaceTask task : ordered) {
            Map<String, String> taskInputs = new LinkedHashMap<>();
            for (String name : task.inputs().keySet()) {
                if (inputs.containsKey(name)) taskInputs.put(name, inputs.get(name));
            }
            plans.add(buildExecutionPlan(task, projectRoot, activeFile, taskInputs));
        }
        return List.copyOf(plans);
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
        return buildExecutionPlan(task, projectRoot, activeFile, Map.of());
    }

    TaskExecutionPlan buildExecutionPlan(WorkspaceTask task, File projectRoot, File activeFile,
                                         Map<String, String> suppliedInputs) throws IOException {
        if (task == null) throw new IOException("task required");
        Map<String, String> inputs = resolvedInputValues(task, suppliedInputs);
        File workspace = canonicalDirectory(projectRoot, "workspace directory required");
        String command = task.hasDirectArguments() ? "" : task.hasShellArguments()
            ? ShellCommand.posixQuotedCommand(expandShellArguments(task.shellArguments(), workspace, activeFile, inputs))
            : expandVariables(task.command(), workspace, activeFile, inputs);
        if (!task.hasDirectArguments() && !task.hasShellArguments()) validateCommand(command);
        String cwdValue = expandVariables(task.cwd(), workspace, activeFile, inputs);
        File cwd = resolveWorkspaceDirectory(workspace, cwdValue);
        Map<String, String> environment = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : task.environment().entrySet()) {
            environment.put(entry.getKey(), expandVariables(entry.getValue(), workspace, activeFile, inputs));
        }
        Map<String, String> shellEnvironment = new HashMap<>(System.getenv());
        shellEnvironment.putAll(environment);
        List<String> processCommand = task.hasDirectArguments()
            ? expandDirectArguments(task.directArguments(), workspace, activeFile, inputs)
            : task.shell() == ShellPolicy.LOGIN
                ? ShellCommand.forCommand(command, shellEnvironment, path -> new File(path).canExecute())
                : task.shell() == ShellPolicy.SHELL
                    ? ShellCommand.nonLoginForCommand(command, shellEnvironment, path -> new File(path).canExecute())
                : ShellCommand.directCommand(command);
        if (processCommand.isEmpty()) throw new IOException("task command required");
        return new TaskExecutionPlan(task, workspace, task.hasDirectArguments() ? displayDirectCommand(processCommand) : command,
            processCommand, cwd, environment, inputs);
    }

    RemoteCommandRequest buildRemoteCommandRequest(TaskExecutionPlan plan, Path connectionRoot, String executionRoot,
                                                   File activeFile) throws IOException {
        if (plan == null || connectionRoot == null) throw new IOException("remote task plan and connection root are required");
        Path root = connectionRoot.toAbsolutePath().normalize();
        Path workingDirectory = plan.workingDirectory().toPath().toAbsolutePath().normalize();
        if (!workingDirectory.startsWith(root)) throw new IOException("task directory is outside the connected workspace");
        String relativeDirectory = root.relativize(workingDirectory).toString().replace(File.separatorChar, '/');
        String remoteCommand = plan.task().hasDirectArguments() ? "" : plan.task().hasShellArguments()
            ? ShellCommand.posixQuotedCommand(expandRemoteShellArguments(plan.task().shellArguments(), plan.workspace(), activeFile, root, executionRoot,
                plan.inputValues()))
            : expandRemoteVariables(plan.task().command(), plan.workspace(), activeFile, root, executionRoot, plan.inputValues());
        Map<String, String> environment = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : plan.task().environment().entrySet()) {
            environment.put(entry.getKey(), expandRemoteVariables(entry.getValue(), plan.workspace(), activeFile, root, executionRoot, plan.inputValues()));
        }
        List<String> command = plan.task().hasDirectArguments()
            ? expandRemoteDirectArguments(plan.task().directArguments(), plan.workspace(), activeFile, root, executionRoot, plan.inputValues())
            : plan.task().shell() == ShellPolicy.LOGIN ? List.of("sh", "-lc", remoteCommand)
                : plan.task().shell() == ShellPolicy.SHELL ? List.of("sh", "-c", remoteCommand)
                    : ShellCommand.directCommand(remoteCommand);
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
            if (task.customProblemMatcher() != null) lines.add("problem_pattern = " + tomlString(task.customProblemMatcher().source()));
            if (task.presentation() != Presentation.ON_FAILURE) {
                lines.add("presentation = " + tomlString(task.presentation().configValue()));
            }
            if (task.background()) lines.add("background = true");
            if (!task.readyWhen().isBlank()) lines.add("ready_when = " + tomlString(task.readyWhen()));
            if (!task.dependencies().isEmpty()) {
                lines.add("depends_on = [" + task.dependencies().stream().map(this::tomlString)
                    .collect(java.util.stream.Collectors.joining(", ")) + "]");
            }
            if (task.dependencyOrder() != DependencyOrder.SEQUENTIAL) {
                lines.add("depends_order = " + tomlString(task.dependencyOrder().configValue()));
            }
            if (!task.environment().isEmpty()) {
                lines.add("");
                lines.add("[task." + name + ".env]");
                List<String> keys = new ArrayList<>(task.environment().keySet());
                Collections.sort(keys);
                for (String key : keys) lines.add(key + " = " + tomlString(task.environment().get(key)));
            }
            if (!task.inputs().isEmpty()) {
                List<String> inputNames = new ArrayList<>(task.inputs().keySet());
                Collections.sort(inputNames);
                for (String inputName : inputNames) {
                    TaskInput input = task.inputs().get(inputName);
                    lines.add("");
                    lines.add("[task." + name + ".input." + inputName + "]");
                    if (input.defaultValue() != null) lines.add("default = " + tomlString(input.defaultValue()));
                    if (!input.options().isEmpty()) {
                        lines.add("options = [" + input.options().stream().map(this::tomlString)
                            .collect(java.util.stream.Collectors.joining(", ")) + "]");
                    }
                }
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
        return new WorkspaceTask(name, displayDirectCommand(values), cwd, valuesEnvironment, ShellPolicy.DIRECT, problemMatcher, presentation,
            values, null, true, List.of(), DependencyOrder.SEQUENTIAL, TaskGroup.NONE, false, false, "", Map.of(), null);
    }

    /**
     * Creates an ephemeral POSIX shell task from separately held command and arguments.
     * It is intentionally not serializable: the original values must be expanded then
     * quoted at the execution boundary, rather than flattened into a shell string.
     */
    static WorkspaceTask shellWorkspaceTask(String name, List<String> arguments, String cwd, Map<String, String> environment,
                                            ProblemMatcher problemMatcher, Presentation presentation) {
        if (!isValidTaskName(name)) throw new IllegalArgumentException("invalid task name: " + name);
        List<String> values = validatedArguments(arguments, "shell task argument");
        if (cwd == null || cwd.isBlank()) throw new IllegalArgumentException("cwd must not be empty");
        validateSingleLine(cwd, "cwd");
        Map<String, String> valuesEnvironment = validatedEnvironment(environment);
        if (problemMatcher == null || presentation == null) throw new IllegalArgumentException("task settings required");
        return new WorkspaceTask(name, displayDirectCommand(values), cwd, valuesEnvironment, ShellPolicy.SHELL, problemMatcher, presentation,
            null, values, true, List.of(), DependencyOrder.SEQUENTIAL, TaskGroup.NONE, false, false, "", Map.of(), null);
    }

    /** Creates an ephemeral shell task whose sole command is intentionally raw shell syntax. */
    static WorkspaceTask rawShellWorkspaceTask(String name, String command, String cwd, Map<String, String> environment,
                                                ProblemMatcher problemMatcher, Presentation presentation) {
        if (!isValidTaskName(name)) throw new IllegalArgumentException("invalid task name: " + name);
        validateCommand(command);
        if (cwd == null || cwd.isBlank()) throw new IllegalArgumentException("cwd must not be empty");
        validateSingleLine(cwd, "cwd");
        Map<String, String> valuesEnvironment = validatedEnvironment(environment);
        if (problemMatcher == null || presentation == null) throw new IllegalArgumentException("task settings required");
        return new WorkspaceTask(name, command.trim(), cwd, valuesEnvironment, ShellPolicy.SHELL, problemMatcher, presentation,
            null, null, true, List.of(), DependencyOrder.SEQUENTIAL, TaskGroup.NONE, false, false, "", Map.of(), null);
    }

    static WorkspaceTask withDependencies(WorkspaceTask task, List<String> dependencies) {
        if (task == null) throw new IllegalArgumentException("task required");
        List<String> values = validatedDependencies(dependencies);
        return new WorkspaceTask(task.name(), task.command(), task.cwd(), task.environment(), task.shell(), task.problemMatcher(),
            task.presentation(), task.directArguments, task.shellArguments, task.sessionOnly(), values, task.dependencyOrder(), task.group(), task.defaultGroup(), task.background(),
            task.readyWhen(), task.inputs(), task.customProblemMatcher());
    }

    static WorkspaceTask withDependencyOrder(WorkspaceTask task, DependencyOrder order) {
        if (task == null || order == null) throw new IllegalArgumentException("task dependency order is required");
        return new WorkspaceTask(task.name(), task.command(), task.cwd(), task.environment(), task.shell(), task.problemMatcher(),
            task.presentation(), task.directArguments, task.shellArguments, task.sessionOnly(), task.dependencies(), order, task.group(), task.defaultGroup(),
            task.background(), task.readyWhen(), task.inputs(), task.customProblemMatcher());
    }

    static WorkspaceTask withGroup(WorkspaceTask task, TaskGroup group, boolean defaultGroup) {
        if (task == null) throw new IllegalArgumentException("task required");
        if (group == null) throw new IllegalArgumentException("task group required");
        return new WorkspaceTask(task.name(), task.command(), task.cwd(), task.environment(), task.shell(), task.problemMatcher(),
            task.presentation(), task.directArguments, task.shellArguments, task.sessionOnly(), task.dependencies(), task.dependencyOrder(), group, defaultGroup, task.background(),
            task.readyWhen(), task.inputs(), task.customProblemMatcher());
    }

    static WorkspaceTask withBackground(WorkspaceTask task, boolean background) {
        if (task == null) throw new IllegalArgumentException("task required");
        return new WorkspaceTask(task.name(), task.command(), task.cwd(), task.environment(), task.shell(), task.problemMatcher(),
            task.presentation(), task.directArguments, task.shellArguments, task.sessionOnly(), task.dependencies(), task.dependencyOrder(), task.group(), task.defaultGroup(), background,
            background ? task.readyWhen() : "", task.inputs(), task.customProblemMatcher());
    }

    static WorkspaceTask withReadinessMarker(WorkspaceTask task, String readyWhen) {
        if (task == null) throw new IllegalArgumentException("task required");
        String marker = readyWhen == null || readyWhen.isEmpty() ? "" : validatedReadinessMarker(readyWhen);
        if (!marker.isEmpty() && !task.background()) throw new IllegalArgumentException("ready_when requires background = true");
        return new WorkspaceTask(task.name(), task.command(), task.cwd(), task.environment(), task.shell(), task.problemMatcher(),
            task.presentation(), task.directArguments, task.shellArguments, task.sessionOnly(), task.dependencies(), task.dependencyOrder(), task.group(), task.defaultGroup(),
            task.background(), marker, task.inputs(), task.customProblemMatcher());
    }

    static WorkspaceTask withInputs(WorkspaceTask task, Map<String, TaskInput> inputs) {
        if (task == null) throw new IllegalArgumentException("task required");
        Map<String, TaskInput> values = validatedTaskInputs(inputs);
        return new WorkspaceTask(task.name(), task.command(), task.cwd(), task.environment(), task.shell(), task.problemMatcher(),
            task.presentation(), task.directArguments, task.shellArguments, task.sessionOnly(), task.dependencies(), task.dependencyOrder(), task.group(), task.defaultGroup(),
            task.background(), task.readyWhen(), values, task.customProblemMatcher());
    }

    static WorkspaceTask withCustomProblemMatcher(WorkspaceTask task, TaskDiagnosticTemplate matcher) {
        if (task == null || matcher == null) throw new IllegalArgumentException("custom problem matcher is required");
        if (task.problemMatcher() != ProblemMatcher.CUSTOM) throw new IllegalArgumentException("custom matcher requires problem_matcher = custom");
        return new WorkspaceTask(task.name(), task.command(), task.cwd(), task.environment(), task.shell(), task.problemMatcher(),
            task.presentation(), task.directArguments, task.shellArguments, task.sessionOnly(), task.dependencies(), task.dependencyOrder(), task.group(), task.defaultGroup(),
            task.background(), task.readyWhen(), task.inputs(), matcher);
    }

    /** A watcher may only be the final plan because it does not complete until explicitly stopped. */
    static String backgroundPlanError(List<TaskExecutionPlan> plans) {
        if (plans == null) return null;
        for (int index = 0; index + 1 < plans.size(); index++) {
            TaskExecutionPlan plan = plans.get(index);
            if (plan != null && plan.task() != null && plan.task().background()) {
                return "background task '" + plan.task().name() + "' cannot be a dependency";
            }
        }
        return null;
    }

    static boolean hasBackgroundTask(List<TaskExecutionPlan> plans) {
        if (plans == null) return false;
        for (TaskExecutionPlan plan : plans) {
            if (plan != null && plan.task() != null && plan.task().background()) return true;
        }
        return false;
    }

    /** Returns an explicit imported default build/test task only when it is unambiguous. */
    static WorkspaceTask defaultGroupTask(String taskName, Map<String, WorkspaceTask> tasks) {
        TaskGroup requested = TaskGroup.requestedBy(taskName);
        if (requested == TaskGroup.NONE || tasks == null || tasks.isEmpty()) return null;
        WorkspaceTask selected = null;
        for (WorkspaceTask task : tasks.values()) {
            if (task == null || task.group() != requested || !task.defaultGroup()) continue;
            if (selected != null) return null;
            selected = task;
        }
        return selected;
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
        ProblemMatcher matcher = ProblemMatcher.parse(table.get("problem_matcher"));
        TaskDiagnosticTemplate customMatcher = customProblemMatcher(table, matcher);
        Map<String, TaskInput> inputs = taskInputs(table);
        DependencyOrder dependencyOrder = DependencyOrder.parse(table.get("depends_order"));
        WorkspaceTask task = new WorkspaceTask(name, ((String) command).trim(), cwdValue, environment,
            ShellPolicy.parse(table.get("shell")), matcher,
            Presentation.parse(table.get("presentation")));
        task = withDependencies(task, dependencies(table));
        task = withDependencyOrder(task, dependencyOrder);
        task = withBackground(task, background(table));
        task = withReadinessMarker(task, readinessMarker(table));
        task = withInputs(task, inputs);
        return customMatcher == null ? task : withCustomProblemMatcher(task, customMatcher);
    }

    private static boolean background(TomlTable table) {
        Object value = table.get("background");
        if (value == null) return false;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("background must be TOML boolean");
        return (Boolean) value;
    }

    private static String readinessMarker(TomlTable table) {
        Object value = table.get("ready_when");
        if (value == null) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException("ready_when must be TOML string");
        return validatedReadinessMarker((String) value);
    }

    private static String validatedReadinessMarker(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) {
            throw new IllegalArgumentException("ready_when must be a non-empty single-line marker of at most 256 characters");
        }
        validateSingleLine(value, "ready_when");
        return value;
    }

    private static TaskDiagnosticTemplate customProblemMatcher(TomlTable table, ProblemMatcher matcher) {
        Object value = table.get("problem_pattern");
        if (matcher != ProblemMatcher.CUSTOM) {
            if (value != null) throw new IllegalArgumentException("problem_pattern requires problem_matcher = custom");
            return null;
        }
        if (!(value instanceof String)) throw new IllegalArgumentException("custom problem_matcher requires problem_pattern TOML string");
        return TaskDiagnosticTemplate.parse((String) value);
    }

    private static Map<String, TaskInput> taskInputs(TomlTable table) {
        Object value = table.get("input");
        if (value == null) return Map.of();
        if (!(value instanceof TomlTable inputs)) throw new IllegalArgumentException("input must be a TOML table");
        if (inputs.size() > MAX_TASK_INPUTS) throw new IllegalArgumentException("input has more than " + MAX_TASK_INPUTS + " entries");
        Map<String, TaskInput> result = new LinkedHashMap<>();
        for (String name : inputs.keySet()) {
            if (!isValidTaskInputName(name)) throw new IllegalArgumentException("invalid task input name: " + name);
            Object raw = inputs.get(name);
            if (!(raw instanceof TomlTable input)) throw new IllegalArgumentException("input." + name + " must be a TOML table");
            result.put(name, taskInput(name, input));
        }
        return validatedTaskInputs(result);
    }

    private static TaskInput taskInput(String name, TomlTable table) {
        for (String field : table.keySet()) {
            if (!"default".equals(field) && !"options".equals(field)) {
                throw new IllegalArgumentException("unknown field input." + name + "." + field);
            }
        }
        Object defaultValue = table.get("default");
        if (defaultValue != null && !(defaultValue instanceof String)) throw new IllegalArgumentException("input." + name + ".default must be TOML string");
        String fallback = defaultValue == null ? null : (String) defaultValue;
        if (fallback != null) validateTaskInputValue(fallback, "input." + name + ".default");
        Object optionsValue = table.get("options");
        List<String> options = new ArrayList<>();
        if (optionsValue != null) {
            if (!(optionsValue instanceof TomlArray array)) throw new IllegalArgumentException("input." + name + ".options must be a TOML string array");
            if (array.size() == 0 || array.size() > MAX_INPUT_OPTIONS) {
                throw new IllegalArgumentException("input." + name + ".options must contain one to " + MAX_INPUT_OPTIONS + " values");
            }
            for (int index = 0; index < array.size(); index++) {
                Object entry = array.get(index);
                if (!(entry instanceof String)) throw new IllegalArgumentException("input." + name + ".options must be a TOML string array");
                String option = (String) entry;
                validateTaskInputValue(option, "input." + name + ".options");
                if (options.contains(option)) throw new IllegalArgumentException("input." + name + ".options has duplicate value");
                options.add(option);
            }
        }
        if (fallback != null && !options.isEmpty() && !options.contains(fallback)) {
            throw new IllegalArgumentException("input." + name + ".default must be one of its options");
        }
        return new TaskInput(name, fallback, options);
    }

    private void validateTask(WorkspaceTask task) {
        if (!isValidTaskName(task.name())) throw new IllegalArgumentException("invalid task name: " + task.name());
        if (task.sessionOnly()) {
            throw new IllegalArgumentException("ephemeral imported tasks cannot be written to .shedtasks");
        }
        validateCommand(task.command());
        if (task.cwd() == null || task.cwd().isBlank()) throw new IllegalArgumentException("cwd must not be empty");
        validateSingleLine(task.cwd(), "cwd");
        if (task.shell() == null || task.problemMatcher() == null || task.presentation() == null || task.dependencyOrder() == null) {
            throw new IllegalArgumentException("task settings required");
        }
        if (task.problemMatcher() == ProblemMatcher.CUSTOM && task.customProblemMatcher() == null) {
            throw new IllegalArgumentException("custom problem_matcher requires problem_pattern");
        }
        if (task.problemMatcher() != ProblemMatcher.CUSTOM && task.customProblemMatcher() != null) {
            throw new IllegalArgumentException("problem_pattern requires problem_matcher = custom");
        }
        validatedTaskInputs(task.inputs());
        for (Map.Entry<String, String> entry : task.environment().entrySet()) {
            if (!ENVIRONMENT_NAME.matcher(entry.getKey()).matches()) throw new IllegalArgumentException("invalid env name: " + entry.getKey());
            validateEnvironmentValue(entry.getValue());
        }
        validatedDependencies(task.dependencies());
    }

    private void rejectUnknownFields(TomlTable table, String name) {
        for (String field : table.keySet()) {
            if (!"command".equals(field) && !"cwd".equals(field) && !"env".equals(field)
                && !"shell".equals(field) && !"problem_matcher".equals(field) && !"presentation".equals(field)
                && !"depends_on".equals(field) && !"background".equals(field) && !"ready_when".equals(field)
                && !"problem_pattern".equals(field) && !"input".equals(field) && !"depends_order".equals(field)) {
                throw new IllegalArgumentException("unknown field " + field);
            }
        }
    }

    static Map<String, String> parseInputAssignments(List<String> values, int firstIndex) {
        if (values == null || firstIndex < 0 || firstIndex > values.size()) throw new IllegalArgumentException("task input assignments required");
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = firstIndex; index < values.size(); index++) {
            String assignment = values.get(index);
            int separator = assignment == null ? -1 : assignment.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("task input must use name=value: " + assignment);
            String name = assignment.substring(0, separator);
            String value = assignment.substring(separator + 1);
            if (!isValidTaskInputName(name)) throw new IllegalArgumentException("invalid task input name: " + name);
            validateTaskInputValue(value, "task input " + name);
            if (result.put(name, value) != null) throw new IllegalArgumentException("duplicate task input: " + name);
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> validatedInputAssignments(Map<String, String> values) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) return result;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            if (!isValidTaskInputName(name)) throw new IOException("invalid task input name: " + name);
            try {
                validateTaskInputValue(entry.getValue(), "task input " + name);
            } catch (IllegalArgumentException error) {
                throw new IOException(error.getMessage(), error);
            }
            if (result.put(name, entry.getValue()) != null) throw new IOException("duplicate task input: " + name);
        }
        return result;
    }

    private static Map<String, TaskInput> validatedTaskInputs(Map<String, TaskInput> values) {
        Map<String, TaskInput> result = new LinkedHashMap<>();
        if (values == null) return result;
        if (values.size() > MAX_TASK_INPUTS) throw new IllegalArgumentException("input has more than " + MAX_TASK_INPUTS + " entries");
        for (Map.Entry<String, TaskInput> entry : values.entrySet()) {
            String name = entry.getKey();
            TaskInput input = entry.getValue();
            if (!isValidTaskInputName(name) || input == null || !name.equals(input.name())) {
                throw new IllegalArgumentException("invalid task input: " + name);
            }
            if (input.defaultValue() != null) validateTaskInputValue(input.defaultValue(), "input." + name + ".default");
            if (input.options().size() > MAX_INPUT_OPTIONS) throw new IllegalArgumentException("input." + name + ".options has too many values");
            for (String option : input.options()) validateTaskInputValue(option, "input." + name + ".options");
            if (new java.util.HashSet<>(input.options()).size() != input.options().size()) {
                throw new IllegalArgumentException("input." + name + ".options has duplicate value");
            }
            if (input.defaultValue() != null && !input.options().isEmpty() && !input.options().contains(input.defaultValue())) {
                throw new IllegalArgumentException("input." + name + ".default must be one of its options");
            }
            result.put(name, input);
        }
        return result;
    }

    private static boolean isValidTaskInputName(String value) {
        return value != null && TASK_INPUT_NAME.matcher(value).matches();
    }

    private static void validateTaskInputValue(String value, String label) {
        if (value == null || value.isEmpty() || value.length() > MAX_INPUT_VALUE_LENGTH) {
            throw new IllegalArgumentException(label + " must be a non-empty single-line value of at most " + MAX_INPUT_VALUE_LENGTH + " characters");
        }
        validateSingleLine(value, label);
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

    private List<String> dependencies(TomlTable table) {
        Object value = table.get("depends_on");
        if (value == null) return List.of();
        if (!(value instanceof TomlArray values)) throw new IllegalArgumentException("depends_on must be a TOML array of task names");
        if (values.size() > MAX_DEPENDENCIES) throw new IllegalArgumentException("depends_on has more than " + MAX_DEPENDENCIES + " entries");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Object entry = values.get(index);
            if (!(entry instanceof String)) throw new IllegalArgumentException("depends_on entries must be task names");
            result.add((String) entry);
        }
        return validatedDependencies(result);
    }

    private static List<String> validatedDependencies(List<String> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) return List.of();
        if (dependencies.size() > MAX_DEPENDENCIES) throw new IllegalArgumentException("task has more than " + MAX_DEPENDENCIES + " dependencies");
        List<String> result = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String dependency : dependencies) {
            if (!isValidTaskName(dependency)) throw new IllegalArgumentException("invalid dependency task name: " + dependency);
            if (!seen.add(dependency)) throw new IllegalArgumentException("duplicate dependency task: " + dependency);
            result.add(dependency);
        }
        return List.copyOf(result);
    }

    private void resolveTaskDependencies(String name, Map<String, WorkspaceTask> tasks, List<String> path,
                                         java.util.Set<String> completed, List<WorkspaceTask> ordered) throws IOException {
        if (completed.contains(name)) return;
        int cycleStart = path.indexOf(name);
        if (cycleStart >= 0) {
            List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
            cycle.add(name);
            throw new IOException("task dependency cycle: " + String.join(" -> ", cycle));
        }
        WorkspaceTask task = tasks.get(name);
        if (task == null) {
            String parent = path.isEmpty() ? "requested task" : "task " + path.get(path.size() - 1);
            throw new IOException("task dependency not found: " + name + " (required by " + parent + ")");
        }
        if (path.size() >= MAX_DEPENDENCIES) throw new IOException("task dependency graph exceeds " + MAX_DEPENDENCIES + " tasks");
        path.add(name);
        try {
            for (String dependency : task.dependencies()) {
                resolveTaskDependencies(dependency, tasks, path, completed, ordered);
            }
            completed.add(name);
            ordered.add(task);
        } finally {
            path.remove(path.size() - 1);
        }
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

    private String expandVariables(String value, File workspace, File activeFile, Map<String, String> inputValues) throws IOException {
        if (value == null) throw new IOException("task value required");
        java.util.regex.Matcher matcher = VARIABLE.matcher(value);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "workspaceFolder" -> workspace.getPath();
                case "workspaceFolderBasename" -> workspaceBasename(workspace);
                case "file" -> activeFilePath(activeFile);
                case "fileWorkspaceFolder" -> fileWorkspaceFolder(workspace, activeFile);
                case "relativeFile" -> relativeFilePath(workspace, activeFile);
                case "relativeFileDirname" -> relativeFileDirectory(workspace, activeFile);
                case "fileBasename" -> activeFileName(activeFile);
                case "fileBasenameNoExtension" -> activeFileBasenameWithoutExtension(activeFile);
                case "fileExtname" -> activeFileExtension(activeFile);
                case "fileDirname" -> activeFileDirectory(activeFile);
                case "fileDirnameBasename" -> activeFileDirectoryBasename(activeFile);
                default -> taskInputVariable(matcher.group(1), inputValues);
            };
            matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private List<String> expandDirectArguments(List<String> values, File workspace, File activeFile,
                                               Map<String, String> inputValues) throws IOException {
        return expandArguments(values, workspace, activeFile, inputValues, "direct task argument");
    }

    private List<String> expandShellArguments(List<String> values, File workspace, File activeFile,
                                              Map<String, String> inputValues) throws IOException {
        return expandArguments(values, workspace, activeFile, inputValues, "shell task argument");
    }

    private List<String> expandArguments(List<String> values, File workspace, File activeFile, Map<String, String> inputValues,
                                         String label) throws IOException {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String expanded = expandVariables(value, workspace, activeFile, inputValues);
            validateSingleLine(expanded, label);
            result.add(expanded);
        }
        if (result.isEmpty()) throw new IOException("task command required");
        return List.copyOf(result);
    }

    private List<String> expandRemoteDirectArguments(List<String> values, File workspace, File activeFile, Path connectionRoot,
                                                      String executionRoot, Map<String, String> inputValues) throws IOException {
        return expandRemoteArguments(values, workspace, activeFile, connectionRoot, executionRoot, inputValues, "direct task argument");
    }

    private List<String> expandRemoteShellArguments(List<String> values, File workspace, File activeFile, Path connectionRoot,
                                                     String executionRoot, Map<String, String> inputValues) throws IOException {
        return expandRemoteArguments(values, workspace, activeFile, connectionRoot, executionRoot, inputValues, "shell task argument");
    }

    private List<String> expandRemoteArguments(List<String> values, File workspace, File activeFile, Path connectionRoot,
                                               String executionRoot, Map<String, String> inputValues, String label) throws IOException {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String expanded = expandRemoteVariables(value, workspace, activeFile, connectionRoot, executionRoot, inputValues);
            validateSingleLine(expanded, label);
            result.add(expanded);
        }
        if (result.isEmpty()) throw new IOException("task command required");
        return List.copyOf(result);
    }

    private String expandRemoteVariables(String value, File workspace, File activeFile, Path connectionRoot,
                                         String executionRoot, Map<String, String> inputValues) throws IOException {
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
                case "workspaceFolderBasename" -> remotePathBasename(remotePath(executionRoot, connectionRoot.relativize(workspacePath)));
                case "file" -> remoteFilePath(activeFile, connectionRoot, executionRoot);
                case "fileWorkspaceFolder" -> {
                    fileWorkspaceFolder(workspace, activeFile);
                    yield remotePath(executionRoot, connectionRoot.relativize(workspacePath));
                }
                case "relativeFile" -> relativeFilePath(workspace, activeFile);
                case "relativeFileDirname" -> relativeFileDirectory(workspace, activeFile);
                case "fileBasename" -> activeFileName(activeFile);
                case "fileBasenameNoExtension" -> activeFileBasenameWithoutExtension(activeFile);
                case "fileExtname" -> activeFileExtension(activeFile);
                case "fileDirname" -> remoteFileDirectory(activeFile, connectionRoot, executionRoot);
                case "fileDirnameBasename" -> activeFileDirectoryBasename(activeFile);
                default -> taskInputVariable(matcher.group(1), inputValues);
            };
            matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private String taskInputVariable(String variable, Map<String, String> inputValues) throws IOException {
        if (variable == null || !variable.startsWith("input:")) throw new IOException("unsupported task variable: ${" + variable + "}");
        String name = variable.substring("input:".length());
        if (!isValidTaskInputName(name) || inputValues == null || !inputValues.containsKey(name)) {
            throw new IOException("task input is not declared: " + name);
        }
        return inputValues.get(name);
    }

    private Map<String, String> resolvedInputValues(WorkspaceTask task, Map<String, String> suppliedInputs) throws IOException {
        Map<String, String> supplied = validatedInputAssignments(suppliedInputs);
        for (String name : supplied.keySet()) {
            if (!task.inputs().containsKey(name)) throw new IOException("task input is not declared: " + name);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (TaskInput input : task.inputs().values()) {
            String value = supplied.containsKey(input.name()) ? supplied.get(input.name()) : input.defaultValue();
            if (value == null) throw new IOException("task input is required: " + input.name() + " (use " + input.name() + "=<value>)");
            if (!input.options().isEmpty() && !input.options().contains(value)) {
                throw new IOException("task input " + input.name() + " must be one of: " + String.join(", ", input.options()));
            }
            result.put(input.name(), value);
        }
        return result;
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
        return canonicalActiveFile(activeFile, "${file} requires a file-backed active buffer").getPath();
    }

    private String workspaceBasename(File workspace) throws IOException {
        File canonical = canonicalDirectory(workspace, "${workspaceFolderBasename} requires a workspace directory");
        String name = canonical.getName();
        if (name.isEmpty()) throw new IOException("${workspaceFolderBasename} is unavailable for this workspace");
        return name;
    }

    private String fileWorkspaceFolder(File workspace, File activeFile) throws IOException {
        File file = canonicalActiveFile(activeFile, "${fileWorkspaceFolder} requires a file-backed active buffer");
        File canonicalWorkspace = canonicalDirectory(workspace, "${fileWorkspaceFolder} requires a workspace directory");
        if (!file.toPath().startsWith(canonicalWorkspace.toPath())) {
            throw new IOException("${fileWorkspaceFolder} requires the active file to be inside workspace");
        }
        return canonicalWorkspace.getPath();
    }

    private String relativeFilePath(File workspace, File activeFile) throws IOException {
        File file = canonicalActiveFile(activeFile, "${relativeFile} requires a file-backed active buffer");
        Path workspacePath = workspace.toPath();
        if (!file.toPath().startsWith(workspacePath)) throw new IOException("${relativeFile} must be inside workspace");
        return workspacePath.relativize(file.toPath()).toString();
    }

    private String relativeFileDirectory(File workspace, File activeFile) throws IOException {
        File file = canonicalActiveFile(activeFile, "${relativeFileDirname} requires a file-backed active buffer");
        Path workspacePath = workspace.toPath();
        if (!file.toPath().startsWith(workspacePath)) throw new IOException("${relativeFileDirname} must be inside workspace");
        Path relative = workspacePath.relativize(file.toPath());
        Path parent = relative.getParent();
        return parent == null ? "." : parent.toString();
    }

    private String activeFileName(File activeFile) throws IOException {
        File file = canonicalActiveFile(activeFile, "${fileBasename} requires a file-backed active buffer");
        return file.getName();
    }

    private String activeFileBasenameWithoutExtension(File activeFile) throws IOException {
        String name = canonicalActiveFile(activeFile, "${fileBasenameNoExtension} requires a file-backed active buffer").getName();
        int extension = name.lastIndexOf('.');
        return extension <= 0 ? name : name.substring(0, extension);
    }

    private String activeFileExtension(File activeFile) throws IOException {
        String name = canonicalActiveFile(activeFile, "${fileExtname} requires a file-backed active buffer").getName();
        int extension = name.lastIndexOf('.');
        return extension <= 0 ? "" : name.substring(extension);
    }

    private String activeFileDirectory(File activeFile) throws IOException {
        File file = canonicalActiveFile(activeFile, "${fileDirname} requires a file-backed active buffer");
        File parent = file.getParentFile();
        if (parent == null) throw new IOException("${fileDirname} is unavailable for the active file");
        return parent.getPath();
    }

    private String activeFileDirectoryBasename(File activeFile) throws IOException {
        File file = canonicalActiveFile(activeFile, "${fileDirnameBasename} requires a file-backed active buffer");
        File parent = file.getParentFile();
        if (parent == null || parent.getName().isEmpty()) throw new IOException("${fileDirnameBasename} is unavailable for the active file");
        return parent.getName();
    }

    private String remoteFileDirectory(File activeFile, Path connectionRoot, String executionRoot) throws IOException {
        String path = remoteFilePath(activeFile, connectionRoot, executionRoot);
        int slash = path.lastIndexOf('/');
        if (slash <= 0) throw new IOException("${fileDirname} is unavailable for the active file");
        return path.substring(0, slash);
    }

    private String remotePathBasename(String path) throws IOException {
        String normalized = path == null ? "" : path.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) normalized = normalized.substring(0, normalized.length() - 1);
        int slash = normalized.lastIndexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        if (name.isEmpty()) throw new IOException("${workspaceFolderBasename} is unavailable for the remote workspace");
        return name;
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

    private static List<String> validatedArguments(List<String> arguments, String label) {
        if (arguments == null || arguments.isEmpty()) throw new IllegalArgumentException(label + "s required");
        List<String> values = new ArrayList<>();
        for (String argument : arguments) {
            if (argument == null) throw new IllegalArgumentException(label + " required");
            validateSingleLine(argument, label);
            values.add(argument);
        }
        return List.copyOf(values);
    }

    private static Map<String, String> validatedEnvironment(Map<String, String> environment) {
        Map<String, String> values = environment == null ? Map.of() : environment;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!ENVIRONMENT_NAME.matcher(entry.getKey()).matches()) throw new IllegalArgumentException("invalid env name: " + entry.getKey());
            if (entry.getValue() == null) throw new IllegalArgumentException("environment value required");
            validateSingleLine(entry.getValue(), "environment value");
        }
        return Map.copyOf(values);
    }

    private String location(org.tomlj.TomlPosition position) {
        return position == null ? "" : "line " + position.line() + ", column " + position.column() + ": ";
    }

    private String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
