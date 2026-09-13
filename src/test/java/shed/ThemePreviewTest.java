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
        assertEquals("one-dark-pro", first.id());
        assertEquals("One Dark Pro", first.displayName());
        assertEquals("#282C34", hex(first.normal()));
        assertEquals("#61AFEF", hex(first.accent()));
        assertEquals(config.getThemeIds(), previews.stream().map(ThemePreview::id).toList());
        assertTrue(previews.stream().allMatch(preview -> preview.foreground() != null && preview.stringAccent() != null));
        assertEquals(52, previews.size());
        for (ThemePreview preview : previews) {
            assertEquals(preview.id(), config.setTheme(preview.displayName()));
            assertEquals(preview.id(), config.getThemeId());
            assertTrue(contrast(preview.foreground(), preview.normal()) >= 4.5,
                preview.id() + " needs readable editor text");
        }
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
