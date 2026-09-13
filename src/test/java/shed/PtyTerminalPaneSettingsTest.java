package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.Locale;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PtyTerminalPaneSettingsTest {
    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void saveHome() {
        originalHome = System.getProperty("user.home");
    }

    @AfterEach
    void restoreHome() {
        if (originalHome != null) {
            System.setProperty("user.home", originalHome);
        }
    }

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

    @Test
    void keepsEveryAnsiForegroundReadableAgainstTheTerminalBackground() {
        PtyTerminalPane.ShedTerminalSettingsProvider settings = new PtyTerminalPane.ShedTerminalSettingsProvider(null, new Font("Monospaced", Font.PLAIN, 14));
        assertReadableAnsiPalette(settings);
    }

    @Test
    void keepsTheActiveThemeAnsiPaletteReadableAgainstItsBackground() {
        System.setProperty("user.home", tempDir.resolve("home-terminal-palette").toString());
        ConfigManager configManager = new ConfigManager();
        PtyTerminalPane.ShedTerminalSettingsProvider settings = new PtyTerminalPane.ShedTerminalSettingsProvider(configManager, new Font("Monospaced", Font.PLAIN, 14));

        for (String theme : configManager.getThemeIds()) {
            assertEquals(theme, configManager.setTheme(theme));
            settings.refresh(configManager, new Font("Monospaced", Font.PLAIN, 14));
            assertReadableAnsiPalette(settings);
        }
    }

    @Test
    void refreshAdoptsTheCurrentThemeForAnExistingTerminalSession() {
        System.setProperty("user.home", tempDir.resolve("home-terminal-refresh").toString());
        ConfigManager configManager = new ConfigManager();
        PtyTerminalPane.ShedTerminalSettingsProvider settings = new PtyTerminalPane.ShedTerminalSettingsProvider(configManager,
            new Font("Monospaced", Font.PLAIN, 14));
        Color before = settings.getDefaultBackground().toColor();

        assertEquals("dracula", configManager.setTheme("dracula"));
        settings.refresh(configManager, new Font("Monospaced", Font.PLAIN, 14));

        Color after = settings.getDefaultBackground().toColor();
        assertNotEquals(before, after);
        assertEquals(configManager.getNormalColor().getRed(), after.getRed());
        assertEquals(configManager.getNormalColor().getGreen(), after.getGreen());
        assertEquals(configManager.getNormalColor().getBlue(), after.getBlue());
        assertReadableAnsiPalette(settings);
    }

    private void assertReadableAnsiPalette(PtyTerminalPane.ShedTerminalSettingsProvider settings) {
        Color background = settings.getDefaultBackground().toColor();
        java.awt.Color terminalBackground = new java.awt.Color(background.getRed(), background.getGreen(), background.getBlue());

        for (int index = 0; index < 16; index++) {
            Color foreground = settings.getTerminalColorPalette().getForeground(TerminalColor.index(index));
            java.awt.Color terminalForeground = new java.awt.Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue());
            assertTrue(AccessibilitySupport.meetsTextContrast(terminalForeground, terminalBackground), "ANSI color " + index);
        }
    }
}
