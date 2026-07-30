package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Canvas;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import org.junit.jupiter.api.Test;

class PlainKeymapTest {
    @Test
    void mapsFixedMenuShortcutsAndHelp() {
        assertEquals(PlainKeymap.Action.SAVE, action(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.FIND_FILE, action(KeyEvent.VK_P, InputEvent.META_DOWN_MASK));
        assertEquals(PlainKeymap.Action.COMMANDS, action(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.BUFFERS, action(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.CLOSE, action(KeyEvent.VK_W, InputEvent.META_DOWN_MASK));
        assertEquals(PlainKeymap.Action.HELP, action(KeyEvent.VK_F1, 0));
    }

    @Test
    void leavesNormalTextAndVimEscapeUnmapped() {
        assertEquals(PlainKeymap.Action.NONE, action(KeyEvent.VK_I, 0));
        assertEquals(PlainKeymap.Action.NONE, action(KeyEvent.VK_ESCAPE, InputEvent.CTRL_DOWN_MASK));
    }

    @Test
    void recognizesVimCommandPaletteShortcut() {
        assertEquals(true, InputController.isCommandPaletteShortcut(keyEvent(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(true, InputController.isCommandPaletteShortcut(keyEvent(KeyEvent.VK_P, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(false, InputController.isCommandPaletteShortcut(keyEvent(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK)));
        assertEquals(false, InputController.isCommandPaletteShortcut(keyEvent(KeyEvent.VK_P, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
    }

    private PlainKeymap.Action action(int keyCode, int modifiers) {
        return PlainKeymap.actionFor(keyEvent(keyCode, modifiers));
    }

    private KeyEvent keyEvent(int keyCode, int modifiers) {
        return new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED);
    }
}
