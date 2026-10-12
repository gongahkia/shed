package shed;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** A physical macOS font lets the initial editor paint before Java scans installed fonts. */
final class StagedStartupFont {
    private static final Path MAC_MONO_FONT = Path.of("/System/Library/Fonts/SFNSMono.ttf");

    private StagedStartupFont() {
    }

    static Font forConfiguration(ConfigManager config) {
        if (GraphicsEnvironment.isHeadless()
            || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
            || !"Monospaced".equalsIgnoreCase(config.getFontFamily())
            || !config.getUiFontFamily().isBlank()
            || config.getUiFontSize() != 0
            || !Files.isReadable(MAC_MONO_FONT)) {
            return null;
        }
        try {
            return Font.createFont(Font.TRUETYPE_FONT, MAC_MONO_FONT.toFile());
        } catch (FontFormatException | IOException | RuntimeException ignored) {
            return null;
        }
    }

    static void loadSystemFonts() {
        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
    }
}
