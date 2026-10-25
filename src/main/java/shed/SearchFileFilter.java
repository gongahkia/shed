package shed;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Comma-separated root-relative globs; braces retain their internal commas. */
final class SearchFileFilter {
    private final List<Glob> includes;
    private final List<Glob> excludes;
    SearchFileFilter(String include, String exclude) { includes = compile(include); excludes = compile(exclude); }
    boolean accepts(Path relative) {
        return accepts(relative, () -> false);
    }
    boolean accepts(Path relative, BooleanSupplier cancelled) {
        String path = relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
        String name = relative.getFileName().toString();
        return (includes.isEmpty() || includes.stream().anyMatch(pattern -> pattern.matches(path, name, cancelled)))
            && excludes.stream().noneMatch(pattern -> pattern.matches(path, name, cancelled));
    }
    private record Glob(Pattern direct, Pattern rootAlternative, boolean basename) {
        boolean matches(String path, String name, BooleanSupplier cancelled) {
            return SearchPattern.checkedMatcher(direct, path, cancelled).matches()
                || basename && SearchPattern.checkedMatcher(direct, name, cancelled).matches()
                || rootAlternative != null && SearchPattern.checkedMatcher(rootAlternative, path, cancelled).matches();
        }
    }
    private static List<Glob> compile(String input) {
        List<Glob> result = new ArrayList<>();
        String text = input == null ? "" : input;
        int braces = 0, start = 0;
        for (int index = 0; index <= text.length(); index++) {
            if (index < text.length() && text.charAt(index) == '{') braces++;
            if (index < text.length() && text.charAt(index) == '}') braces--;
            if (index == text.length() || text.charAt(index) == ',' && braces == 0) {
                String glob = text.substring(start, index).trim().replace('\\', '/');
                start = index + 1;
                if (glob.isEmpty()) continue;
                if (glob.endsWith("/")) glob += "**";
                Pattern direct = pattern(glob);
                Pattern rootAlternative = glob.startsWith("**/") ? pattern(glob.substring(3)) : null;
                boolean basename = !glob.contains("/");
                result.add(new Glob(direct, rootAlternative, basename));
            }
        }
        return List.copyOf(result);
    }

    /** Preserve NIO glob syntax while matching through a cancellable CharSequence. */
    private static Pattern pattern(String glob) {
        try {
            FileSystems.getDefault().getPathMatcher("glob:" + glob); // Validate ranges, groups and platform syntax.
            StringBuilder expression = new StringBuilder();
            boolean group = false;
            for (int i = 0; i < glob.length(); i++) {
                char value = glob.charAt(i);
                switch (value) {
                    case '*' -> {
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') { expression.append(".*"); i++; }
                        else expression.append("[^/]*");
                    }
                    case '?' -> expression.append("[^/]");
                    case '{' -> { expression.append("(?:"); group = true; }
                    case '}' -> { expression.append(group ? ")" : "\\}"); group = false; }
                    case ',' -> expression.append(group ? "|" : ",");
                    case '[' -> {
                        expression.append("[[^/]&&["); i++;
                        if (glob.charAt(i) == '!') { expression.append('^'); i++; }
                        else if (glob.charAt(i) == '^') { expression.append("\\^"); i++; }
                        for (; glob.charAt(i) != ']'; i++) {
                            char item = glob.charAt(i);
                            if (item == '[' || item == '&' || item == '\\') expression.append('\\');
                            expression.append(item);
                        }
                        expression.append("]]");
                    }
                    default -> {
                        if (".()+|^$\\".indexOf(value) >= 0) expression.append('\\');
                        expression.append(value);
                    }
                }
            }
            int flags = FileSystems.getDefault().getSeparator().equals("\\") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            return Pattern.compile(expression.toString(), flags);
        } catch (StackOverflowError tooComplex) { throw new IllegalArgumentException("Path filter is too complex"); }
    }
}
