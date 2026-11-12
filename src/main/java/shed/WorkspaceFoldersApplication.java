package shed;

import java.nio.file.Path;
import java.util.List;

/** Explicit workspace-folder operations; implementations own tree/index presentation effects.
 * All calls are synchronous on the owning UI thread. Paths identify choices across list changes. */
public interface WorkspaceFoldersApplication {
    List<Path> roots();
    Path activeRoot();
    String displayRoot(Path root);
    String addFolder(Path path, boolean activate);
    String removeFolder(Path path);
    String activateFolder(Path path, boolean showTree);
}
