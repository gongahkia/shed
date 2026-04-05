package shed;

import java.awt.event.KeyEvent;

public class ModeEngine {
    public void dispatch(Texteditor editor, EditorState state, KeyEvent e) {
        if (editor == null || state == null || e == null) {
            return;
        }
        EditorMode mode = state.mode == null ? EditorMode.NORMAL : state.mode;
        switch (mode) {
            case NORMAL:
                editor.handleNormalMode(e);
                break;
            case INSERT:
                editor.handleInsertMode(e);
                break;
            case VISUAL:
            case VISUAL_LINE:
                editor.handleVisualMode(e);
                break;
            case REPLACE:
                editor.handleReplaceMode(e);
                break;
            case COMMAND:
                editor.handleCommandMode(e);
                break;
            case SEARCH:
                editor.handleSearchMode(e);
                break;
            default:
                break;
        }
    }
}
