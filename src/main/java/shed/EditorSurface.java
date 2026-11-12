package shed;

/** A pane's editing surface. Offsets are UTF-16 document offsets. Access belongs to the
 * application's UI thread; implementations retain live document anchors in captured views. */
public interface EditorSurface {
    record Selection(int dot, int mark) { }
    record Viewport(int x, int y) { }
    interface View {
        void restoreSelection(EditorSurface surface);
        void restoreViewport(EditorSurface surface);
    }
    int length();
    Selection selection();
    void select(int dot, int mark);
    Viewport viewport();
    void scrollTo(Viewport viewport);
    void focus();
    void replace(int start, int end, String text);
    boolean editable();
    void undo();
    void redo();
    View captureView();
}
