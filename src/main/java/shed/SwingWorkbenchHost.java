package shed;

import java.nio.file.Path;

/** Explicit legacy integration adapter. UI types and feature controllers stop at this class. */
final class SwingWorkbenchHost implements DocumentWorkbench.Host {
    private final Texteditor editor;
    private final LegacyDocumentStore documents;
    SwingWorkbenchHost(Texteditor editor, LegacyDocumentStore documents) { this.editor = editor; this.documents = documents; }
    public DocumentWorkspace.Id activeId() { return documents.id(editor.getCurrentBuffer()); }
    public void activate(DocumentWorkspace.Id id) {
        var buffer = documents.resolve(id);
        if (buffer != null) editor.paneBufferController.switchToBuffer(editor.buffers.indexOf(buffer));
    }
    public String open(Path path, int line, int column, String label) {
        if (line > 0) return editor.openFileAtAsync(path.toFile(), line, column, "Opened: " + label);
        editor.openFileAsync(path.toFile());
        return "Opening: " + label;
    }
    public String save(String targetPath, boolean closeAfterSave, boolean force) {
        return editor.formatOnSaveController.requestCurrent(targetPath, closeAfterSave, force);
    }
    public void removeActiveDocument() { editor.showMessage(editor.closeActiveBufferWithoutExiting(true)); }
    public String split(boolean vertical) { return editor.paneBufferController.splitWindow(vertical); }
    public String closeWindow(boolean force) { return editor.sessionConfigController.requestCloseActiveWindow(force); }
    public String navigate(boolean forward) { return editor.editActionController.navigateHistory(forward); }
    public EditorSurface surface() {
        var pane = editor.getActivePane();
        return pane == null ? null : pane.surface();
    }
}
