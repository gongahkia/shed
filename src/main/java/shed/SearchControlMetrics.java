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
    static void apply(Container parent, Texteditor editor) {
        double zoom = editor.configManager.getUiZoom();
        for (Component child : parent.getComponents()) {
            if (child instanceof javax.swing.JScrollPane) continue;
            if (child instanceof javax.swing.JLabel || child instanceof AbstractButton || child instanceof JTextField || child instanceof JComboBox<?>)
                child.setFont(editor.resolveUiFont());
            int height = Math.max(UiZoom.scale(40, zoom), child.getFont() == null ? 0 : child.getFontMetrics(child.getFont()).getHeight() + UiZoom.scale(10, zoom));
            if (child instanceof AbstractButton button) {
                if (button instanceof javax.swing.JButton push) styleButton(push, editor.configManager);
                int width = button.getFontMetrics(button.getFont()).stringWidth(button.getText() == null ? "" : button.getText())
                    + UiZoom.scale(button instanceof JCheckBox ? 34 : 24, zoom);
                button.setPreferredSize(new Dimension(width, height));
            } else if (child instanceof JTextField field) {
                field.setPreferredSize(new Dimension(UiZoom.scale(180, zoom), height));
            } else if (child instanceof JComboBox<?> combo) {
                combo.setPreferredSize(new Dimension(UiZoom.scale(145, zoom), height));
            } else if (child instanceof Container nested) apply(nested, editor);
        }
        parent.invalidate();
        if (parent instanceof javax.swing.JComponent component) component.revalidate();
    }
    static void styleButton(javax.swing.JButton button, ConfigManager config) {
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override protected void paintText(java.awt.Graphics graphics, javax.swing.JComponent component,
                                               java.awt.Rectangle bounds, String text) {
                if (component.isEnabled()) { super.paintText(graphics, component, bounds, text); return; }
                graphics.setColor(config.getMutedForegroundColor());
                javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(graphics, text, -1,
                    bounds.x, bounds.y + graphics.getFontMetrics().getAscent());
            }
            @Override protected void paintFocus(java.awt.Graphics graphics, AbstractButton target,
                                                java.awt.Rectangle view, java.awt.Rectangle text, java.awt.Rectangle icon) {
                graphics.setColor(config.getFocusColor());
                graphics.drawRect(2, 2, target.getWidth() - 5, target.getHeight() - 5);
            }
        });
        button.setOpaque(true); button.setContentAreaFilled(true);
        button.setBackground(config.getRaisedSurfaceColor()); button.setForeground(config.getEditorForeground());
        button.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(config.getSubtleBorderColor()),
            javax.swing.BorderFactory.createEmptyBorder(4, 9, 4, 9)));
    }
}
