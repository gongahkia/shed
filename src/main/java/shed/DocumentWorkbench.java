package shed;

import java.nio.file.Path;
import java.util.List;

/** Coordinates document identity and explicit close decisions independently of presentation.
 * The host is the integration boundary for existing save/open and specialized view pipelines. */
final class DocumentWorkbench implements DocumentWorkspace {
    interface Host {
        Id activeId();
        void activate(Id id);
        String open(Path path, int line, int column, String label);
        String save(String targetPath, boolean closeAfterSave, boolean force);
        void removeActiveDocument();
        String split(boolean vertical);
        String closeWindow(boolean force);
        String navigate(boolean forward);
        EditorSurface surface();
    }
    private final LegacyDocumentStore documents;
    private final Host host;
    private final WorkspaceRoots roots;
    DocumentWorkbench(LegacyDocumentStore documents, WorkspaceRoots roots, Host host) {
        this.documents = documents; this.roots = roots; this.host = host;
    }
    public List<Document> documents() { return documents.documents(); }
    public Document activeDocument() { return documents.describe(documents.resolve(host.activeId())); }
    public boolean activate(Id id) {
        if (documents.resolve(id) == null) return false;
        host.activate(id);
        return true;
    }
    public String cycle(int direction) {
        List<Document> open = documents();
        if (open.isEmpty()) return "No buffers open";
        Id active = host.activeId();
        int index = 0;
        for (int i = 0; i < open.size(); i++) if (open.get(i).id().equals(active)) index = i;
        int next = Math.floorMod(index + (direction < 0 ? -1 : 1), open.size());
        activate(open.get(next).id());
        return "Buffer " + (next + 1) + " of " + open.size();
    }
    public boolean isOpen(Path path) {
        String absolute = path.toFile().getAbsolutePath();
        return documents().stream().anyMatch(document -> absolute.equals(document.path()));
    }
    public String open(Path path, int line, int column, String label) {
        return path == null ? "File path is unavailable" : host.open(path, line, column, label);
    }
    public String save(String targetPath, boolean closeAfterSave, boolean force) {
        return host.save(targetPath, closeAfterSave, force);
    }
    public boolean close(Id id, boolean discard) {
        FileBuffer target = documents.resolve(id);
        if (target == null || target.isModified() && !discard) return false;
        host.activate(id);
        // Activation can synchronously invoke integrations; never discard a different document.
        if (!id.equals(host.activeId()) || documents.resolve(id) != target) return false;
        host.removeActiveDocument();
        return documents.resolve(id) == null;
    }
    public String split(boolean vertical) { return host.split(vertical); }
    public String closeWindow(boolean force) { return host.closeWindow(force); }
    public String navigate(boolean forward) { return host.navigate(forward); }
    public EditorSurface activeSurface() { return host.surface(); }
    public List<Path> roots() { return roots.all(); }
    public Path activeRoot() { return roots.active(); }
}
