// Clipboard Manager Class
// Handles yank, delete, and paste operations with both internal and system clipboard

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.DataFlavor;
import javax.swing.JTextArea;

public class ClipboardManager {
    private String clipboardBuffer;
    private boolean lastYankWasLine;
    private Clipboard systemClipboard;

    public ClipboardManager() {
        this.clipboardBuffer = "";
        this.lastYankWasLine = false;
        this.systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    // Yank (copy) entire line at cursor position
    public String yankLine(JTextArea textArea) {
        try {
            int caretPos = textArea.getCaretPosition();
            String text = textArea.getText();
            int lineStart = getLineStart(text, caretPos);
            int lineEnd = getLineEnd(text, caretPos);

            String line = text.substring(lineStart, lineEnd);
            if (lineEnd < text.length() && text.charAt(lineEnd) == '\n') {
                line += "\n";
            }

            this.clipboardBuffer = line;
            this.lastYankWasLine = true;

            // Copy to system clipboard
            StringSelection selection = new StringSelection(line);
            systemClipboard.setContents(selection, null);

            return line;
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // Yank (copy) character-wise selection
    public String yankSelection(String text) {
        this.clipboardBuffer = text;
        this.lastYankWasLine = false;

        // Copy to system clipboard
        StringSelection selection = new StringSelection(text);
        systemClipboard.setContents(selection, null);

        return text;
    }

    // Delete entire line at cursor position
    public String deleteLine(JTextArea textArea) {
        try {
            int caretPos = textArea.getCaretPosition();
            String text = textArea.getText();
            int lineStart = getLineStart(text, caretPos);
            int lineEnd = getLineEnd(text, caretPos);

            String line = text.substring(lineStart, lineEnd);
            if (lineEnd < text.length() && text.charAt(lineEnd) == '\n') {
                line += "\n";
                lineEnd++;
            }

            // Store deleted text in clipboard
            this.clipboardBuffer = line;
            this.lastYankWasLine = true;
            StringSelection selection = new StringSelection(line);
            systemClipboard.setContents(selection, null);

            // Remove line from text
            String newText = text.substring(0, lineStart) + text.substring(lineEnd);
            textArea.setText(newText);
            textArea.setCaretPosition(Math.min(lineStart, newText.length()));

            return line;
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // Delete character at cursor position
    public void deleteChar(JTextArea textArea) {
        try {
            int caretPos = textArea.getCaretPosition();
            String text = textArea.getText();

            if (caretPos < text.length()) {
                String newText = text.substring(0, caretPos) + text.substring(caretPos + 1);
                textArea.setText(newText);
                textArea.setCaretPosition(caretPos);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Delete word forward
    public void deleteWord(JTextArea textArea) {
        try {
            int caretPos = textArea.getCaretPosition();
            String text = textArea.getText();
            int wordEnd = findWordEnd(text, caretPos);

            if (wordEnd > caretPos) {
                String deleted = text.substring(caretPos, wordEnd);
                this.clipboardBuffer = deleted;
                this.lastYankWasLine = false;

                String newText = text.substring(0, caretPos) + text.substring(wordEnd);
                textArea.setText(newText);
                textArea.setCaretPosition(caretPos);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Delete to end of line
    public void deleteToEndOfLine(JTextArea textArea) {
        try {
            int caretPos = textArea.getCaretPosition();
            String text = textArea.getText();
            int lineEnd = getLineEnd(text, caretPos);

            if (lineEnd > caretPos) {
                String deleted = text.substring(caretPos, lineEnd);
                this.clipboardBuffer = deleted;
                this.lastYankWasLine = false;

                String newText = text.substring(0, caretPos) + text.substring(lineEnd);
                textArea.setText(newText);
                textArea.setCaretPosition(caretPos);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Paste after cursor (P = before cursor)
    public void paste(JTextArea textArea, boolean before) {
        try {
            String text = textArea.getText();
            int caretPos = textArea.getCaretPosition();

            // Try to get from system clipboard if internal clipboard is empty
            String content = clipboardBuffer;
            if (content.isEmpty()) {
                try {
                    content = (String) systemClipboard.getData(DataFlavor.stringFlavor);
                } catch (Exception e) {
                    return;
                }
            }

            if (lastYankWasLine) {
                // Line-wise paste
                int lineStart = getLineStart(text, caretPos);
                int lineEnd = getLineEnd(text, caretPos);

                if (!content.endsWith("\n")) {
                    content += "\n";
                }

                int pastePos = before ? lineStart : (lineEnd < text.length() ? lineEnd + 1 : lineEnd);
                String newText = text.substring(0, pastePos) + content + text.substring(pastePos);
                textArea.setText(newText);
                textArea.setCaretPosition(pastePos);
            } else {
                // Character-wise paste
                int pastePos = before ? caretPos : (caretPos < text.length() ? caretPos + 1 : caretPos);
                String newText = text.substring(0, pastePos) + content + text.substring(pastePos);
                textArea.setText(newText);
                textArea.setCaretPosition(pastePos);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Get clipboard content
    public String getClipboardContent() {
        return clipboardBuffer;
    }

    // Check if last yank was line-wise
    public boolean wasLastYankLine() {
        return lastYankWasLine;
    }

    // Helper: Find start of line containing position
    private int getLineStart(String text, int pos) {
        int lineStart = text.lastIndexOf('\n', pos - 1);
        return lineStart == -1 ? 0 : lineStart + 1;
    }

    // Helper: Find end of line containing position
    private int getLineEnd(String text, int pos) {
        int lineEnd = text.indexOf('\n', pos);
        return lineEnd == -1 ? text.length() : lineEnd;
    }

    // Helper: Find end of word starting at position
    private int findWordEnd(String text, int pos) {
        if (pos >= text.length()) return pos;

        // Skip current word
        while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        // Skip whitespace to next word
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }

        return pos;
    }
}
