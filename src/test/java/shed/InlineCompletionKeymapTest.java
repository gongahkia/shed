package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InlineCompletionKeymapTest {
    @TempDir Path temporary;
    @Test void legacyVimTabAndEscapeMappingsKeepPriorityWithoutAffectingPlainEditing() {
        String home = System.getProperty("user.home"); System.setProperty("user.home", temporary.toString());
        try {
            var config = new ConfigManager(); config.set("keymap.profile", "vim");
            var tab = new KeyEvent(new JTextArea(), KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_TAB, '\t');
            var escape = new KeyEvent(new JTextArea(), KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
            assertFalse(InlineCompletionController.hasLegacyMapping(config, EditorMode.INSERT, tab));
            config.set("keybind.insert.<tab>", "nop"); config.set("keybind.global.<esc>", "<esc>");
            assertTrue(InlineCompletionController.hasLegacyMapping(config, EditorMode.INSERT, tab));
            assertFalse(InlineCompletionController.hasLegacyMapping(config, EditorMode.NORMAL, tab));
            assertTrue(InlineCompletionController.hasLegacyMapping(config, EditorMode.INSERT, escape));
            config.set("keymap.profile", "plain"); assertFalse(InlineCompletionController.hasLegacyMapping(config, EditorMode.INSERT, tab));
        } finally { System.setProperty("user.home", home); }
    }
}
