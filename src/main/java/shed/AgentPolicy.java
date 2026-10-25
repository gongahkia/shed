package shed;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Application-mediated capabilities; external executable containment is a separate boundary. */
public record AgentPolicy(Set<Capability> capabilities, List<String> protectedPaths,
                          List<String> excludedPaths, boolean backgroundOnly) {
    public enum Capability { READ, PROPOSE, APPLY, RUN_HEADLESS, RUN_INTERACTIVE, OPEN_UI, NOTIFY, START_AGENT, WORKTREE, NETWORK }

    public AgentPolicy {
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities));
        protectedPaths = validatedPatterns(protectedPaths);
        excludedPaths = validatedPatterns(excludedPaths);
    }

    public static AgentPolicy readOnly() {
        return new AgentPolicy(Set.of(Capability.READ), List.of("**/README.md"), List.of(), true);
    }

    public void require(Capability capability) {
        if (!capabilities.contains(capability)) throw new WorkspaceApiException(
            WorkspaceApiException.Code.PERMISSION_DENIED, "Capability is not granted", Map.of("capability", capability.name()));
        if (backgroundOnly && (capability == Capability.RUN_INTERACTIVE || capability == Capability.OPEN_UI || capability == Capability.NOTIFY))
            throw new WorkspaceApiException(WorkspaceApiException.Code.DEFERRED_REQUIRES_FOREGROUND,
                "Action requires foreground interaction", Map.of("capability", capability.name()));
    }

    /** Receives an already canonical, root-relative path from the workspace boundary. */
    public void checkPath(Path relative, Capability capability) {
        require(capability);
        if (relative == null || relative.isAbsolute() || relative.normalize().startsWith(".."))
            throw new WorkspaceApiException(WorkspaceApiException.Code.OUTSIDE_WORKSPACE, "Path is outside the workspace");
        for (Path segment : relative) {
            if (segment.toString().equalsIgnoreCase(".git") || WorkspaceInternalPaths.recoveryArtifactName(segment.toString()))
                throw new WorkspaceApiException(WorkspaceApiException.Code.PROTECTED_PATH, "Git administration and internal recovery resources are not exposed through ordinary file access");
        }
        if (matches(excludedPaths, relative))
            throw new WorkspaceApiException(WorkspaceApiException.Code.EXCLUDED_PATH, "Path is excluded from agent access");
        if ((capability == Capability.PROPOSE || capability == Capability.APPLY) && matches(protectedPaths, relative))
            throw new WorkspaceApiException(WorkspaceApiException.Code.PROTECTED_PATH, "Path is protected from agent changes");
    }

    public Map<String, Object> toMap() {
        return Map.of("capabilities", capabilities.stream().map(Enum::name).sorted().toList(),
            "protectedPaths", protectedPaths, "excludedPaths", excludedPaths, "backgroundOnly", backgroundOnly,
            "externalProcessSandbox", false);
    }

    private static List<String> validatedPatterns(List<String> patterns) {
        List<String> copy = List.copyOf(Objects.requireNonNull(patterns));
        if (copy.size() > 100) throw new IllegalArgumentException("At most 100 path patterns are supported");
        for (String pattern : copy) {
            if (pattern.isBlank() || pattern.length() > 1024 || pattern.indexOf('\0') >= 0 || pattern.indexOf('\\') >= 0
                || pattern.startsWith("/") || pattern.contains(":")) throw new IllegalArgumentException("Expected a relative slash-separated glob");
            for (String segment : pattern.split("/")) if (segment.equals("..")) throw new IllegalArgumentException("Traversal patterns are not allowed");
            FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        }
        return copy;
    }

    private static boolean matches(List<String> patterns, Path path) {
        for (String pattern : patterns) {
            if (match(pattern, path) || pattern.startsWith("**/") && match(pattern.substring(3), path)) return true;
        }
        return false;
    }

    private static boolean match(String pattern, Path path) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        return matcher.matches(path);
    }
}
