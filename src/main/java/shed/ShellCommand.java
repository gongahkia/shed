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
        return forCommand(command, env, executable, windowsHost());
    }

    static List<String> forCommand(String command, Map<String, String> env, Predicate<String> executable, boolean windows) {
        String shell = resolveShell(env, executable, windows);
        if (powerShell(shell)) return List.of(shell, "-NoProfile", "-Command", command);
        if (commandPrompt(shell)) return List.of(shell, "/d", "/s", "/c", command);
        String flag = usesLoginFlag(shell) ? "-lc" : "-c";
        return List.of(shell, flag, command);
    }

    /** Starts the configured POSIX-like shell without loading its login startup files. */
    static List<String> nonLoginForCommand(String command, Map<String, String> env, Predicate<String> executable) {
        return nonLoginForCommand(command, env, executable, windowsHost());
    }

    static List<String> nonLoginForCommand(String command, Map<String, String> env, Predicate<String> executable, boolean windows) {
        String shell = resolveShell(env, executable, windows);
        if (powerShell(shell)) return List.of(shell, "-NoProfile", "-Command", command);
        if (commandPrompt(shell)) return List.of(shell, "/d", "/s", "/c", command);
        return List.of(shell, "-c", command);
    }

    /**
     * Builds a POSIX shell command from already-separated values.  Expanding variables
     * before this method and quoting each value here preserves arguments containing
     * whitespace, quotes, or shell metacharacters without turning them into syntax.
     */
    static String posixQuotedCommand(List<String> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("task command required");
        java.util.ArrayList<String> quoted = new java.util.ArrayList<>();
        for (String value : values) {
            if (value == null) throw new IllegalArgumentException("task command value required");
            quoted.add(posixQuote(value));
        }
        return String.join(" ", quoted);
    }

    static String posixQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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
                char next = index + 1 < command.length() ? command.charAt(index + 1) : '\0';
                if (Character.isWhitespace(next) || next == '\\' || next == '\'' || next == '\"') {
                    escaped = true;
                } else {
                    current.append(character);
                }
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
        return interactiveCommand(env, executable, windowsHost());
    }

    static List<String> interactiveCommand(Map<String, String> env, Predicate<String> executable, boolean windows) {
        String shell = resolveShell(env, executable, windows);
        if (powerShell(shell)) return List.of(shell, "-NoLogo");
        return usesLoginFlag(shell) ? List.of(shell, "-l") : List.of(shell);
    }

    static String resolveShell(Map<String, String> env, Predicate<String> executable) {
        return resolveShell(env, executable, windowsHost());
    }

    static String resolveShell(Map<String, String> env, Predicate<String> executable, boolean windows) {
        String shell = env == null ? null : env.get("SHELL");
        if (isUsableShell(shell, executable)) {
            return shell;
        }
        if (windows) {
            String systemRoot = env == null ? null : firstPresent(env, "SystemRoot", "SYSTEMROOT");
            if (systemRoot != null && !systemRoot.isBlank()) {
                String separator = systemRoot.endsWith("\\") || systemRoot.endsWith("/") ? "" : "\\";
                String powerShell = systemRoot + separator + "System32\\WindowsPowerShell\\v1.0\\powershell.exe";
                if (isUsableShell(powerShell, executable)) return powerShell;
            }
            String comSpec = env == null ? null : firstPresent(env, "ComSpec", "COMSPEC");
            if (isUsableShell(comSpec, executable)) return comSpec;
            return "cmd.exe";
        }
        for (String fallback : FALLBACK_SHELLS) {
            if (isUsableShell(fallback, executable)) {
                return fallback;
            }
        }
        return "sh";
    }

    private static String firstPresent(Map<String, String> environment, String first, String second) {
        String value = environment.get(first);
        return value == null || value.isBlank() ? environment.get(second) : value;
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
            || "fish".equals(name)
            || powerShell(name)
            || commandPrompt(name)
            || "sh".equals(name)
            || "dash".equals(name)
            || "ksh".equals(name)
            || "mksh".equals(name);
    }

    private static boolean usesLoginFlag(String shell) {
        String name = basename(shell).toLowerCase(Locale.ROOT);
        return "bash".equals(name) || "zsh".equals(name);
    }

    private static boolean powerShell(String shell) {
        String name = basename(shell).toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        return "powershell".equals(name) || "pwsh".equals(name);
    }

    private static boolean commandPrompt(String shell) {
        String name = basename(shell).toLowerCase(Locale.ROOT);
        return "cmd".equals(name) || "cmd.exe".equals(name);
    }

    private static boolean windowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String basename(String path) {
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }
}
