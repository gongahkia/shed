package shed;

import java.io.IOException;
import java.nio.file.Files;
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
        List<Path> normalizedRoots = roots.stream().filter(root -> root != null).map(root -> root.toAbsolutePath().normalize()).toList();
        Path direct = normalizedRoots.stream().filter(normalized::startsWith)
            .max(Comparator.comparingInt(Path::getNameCount)).orElse(null);
        if (direct != null) return direct;
        Path resolved = normalizedIdentity(normalized);
        return normalizedRoots.stream().filter(root -> resolved.startsWith(normalizedIdentity(root)))
            .max(Comparator.comparingInt(Path::getNameCount)).orElse(null);
    }

    /** Resolve aliases in an existing parent while retaining a file path that has not been created yet. */
    static Path normalizedIdentity(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return normalized;
        try {
            return existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
        } catch (IOException | SecurityException error) {
            return normalized;
        }
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
