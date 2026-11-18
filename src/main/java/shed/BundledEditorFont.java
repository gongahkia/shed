package shed;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;

/** One lazily loaded physical font; no dependency on a system font installation. */
final class BundledEditorFont {
    static final String FAMILY = "JetBrains Mono";
    private static final String RESOURCE = "/assets/fonts/JetBrainsMono-Regular.ttf";

    private BundledEditorFont() { }

    static boolean matches(String family) {
        return family != null && (FAMILY.equalsIgnoreCase(family.trim()) || "JetBrainsMono".equalsIgnoreCase(family.trim()));
    }

    static boolean available() { return BundledEditorFont.class.getResource(RESOURCE) != null; }

    static Font atSize(float size) {
        return Holder.FONT == null ? null : Holder.FONT.deriveFont(Font.PLAIN, size);
    }

    private static final class Holder {
        static final Font FONT = load();
        private static Font load() {
            try (InputStream source = BundledEditorFont.class.getResourceAsStream(RESOURCE)) {
                if (source == null) return null;
                Font font = Font.createFont(Font.TRUETYPE_FONT, source);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
                return font;
            } catch (java.io.IOException | java.awt.FontFormatException | RuntimeException unavailable) {
                return null;
            }
        }
    }
}
