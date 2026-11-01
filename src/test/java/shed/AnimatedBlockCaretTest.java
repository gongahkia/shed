package shed;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimatedBlockCaretTest {
    @Test
    void navigationAnimatesPixelsWhileTheInsertionPositionUpdatesImmediately() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = area();
            BlockCaret caret = (BlockCaret) area.getCaret();
            try {
                paint(caret);
                caret.setDot(20);
                BufferedImage moving = paint(caret);
                assertEquals(20, caret.getDot());
                assertEquals(Color.BLUE.getRGB(), moving.getRGB(2, 2));
                assertEquals(0, moving.getRGB(cell(area) * 20 + 2, 2), "Drawing should still be near the old position");
                area.putClientProperty("shed.caretAnimation", false);
                BufferedImage settled = paint(caret);
                assertEquals(0, settled.getRGB(2, 2));
                assertEquals(Color.BLUE.getRGB(), settled.getRGB(cell(area) * 20 + 2, 2));
            } finally { area.setCaret(new javax.swing.text.DefaultCaret()); }
        });
    }

    @Test
    void editsSelectionsFocusLossAndDocumentReplacementSnapToTheRealCaret() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = area();
            BlockCaret caret = (BlockCaret) area.getCaret();
            try {
                paint(caret); caret.setDot(20); paint(caret);
                area.insert(" ", 0);
                assertEquals(Color.BLUE.getRGB(), paint(caret).getRGB(cell(area) * 21 + 2, 2));
                caret.setDot(0); paint(caret); caret.moveDot(20);
                assertEquals(Color.BLUE.getRGB(), paint(caret).getRGB(cell(area) * 20 + 2, 2));
                caret.setDot(0); paint(caret); caret.setVisible(false); caret.setVisible(true);
                assertEquals(Color.BLUE.getRGB(), paint(caret).getRGB(2, 2));
                PlainDocument previous = (PlainDocument) area.getDocument();
                int listeners = previous.getDocumentListeners().length;
                area.setDocument(new PlainDocument());
                assertTrue(previous.getDocumentListeners().length < listeners);
                caret.setVisible(true);
                assertEquals(Color.BLUE.getRGB(), paint(caret).getRGB(2, 2));
            } finally { area.setCaret(new javax.swing.text.DefaultCaret()); }
        });
    }

    @Test
    void swingTimerSettlesAtTheDestinationAndStopsAfterDeinstall() throws Exception {
        var finished = new java.util.concurrent.CompletableFuture<Void>();
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = area();
            BlockCaret caret = (BlockCaret) area.getCaret();
            paint(caret);
            caret.setDot(20);
            paint(caret);
            javax.swing.Timer check = new javax.swing.Timer(400, event -> {
                try {
                    BufferedImage settled = paint(caret);
                    assertEquals(0, settled.getRGB(2, 2));
                    assertEquals(Color.BLUE.getRGB(), settled.getRGB(cell(area) * 20 + 2, 2));
                    caret.setDot(0); paint(caret);
                    area.setCaret(new javax.swing.text.DefaultCaret());
                    assertEquals(0, paint(caret).getRGB(2, 2));
                    finished.complete(null);
                } catch (Throwable error) { finished.completeExceptionally(error); }
                finally { area.setCaret(new javax.swing.text.DefaultCaret()); }
            });
            check.setRepeats(false);
            check.start();
        });
        finished.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static JTextArea area() {
        JTextArea area = new JTextArea("                              ") {
            @Override public boolean isShowing() { return true; }
        };
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        area.setSize(600, 100);
        area.setCaretColor(Color.BLUE);
        area.setCaret(new BlockCaret());
        area.getCaret().setBlinkRate(0);
        area.getCaret().setVisible(true);
        return area;
    }

    private static int cell(JTextArea area) {
        return area.getFontMetrics(area.getFont()).charWidth(' ');
    }

    private static BufferedImage paint(BlockCaret caret) {
        BufferedImage image = new BufferedImage(600, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { caret.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }
}
