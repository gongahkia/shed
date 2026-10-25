package shed;

import java.awt.Point;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import javax.swing.text.Position;

/** A split's view of an editable document; positions track edits made in other splits. */
final class BufferViewState {
    private final Position dot;
    private final Position mark;
    private final Point viewport;
    private final int originalDot;
    private final int originalMark;

    private BufferViewState(Position dot, Position mark, Point viewport) {
        this.dot = dot;
        this.mark = mark;
        this.viewport = viewport;
        this.originalDot = dot.getOffset();
        this.originalMark = mark.getOffset();
    }

    static BufferViewState capture(JTextArea area, JScrollPane scroll) {
        try {
            return new BufferViewState(area.getDocument().createPosition(area.getCaret().getDot()),
                area.getDocument().createPosition(area.getCaret().getMark()), scroll.getViewport().getViewPosition());
        } catch (BadLocationException error) {
            throw new IllegalStateException("Caret outside its document", error);
        }
    }

    void restoreSelection(JTextArea area) {
        int length = area.getDocument().getLength();
        area.setCaretPosition(Math.min(mark.getOffset(), length));
        area.moveCaretPosition(Math.min(dot.getOffset(), length));
    }

    void restoreSelectionAfterChanges(JTextArea area, String before, String after) {
        var changes = WorkspaceTextEdits.diff(before, after);
        area.setCaretPosition(Math.min(mapOffset(originalMark, changes), after.length()));
        area.moveCaretPosition(Math.min(mapOffset(originalDot, changes), after.length()));
    }

    private static int mapOffset(int offset, java.util.List<WorkspaceTextEdits.Hunk> changes) {
        int shift = 0;
        for (var change : changes) {
            if (offset < change.start()) break;
            if (offset >= change.end()) shift += change.replacement().length() - change.before().length();
            else return change.start() + shift + Math.min(offset - change.start(), change.replacement().length());
        }
        return offset + shift;
    }

    void restoreViewport(JScrollPane scroll) {
        var view = scroll.getViewport();
        var size = view.getViewSize();
        var extent = view.getExtentSize();
        view.setViewPosition(new Point(Math.min(viewport.x, Math.max(0, size.width - extent.width)),
            Math.min(viewport.y, Math.max(0, size.height - extent.height))));
    }
}
