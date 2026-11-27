package shed;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Click-through, window-owned feedback; never moves the system pointer. */
final class AgentUiPresentation extends JComponent implements AutoCloseable {
    private JLayeredPane layer;
    private Component target;
    private String agent = "Agent";
    private float progress;
    private boolean typing, motion;

    AgentUiPresentation() { setOpaque(false); setFocusable(false); }
    @Override public boolean contains(int x, int y) { return false; }

    void show(Component target, String agent, boolean motion) {
        requireEdt();
        close();
        this.target = target; this.agent = agent; this.motion = motion;
        JRootPane root = SwingUtilities.getRootPane(target);
        if (root == null) return;
        layer = root.getLayeredPane();
        setBounds(0, 0, layer.getWidth(), layer.getHeight());
        layer.add(this, JLayeredPane.DRAG_LAYER);
        frame(0, false);
    }

    void frame(float progress, boolean typing) {
        requireEdt();
        Rectangle old = drawingBounds();
        this.progress = Math.clamp(progress, 0, 1); this.typing = typing;
        if (layer != null) {
            setSize(layer.getSize());
            repaint(old.union(drawingBounds()));
        }
    }

    private Point anchor() {
        if (target == null || layer == null) return new Point();
        Point point = new Point(Math.max(0, target.getWidth() / 2), Math.max(0, target.getHeight() / 2));
        if (typing && target instanceof JTextComponent field) {
            try {
                var caret = field.modelToView2D(field.getCaretPosition());
                if (caret != null) point = new Point((int)caret.getX(), (int)caret.getCenterY());
            } catch (javax.swing.text.BadLocationException unavailable) { return new Point(); }
        }
        point = SwingUtilities.convertPoint(target, point, this);
        if (motion && progress < .55f && !typing) {
            double remaining = Math.pow(1 - progress / .55, 3);
            point.translate((int)(-42 * remaining), (int)(28 * remaining));
        }
        return point;
    }

    private Rectangle drawingBounds() {
        Point p = anchor();
        return new Rectangle(p.x - 35, p.y - 35, 210, 85);
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (target == null || !target.isShowing() || layer == null) return;
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Point p = anchor(); g.translate(p.x, p.y);
            Color color = new Color(32, 153, 113);
            if (progress >= .55f && !typing) {
                float phase = (progress - .55f) / .45f;
                int radius = motion ? 5 + (int)(18 * phase) : 10;
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(130 * (1 - phase))));
                g.setStroke(new BasicStroke(2)); g.drawOval(-radius, -radius, radius * 2, radius * 2);
            }
            Path2D pointer = new Path2D.Float();
            pointer.moveTo(0, 0); pointer.lineTo(1, 21); pointer.lineTo(7, 16);
            pointer.lineTo(12, 25); pointer.lineTo(16, 23); pointer.lineTo(11, 14);
            pointer.lineTo(19, 13); pointer.closePath();
            g.setColor(Color.WHITE); g.setStroke(new BasicStroke(3)); g.draw(pointer);
            g.setColor(color); g.fill(pointer);
            g.setFont(target.getFont() == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 11) : target.getFont().deriveFont(11f));
            String label = agent.length() > 22 ? agent.substring(0, 22) : agent;
            int width = g.getFontMetrics().stringWidth(label) + 12;
            g.fillRoundRect(21, 16, width, 20, 7, 7);
            g.setColor(Color.WHITE); g.drawString(label, 27, 30);
        } finally { g.dispose(); }
    }

    boolean installed() { return layer != null; }
    @Override public void close() {
        requireEdt();
        if (layer != null) { JLayeredPane old = layer; Rectangle bounds = drawingBounds(); old.remove(this); old.repaint(bounds); }
        layer = null; target = null; typing = false; progress = 0;
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("UI presentation belongs on the EDT");
    }
}
