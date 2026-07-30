package shed;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

final class ShellCommand {
    private static final List<String> FALLBACK_SHELLS = List.of(
        "/bin/bash",
        "/usr/bin/bash",
        "/bin/zsh",
        "/usr/bin/zsh",
        "/bin/sh",
        "/usr/bin/sh"
    );

    private ShellCommand() {}

    static List<String> forCommand(String command) {
        return forCommand(command, System.getenv(), path -> new File(path).canExecute());
    }

    static List<String> forCommand(String command, Map<String, String> env, Predicate<String> executable) {
        String shell = resolveShell(env, executable);
        String flag = usesLoginFlag(shell) ? "-lc" : "-c";
        return List.of(shell, flag, command);
    }

    static List<String> directCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("task command required");
        }
        java.util.ArrayList<String> tokens = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        boolean tokenStarted = false;
        char quote = '\0';
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
                tokenStarted = true;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                tokenStarted = true;
                continue;
            }
            if (quote != '\0') {
                if (character == quote) quote = '\0';
                else current.append(character);
                continue;
            }
            if (character == '\'' || character == '\"') {
                quote = character;
                tokenStarted = true;
                continue;
            }
            if (Character.isWhitespace(character)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                continue;
            }
            current.append(character);
            tokenStarted = true;
        }
        if (escaped) throw new IllegalArgumentException("direct task command ends with an escape");
        if (quote != '\0') throw new IllegalArgumentException("direct task command has an unclosed quote");
        if (tokenStarted) tokens.add(current.toString());
        if (tokens.isEmpty()) throw new IllegalArgumentException("task command required");
        return List.copyOf(tokens);
    }

    static List<String> interactiveCommand() {
        return interactiveCommand(System.getenv(), path -> new File(path).canExecute());
    }

    static List<String> interactiveCommand(Map<String, String> env, Predicate<String> executable) {
        String shell = resolveShell(env, executable);
        return usesLoginFlag(shell) ? List.of(shell, "-l") : List.of(shell);
    }

    static String resolveShell(Map<String, String> env, Predicate<String> executable) {
        String shell = env == null ? null : env.get("SHELL");
        if (isUsableShell(shell, executable)) {
            return shell;
        }
        for (String fallback : FALLBACK_SHELLS) {
            if (isUsableShell(fallback, executable)) {
                return fallback;
            }
        }
        return "sh";
    }

    private static boolean isUsableShell(String shell, Predicate<String> executable) {
        if (shell == null || shell.isBlank() || !isSupportedShellName(shell)) {
            return false;
        }
        if (!shell.contains("/") && !shell.contains("\\")) {
            return true;
        }
        return executable != null && executable.test(shell);
    }

    private static boolean isSupportedShellName(String shell) {
        String name = basename(shell).toLowerCase(Locale.ROOT);
        return "bash".equals(name)
            || "zsh".equals(name)
            || "sh".equals(name)
            || "dash".equals(name)
            || "ksh".equals(name)
            || "mksh".equals(name);
    }

    private static boolean usesLoginFlag(String shell) {
        String name = basename(shell).toLowerCase(Locale.ROOT);
        return "bash".equals(name) || "zsh".equals(name);
    }

    private static String basename(String path) {
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }
}
