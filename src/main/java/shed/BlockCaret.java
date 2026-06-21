package shed;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

final class BlockCaret extends DefaultCaret {
    private static final long serialVersionUID = 1L;

    @Override
    public void paint(Graphics g) {
        JTextComponent component = getComponent();
        if (component == null || !isVisible()) {
            return;
        }
        try {
            Rectangle2D modelBounds = component.modelToView2D(getDot());
            if (modelBounds == null) {
                return;
            }
            Rectangle bounds = modelBounds.getBounds();
            int caretWidth = Math.max(1, blockWidth(component, getDot()));
            g.setXORMode(component.getBackground());
            g.fillRect(bounds.x, bounds.y, caretWidth, bounds.height);
            g.setPaintMode();
        } catch (BadLocationException ignored) {
        }
    }

    @Override
    protected synchronized void damage(Rectangle r) {
        if (r == null) {
            return;
        }
        JTextComponent component = getComponent();
        x = r.x;
        y = r.y;
        height = r.height;
        width = component == null ? Math.max(1, r.width) : Math.max(1, blockWidth(component, getDot()));
        repaint();
    }

    private int blockWidth(JTextComponent component, int dot) {
        try {
            int length = component.getDocument().getLength();
            if (dot < length) {
                Rectangle2D current = component.modelToView2D(dot);
                Rectangle2D next = component.modelToView2D(dot + 1);
                if (current != null && next != null) {
                    int width = (int) Math.round(next.getX() - current.getX());
                    if (width > 0) {
                        return width;
                    }
                }
            }
        } catch (BadLocationException ignored) {
        }
        return Math.max(1, component.getFontMetrics(component.getFont()).charWidth('W'));
    }
}
