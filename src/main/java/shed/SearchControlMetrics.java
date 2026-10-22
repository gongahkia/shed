package shed;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextField;

/** Shared, zoom-aware hit areas for the three search surfaces. */
final class SearchControlMetrics {
    private SearchControlMetrics() { }
    static void apply(Container parent, double zoom) {
        for (Component child : parent.getComponents()) {
            int height = Math.max(UiZoom.scale(40, zoom), child.getFont() == null ? 0 : child.getFontMetrics(child.getFont()).getHeight() + UiZoom.scale(10, zoom));
            if (child instanceof AbstractButton button) {
                int width = button.getFontMetrics(button.getFont()).stringWidth(button.getText() == null ? "" : button.getText())
                    + UiZoom.scale(button instanceof JCheckBox ? 34 : 24, zoom);
                button.setPreferredSize(new Dimension(width, height));
            } else if (child instanceof JTextField field) {
                field.setPreferredSize(new Dimension(UiZoom.scale(180, zoom), height));
            } else if (child instanceof JComboBox<?> combo) {
                combo.setPreferredSize(new Dimension(UiZoom.scale(145, zoom), height));
            } else if (child instanceof Container nested) apply(nested, zoom);
        }
    }
}
