package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

public class PtyTerminalPaneSettingsTest {
    @Test
    void keepsPlatformClipboardShortcutsAndAvoidsX11SelectionSemantics() {
        PtyTerminalPane.ShedTerminalSettingsProvider settings = new PtyTerminalPane.ShedTerminalSettingsProvider(null, new Font("Monospaced", Font.PLAIN, 14));
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        int modifiers = mac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;

        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_C, modifiers), settings.getCopyActionPresentation().getKeyStrokes().getFirst());
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_V, modifiers), settings.getPasteActionPresentation().getKeyStrokes().getFirst());
        assertFalse(settings.copyOnSelect());
        assertFalse(settings.pasteOnMiddleMouseClick());
        assertFalse(settings.emulateX11CopyPaste());
    }
}
