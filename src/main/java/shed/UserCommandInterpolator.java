package shed;

/** Expands documented placeholders in explicit user commands. */
final class UserCommandInterpolator {
    private UserCommandInterpolator() {
    }

    static String interpolate(String command, String file, int line, int column, String word, String selection) {
        if (command == null) return null;
        return command.replace("%file", file == null ? "" : file)
            .replace("%line", Integer.toString(Math.max(1, line)))
            .replace("%col", Integer.toString(Math.max(0, column)))
            .replace("%word", word == null ? "" : word)
            .replace("%selection", selection == null ? "" : selection);
    }
}
