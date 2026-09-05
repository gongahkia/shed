package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a deliberately small, lossless subset of workspace {@code .vscode/tasks.json}
 * or the {@code tasks} object of an explicitly imported standard {@code .code-workspace}.
 *
 * <p>Explicit {@code process} tasks are represented as direct argv. On a POSIX host, explicit
 * {@code shell} tasks are represented either as their raw single command or as separately held
 * values that are expanded and strongly quoted at the execution boundary. Imported tasks are
 * session-only {@link TaskService.WorkspaceTask} values; they do not modify {@code tasks.json}
 * or create {@code .shedtasks}.</p>
 */
final class VsCodeTaskImporter {
    static final String NAME_PREFIX = "vscode-";
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final int MAX_TASKS = 100;
    private static final int MAX_ARGUMENTS = 256;
    private static final Set<String> SUPPORTED_TASK_FIELDS = Set.of("label", "type", "command", "args", "options", "problemMatcher", "presentation",
        "dependsOn", "dependsOrder");
    private static final Set<String> SUPPORTED_OPTIONS_FIELDS = Set.of("cwd", "env");
    private static final Set<String> SUPPORTED_PRESENTATION_FIELDS = Set.of("reveal");
    private static final Set<String> SUPPORTED_VARIABLES = Set.of("${workspaceFolder}", "${workspaceFolderBasename}",
        "${file}", "${fileWorkspaceFolder}", "${relativeFile}", "${relativeFileDirname}", "${fileBasename}",
        "${fileBasenameNoExtension}", "${fileExtname}", "${fileDirname}", "${fileDirnameBasename}");

    record Report(Path source, Map<String, TaskService.WorkspaceTask> tasks, Map<String, String> taskNamesByLabel,
                  List<String> accepted, List<String> skipped, String failure) {
        Report {
            source = source == null ? null : source.toAbsolutePath().normalize();
            tasks = tasks == null ? Map.of() : Map.copyOf(tasks);
            taskNamesByLabel = taskNamesByLabel == null ? Map.of() : Map.copyOf(taskNamesByLabel);
            accepted = accepted == null ? List.of() : List.copyOf(accepted);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
            failure = failure == null ? "" : failure;
        }

        boolean present() { return source != null; }
        boolean readable() { return present() && failure.isEmpty(); }
    }

    private VsCodeTaskImporter() {
    }

    static Report read(Path workspace, Set<String> reservedNames) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return new Report(null, Map.of(), Map.of(), List.of(), List.of(), "Workspace root is unavailable.");
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path source = root.resolve(".vscode").resolve("tasks.json").normalize();
        if (!source.startsWith(root) || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return new Report(null, Map.of(), Map.of(), List.of(), List.of(), "");
        if (!Files.isDirectory(source.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            return new Report(source, Map.of(), Map.of(), List.of(), List.of(), ".vscode is not a regular directory.");
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return new Report(source, Map.of(), Map.of(), List.of(), List.of(), "tasks.json is not a regular file.");
        }
        try {
            long size = Files.size(source);
            if (size > MAX_BYTES) return new Report(source, Map.of(), Map.of(), List.of(), List.of(), "tasks.json exceeds the 1 MiB import limit.");
            return importDocument(source, Jsonc.parseObject(Files.readString(source, StandardCharsets.UTF_8)), reservedNames, "tasks.json");
        } catch (IOException | IllegalArgumentException error) {
            String detail = error.getMessage();
            if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
            return new Report(source, Map.of(), Map.of(), List.of(), List.of(), "tasks.json could not be read: " + oneLine(detail));
        }
    }

    /** Reads the same strict task subset when it is embedded in an imported .code-workspace document. */
    static Report readWorkspaceConfiguration(Path source, Object configuration, Set<String> reservedNames) {
        Map<String, Object> document = MiniJson.asObject(configuration);
        if (document == null) {
            return new Report(source, Map.of(), Map.of(), List.of(), List.of(), "workspace tasks must be an object.");
        }
        return importDocument(source, document, reservedNames, "workspace tasks");
    }

    /** Keeps only labels that resolve to exactly one accepted task across every supplied source. */
    static Map<String, String> uniqueTaskNamesByLabel(Report... reports) {
        Map<String, String> unique = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        if (reports == null) return Map.of();
        for (Report report : reports) {
            if (report == null) continue;
            for (Map.Entry<String, String> entry : report.taskNamesByLabel().entrySet()) {
                String label = entry.getKey();
                if (ambiguous.contains(label)) continue;
                if (unique.containsKey(label)) {
                    unique.remove(label);
                    ambiguous.add(label);
                } else {
                    unique.put(label, entry.getValue());
                }
            }
        }
        return Map.copyOf(unique);
    }

    private static Report importDocument(Path source, Map<String, Object> document, Set<String> reservedNames, String subject) {
        if (!"2.0.0".equals(MiniJson.asString(document == null ? null : document.get("version")))) {
            return new Report(source, Map.of(), Map.of(), List.of(), List.of(), subject + " version must be the string 2.0.0.");
        }
        List<Object> entries = MiniJson.asArray(document.get("tasks"));
        if (entries == null) return new Report(source, Map.of(), Map.of(), List.of(), List.of(), subject + " must contain a tasks array.");
        if (entries.size() > MAX_TASKS) return new Report(source, Map.of(), Map.of(), List.of(), List.of(), subject + " has more than " + MAX_TASKS + " tasks.");
        Set<String> names = new LinkedHashSet<>(reservedNames == null ? Set.of() : reservedNames);
        List<PendingImport> pending = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = MiniJson.asObject(entries.get(index));
            String prefix = "task " + (index + 1);
            if (entry == null) {
                skipped.add(prefix + ": entry must be an object.");
                continue;
            }
            ImportResult result = importEntry(entry, names);
            if (result.error() != null) skipped.add(prefix + ": " + result.error());
            else {
                names.add(result.task().name());
                pending.add(new PendingImport(prefix, result.task(), result.label(), result.dependencyLabels()));
            }
        }
        resolveDependencies(pending, skipped);
        Map<String, TaskService.WorkspaceTask> imported = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Integer> labelCounts = labelCounts(pending);
        List<String> accepted = new ArrayList<>();
        for (PendingImport item : pending) {
            imported.put(item.task().name(), item.task());
            accepted.add(item.task().name());
            if (labelCounts.getOrDefault(item.label(), 0) == 1) labels.put(item.label(), item.task().name());
        }
        return new Report(source, imported, labels, accepted, skipped, "");
    }

    private record ImportResult(TaskService.WorkspaceTask task, String label, List<String> dependencyLabels, String error) {
        static ImportResult rejected(String error) { return new ImportResult(null, null, List.of(), error); }
    }

    private record PendingImport(String prefix, TaskService.WorkspaceTask task, String label, List<String> dependencyLabels) {
        PendingImport {
            dependencyLabels = List.copyOf(dependencyLabels == null ? List.of() : dependencyLabels);
        }

        PendingImport withTask(TaskService.WorkspaceTask replacement) {
            return new PendingImport(prefix, replacement, label, dependencyLabels);
        }
    }

    private static ImportResult importEntry(Map<String, Object> entry, Set<String> names) {
        Set<String> unsupported = new LinkedHashSet<>(entry.keySet());
        unsupported.removeAll(SUPPORTED_TASK_FIELDS);
        if (!unsupported.isEmpty()) return ImportResult.rejected("uses unsupported field" + (unsupported.size() == 1 ? " " : "s ")
            + String.join(", ", unsupported.stream().sorted().toList()) + "; it was not altered or started.");
        String label = requiredText(entry, "label");
        if (label == null) return ImportResult.rejected("label must be a non-empty, single-line string of at most 120 characters.");
        String type = requiredText(entry, "type");
        boolean process = "process".equalsIgnoreCase(type);
        boolean shell = "shell".equalsIgnoreCase(type);
        if (!process && !shell) {
            return ImportResult.rejected("only explicit type process or POSIX shell is supported; provider and automatic tasks are not imported.");
        }
        if (shell && isWindowsHost()) {
            return ImportResult.rejected("shell tasks require a POSIX host; Windows shell quoting and selection are not translated.");
        }
        String command = requiredText(entry, "command");
        if (command == null) return ImportResult.rejected("command must be a non-empty, single-line string of at most 120 characters.");
        List<String> arguments = stringArray(entry.get("args"));
        if (arguments == null || entry.containsKey("args") && MiniJson.asArray(entry.get("args")) == null) {
            return ImportResult.rejected("args must be an array of at most " + MAX_ARGUMENTS + " strings when present.");
        }
        List<String> commandArguments = new ArrayList<>();
        commandArguments.add(command);
        commandArguments.addAll(arguments);
        if (!supportedVariables(commandArguments)) return ImportResult.rejected("command or args use unsupported VS Code variables.");
        Options options;
        try {
            options = options(entry.get("options"));
        } catch (IllegalArgumentException error) {
            return ImportResult.rejected(error.getMessage());
        }
        if (!supportedVariables(List.of(options.cwd())) || !supportedVariables(new ArrayList<>(options.environment().values()))) {
            return ImportResult.rejected("options.cwd or options.env use unsupported VS Code variables.");
        }
        TaskService.ProblemMatcher matcher;
        try {
            matcher = problemMatcher(entry.get("problemMatcher"));
        } catch (IllegalArgumentException error) {
            return ImportResult.rejected(error.getMessage());
        }
        TaskService.Presentation presentation;
        try {
            presentation = presentation(entry.get("presentation"));
        } catch (IllegalArgumentException error) {
            return ImportResult.rejected(error.getMessage());
        }
        List<String> dependencyLabels;
        try {
            dependencyLabels = dependencyLabels(entry);
        } catch (IllegalArgumentException error) {
            return ImportResult.rejected(error.getMessage());
        }
        String name = uniqueName(label, names);
        try {
            TaskService.WorkspaceTask task = process
                ? TaskService.directWorkspaceTask(name, commandArguments, options.cwd(), options.environment(), matcher, presentation)
                : arguments.isEmpty()
                    ? TaskService.rawShellWorkspaceTask(name, command, options.cwd(), options.environment(), matcher, presentation)
                    : TaskService.shellWorkspaceTask(name, commandArguments, options.cwd(), options.environment(), matcher, presentation);
            return new ImportResult(task, label, dependencyLabels, null);
        } catch (IllegalArgumentException error) {
            return ImportResult.rejected(oneLine(error.getMessage()));
        }
    }

    private static void resolveDependencies(List<PendingImport> pending, List<String> skipped) {
        boolean changed;
        do {
            changed = false;
            Map<String, List<String>> namesByLabel = namesByLabel(pending);
            for (int index = 0; index < pending.size(); index++) {
                PendingImport item = pending.get(index);
                List<String> dependencies = new ArrayList<>();
                String failure = null;
                for (String label : item.dependencyLabels()) {
                    List<String> matches = namesByLabel.get(label);
                    if (matches == null || matches.isEmpty()) {
                        failure = "dependsOn label is not an accepted task: " + label + ".";
                        break;
                    }
                    if (matches.size() != 1) {
                        failure = "dependsOn label is ambiguous: " + label + ".";
                        break;
                    }
                    dependencies.add(matches.get(0));
                }
                if (failure != null) {
                    skipped.add(item.prefix() + ": " + failure);
                    pending.remove(index--);
                    changed = true;
                    continue;
                }
                if (!dependencies.equals(item.task().dependencies())) {
                    pending.set(index, item.withTask(TaskService.withDependencies(item.task(), dependencies)));
                }
            }
        } while (changed);
    }

    private static Map<String, List<String>> namesByLabel(List<PendingImport> pending) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (PendingImport item : pending) {
            result.computeIfAbsent(item.label(), ignored -> new ArrayList<>()).add(item.task().name());
        }
        return result;
    }

    private static Map<String, Integer> labelCounts(List<PendingImport> pending) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (PendingImport item : pending) result.merge(item.label(), 1, Integer::sum);
        return result;
    }

    private static List<String> dependencyLabels(Map<String, Object> entry) {
        boolean hasDependencies = entry.containsKey("dependsOn");
        boolean hasOrder = entry.containsKey("dependsOrder");
        if (!hasDependencies) {
            if (hasOrder) throw new IllegalArgumentException("dependsOrder requires dependsOn.");
            return List.of();
        }
        if (!"sequence".equalsIgnoreCase(MiniJson.asString(entry.get("dependsOrder")))) {
            throw new IllegalArgumentException("dependsOn requires dependsOrder: sequence; parallel dependency execution is not imported.");
        }
        Object raw = entry.get("dependsOn");
        List<String> labels;
        if (raw instanceof String text) labels = List.of(text);
        else {
            List<Object> values = MiniJson.asArray(raw);
            if (values == null || values.isEmpty() || values.size() > MAX_TASKS) {
                throw new IllegalArgumentException("dependsOn must be one to " + MAX_TASKS + " task labels.");
            }
            List<String> parsed = new ArrayList<>();
            for (Object value : values) {
                String label = MiniJson.asString(value);
                if (!validText(label)) throw new IllegalArgumentException("dependsOn entries must be non-empty, single-line task labels.");
                parsed.add(label);
            }
            labels = List.copyOf(parsed);
        }
        if (labels.size() > MAX_TASKS || labels.stream().anyMatch(label -> !validText(label))) {
            throw new IllegalArgumentException("dependsOn entries must be non-empty, single-line task labels.");
        }
        if (new LinkedHashSet<>(labels).size() != labels.size()) throw new IllegalArgumentException("dependsOn must not repeat a task label.");
        return List.copyOf(labels);
    }

    private record Options(String cwd, Map<String, String> environment) {
    }

    private static Options options(Object value) {
        if (value == null) return new Options("${workspaceFolder}", Map.of());
        Map<String, Object> fields = MiniJson.asObject(value);
        if (fields == null) throw new IllegalArgumentException("options must be an object when present.");
        Set<String> unsupported = new LinkedHashSet<>(fields.keySet());
        unsupported.removeAll(SUPPORTED_OPTIONS_FIELDS);
        if (!unsupported.isEmpty()) throw new IllegalArgumentException("options use unsupported field" + (unsupported.size() == 1 ? " " : "s ")
            + String.join(", ", unsupported.stream().sorted().toList()) + ".");
        String cwd = fields.containsKey("cwd") ? requiredText(fields, "cwd") : "${workspaceFolder}";
        if (cwd == null) throw new IllegalArgumentException("options.cwd must be a non-empty, single-line string of at most 120 characters.");
        Map<String, String> environment = stringObject(fields.get("env"));
        if (environment == null) throw new IllegalArgumentException("options.env must be an object with string values when present.");
        return new Options(cwd, environment);
    }

    private static TaskService.ProblemMatcher problemMatcher(Object value) {
        if (value == null) return TaskService.ProblemMatcher.NONE;
        if (value instanceof String) return namedProblemMatcher((String) value);
        List<Object> entries = MiniJson.asArray(value);
        if (entries != null && entries.isEmpty()) return TaskService.ProblemMatcher.NONE;
        if (entries == null || entries.size() != 1) {
            throw new IllegalArgumentException("problemMatcher must be absent, an empty array, or one supported built-in matcher.");
        }
        String name = MiniJson.asString(entries.getFirst());
        if (name == null) throw new IllegalArgumentException("problemMatcher entries must be built-in matcher names.");
        return namedProblemMatcher(name);
    }

    private static TaskService.ProblemMatcher namedProblemMatcher(String value) {
        String name = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (name) {
            case "$go", "$gcc" -> TaskService.ProblemMatcher.GENERIC;
            case "$tsc" -> TaskService.ProblemMatcher.TYPESCRIPT;
            case "$eslint-compact", "$eslint-stylish" -> TaskService.ProblemMatcher.ESLINT;
            case "$mscompile" -> TaskService.ProblemMatcher.MSCOMPILE;
            case "$tsc-watch" -> throw new IllegalArgumentException("problemMatcher $tsc-watch requires unsupported background-task lifecycle.");
            default -> throw new IllegalArgumentException("problemMatcher is not in Shed's supported built-in subset: "
                + (value == null || value.isBlank() ? "(empty)" : value) + ".");
        };
    }

    private static TaskService.Presentation presentation(Object value) {
        if (value == null) return TaskService.Presentation.ALWAYS;
        Map<String, Object> fields = MiniJson.asObject(value);
        if (fields == null) throw new IllegalArgumentException("presentation must be an object when present.");
        Set<String> unsupported = new LinkedHashSet<>(fields.keySet());
        unsupported.removeAll(SUPPORTED_PRESENTATION_FIELDS);
        if (!unsupported.isEmpty()) throw new IllegalArgumentException("presentation uses unsupported field" + (unsupported.size() == 1 ? " " : "s ")
            + String.join(", ", unsupported.stream().sorted().toList()) + ".");
        if (!fields.containsKey("reveal")) return TaskService.Presentation.ALWAYS;
        String reveal = MiniJson.asString(fields.get("reveal"));
        if ("always".equalsIgnoreCase(reveal)) return TaskService.Presentation.ALWAYS;
        if ("never".equalsIgnoreCase(reveal)) return TaskService.Presentation.NEVER;
        throw new IllegalArgumentException("presentation.reveal must be always or never when present.");
    }

    private static String uniqueName(String label, Set<String> used) {
        StringBuilder slug = new StringBuilder();
        boolean separator = false;
        for (int index = 0; index < label.length(); index++) {
            char character = Character.toLowerCase(label.charAt(index));
            if (Character.isLetterOrDigit(character) || character == '_' || character == '-') {
                slug.append(character);
                separator = false;
            } else if (!separator && !slug.isEmpty()) {
                slug.append('-');
                separator = true;
            }
        }
        while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '-') slug.setLength(slug.length() - 1);
        String base = NAME_PREFIX + (slug.isEmpty() ? "task" : slug.substring(0, Math.min(80, slug.length())));
        String result = base;
        int suffix = 2;
        while (used.contains(result)) result = base + "-" + suffix++;
        return result;
    }

    private static String requiredText(Map<String, Object> fields, String field) {
        String value = fields == null ? null : MiniJson.asString(fields.get(field));
        return validText(value) ? value : null;
    }

    private static boolean validText(String value) {
        if (value == null || value.isBlank() || value.length() > 120) return false;
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return false;
        return true;
    }

    private static List<String> stringArray(Object value) {
        if (value == null) return List.of();
        List<Object> values = MiniJson.asArray(value);
        if (values == null || values.size() > MAX_ARGUMENTS) return null;
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = MiniJson.asString(item);
            if (text == null || text.length() > 65536 || containsInvalidControl(text)) return null;
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static Map<String, String> stringObject(Object value) {
        if (value == null) return Map.of();
        Map<String, Object> values = MiniJson.asObject(value);
        if (values == null) return null;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            String text = MiniJson.asString(entry.getValue());
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]*") || text == null || text.length() > 65536 || containsInvalidControl(text)) return null;
            result.put(key, text);
        }
        return Map.copyOf(result);
    }

    private static boolean supportedVariables(List<String> values) {
        for (String value : values == null ? List.<String>of() : values) {
            int index = 0;
            while (index < value.length()) {
                int start = value.indexOf("${", index);
                if (start < 0) break;
                int end = value.indexOf('}', start + 2);
                if (end < 0) return false;
                String variable = value.substring(start, end + 1);
                if (!SUPPORTED_VARIABLES.contains(variable)) return false;
                index = end + 1;
            }
        }
        return true;
    }

    private static boolean containsInvalidControl(String value) {
        if (value == null) return true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\u0000' || character == '\n' || character == '\r' || Character.isISOControl(character) && character != '\t') return true;
        }
        return false;
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
