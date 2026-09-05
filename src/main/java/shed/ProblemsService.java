package shed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ProblemsService {
    static final int MAX_RETAINED_ENTRIES = 2_000;

    enum Severity {
        ERROR,
        WARNING,
        INFO,
        HINT,
        OTHER;

        static Severity fromLsp(int value) {
            return switch (value) {
                case 1 -> ERROR;
                case 2 -> WARNING;
                case 3 -> INFO;
                case 4 -> HINT;
                default -> OTHER;
            };
        }
    }

    static final class Problem {
        private final String filePath;
        private final int line;
        private final int column;
        private final String message;
        private final String source;
        private final Severity severity;

        Problem(String filePath, int line, int column, String message, String source, Severity severity) {
            this.filePath = filePath == null ? "" : filePath;
            this.line = Math.max(1, line);
            this.column = Math.max(1, column);
            this.message = message == null ? "" : message;
            this.source = source == null || source.isBlank() ? "quickfix" : source.trim();
            this.severity = severity == null ? Severity.OTHER : severity;
        }

        String filePath() { return filePath; }
        int line() { return line; }
        int column() { return column; }
        String message() { return message; }
        String source() { return source; }
        Severity severity() { return severity; }

        String identity() {
            return filePath + "\u0000" + line + "\u0000" + column + "\u0000" + message + "\u0000" + severity;
        }
    }

    private final LinkedHashMap<String, List<Problem>> retainedBySource = new LinkedHashMap<>();

    synchronized void recordQuickfixEntries(List<QuickfixService.Entry> entries) {
        if (entries == null || entries.isEmpty()) return;
        Map<String, List<Problem>> grouped = new LinkedHashMap<>();
        for (QuickfixService.Entry entry : entries) {
            if (entry == null || isLiveLspDiagnostic(entry.getSource())) continue;
            String source = normalizedSource(entry.getSource());
            grouped.computeIfAbsent(source, ignored -> new ArrayList<>()).add(new Problem(
                entry.getFilePath(), entry.getLine(), entry.getColumn(), entry.getMessage(), source, fromQuickfixSeverity(entry.getSeverity())
            ));
        }
        for (Map.Entry<String, List<Problem>> entry : grouped.entrySet()) {
            retainedBySource.remove(entry.getKey());
            List<Problem> values = entry.getValue();
            retainedBySource.put(entry.getKey(), List.copyOf(values.size() <= MAX_RETAINED_ENTRIES
                ? values : values.subList(0, MAX_RETAINED_ENTRIES)));
        }
        trimToCapacity();
    }

    synchronized void clearQuickfixSource(String source) {
        retainedBySource.remove(normalizedSource(source));
    }

    synchronized List<Problem> snapshot(Collection<Problem> liveLspDiagnostics) {
        Map<String, Problem> unique = new LinkedHashMap<>();
        if (liveLspDiagnostics != null) {
            for (Problem problem : liveLspDiagnostics) {
                if (problem != null) unique.put(problem.identity(), problem);
            }
        }
        for (List<Problem> retained : retainedBySource.values()) {
            for (Problem problem : retained) unique.putIfAbsent(problem.identity(), problem);
        }
        List<Problem> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing((Problem value) -> value.severity().ordinal())
            .thenComparing(Problem::filePath, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(Problem::line)
            .thenComparingInt(Problem::column)
            .thenComparing(Problem::message, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    synchronized int retainedEntryCount() {
        int total = 0;
        for (List<Problem> problems : retainedBySource.values()) total += problems.size();
        return total;
    }

    private void trimToCapacity() {
        int retained = retainedEntryCount();
        while (retained > MAX_RETAINED_ENTRIES && !retainedBySource.isEmpty()) {
            String oldest = retainedBySource.keySet().iterator().next();
            List<Problem> removed = retainedBySource.remove(oldest);
            retained -= removed == null ? 0 : removed.size();
        }
    }

    private static boolean isLiveLspDiagnostic(String source) {
        return source != null && source.toLowerCase(Locale.ROOT).startsWith("diag-");
    }

    private static Severity fromQuickfixSeverity(QuickfixService.Severity severity) {
        if (severity == null) return Severity.OTHER;
        return switch (severity) {
            case ERROR -> Severity.ERROR;
            case WARNING -> Severity.WARNING;
            case INFO -> Severity.INFO;
            case HINT -> Severity.HINT;
            case OTHER -> Severity.OTHER;
        };
    }

    private static String normalizedSource(String source) {
        return source == null || source.isBlank() ? "quickfix" : source.trim();
    }
}
