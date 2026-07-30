package shed;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TaskProblemParser {
    private static final Pattern GENERIC_PATTERN = Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?:(.*)$");

    private TaskProblemParser() {
    }

    static List<QuickfixService.Entry> parseGeneric(String output, String source, File workingDirectory) {
        List<QuickfixService.Entry> entries = new ArrayList<>();
        if (output == null || output.isBlank() || workingDirectory == null) return entries;
        String resolvedSource = source == null ? "" : source;
        for (String line : output.split("\\n")) {
            Matcher matcher = GENERIC_PATTERN.matcher(line);
            if (!matcher.matches()) continue;
            int lineNumber;
            int columnNumber = 1;
            try {
                lineNumber = Integer.parseInt(matcher.group(2));
                String column = matcher.group(3);
                if (column != null && !column.isBlank()) columnNumber = Integer.parseInt(column);
            } catch (NumberFormatException ignored) {
                continue;
            }
            File path = new File(matcher.group(1).trim());
            if (!path.isAbsolute()) path = new File(workingDirectory, path.getPath());
            try {
                path = path.getCanonicalFile();
            } catch (IOException ignored) {
                path = path.getAbsoluteFile();
            }
            String message = matcher.group(4) == null ? "" : matcher.group(4).trim();
            entries.add(new QuickfixService.Entry(path.getPath(), lineNumber, columnNumber, message, resolvedSource));
        }
        return entries;
    }
}
