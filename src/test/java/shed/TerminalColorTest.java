package shed;

import static org.junit.jupiter.api.Assertions.*;
import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalPanel;
import java.awt.Color;
import java.awt.Font;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production emulator/settings, in memory only; never starts a PTY, process or shell. */
class TerminalColorTest {
    @TempDir Path temporary;
    private String previousHome;

    @BeforeEach void isolatedHome() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", temporary.toRealPath().toString());
    }
    @AfterEach void restoreHome() { System.setProperty("user.home", previousHome); }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private record Screen(TerminalTextBuffer buffer, PtyTerminalPane.ShedTerminalSettingsProvider settings, StyleState style) { }
    private static Screen render(ConfigManager config, String text) throws Exception {
        return edt(() -> {
            var settings = new PtyTerminalPane.ShedTerminalSettingsProvider(config, new Font("Monospaced", Font.PLAIN, 16));
            var style = new StyleState();
            style.setDefaultStyle(settings.getDefaultStyle());
            var buffer = new TerminalTextBuffer(80, 24, style);
            var panel = new TerminalPanel(settings, buffer, style);
            var terminal = new JediTerminal(panel, buffer, style);
            var emulator = new JediEmulator(new ArrayTerminalDataStream(text.toCharArray()), terminal);
            while (emulator.hasNext()) emulator.next();
            return new Screen(buffer, settings, style);
        });
    }
    private static Color awt(com.jediterm.core.Color color) { return new Color(color.getRed(), color.getGreen(), color.getBlue()); }

    @Test void ordinaryAnsiOutputHasActualColorStylesAndNoEscapeLeakage() throws Exception {
        var screen = render(new ConfigManager(), "\u001b[31mR\u001b[32mG\u001b[34mB\u001b[0m plain");
        assertEquals(1, screen.buffer().getStyleAt(0, 0).getForeground().getColorIndex());
        assertEquals(2, screen.buffer().getStyleAt(1, 0).getForeground().getColorIndex());
        assertEquals(4, screen.buffer().getStyleAt(2, 0).getForeground().getColorIndex());
        assertTrue(screen.buffer().getScreenLines().contains("RGB plain"));
        assertFalse(screen.buffer().getScreenLines().contains("\u001b"));
    }

    @Test void standardRedYellowAndMagentaRemainDistinctInLightAndDarkThemes() {
        ConfigManager config = new ConfigManager();
        for (String theme : new String[]{"shed-light", "shed-dark"}) {
            config.setTheme(theme);
            var settings = new PtyTerminalPane.ShedTerminalSettingsProvider(config, null);
            Set<Color> colors = new HashSet<>();
            for (int index : new int[]{1, 3, 5}) colors.add(awt(settings.getTerminalColorPalette().getForeground(TerminalColor.index(index))));
            assertEquals(3, colors.size(), theme + " must not derive ANSI hues from identical mode backgrounds");
            Color red = awt(settings.getTerminalColorPalette().getForeground(TerminalColor.index(1)));
            assertTrue(red.getRed() > red.getGreen() && red.getRed() > red.getBlue(), theme + " red must remain red");
        }
    }

    @Test void indexedColorsPreserveForegroundAndBackgroundIndependently() throws Exception {
        var screen = render(new ConfigManager(), "\u001b[38;5;196mX\u001b[48;5;33mY");
        var first = screen.buffer().getStyleAt(0, 0);
        var second = screen.buffer().getStyleAt(1, 0);
        // Jediterm resolves extended indexed SGR colors to RGB; verify their visible meaning.
        assertEquals(first.getForeground(), second.getForeground());
        assertEquals(new Color(255, 0, 0), awt(screen.settings().getTerminalColorPalette().getForeground(first.getForeground())));
        assertEquals(new Color(0, 135, 255), awt(screen.settings().getTerminalColorPalette().getBackground(second.getBackground())));
    }

    @Test void rgbColorAndResetKeepExactOutputAndRestoreDefaults() throws Exception {
        var screen = render(new ConfigManager(), "\u001b[38;2;18;164;239mR\u001b[48;2;36;48;60mB\u001b[0mN");
        assertEquals(new Color(18, 164, 239), awt(screen.buffer().getStyleAt(0, 0).getForeground().toColor()));
        assertEquals(new Color(36, 48, 60), awt(screen.buffer().getStyleAt(1, 0).getBackground().toColor()));
        var reset = screen.buffer().getStyleAt(2, 0);
        // null removes the explicit override, revealing the unchanged terminal defaults.
        assertNull(reset.getForeground());
        assertNull(reset.getBackground());
        assertEquals(screen.settings().getDefaultForeground(), screen.style().getDefaultForeground());
        assertEquals(screen.settings().getDefaultBackground(), screen.style().getDefaultBackground());
        assertTrue(screen.buffer().getScreenLines().contains("RBN"));
    }

    @Test void defaultAnsiForegroundPaletteRetainsReadableContrast() {
        ConfigManager config = new ConfigManager();
        for (String theme : new String[]{"shed-light", "shed-dark", "nib-light", "nib-dark"}) {
            config.setTheme(theme);
            var settings = new PtyTerminalPane.ShedTerminalSettingsProvider(config, null);
            for (int index = 0; index < 16; index++) {
                Color foreground = awt(settings.getTerminalColorPalette().getForeground(TerminalColor.index(index)));
                assertTrue(AccessibilitySupport.contrastRatio(foreground, config.getNormalColor()) >= 4.5, theme + ": " + index);
            }
        }
    }

    @Test void dedicatedNibAnsiBackgroundsRemainTheirOfficialPalette() {
        ConfigManager config = new ConfigManager();
        for (String theme : new String[]{"nib-dark", "nib-light"}) {
            config.setTheme(theme);
            var palette = new PtyTerminalPane.ShedTerminalSettingsProvider(config, null).getTerminalColorPalette();
            Color[] expected = config.getThemeAnsiColors();
            for (int index = 0; index < expected.length; index++)
                assertEquals(expected[index], awt(palette.getBackground(TerminalColor.index(index))));
        }
    }

    @Test void refreshingThemeReusesSettingsAndPreservesConfiguredTerminalFont() {
        ConfigManager config = new ConfigManager();
        Font font = new Font("Monospaced", Font.PLAIN, 19);
        var settings = new PtyTerminalPane.ShedTerminalSettingsProvider(config, font);
        Color before = awt(settings.getDefaultBackground().toColor());
        config.setTheme("shed-dark");
        settings.refresh(config, font);
        assertNotEquals(before, awt(settings.getDefaultBackground().toColor()));
        assertEquals(font, settings.getTerminalFont());
        assertEquals(19f, settings.getTerminalFontSize());
    }
}
