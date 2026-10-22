package shed;

import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;

/** Resolves one-based line/UTF-16 column coordinates without crossing into the next line. */
final class EditorLocation {
    private EditorLocation() { }

    static int offset(JTextArea area, int line, int column) throws BadLocationException {
        int lineIndex = Math.min(Math.max(1, line), area.getLineCount()) - 1;
        int start = area.getLineStartOffset(lineIndex);
        int end = Math.min(area.getDocument().getLength(), area.getLineEndOffset(lineIndex));
        while (end > start) {
            char last = area.getDocument().getText(end - 1, 1).charAt(0);
            if (last != '\n' && last != '\r') break;
            end--;
        }
        int offset = start + Math.min(Math.max(1, column) - 1, end - start);
        if (offset > start && offset < end) {
            String pair = area.getDocument().getText(offset - 1, 2);
            if (Character.isSurrogatePair(pair.charAt(0), pair.charAt(1))) offset--;
        }
        return offset;
    }
}
