package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Canvas;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import org.junit.jupiter.api.Test;

class PlainKeymapTest {
    @Test
    void mapsFixedMenuShortcutsAndHelp() {
        assertEquals(PlainKeymap.Action.NEW, action(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.SAVE, action(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.SAVE_AS, action(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.OPEN, action(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.OPEN_FOLDER, action(KeyEvent.VK_O, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.FIND_FILE, action(KeyEvent.VK_P, InputEvent.META_DOWN_MASK));
        assertEquals(PlainKeymap.Action.COMMANDS, action(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.BUFFERS, action(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.NEXT_SELECTION, action(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.NEXT_SELECTION, action(KeyEvent.VK_D, InputEvent.META_DOWN_MASK));
        assertEquals(PlainKeymap.Action.CURSOR_ABOVE, action(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.CURSOR_BELOW, action(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.CLOSE, action(KeyEvent.VK_W, InputEvent.META_DOWN_MASK));
        assertEquals(PlainKeymap.Action.QUIT, action(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.HELP, action(KeyEvent.VK_F1, 0));
    }

    @Test
    void mapsConventionalEditingAndSearchShortcuts() {
        assertEquals(PlainKeymap.Action.NAVIGATE_BACK, action(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.NAVIGATE_FORWARD,
            action(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.FIND, action(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.FIND_IN_FILES, action(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.FIND_IN_FILES, action(KeyEvent.VK_F, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.FIND_NEXT, action(KeyEvent.VK_F3, 0));
        assertEquals(PlainKeymap.Action.FIND_PREVIOUS, action(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.REPLACE, action(KeyEvent.VK_F, InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.NONE, action(KeyEvent.VK_H, InputEvent.META_DOWN_MASK));
        assertEquals(PlainKeymap.Action.GO_TO_LINE, action(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.FIND_NEXT, action(KeyEvent.VK_G, InputEvent.META_DOWN_MASK));
        assertEquals(PlainKeymap.Action.FIND_PREVIOUS, action(KeyEvent.VK_G, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.SYMBOLS, action(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.WORKSPACE_SYMBOLS,
            action(KeyEvent.VK_R, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.UNDO, action(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK));
        assertEquals(PlainKeymap.Action.REDO, action(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.REDO, action(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.NEXT_BUFFER, action(KeyEvent.VK_PAGE_DOWN, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.PREVIOUS_BUFFER, action(KeyEvent.VK_PAGE_UP, InputEvent.CTRL_DOWN_MASK));
    }

    @Test
    void leavesNormalTextAndVimEscapeUnmapped() {
        assertEquals(PlainKeymap.Action.NONE, action(KeyEvent.VK_I, 0));
        assertEquals(PlainKeymap.Action.NONE, action(KeyEvent.VK_ESCAPE, InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.NONE, action(KeyEvent.VK_S, InputEvent.ALT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK));
        assertEquals(PlainKeymap.Action.NONE, action(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK));
        assertEquals(PlainKeymap.Action.OPEN_FOLDER, action(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(PlainKeymap.Action.NONE, action(KeyEvent.VK_F1, InputEvent.SHIFT_DOWN_MASK));
    }

    @Test
    void recognizesVimCommandPaletteShortcut() {
        assertEquals(true, InputController.isCommandPaletteShortcut(keyEvent(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(true, InputController.isCommandPaletteShortcut(keyEvent(KeyEvent.VK_P, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(false, InputController.isCommandPaletteShortcut(keyEvent(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK)));
        assertEquals(false, InputController.isCommandPaletteShortcut(keyEvent(KeyEvent.VK_P, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
    }

    @Test
    void openFolderShortcutIsAvailableIndependentlyOfKeymap() {
        assertEquals(true, InputController.isOpenFolderShortcut(keyEvent(KeyEvent.VK_O, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(true, InputController.isOpenFolderShortcut(keyEvent(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(false, InputController.isOpenFolderShortcut(keyEvent(KeyEvent.VK_O, InputEvent.META_DOWN_MASK)));
    }

    @Test
    void settingsShortcutIsAvailableIndependentlyOfKeymap() {
        assertEquals(true, InputController.isSettingsShortcut(keyEvent(KeyEvent.VK_COMMA, InputEvent.META_DOWN_MASK)));
        assertEquals(true, InputController.isSettingsShortcut(keyEvent(KeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK)));
        assertEquals(false, InputController.isSettingsShortcut(keyEvent(KeyEvent.VK_COMMA, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
    }

    @Test
    void recognizesMacPaneShortcuts() {
        assertEquals(InputController.PaneShortcut.HORIZONTAL_SPLIT, InputController.paneShortcut(keyEvent(KeyEvent.VK_D, InputEvent.META_DOWN_MASK)));
        assertEquals(InputController.PaneShortcut.VERTICAL_SPLIT,
            InputController.paneShortcut(keyEvent(KeyEvent.VK_D, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(InputController.PaneShortcut.CLOSE, InputController.paneShortcut(keyEvent(KeyEvent.VK_W, InputEvent.META_DOWN_MASK)));
        assertEquals(InputController.PaneShortcut.NONE, InputController.paneShortcut(keyEvent(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK)));
    }

    @Test
    void adjustsUiZoomInBoundedTenthSteps() {
        assertEquals(1.1, UiZoom.adjust(1.0, 1));
        assertEquals(0.9, UiZoom.adjust(1.0, -1));
        assertEquals(UiZoom.MAXIMUM, UiZoom.adjust(UiZoom.MAXIMUM, 1));
        assertEquals(UiZoom.MINIMUM, UiZoom.adjust(UiZoom.MINIMUM, -1));
    }

    @Test
    void recognizesPlatformZoomWithoutStealingMacNavigation() {
        int primary = ShortcutKey.primaryMask();
        assertEquals(1, InputController.uiZoomDirection(keyEvent(KeyEvent.VK_EQUALS, primary | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(-1, InputController.uiZoomDirection(keyEvent(KeyEvent.VK_MINUS, primary)));
        if (ShortcutKey.isMac()) assertEquals(0, InputController.uiZoomDirection(keyEvent(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK)));
        assertEquals(0, InputController.uiZoomDirection(keyEvent(KeyEvent.VK_MINUS, InputEvent.ALT_DOWN_MASK)));
    }

    @Test
    void recognizesControlAndCommandUiZoomResetShortcuts() {
        assertEquals(true, InputController.isUiZoomResetShortcut(keyEvent(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK)));
        assertEquals(true, InputController.isUiZoomResetShortcut(keyEvent(KeyEvent.VK_NUMPAD0, InputEvent.META_DOWN_MASK)));
        assertEquals(false, InputController.isUiZoomResetShortcut(keyEvent(KeyEvent.VK_0, InputEvent.ALT_DOWN_MASK)));
    }

    private PlainKeymap.Action action(int keyCode, int modifiers) {
        return PlainKeymap.actionFor(keyEvent(keyCode, modifiers));
    }

    private KeyEvent keyEvent(int keyCode, int modifiers) {
        return new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED);
    }
}
