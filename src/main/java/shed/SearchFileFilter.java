package shed;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/** Comma-separated root-relative globs; braces retain their internal commas. */
final class SearchFileFilter {
    private final List<PathMatcher> includes;
    private final List<PathMatcher> excludes;
    SearchFileFilter(String include, String exclude) { includes = compile(include); excludes = compile(exclude); }
    boolean accepts(Path relative) {
        return (includes.isEmpty() || includes.stream().anyMatch(pattern -> pattern.matches(relative)))
            && excludes.stream().noneMatch(pattern -> pattern.matches(relative));
    }
    private static List<PathMatcher> compile(String input) {
        List<PathMatcher> result = new ArrayList<>();
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
                PathMatcher direct = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                PathMatcher rootAlternative = glob.startsWith("**/") ? FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)) : null;
                boolean basename = !glob.contains("/");
                result.add(path -> direct.matches(path) || basename && direct.matches(path.getFileName())
                    || rootAlternative != null && rootAlternative.matches(path));
            }
        }
        return List.copyOf(result);
    }
}
