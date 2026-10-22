package shed;

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
        return WorkspaceRootResolver.pathWithinRoot(localPath, target.localRoot()) == null ? null : target;
    }

    synchronized Target targetForPath(Path localPath) {
        if (localPath == null) return null;
        Path candidate = WorkspaceRootResolver.normalizedIdentity(localPath);
        Target selected = null;
        int selectedDepth = -1;
        for (Target target : targets.values()) {
            Path root = WorkspaceRootResolver.normalizedIdentity(target.localRoot());
            if (candidate.startsWith(root) && root.getNameCount() > selectedDepth) {
                selected = target;
                selectedDepth = root.getNameCount();
            }
        }
        return selected;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
