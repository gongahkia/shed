package shed;

import java.awt.event.KeyEvent;

final class EmacsKeymap {
    enum Prefix {
        NONE,
        CONTROL_X
    }

    enum Action {
        NONE(null),
        SAVE("w"),
        FIND_FILE("files"),
        BUFFERS("buffers"),
        KILL_BUFFER("bd"),
        QUIT("q"),
        COMMANDS("palette"),
        HELP("help emacs"),
        FORWARD_CHAR(null),
        BACKWARD_CHAR(null),
        NEXT_LINE(null),
        PREVIOUS_LINE(null),
        LINE_START(null),
        LINE_END(null),
        FORWARD_WORD(null),
        BACKWARD_WORD(null),
        FILE_START(null),
        FILE_END(null),
        PAGE_DOWN(null),
        PAGE_UP(null),
        DELETE_FORWARD(null),
        KILL_LINE(null),
        KILL_REGION(null),
        COPY_REGION(null),
        YANK(null),
        CANCEL(null);

        private final String exCommand;

        Action(String exCommand) {
            this.exCommand = exCommand;
        }

        String exCommand() {
            return exCommand;
        }
    }

    record Resolution(Prefix nextPrefix, Action action, boolean consume) {
    }

    private EmacsKeymap() {
    }

    static Resolution resolve(Prefix prefix, KeyEvent event) {
        if (event == null) {
            return new Resolution(Prefix.NONE, Action.NONE, false);
        }
        if (prefix == Prefix.CONTROL_X) {
            return resolveControlX(event);
        }
        if (event.getKeyCode() == KeyEvent.VK_F1 || control(event, KeyEvent.VK_H)) {
            return action(Action.HELP);
        }
        if (control(event, KeyEvent.VK_X)) {
            return new Resolution(Prefix.CONTROL_X, Action.NONE, true);
        }
        if (control(event, KeyEvent.VK_F)) return action(Action.FORWARD_CHAR);
        if (control(event, KeyEvent.VK_B)) return action(Action.BACKWARD_CHAR);
        if (control(event, KeyEvent.VK_N)) return action(Action.NEXT_LINE);
        if (control(event, KeyEvent.VK_P)) return action(Action.PREVIOUS_LINE);
        if (control(event, KeyEvent.VK_A)) return action(Action.LINE_START);
        if (control(event, KeyEvent.VK_E)) return action(Action.LINE_END);
        if (control(event, KeyEvent.VK_V)) return action(Action.PAGE_DOWN);
        if (control(event, KeyEvent.VK_D)) return action(Action.DELETE_FORWARD);
        if (control(event, KeyEvent.VK_K)) return action(Action.KILL_LINE);
        if (control(event, KeyEvent.VK_W)) return action(Action.KILL_REGION);
        if (control(event, KeyEvent.VK_Y)) return action(Action.YANK);
        if (control(event, KeyEvent.VK_G)) return action(Action.CANCEL);
        if (meta(event, KeyEvent.VK_X)) return action(Action.COMMANDS);
        if (meta(event, KeyEvent.VK_F)) return action(Action.FORWARD_WORD);
        if (meta(event, KeyEvent.VK_B)) return action(Action.BACKWARD_WORD);
        if (meta(event, KeyEvent.VK_V)) return action(Action.PAGE_UP);
        if (meta(event, KeyEvent.VK_W)) return action(Action.COPY_REGION);
        if (event.isAltDown() && event.isShiftDown() && event.getKeyCode() == KeyEvent.VK_COMMA) return action(Action.FILE_START);
        if (event.isAltDown() && event.isShiftDown() && event.getKeyCode() == KeyEvent.VK_PERIOD) return action(Action.FILE_END);
        return new Resolution(Prefix.NONE, Action.NONE, false);
    }

    private static Resolution resolveControlX(KeyEvent event) {
        if (control(event, KeyEvent.VK_S)) return action(Action.SAVE);
        if (control(event, KeyEvent.VK_F)) return action(Action.FIND_FILE);
        if (control(event, KeyEvent.VK_B)) return action(Action.BUFFERS);
        if (control(event, KeyEvent.VK_C)) return action(Action.QUIT);
        if (control(event, KeyEvent.VK_H)) return action(Action.HELP);
        if (control(event, KeyEvent.VK_G)) return action(Action.CANCEL);
        if (!event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
            return switch (event.getKeyCode()) {
                case KeyEvent.VK_B -> action(Action.BUFFERS);
                case KeyEvent.VK_K -> action(Action.KILL_BUFFER);
                default -> new Resolution(Prefix.NONE, Action.NONE, true);
            };
        }
        return new Resolution(Prefix.NONE, Action.NONE, true);
    }

    private static Resolution action(Action action) {
        return new Resolution(Prefix.NONE, action, true);
    }

    private static boolean control(KeyEvent event, int keyCode) {
        return event.isControlDown() && !event.isAltDown() && !event.isMetaDown() && event.getKeyCode() == keyCode;
    }

    private static boolean meta(KeyEvent event, int keyCode) {
        return event.isAltDown() && !event.isControlDown() && !event.isMetaDown() && event.getKeyCode() == keyCode;
    }
}
