package shed;

import java.awt.event.KeyEvent;

final class PlainKeymap {
    enum Action {
        NONE(null),
        NEW(null),
        SAVE("w"),
        SAVE_AS(null),
        OPEN("open file"),
        FIND_FILE("files"),
        FIND(null),
        FIND_NEXT(null),
        FIND_PREVIOUS(null),
        REPLACE(null),
        GO_TO_LINE(null),
        UNDO(null),
        REDO(null),
        NEXT_SELECTION(null),
        CURSOR_ABOVE(null),
        CURSOR_BELOW(null),
        NEXT_BUFFER("bn"),
        PREVIOUS_BUFFER("bp"),
        COMMANDS("palette"),
        BUFFERS("buffers"),
        CLOSE("close"),
        QUIT("qa"),
        HELP("help plain");

        private final String exCommand;

        Action(String exCommand) {
            this.exCommand = exCommand;
        }

        String exCommand() {
            return exCommand;
        }
    }

    private PlainKeymap() {
    }

    static Action actionFor(KeyEvent event) {
        if (event == null) {
            return Action.NONE;
        }
        if (event.isControlDown() && event.isMetaDown()) {
            return Action.NONE;
        }
        if (event.isAltDown()) {
            if (event.isControlDown() || event.isMetaDown() || event.isShiftDown()) return Action.NONE;
            return switch (event.getKeyCode()) {
                case KeyEvent.VK_UP -> Action.CURSOR_ABOVE;
                case KeyEvent.VK_DOWN -> Action.CURSOR_BELOW;
                default -> Action.NONE;
            };
        }
        if (!event.isControlDown() && !event.isMetaDown()) {
            return switch (event.getKeyCode()) {
                case KeyEvent.VK_F1 -> event.isShiftDown() ? Action.NONE : Action.HELP;
                case KeyEvent.VK_F3 -> event.isShiftDown() ? Action.FIND_PREVIOUS : Action.FIND_NEXT;
                default -> Action.NONE;
            };
        }
        boolean shift = event.isShiftDown();
        return switch (event.getKeyCode()) {
            case KeyEvent.VK_N -> shift ? Action.NONE : Action.NEW;
            case KeyEvent.VK_S -> shift ? Action.SAVE_AS : Action.SAVE;
            case KeyEvent.VK_O -> shift ? Action.NONE : Action.OPEN;
            case KeyEvent.VK_P -> shift ? Action.COMMANDS : Action.FIND_FILE;
            case KeyEvent.VK_F -> shift ? Action.NONE : Action.FIND;
            case KeyEvent.VK_H -> shift ? Action.NONE : Action.REPLACE;
            case KeyEvent.VK_G -> shift ? Action.NONE : Action.GO_TO_LINE;
            case KeyEvent.VK_Z -> shift ? Action.REDO : Action.UNDO;
            case KeyEvent.VK_D -> shift ? Action.NONE : Action.NEXT_SELECTION;
            case KeyEvent.VK_Y -> shift ? Action.NONE : Action.REDO;
            case KeyEvent.VK_PAGE_DOWN -> shift ? Action.NONE : Action.NEXT_BUFFER;
            case KeyEvent.VK_PAGE_UP -> shift ? Action.NONE : Action.PREVIOUS_BUFFER;
            case KeyEvent.VK_B -> shift ? Action.NONE : Action.BUFFERS;
            case KeyEvent.VK_W -> shift ? Action.NONE : Action.CLOSE;
            case KeyEvent.VK_Q -> shift ? Action.NONE : Action.QUIT;
            default -> Action.NONE;
        };
    }
}
