package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentWorkbenchTest {
    @TempDir Path directory;
    private static final class Host implements DocumentWorkbench.Host {
        final LegacyDocumentStore store;
        DocumentWorkspace.Id active;
        boolean changedDuringActivation;
        boolean removed;
        Host(LegacyDocumentStore store) { this.store = store; }
        public DocumentWorkspace.Id activeId() { return active; }
        public void activate(DocumentWorkspace.Id id) { active = changedDuringActivation ? null : id; }
        public void removeActiveDocument() { store.legacyBuffers().remove(store.resolve(active)); removed = true; active = null; }
        public String open(Path path, int line, int column, String label) {
            try {
                FileBuffer buffer = store.load(path); store.legacyBuffers().add(buffer); active = store.id(buffer);
                return "opened";
            } catch (Exception error) { throw new RuntimeException(error); }
        }
        public String save(String target, boolean close, boolean force) {
            try { store.resolve(active).save(); return "saved"; }
            catch (Exception error) { throw new RuntimeException(error); }
        }
        public String split(boolean vertical) { return "split"; }
        public String closeWindow(boolean force) { return "closed"; }
        public String navigate(boolean forward) { return "navigate"; }
        public EditorSurface surface() { return null; }
        public List<Path> roots() { return List.of(); }
        public Path activeRoot() { return null; }
    }
    @Test void realDocumentLifecyclePreservesTextHistoryEncodingAndExplicitDirtyDecisions() throws Exception {
        Path file = Files.writeString(directory.resolve("document.txt"), "a\r\nb\r\n");
        LegacyDocumentStore store = new LegacyDocumentStore(null);
        Host host = new Host(store);
        DocumentWorkspace workspace = new DocumentWorkbench(store, new WorkspaceRoots(), host);
        assertEquals("opened", workspace.open(file, 0, 0, "document.txt"));
        var identity = workspace.activeDocument().id();
        FileBuffer document = store.resolve(identity);
        document.getDocument().insertString(0, "prefix", null);
        document.setModified(true);
        assertTrue(workspace.activeDocument().modified());
        assertFalse(workspace.close(identity, false));
        assertSame(document, store.resolve(identity));
        document.getUndoManager().undo();
        assertEquals("a\nb\n", document.getContent());
        document.getUndoManager().redo();
        assertEquals("prefixa\nb\n", document.getContent());
        assertEquals("saved", workspace.save(null, false, false));
        assertEquals("prefixa\r\nb\r\n", Files.readString(file));
        assertEquals(identity, workspace.activeDocument().id());
        assertTrue(document.getUndoManager().canUndo(), "saving must retain native history");
        assertTrue(workspace.close(identity, false));
        assertFalse(workspace.activate(identity));
        assertTrue(workspace.documents().isEmpty());
    }
    @Test void identitiesSurviveReorderingAndCloseRevalidatesActivation() {
        LegacyDocumentStore store = new LegacyDocumentStore(null);
        FileBuffer first = FileBuffer.createScratch("same", "first"), second = FileBuffer.createScratch("same", "second");
        store.legacyBuffers().addAll(List.of(first, second));
        Host host = new Host(store);
        DocumentWorkspace workspace = new DocumentWorkbench(store, new WorkspaceRoots(), host);
        var initial = workspace.documents();
        Collections.reverse(store.legacyBuffers());
        assertTrue(workspace.activate(initial.getFirst().id()));
        assertSame(first, store.resolve(host.active));
        assertEquals("Buffer 1 of 2", workspace.cycle(1));
        assertSame(second, store.resolve(host.active));
        host.changedDuringActivation = true;
        assertFalse(workspace.close(initial.getFirst().id(), true));
        assertFalse(host.removed);
        assertEquals(2, workspace.documents().size());
        assertThrows(UnsupportedOperationException.class, () -> initial.clear());
    }
}
