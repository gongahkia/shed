package shed;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Position;

/** Maintains placeholder positions while a snippet is edited. */
final class SnippetSession {
    private Document document;
    private List<Placeholder> placeholders = List.of();
    private int index = -1;

    boolean begin(JTextArea area, int baseOffset, List<SnippetExpansion.Placeholder> source) {
        clear();
        if (area == null || source == null || source.isEmpty()) return false;
        Document target = area.getDocument();
        List<Placeholder> resolved = new ArrayList<>();
        try {
            for (SnippetExpansion.Placeholder placeholder : source) {
                int start = baseOffset + placeholder.start();
                int end = baseOffset + placeholder.end();
                if (start < 0 || end < start || end > target.getLength()) return false;
                resolved.add(new Placeholder(target.createPosition(start), target.createPosition(end)));
            }
        } catch (BadLocationException error) {
            return false;
        }
        document = target;
        placeholders = List.copyOf(resolved);
        index = 0;
        select(area);
        return true;
    }

    boolean move(JTextArea area, int direction) {
        if (area == null || area.getDocument() != document || placeholders.isEmpty()) return false;
        int next = index + direction;
        if (next < 0 || next >= placeholders.size()) {
            clear();
            return false;
        }
        index = next;
        select(area);
        return true;
    }

    void clear() {
        document = null;
        placeholders = List.of();
        index = -1;
    }

    private void select(JTextArea area) {
        Placeholder placeholder = placeholders.get(index);
        int start = placeholder.start().getOffset();
        int end = placeholder.end().getOffset();
        if (start < 0 || end < start || end > area.getDocument().getLength()) {
            clear();
            return;
        }
        area.setCaretPosition(start);
        area.moveCaretPosition(end);
    }

    private record Placeholder(Position start, Position end) {}
}
