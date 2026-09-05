package shed;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Detects fixed, standard shell profiles without starting a shell process. */
final class BuiltInTerminalProfiles {
    record Profile(String id, String displayName, List<String> command) {
        Profile {
            if (id == null || !id.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("terminal profile id is invalid");
            if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("terminal profile display name is required");
            command = List.copyOf(command == null ? List.of() : command);
            if (command.isEmpty()) throw new IllegalArgumentException("terminal profile command is required");
        }
    }

    private BuiltInTerminalProfiles() {
    }

    static List<Profile> detect() {
        return detect(System.getenv(), path -> new File(path).canExecute(), windowsHost());
    }

    static List<Profile> detect(Map<String, String> environment, Predicate<String> executable, boolean windows) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        Predicate<String> canExecute = executable == null ? path -> false : executable;
        List<Profile> profiles = new ArrayList<>();
        if (windows) {
            add(profiles, "pwsh", "PowerShell 7", findExecutable("pwsh", env, canExecute, true), List.of("-NoLogo"));
            add(profiles, "powershell", "Windows PowerShell", windowsPowerShell(env, canExecute), List.of("-NoLogo"));
            add(profiles, "cmd", "Command Prompt", commandPrompt(env, canExecute), List.of());
        } else {
            add(profiles, "bash", "Bash", findExecutable("bash", env, canExecute, false), List.of("-l"));
            add(profiles, "zsh", "Zsh", findExecutable("zsh", env, canExecute, false), List.of("-l"));
            add(profiles, "fish", "Fish", findExecutable("fish", env, canExecute, false), List.of());
            add(profiles, "pwsh", "PowerShell 7", findExecutable("pwsh", env, canExecute, false), List.of("-NoLogo"));
        }
        return List.copyOf(profiles);
    }

    static Profile resolve(String id, List<Profile> profiles) {
        if (id == null || profiles == null) return null;
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (normalized.regionMatches(true, 0, "builtin:", 0, "builtin:".length())) {
            normalized = normalized.substring("builtin:".length());
        }
        for (Profile profile : profiles) {
            if (profile.id().equalsIgnoreCase(normalized)) return profile;
        }
        return null;
    }

    private static void add(List<Profile> profiles, String id, String displayName, String executable, List<String> arguments) {
        if (executable == null) return;
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        profiles.add(new Profile(id, displayName, command));
    }

    private static String windowsPowerShell(Map<String, String> env, Predicate<String> executable) {
        String root = firstPresent(env, "SystemRoot", "SYSTEMROOT");
        if (root != null && !root.isBlank()) {
            String separator = root.endsWith("\\") || root.endsWith("/") ? "" : "\\";
            String path = root + separator + "System32\\WindowsPowerShell\\v1.0\\powershell.exe";
            if (executable.test(path)) return path;
        }
        return findExecutable("powershell.exe", env, executable, true);
    }

    private static String commandPrompt(Map<String, String> env, Predicate<String> executable) {
        String configured = firstPresent(env, "ComSpec", "COMSPEC");
        if (configured != null && !configured.isBlank() && executable.test(configured)) return configured;
        return findExecutable("cmd.exe", env, executable, true);
    }

    private static String findExecutable(String name, Map<String, String> env, Predicate<String> executable, boolean windows) {
        String executableName = windows && !name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name + ".exe" : name;
        String shell = env.get("SHELL");
        if (shell != null && shellName(shell).equals(shellName(executableName)) && executable.test(shell)) return shell;
        String path = env.get("PATH");
        if (path == null || path.isBlank()) return null;
        String separator = windows ? ";" : File.pathSeparator;
        String directorySeparator = windows ? "\\" : File.separator;
        for (String entry : path.split(java.util.regex.Pattern.quote(separator))) {
            if (entry == null || entry.isBlank()) continue;
            String candidate = entry.endsWith("/") || entry.endsWith("\\") ? entry + executableName : entry + directorySeparator + executableName;
            if (executable.test(candidate)) return candidate;
        }
        return null;
    }

    private static String firstPresent(Map<String, String> environment, String first, String second) {
        String value = environment.get(first);
        return value == null || value.isBlank() ? environment.get(second) : value;
    }

    private static String shellName(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = (slash < 0 ? normalized : normalized.substring(slash + 1)).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static boolean windowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
