package shed;

import java.awt.Component;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

public class EditorPane {
    private final JTextArea textArea;
    private final LineNumberPanel lineNumberPanel;
    private final JScrollPane scrollPane;
    private final SearchManager searchManager;
    private FileBuffer buffer;
    private LargeFileProjection largeFileProjection;
    private PtyTerminalPane terminalPane;

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
        return terminalPane == null ? scrollPane : terminalPane.getComponent();
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
}
