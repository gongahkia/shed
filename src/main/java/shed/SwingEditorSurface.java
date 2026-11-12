package shed;

import java.awt.Point;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Legacy Swing surface. It owns no text or undo history; both belong to the attached document. */
final class SwingEditorSurface implements EditorSurface {
    private final JTextArea area;
    private final JScrollPane scroll;
    private final java.util.function.Supplier<javax.swing.undo.UndoManager> history;
    private final java.util.function.BooleanSupplier writable;

    SwingEditorSurface(JTextArea area, JScrollPane scroll,
                       java.util.function.Supplier<javax.swing.undo.UndoManager> history) {
        this(area, scroll, history, () -> true);
    }
    SwingEditorSurface(JTextArea area, JScrollPane scroll,
                       java.util.function.Supplier<javax.swing.undo.UndoManager> history,
                       java.util.function.BooleanSupplier writable) {
        this.area = area; this.scroll = scroll; this.history = history; this.writable = writable;
    }
    public int length() { return area.getDocument().getLength(); }
    public Selection selection() { return new Selection(area.getCaret().getDot(), area.getCaret().getMark()); }
    public void select(int dot, int mark) {
        int length = length();
        area.setCaretPosition(Math.max(0, Math.min(mark, length)));
        area.moveCaretPosition(Math.max(0, Math.min(dot, length)));
    }
    public Viewport viewport() {
        Point point = scroll.getViewport().getViewPosition();
        return new Viewport(point.x, point.y);
    }
    public void scrollTo(Viewport point) {
        var view = scroll.getViewport();
        var size = view.getViewSize();
        var extent = view.getExtentSize();
        view.setViewPosition(new Point(Math.max(0, Math.min(point.x(), size.width - extent.width)),
            Math.max(0, Math.min(point.y(), size.height - extent.height))));
    }
    public void focus() { area.requestFocusInWindow(); }
    public void replace(int start, int end, String text) {
        if (!writable.getAsBoolean()) throw new IllegalStateException("The document surface is read-only");
        area.replaceRange(text, start, end);
    }
    public boolean editable() { return area.isEditable(); }
    public void undo() { var undo = history.get(); if (undo != null && undo.canUndo()) undo.undo(); }
    public void redo() { var undo = history.get(); if (undo != null && undo.canRedo()) undo.redo(); }
    public View captureView() { return BufferViewState.capture(area, scroll); }
}
