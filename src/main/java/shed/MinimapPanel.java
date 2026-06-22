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
    private final PerfService perfService;

    public MinimapPanel(JTextArea textArea) {
        this(textArea, null);
    }

    public MinimapPanel(JTextArea textArea, PerfService perfService) {
        this.textArea = textArea;
        this.perfService = perfService;
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
        long started = System.nanoTime();
        String detail = "";
        try {
            super.paintComponent(g);
            if (textArea.getDocument().getLength() == 0) return;
            Graphics2D g2 = (Graphics2D) g;
            int totalLines = Math.max(1, textArea.getLineCount());
            int panelH = getHeight();
            detail = "lines=" + totalLines + " height=" + panelH;
            double scale = scaleForLineCount(totalLines, panelH);
            int lineStep = sampleStepForLineCount(totalLines, panelH);
            g2.setColor(textColor);
            for (int i = 0; i < totalLines; i += lineStep) {
                int y = (int) (i * scale);
                if (y >= panelH) break;
                int len = lineLength(i);
                if (len > 0) {
                    int w = Math.max(1, (int) (len * ((double) pixelWidth / 120)));
                    int drawWidth = Math.max(1, Math.min(Math.max(1, pixelWidth - 2), w));
                    g2.fillRect(2, y, drawWidth, Math.max(1, (int) Math.ceil(scale * lineStep)));
                }
            }
            try {
                int firstVisLine = textArea.getLineOfOffset(textArea.viewToModel2D(new java.awt.geom.Point2D.Double(0, textArea.getVisibleRect().y)));
                int lastVisLine = textArea.getLineOfOffset(textArea.viewToModel2D(new java.awt.geom.Point2D.Double(0, textArea.getVisibleRect().y + textArea.getVisibleRect().height)));
                int vpTop = (int) (firstVisLine * scale);
                int vpHeight = (int) ((lastVisLine - firstVisLine + 1) * scale);
                g2.setColor(viewportColor);
                g2.fillRect(0, vpTop, pixelWidth, Math.max(4, vpHeight));
            } catch (Exception ignored) {}
        } finally {
            if (perfService != null) {
                perfService.recordDuration("minimap.paint", started, detail);
            }
        }
    }

    private void scrollToMinimapY(int mouseY) {
        int lineCount = Math.max(1, textArea.getLineCount());
        double scale = scaleForLineCount(lineCount, getHeight());
        int targetLine = (int) (mouseY / scale);
        targetLine = Math.max(0, Math.min(targetLine, lineCount - 1));
        try {
            int offset = textArea.getLineStartOffset(targetLine);
            textArea.setCaretPosition(offset);
            Rectangle2D r = textArea.modelToView2D(offset);
            if (r != null) textArea.scrollRectToVisible(r.getBounds());
        } catch (Exception ignored) {}
    }

    private int lineLength(int line) {
        try {
            int start = textArea.getLineStartOffset(line);
            int end = textArea.getLineEndOffset(line);
            int length = Math.max(0, end - start);
            if (length > 0) {
                String tail = textArea.getText(end - 1, 1);
                if ("\n".equals(tail)) {
                    length--;
                }
            }
            return Math.min(length, pixelWidth);
        } catch (Exception ignored) {
            return 0;
        }
    }

    static double scaleForLineCount(int totalLines, int panelHeight) {
        return Math.min(2.0, (double) Math.max(1, panelHeight) / Math.max(1, totalLines));
    }

    static int sampleStepForLineCount(int totalLines, int panelHeight) {
        return Math.max(1, (int) Math.ceil((double) Math.max(1, totalLines) / Math.max(1, panelHeight)));
    }
}
