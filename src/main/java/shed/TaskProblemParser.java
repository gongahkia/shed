package shed;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the small, documented task-diagnostic formats Shed explicitly supports. */
final class TaskProblemParser {
    private static final Pattern GENERIC_PATTERN = Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?:(.*)$");
    private static final Pattern TYPESCRIPT_PATTERN = Pattern.compile(
        "^([^\\s].*)\\((\\d+)(?:,(\\d+))?(?:,\\d+,\\d+)?\\):\\s+(error|warning|info)\\s+(TS\\d+)\\s*:\\s*(.*)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern MSCOMPILE_PATTERN = Pattern.compile(
        "^(.+?)\\((\\d+)(?:,(\\d+))?\\)\\s*:\\s*(fatal error|error|warning|info)\\s+(.+)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ESLINT_COMPACT_PATTERN = Pattern.compile(
        "^(.+?):\\s+line\\s+(\\d+),\\s*col\\s+(\\d+),\\s*(error|warning)\\s*-\\s*(.+)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ESLINT_STYLISH_PATTERN = Pattern.compile(
        "^\\s*(\\d+):(\\d+)\\s+(error|warning)\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    private TaskProblemParser() {
    }

    static List<QuickfixService.Entry> parse(String output, String source, File workingDirectory, TaskService.ProblemMatcher matcher) {
        TaskService.ProblemMatcher selected = matcher == null ? TaskService.ProblemMatcher.GENERIC : matcher;
        return switch (selected) {
            case GENERIC -> parseGeneric(output, source, workingDirectory);
            case TYPESCRIPT -> parseTypescript(output, source, workingDirectory);
            case ESLINT -> parseEslint(output, source, workingDirectory);
            case MSCOMPILE -> parseMsCompile(output, source, workingDirectory);
            case NONE -> List.of();
        };
    }

    static List<QuickfixService.Entry> parseGeneric(String output, String source, File workingDirectory) {
        List<QuickfixService.Entry> entries = new ArrayList<>();
        if (output == null || output.isBlank() || workingDirectory == null) return entries;
        for (String line : output.split("\\R")) {
            Matcher matcher = GENERIC_PATTERN.matcher(line);
            if (!matcher.matches()) continue;
            add(entries, matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), source, workingDirectory,
                severityFromMessage(matcher.group(4)));
        }
        return entries;
    }

    private static List<QuickfixService.Entry> parseTypescript(String output, String source, File workingDirectory) {
        List<QuickfixService.Entry> entries = new ArrayList<>();
        if (output == null || output.isBlank() || workingDirectory == null) return entries;
        for (String line : output.split("\\R")) {
            Matcher matcher = TYPESCRIPT_PATTERN.matcher(line);
            if (!matcher.matches()) continue;
            String message = matcher.group(5) + ": " + matcher.group(6).trim();
            add(entries, matcher.group(1), matcher.group(2), matcher.group(3), message, source, workingDirectory,
                severity(matcher.group(4)));
        }
        return entries;
    }

    private static List<QuickfixService.Entry> parseMsCompile(String output, String source, File workingDirectory) {
        List<QuickfixService.Entry> entries = new ArrayList<>();
        if (output == null || output.isBlank() || workingDirectory == null) return entries;
        for (String line : output.split("\\R")) {
            Matcher matcher = MSCOMPILE_PATTERN.matcher(line);
            if (!matcher.matches()) continue;
            add(entries, matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(5), source, workingDirectory,
                severity(matcher.group(4)));
        }
        return entries;
    }

    private static List<QuickfixService.Entry> parseEslint(String output, String source, File workingDirectory) {
        List<QuickfixService.Entry> entries = new ArrayList<>();
        if (output == null || output.isBlank() || workingDirectory == null) return entries;
        String currentFile = null;
        for (String line : output.split("\\R")) {
            Matcher compact = ESLINT_COMPACT_PATTERN.matcher(line);
            if (compact.matches()) {
                add(entries, compact.group(1), compact.group(2), compact.group(3), compact.group(5), source, workingDirectory,
                    severity(compact.group(4)));
                currentFile = null;
                continue;
            }
            Matcher stylish = ESLINT_STYLISH_PATTERN.matcher(line);
            if (stylish.matches() && currentFile != null) {
                add(entries, currentFile, stylish.group(1), stylish.group(2), stylish.group(4), source, workingDirectory,
                    severity(stylish.group(3)));
                continue;
            }
            String candidate = line == null ? "" : line.strip();
            currentFile = isEslintFileHeader(candidate) ? candidate : null;
        }
        return entries;
    }

    private static boolean isEslintFileHeader(String value) {
        if (value.isBlank() || value.startsWith("✖") || value.startsWith("×")) return false;
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.matches(".+\\.[A-Za-z0-9_-]{1,16}");
    }

    private static void add(List<QuickfixService.Entry> entries, String path, String line, String column, String message,
                            String source, File workingDirectory, QuickfixService.Severity severity) {
        int lineNumber;
        int columnNumber = 1;
        try {
            lineNumber = Integer.parseInt(line);
            if (column != null && !column.isBlank()) columnNumber = Integer.parseInt(column);
        } catch (NumberFormatException ignored) {
            return;
        }
        if (lineNumber < 1 || columnNumber < 1) return;
        entries.add(new QuickfixService.Entry(resolvePath(path, workingDirectory), lineNumber, columnNumber,
            message == null ? "" : message.trim(), source, severity));
    }

    private static String resolvePath(String value, File workingDirectory) {
        File path = new File(value == null ? "" : value.trim());
        if (!path.isAbsolute()) path = new File(workingDirectory, path.getPath());
        try {
            return path.getCanonicalPath();
        } catch (IOException ignored) {
            return path.getAbsolutePath();
        }
    }

    private static QuickfixService.Severity severityFromMessage(String message) {
        String normalized = message == null ? "" : message.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("fatal error") || normalized.startsWith("error")) return QuickfixService.Severity.ERROR;
        if (normalized.startsWith("warning")) return QuickfixService.Severity.WARNING;
        if (normalized.startsWith("info")) return QuickfixService.Severity.INFO;
        return QuickfixService.Severity.OTHER;
    }

    private static QuickfixService.Severity severity(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fatal error", "error" -> QuickfixService.Severity.ERROR;
            case "warning" -> QuickfixService.Severity.WARNING;
            case "info" -> QuickfixService.Severity.INFO;
            default -> QuickfixService.Severity.OTHER;
        };
    }
}
