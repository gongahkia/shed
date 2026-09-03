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

    /**
     * Uses the deepest workspace folder for a resource and otherwise retains the
     * user's selected folder.  This avoids treating a sibling project as the
     * active project merely because it was selected in the Explorer.
     */
    static Path configuredOrActive(Path candidate, List<Path> roots, Path active) {
        Path configured = configuredRoot(candidate, roots);
        return configured == null ? active : configured;
    }
}
