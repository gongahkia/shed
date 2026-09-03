package shed;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Selects the most specific configured multi-root folder containing a path. */
final class WorkspaceRootResolver {
    private WorkspaceRootResolver() {
    }

    static Path configuredRoot(Path candidate, List<Path> roots) {
        if (candidate == null || roots == null) return null;
        Path normalized;
        try {
            normalized = candidate.toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return null;
        }
        return roots.stream().filter(root -> root != null).map(root -> root.toAbsolutePath().normalize())
            .filter(normalized::startsWith).max(Comparator.comparingInt(Path::getNameCount)).orElse(null);
    }
}
