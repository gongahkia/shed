package shed;

import java.awt.Component;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

public class EditorPane {
    private final java.util.Map<FileBuffer, BufferViewState> bufferViews = new java.util.WeakHashMap<>();
    private final JTextArea textArea;
    private final LineNumberPanel lineNumberPanel;
    private final JScrollPane scrollPane;
    private final SearchManager searchManager;
    private FileBuffer buffer;
    private LargeFileProjection largeFileProjection;
    private PtyTerminalPane terminalPane;
    private Component markdownPreviewComponent;
    private Component alternateComponent;
    private boolean hiddenByFocusMode;

    public EditorPane(JTextArea textArea, LineNumberPanel lineNumberPanel, JScrollPane scrollPane, SearchManager searchManager) {
        this.textArea = textArea;
        this.lineNumberPanel = lineNumberPanel;
        this.scrollPane = scrollPane;
        this.searchManager = searchManager;
    }

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

    void rememberBufferView() {
        // A large-file document is only a movable projection, not stable document coordinates.
        if (buffer != null && !buffer.isLargeFile() && textArea.getDocument() == buffer.getDocument()
            && terminalPane == null && alternateComponent == null && markdownPreviewComponent == null) {
            bufferViews.put(buffer, BufferViewState.capture(textArea, scrollPane));
        }
    }

    BufferViewState rememberedView(FileBuffer target) {
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
