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
