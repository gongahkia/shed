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
    }

    private static String hex(java.awt.Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
