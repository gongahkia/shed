package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import org.junit.jupiter.api.Test;

class EmacsKeymapTest {
    @Test
    void resolvesControlXChordsDeterministically() {
        EmacsKeymap.Resolution prefix = resolve(EmacsKeymap.Prefix.NONE, KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK);
        assertEquals(EmacsKeymap.Prefix.CONTROL_X, prefix.nextPrefix());
        assertEquals(EmacsKeymap.Action.NONE, prefix.action());
        assertTrue(prefix.consume());

        EmacsKeymap.Resolution save = resolve(prefix.nextPrefix(), KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK);
        assertEquals(EmacsKeymap.Prefix.NONE, save.nextPrefix());
        assertEquals(EmacsKeymap.Action.SAVE, save.action());
        assertTrue(save.consume());
    }

    @Test
    void cancelsOrIgnoresUnsupportedChordsWithoutFallthrough() {
        EmacsKeymap.Resolution cancelled = resolve(EmacsKeymap.Prefix.CONTROL_X, KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK);
        assertEquals(EmacsKeymap.Prefix.NONE, cancelled.nextPrefix());
        assertEquals(EmacsKeymap.Action.CANCEL, cancelled.action());
        assertTrue(cancelled.consume());

        EmacsKeymap.Resolution unsupported = resolve(EmacsKeymap.Prefix.CONTROL_X, KeyEvent.VK_Q, 0);
        assertEquals(EmacsKeymap.Prefix.NONE, unsupported.nextPrefix());
        assertEquals(EmacsKeymap.Action.NONE, unsupported.action());
        assertTrue(unsupported.consume());
    }

    @Test
    void leavesNormalTextUnconsumedAndMapsEmacsNavigation() {
        EmacsKeymap.Resolution text = resolve(EmacsKeymap.Prefix.NONE, KeyEvent.VK_I, 0);
        assertEquals(EmacsKeymap.Action.NONE, text.action());
        assertFalse(text.consume());
        assertEquals(EmacsKeymap.Action.FORWARD_CHAR,
            resolve(EmacsKeymap.Prefix.NONE, KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK).action());
        assertEquals(EmacsKeymap.Action.COMMANDS,
            resolve(EmacsKeymap.Prefix.NONE, KeyEvent.VK_X, InputEvent.ALT_DOWN_MASK).action());
    }

    private EmacsKeymap.Resolution resolve(EmacsKeymap.Prefix prefix, int keyCode, int modifiers) {
        KeyEvent event = new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED);
        return EmacsKeymap.resolve(prefix, event);
    }
}
