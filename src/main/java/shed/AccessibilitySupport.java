package shed;

import java.awt.Color;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;

final class AccessibilitySupport {
    private AccessibilitySupport() {
    }

    static <T extends JComponent> T describe(T component, String name, String description) {
        AccessibleContext context = component.getAccessibleContext();
        if (context == null) {
            return component;
        }
        context.setAccessibleName(name);
        context.setAccessibleDescription(description);
        return component;
    }

    static double contrastRatio(Color first, Color second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    static boolean meetsTextContrast(Color foreground, Color background) {
        return contrastRatio(foreground, background) >= 4.5;
    }

    private static double luminance(Color color) {
        return 0.2126 * channel(color.getRed()) + 0.7152 * channel(color.getGreen()) + 0.0722 * channel(color.getBlue());
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }
}
