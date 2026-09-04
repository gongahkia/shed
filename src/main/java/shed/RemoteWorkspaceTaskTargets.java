package shed;

import shed.api.RemoteWorkspace;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Shared lookup boundary for explicitly targeting a connected workspace task. */
final class RemoteWorkspaceTaskTargets {
    record Target(String id, RemoteWorkspace workspace, Path localRoot) { }

    private final Map<String, Target> targets = new LinkedHashMap<>();

    synchronized void register(String id, RemoteWorkspace workspace) {
        if (workspace == null || workspace.localRoot() == null) return;
        Path root = workspace.localRoot().toAbsolutePath().normalize();
        targets.put(normalize(id), new Target(normalize(id), workspace, root));
    }

    synchronized void unregister(String id) {
        targets.remove(normalize(id));
    }

    synchronized Target targetFor(String id, Path localPath) {
        Target target = targets.get(normalize(id));
        if (target == null || localPath == null) return null;
        Path candidate = localPath.toAbsolutePath().normalize();
        return candidate.startsWith(target.localRoot()) ? target : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
