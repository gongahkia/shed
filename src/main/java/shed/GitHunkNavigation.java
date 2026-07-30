package shed;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GitHunkNavigation {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: (.*))?$");

    record Hunk(int oldStart, int oldCount, int newStart, int newCount, String context) {
        int targetLine() {
            return Math.max(1, newStart);
        }

        @Override
        public String toString() {
            String suffix = context == null || context.isBlank() ? "" : " " + context;
            return "-" + oldStart + renderedCount(oldCount) + " +" + newStart + renderedCount(newCount) + suffix;
        }
    }

    private GitHunkNavigation() { }

    static List<Hunk> parse(String diff) {
        List<Hunk> hunks = new ArrayList<>();
        if (diff == null || diff.isBlank()) return hunks;
        for (String line : diff.split("\\R")) {
            Matcher matcher = HUNK.matcher(line);
            if (!matcher.matches()) continue;
            hunks.add(new Hunk(number(matcher.group(1)), count(matcher.group(2)), number(matcher.group(3)), count(matcher.group(4)), matcher.group(5)));
        }
        return hunks;
    }

    private static int number(String value) {
        return Integer.parseInt(value);
    }

    private static int count(String value) {
        return value == null ? 1 : Integer.parseInt(value);
    }

    private static String renderedCount(int value) {
        return value == 1 ? "" : "," + value;
    }
}
