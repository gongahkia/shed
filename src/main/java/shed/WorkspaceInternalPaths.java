package shed;

import java.nio.file.Path;
import java.util.Locale;

/** Reserved transaction storage must not become ordinary project source or agent context. */
final class WorkspaceInternalPaths {
    private WorkspaceInternalPaths() { }
    static boolean recoveryArtifactName(String name) { return name.toLowerCase(Locale.ROOT).startsWith(".shed-edit-"); }
    static boolean containsRecoveryArtifact(Path path) {
        for (Path segment : path) if (recoveryArtifactName(segment.toString())) return true;
        return false;
    }
}
