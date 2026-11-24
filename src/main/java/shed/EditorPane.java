package shed;

import java.awt.Component;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

public class EditorPane {
    private final java.util.Map<FileBuffer, EditorSurface.View> bufferViews = new java.util.WeakHashMap<>();
    private final JTextArea textArea;
    private final EditorSurface surface;
    private final LineNumberPanel lineNumberPanel;
    private final JScrollPane scrollPane;
    private final SearchManager searchManager;
    private FileBuffer buffer;
    private LargeFileProjection largeFileProjection;
    private PtyTerminalPane terminalPane;
    private Component markdownPreviewComponent;
    private Component alternateComponent;
    private boolean hiddenByFocusMode;
    private boolean disposed;
    private String observationId;
    private AgentEditPresentation agentPresentation;

    AgentEditPresentation agentPresentation() {
        if (disposed) throw new IllegalStateException("Editor pane is disposed");
        if (agentPresentation == null) agentPresentation = new AgentEditPresentation(textArea);
        return agentPresentation;
    }

    void clearAgentPresentation() { if (agentPresentation != null) agentPresentation.clear(); }

    String observationId() {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Pane identity belongs to the EDT");
        if (disposed) throw new WorkspaceApiException(WorkspaceApiException.Code.PANE_UNAVAILABLE, "Editor pane is unavailable");
        if (observationId == null) observationId = "pane-" + java.util.UUID.randomUUID();
        return observationId;
    }

    public EditorPane(JTextArea textArea, LineNumberPanel lineNumberPanel, JScrollPane scrollPane, SearchManager searchManager) {
        this.textArea = textArea;
        this.lineNumberPanel = lineNumberPanel;
        this.scrollPane = scrollPane;
        this.surface = new SwingEditorSurface(textArea, scrollPane,
            () -> buffer == null || buffer.isLargeFile() ? null : buffer.getUndoManager(),
            () -> buffer != null && !buffer.isLargeFile());
        this.searchManager = searchManager;
    }

    public EditorSurface surface() { return surface; }

    public JTextArea getTextArea() {
        return textArea;
    }

    public LineNumberPanel getLineNumberPanel() {
        return lineNumberPanel;
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public Component getComponent() {
        if (terminalPane != null) return terminalPane.getComponent();
        if (alternateComponent != null) return alternateComponent;
        return markdownPreviewComponent == null ? scrollPane : markdownPreviewComponent;
    }

    public SearchManager getSearchManager() {
        return searchManager;
    }

    public FileBuffer getBuffer() {
        return buffer;
    }

    public void setBuffer(FileBuffer buffer) {
        this.buffer = buffer;
    }

    boolean isDisposed() { return disposed; }

    void disposeSourceView() {
        if (disposed) return;
        disposed = true;
        if (agentPresentation != null) { agentPresentation.close(); agentPresentation = null; }
        searchManager.clearHighlights();
        // Retired carets must not activate panes or inspect another view during detachment.
        for (var listener : textArea.getCaretListeners()) textArea.removeCaretListener(listener);
        for (var listener : textArea.getKeyListeners()) textArea.removeKeyListener(listener);
        for (var listener : textArea.getMouseListeners()) textArea.removeMouseListener(listener);
        // Leave focus-lost delivery intact to finish the existing IME composition protocol.
        // Keep an inert Swing component valid for already-queued UI callbacks.
        textArea.setCaret(new javax.swing.text.DefaultCaret());
        textArea.setDocument(new javax.swing.text.PlainDocument());
        textArea.setEditable(false);
        bufferViews.clear();
        buffer = null;
        largeFileProjection = null;
    }

    void rememberBufferView() {
        // A large-file document is only a movable projection, not stable document coordinates.
        if (buffer != null && !buffer.isLargeFile() && textArea.getDocument() == buffer.getDocument()
            && terminalPane == null && alternateComponent == null && markdownPreviewComponent == null) {
            bufferViews.put(buffer, surface.captureView());
        }
    }

    EditorSurface.View rememberedView(FileBuffer target) {
        return bufferViews.get(target);
    }

    LargeFileProjection getLargeFileProjection() {
        return largeFileProjection;
    }

    void setLargeFileProjection(LargeFileProjection largeFileProjection) {
        this.largeFileProjection = largeFileProjection;
    }

    public PtyTerminalPane getTerminalPane() {
        return terminalPane;
    }

    public void setTerminalPane(PtyTerminalPane terminalPane) {
        closeTerminalPane();
        this.terminalPane = terminalPane;
    }

    public void closeTerminalPane() {
        if (terminalPane != null) {
            terminalPane.close();
            terminalPane = null;
        }
    }

    public boolean isMarkdownPreview() {
        return markdownPreviewComponent != null;
    }

    public void setMarkdownPreviewComponent(Component component) {
        closeTerminalPane();
        alternateComponent = null;
        markdownPreviewComponent = component;
    }

    public void clearMarkdownPreviewComponent() {
        markdownPreviewComponent = null;
    }

    public void setAlternateComponent(Component component) {
        closeTerminalPane();
        markdownPreviewComponent = null;
        alternateComponent = component;
    }

    public void clearAlternateComponent() {
        alternateComponent = null;
    }

    Component getAlternateComponent() {
        return alternateComponent;
    }

    Component getMarkdownPreviewComponent() {
        return markdownPreviewComponent;
    }

    boolean isHiddenByFocusMode() {
        return hiddenByFocusMode;
    }

    void setHiddenByFocusMode(boolean hidden) {
        hiddenByFocusMode = hidden;
    }
}
