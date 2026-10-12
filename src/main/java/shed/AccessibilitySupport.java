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

    static Color readableText(Color foreground, Color background) {
        if (foreground == null || background == null || meetsTextContrast(foreground, background)) return foreground;
        Color target = contrastRatio(Color.BLACK, background) >= contrastRatio(Color.WHITE, background)
            ? Color.BLACK : Color.WHITE;
        double low = 0.0;
        double high = 1.0;
        for (int step = 0; step < 16; step++) {
            double amount = (low + high) / 2.0;
            if (contrastRatio(blend(foreground, target, amount), background) >= 4.5) high = amount;
            else low = amount;
        }
        Color adjusted = blend(foreground, target, high);
        return meetsTextContrast(adjusted, background) ? adjusted : target;
    }

    private static Color blend(Color first, Color second, double amount) {
        return new Color((int) Math.round(first.getRed() * (1 - amount) + second.getRed() * amount),
            (int) Math.round(first.getGreen() * (1 - amount) + second.getGreen() * amount),
            (int) Math.round(first.getBlue() * (1 - amount) + second.getBlue() * amount));
    }

    private static double luminance(Color color) {
        return 0.2126 * channel(color.getRed()) + 0.7152 * channel(color.getGreen()) + 0.0722 * channel(color.getBlue());
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }
}
