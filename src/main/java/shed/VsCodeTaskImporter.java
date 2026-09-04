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
 * Reads a deliberately small, lossless subset of workspace {@code .vscode/tasks.json}.
 *
 * <p>Only explicit {@code process} tasks are represented. Imported tasks are direct argv,
 * session-only {@link TaskService.WorkspaceTask} values; they do not modify {@code tasks.json}
 * or create {@code .shedtasks}.</p>
 */
final class VsCodeTaskImporter {
    static final String NAME_PREFIX = "vscode-";
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final int MAX_TASKS = 100;
    private static final int MAX_ARGUMENTS = 256;
    private static final Set<String> SUPPORTED_TASK_FIELDS = Set.of("label", "type", "command", "args", "options", "problemMatcher", "presentation");
    private static final Set<String> SUPPORTED_OPTIONS_FIELDS = Set.of("cwd", "env");
    private static final Set<String> SUPPORTED_PRESENTATION_FIELDS = Set.of("reveal");

    record Report(Path source, Map<String, TaskService.WorkspaceTask> tasks, List<String> accepted, List<String> skipped,
                  String failure) {
        Report {
            source = source == null ? null : source.toAbsolutePath().normalize();
            tasks = tasks == null ? Map.of() : Map.copyOf(tasks);
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
            return new Report(null, Map.of(), List.of(), List.of(), "Workspace root is unavailable.");
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path source = root.resolve(".vscode").resolve("tasks.json").normalize();
        if (!source.startsWith(root) || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return new Report(null, Map.of(), List.of(), List.of(), "");
        if (!Files.isDirectory(source.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            return new Report(source, Map.of(), List.of(), List.of(), ".vscode is not a regular directory.");
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return new Report(source, Map.of(), List.of(), List.of(), "tasks.json is not a regular file.");
        }
        try {
            long size = Files.size(source);
            if (size > MAX_BYTES) return new Report(source, Map.of(), List.of(), List.of(), "tasks.json exceeds the 1 MiB import limit.");
            return importDocument(source, Jsonc.parseObject(Files.readString(source, StandardCharsets.UTF_8)), reservedNames);
        } catch (IOException | IllegalArgumentException error) {
            String detail = error.getMessage();
            if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
            return new Report(source, Map.of(), List.of(), List.of(), "tasks.json could not be read: " + oneLine(detail));
        }
    }

    private static Report importDocument(Path source, Map<String, Object> document, Set<String> reservedNames) {
        if (!"2.0.0".equals(MiniJson.asString(document == null ? null : document.get("version")))) {
            return new Report(source, Map.of(), List.of(), List.of(), "tasks.json version must be the string 2.0.0.");
        }
        List<Object> entries = MiniJson.asArray(document.get("tasks"));
        if (entries == null) return new Report(source, Map.of(), List.of(), List.of(), "tasks.json must contain a tasks array.");
        if (entries.size() > MAX_TASKS) return new Report(source, Map.of(), List.of(), List.of(), "tasks.json has more than " + MAX_TASKS + " tasks.");
        Set<String> names = new LinkedHashSet<>(reservedNames == null ? Set.of() : reservedNames);
        Map<String, TaskService.WorkspaceTask> imported = new LinkedHashMap<>();
        List<String> accepted = new ArrayList<>();
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
                imported.put(result.task().name(), result.task());
                names.add(result.task().name());
                accepted.add(result.task().name());
            }
        }
        return new Report(source, imported, accepted, skipped, "");
    }

    private record ImportResult(TaskService.WorkspaceTask task, String error) {
        static ImportResult rejected(String error) { return new ImportResult(null, error); }
    }

    private static ImportResult importEntry(Map<String, Object> entry, Set<String> names) {
        Set<String> unsupported = new LinkedHashSet<>(entry.keySet());
        unsupported.removeAll(SUPPORTED_TASK_FIELDS);
        if (!unsupported.isEmpty()) return ImportResult.rejected("uses unsupported field" + (unsupported.size() == 1 ? " " : "s ")
            + String.join(", ", unsupported.stream().sorted().toList()) + "; it was not altered or started.");
        String label = requiredText(entry, "label");
        if (label == null) return ImportResult.rejected("label must be a non-empty, single-line string of at most 120 characters.");
        if (!"process".equalsIgnoreCase(requiredText(entry, "type"))) {
            return ImportResult.rejected("only type process is supported; shell, provider, and automatic tasks are not imported.");
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
        String name = uniqueName(label, names);
        try {
            return new ImportResult(TaskService.directWorkspaceTask(name, commandArguments, options.cwd(), options.environment(), matcher, presentation), null);
        } catch (IllegalArgumentException error) {
            return ImportResult.rejected(oneLine(error.getMessage()));
        }
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
        List<Object> entries = MiniJson.asArray(value);
        if (entries != null && entries.isEmpty()) return TaskService.ProblemMatcher.NONE;
        throw new IllegalArgumentException("problemMatcher is only supported when absent or an empty array.");
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
                if (!"${workspaceFolder}".equals(variable) && !"${file}".equals(variable)
                    && !"${relativeFile}".equals(variable) && !"${fileBasename}".equals(variable)) return false;
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
}
