import javax.swing.JScrollPane;
import javax.swing.JTextArea;

public class EditorPane {
    private final JTextArea textArea;
    private final LineNumberPanel lineNumberPanel;
    private final JScrollPane scrollPane;
    private final SearchManager searchManager;
    private FileBuffer buffer;

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

    public SearchManager getSearchManager() {
        return searchManager;
    }

    public FileBuffer getBuffer() {
        return buffer;
    }

    public void setBuffer(FileBuffer buffer) {
        this.buffer = buffer;
    }
}
