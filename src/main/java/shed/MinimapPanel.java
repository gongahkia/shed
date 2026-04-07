package shed;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import javax.swing.JTextArea;

class MinimapPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JTextArea textArea;
    private Color bgColor = new Color(0x1A, 0x1B, 0x26);
    private Color textColor = new Color(0xFF, 0xFF, 0xFF, 40);
    private Color viewportColor = new Color(0xFF, 0xFF, 0xFF, 20);
    private int pixelWidth = 80;

    public MinimapPanel(JTextArea textArea) {
        this.textArea = textArea;
        setPreferredSize(new Dimension(pixelWidth, Integer.MAX_VALUE));
        setBackground(bgColor);
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { scrollToMinimapY(e.getY()); }
        });
        addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) { scrollToMinimapY(e.getY()); }
        });
    }

    public void setColors(Color bg, Color text) {
        if (bg != null) { bgColor = bg; setBackground(bg); }
        if (text != null) textColor = new Color(text.getRed(), text.getGreen(), text.getBlue(), 40);
    }

    public void setPixelWidth(int width) {
        pixelWidth = Math.max(0, width);
        setPreferredSize(new Dimension(pixelWidth, Integer.MAX_VALUE));
        revalidate();
        repaint();
    }

    public int getPixelWidth() {
        return pixelWidth;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        String text = textArea.getText();
        if (text.isEmpty()) return;
        Graphics2D g2 = (Graphics2D) g;
        String[] lines = text.split("\n", -1);
        int totalLines = lines.length;
        int panelH = getHeight();
        double scale = Math.min(2.0, (double) panelH / Math.max(1, totalLines));
        // draw text as tiny colored pixels
        g2.setColor(textColor);
        for (int i = 0; i < totalLines; i++) {
            int y = (int) (i * scale);
            if (y >= panelH) break;
            String line = lines[i];
            int len = Math.min(line.length(), pixelWidth);
            if (len > 0) {
                int w = Math.max(1, (int) (len * ((double) pixelWidth / 120)));
                g2.fillRect(2, y, w, Math.max(1, (int) scale));
            }
        }
        // draw viewport rectangle using line-based coordinates
        try {
            int firstVisLine = textArea.getLineOfOffset(textArea.viewToModel2D(new java.awt.geom.Point2D.Double(0, textArea.getVisibleRect().y)));
            int lastVisLine = textArea.getLineOfOffset(textArea.viewToModel2D(new java.awt.geom.Point2D.Double(0, textArea.getVisibleRect().y + textArea.getVisibleRect().height)));
            int vpTop = (int) (firstVisLine * scale);
            int vpHeight = (int) ((lastVisLine - firstVisLine + 1) * scale);
            g2.setColor(viewportColor);
            g2.fillRect(0, vpTop, pixelWidth, Math.max(4, vpHeight));
        } catch (Exception ignored) {}
    }

    private void scrollToMinimapY(int mouseY) {
        String text = textArea.getText();
        String[] lines = text.split("\n", -1);
        double scale = Math.min(2.0, (double) getHeight() / Math.max(1, lines.length));
        int targetLine = (int) (mouseY / scale);
        targetLine = Math.max(0, Math.min(targetLine, lines.length - 1));
        try {
            int offset = textArea.getLineStartOffset(targetLine);
            textArea.setCaretPosition(offset);
            Rectangle2D r = textArea.modelToView2D(offset);
            if (r != null) textArea.scrollRectToVisible(r.getBounds());
        } catch (Exception ignored) {}
    }
}
