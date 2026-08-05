package shed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class WorkspaceRoots {
    private final List<Path> roots = new ArrayList<>();
    private Path active;

    List<Path> all() {
        return List.copyOf(roots);
    }

    Path active() {
        return active;
    }

    boolean add(Path path) {
        Path normalized = normalize(path);
        if (roots.contains(normalized)) return false;
        roots.add(normalized);
        if (active == null) active = normalized;
        return true;
    }

    boolean remove(Path path) {
        Path normalized = normalize(path);
        int index = roots.indexOf(normalized);
        if (index < 0) return false;
        roots.remove(index);
        if (normalized.equals(active)) active = roots.isEmpty() ? null : roots.get(Math.min(index, roots.size() - 1));
        return true;
    }

    boolean activate(Path path) {
        Path normalized = normalize(path);
        if (!roots.contains(normalized) || normalized.equals(active)) return false;
        active = normalized;
        return true;
    }

    void replace(List<Path> nextRoots, Path nextActive) {
        roots.clear();
        Set<Path> unique = new LinkedHashSet<>();
        if (nextRoots != null) {
            for (Path root : nextRoots) {
                if (root != null) unique.add(normalize(root));
            }
        }
        roots.addAll(unique);
        Path normalizedActive = nextActive == null ? null : normalize(nextActive);
        active = normalizedActive != null && roots.contains(normalizedActive) ? normalizedActive : roots.stream().findFirst().orElse(null);
    }

    static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
