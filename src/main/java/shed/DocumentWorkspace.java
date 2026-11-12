package shed;

import java.nio.file.Path;
import java.util.List;

/** Application-facing workbench operations, invoked on the UI thread. Save and open enqueue
 * the existing asynchronous pipelines; their returned messages do not imply I/O completion. */
public interface DocumentWorkspace {
    record Id(long value) { }
    record Document(Id id, String name, String path, boolean modified, boolean readOnly, long revision) { }
    List<Document> documents();
    Document activeDocument();
    boolean activate(Id id);
    boolean isOpen(Path path);
    String cycle(int direction);
    String open(Path path, int line, int column, String label);
    String save(String targetPath, boolean closeAfterSave, boolean force);
    boolean close(Id id, boolean discard);
    String split(boolean vertical);
    String closeWindow(boolean force);
    String navigate(boolean forward);
    EditorSurface activeSurface();
    List<Path> roots();
    Path activeRoot();
}
