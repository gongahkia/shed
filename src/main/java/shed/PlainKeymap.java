package shed;

import java.awt.event.KeyEvent;

final class PlainKeymap {
    enum Action {
        NONE(null),
        SAVE("w"),
        FIND_FILE("files"),
        COMMANDS("palette"),
        BUFFERS("buffers"),
        CLOSE("close"),
        HELP("help keymap");

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
        if (event.getKeyCode() == KeyEvent.VK_F1) {
            return Action.HELP;
        }
        if (!event.isControlDown() && !event.isMetaDown()) {
            return Action.NONE;
        }
        return switch (event.getKeyCode()) {
            case KeyEvent.VK_S -> Action.SAVE;
            case KeyEvent.VK_O -> Action.FIND_FILE;
            case KeyEvent.VK_P -> event.isShiftDown() ? Action.COMMANDS : Action.FIND_FILE;
            case KeyEvent.VK_B -> Action.BUFFERS;
            case KeyEvent.VK_W -> Action.CLOSE;
            default -> Action.NONE;
        };
    }
}
