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

    private BufferViewState(Position dot, Position mark, Point viewport) {
        this.dot = dot;
        this.mark = mark;
        this.viewport = viewport;
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

    void restoreViewport(JScrollPane scroll) {
        var view = scroll.getViewport();
        var size = view.getViewSize();
        var extent = view.getExtentSize();
        view.setViewPosition(new Point(Math.min(viewport.x, Math.max(0, size.width - extent.width)),
            Math.min(viewport.y, Math.max(0, size.height - extent.height))));
    }
}
