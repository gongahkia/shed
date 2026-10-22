package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ThemePreviewTest {
    @Test
    void exposesOrderedBuiltInThemeColorsForTheGallery() {
        ConfigManager config = new ConfigManager();

        List<ThemePreview> previews = config.getThemePreviews();

        assertFalse(previews.isEmpty());
        ThemePreview first = previews.getFirst();
        assertEquals("shed-dark", first.id());
        assertEquals("Shed Dark", first.displayName());
        assertEquals("#24272E", hex(first.normal()));
        assertEquals("#82AAFF", hex(first.accent()));
        assertEquals("shed-light", previews.get(1).id());
        assertEquals("#FAFAFC", hex(previews.get(1).normal()));
        assertEquals(config.getThemeIds(), previews.stream().map(ThemePreview::id).toList());
        assertTrue(previews.stream().allMatch(preview -> preview.foreground() != null && preview.stringAccent() != null));
        assertEquals(56, previews.size());
        for (ThemePreview preview : previews) {
            assertEquals(preview.id(), config.setTheme(preview.displayName()));
            assertEquals(preview.id(), config.getThemeId());
            assertTrue(contrast(preview.foreground(), preview.normal()) >= 4.5,
                preview.id() + " needs readable editor text");
        }
    }

    @Test
    void nibThemesUseTheirOfficialEditorAndSyntaxRoles() {
        ConfigManager config = new ConfigManager();
        assertTrue(config.defaultConfigTemplate().contains("shed-dark"));
        assertEquals("nib-dark", config.setTheme("Nib Dark"));
        assertEquals("#C9BE83", hex(config.getCaretColor()));
        assertEquals("#3A5550", hex(config.getSelectionColor()));
        assertEquals("#6FA8A0", hex(config.getSyntaxStringColor()));
        assertEquals("#222620", hex(config.getCurrentLineHighlightColor()));
        assertEquals("#20231F", hex(config.getRaisedSurfaceColor()));
        assertEquals("#272A25", hex(config.getFloatingSurfaceColor()));
        assertEquals("#394835", hex(config.getSearchHighlightColor()));
        assertEquals("#55502F", hex(config.getCurrentSearchHighlightColor()));
        assertEquals("#232622", hex(config.getThemeAnsiColors()[0]));
        assertEquals("#DDDCD2", hex(config.getThemeAnsiColors()[15]));

        assertEquals("nib-light", config.setTheme("Nib Light"));
        assertEquals("#244D68", hex(config.getCaretColor()));
        assertEquals("#AFC9C3", hex(config.getSelectionColor()));
        assertEquals("#536126", hex(config.getSyntaxTypeColor()));
        assertEquals("#E4E7E4", hex(config.getCurrentLineHighlightColor()));
        assertEquals("#E7E8E4", hex(config.getRaisedSurfaceColor()));
        assertEquals("#FAFAF7", hex(config.getFloatingSurfaceColor()));
        assertEquals("#CAD7B4", hex(config.getSearchHighlightColor()));
        assertEquals("#DEC06C", hex(config.getCurrentSearchHighlightColor()));
        assertEquals("#1D2B36", hex(config.getThemeAnsiColors()[0]));
        assertEquals("#303F49", hex(config.getThemeAnsiColors()[15]));
        assertEquals("nib-dark", config.setTheme("nib"));
    }

    private static String hex(java.awt.Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static double contrast(java.awt.Color first, java.awt.Color second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(java.awt.Color color) {
        return 0.2126 * channel(color.getRed()) + 0.7152 * channel(color.getGreen()) + 0.0722 * channel(color.getBlue());
    }

    private static double channel(int component) {
        double value = component / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
