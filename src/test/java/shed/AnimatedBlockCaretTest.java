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
    @Test void presentationPropertiesDoNotCancelNavigationMotion() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area=area();BlockCaret caret=(BlockCaret)area.getCaret();
            try {
                paint(caret);caret.setDot(20);
                area.putClientProperty("shed.exampleStatus", "updated");
                area.setBackground(Color.WHITE);
                assertEquals(Color.BLUE.getRGB(),paint(caret).getRGB(2,2));
                area.setFont(area.getFont().deriveFont(18f));
                assertEquals(Color.BLUE.getRGB(),paint(caret).getRGB(cell(area)*20+2,2),"font geometry changes still snap");
            } finally {area.setCaret(new javax.swing.text.DefaultCaret());}
        });
    }

    @Test void lineBoundaryJumpsAnimateWhenTheViewportScrollsInEitherDirection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area=area();area.setText(" ".repeat(100));area.setSize(1100,100);
            var viewport=new javax.swing.JViewport();viewport.setView(area);viewport.setSize(240,100);
            viewport.setExtentSize(new java.awt.Dimension(240,100));
            BlockCaret caret=(BlockCaret)area.getCaret();
            try {
                caret.setDot(0);viewport.setViewPosition(new java.awt.Point(0,0));
                assertEquals(Color.BLUE.getRGB(),paintInViewport(caret,viewport).getRGB(2,2));
                caret.setDot(90);viewport.setViewPosition(new java.awt.Point(cell(area)*90-180,0));
                assertEquals(Color.BLUE.getRGB(),paintInViewport(caret,viewport).getRGB(2,2),"end jump should depart from its previous screen position");
                area.putClientProperty("shed.caretAnimation",false);paintInViewport(caret,viewport);
                var endPosition=viewport.getViewPosition();
                viewport.setViewPosition(new java.awt.Point(endPosition.x+20,0));
                assertEquals(Color.BLUE.getRGB(),paintInViewport(caret,viewport).getRGB(162,2),"scrolling at a fixed caret position still snaps");
                viewport.setViewPosition(endPosition);paintInViewport(caret,viewport);
                area.putClientProperty("shed.caretAnimation",true);paintInViewport(caret,viewport);
                caret.setDot(0);viewport.setViewPosition(new java.awt.Point(0,0));
                assertEquals(Color.BLUE.getRGB(),paintInViewport(caret,viewport).getRGB(182,2),"start jump should animate back across the visible line");
                assertEquals(0,caret.getDot());
            } finally {area.setCaret(new javax.swing.text.DefaultCaret());}
        });
    }

    private static BufferedImage paintInViewport(BlockCaret caret,javax.swing.JViewport viewport) {
        var image=new BufferedImage(240,100,BufferedImage.TYPE_INT_ARGB);var graphics=image.createGraphics();
        try {var p=viewport.getViewPosition();graphics.translate(-p.x,-p.y);caret.paint(graphics);}
        finally {graphics.dispose();}
        return image;
    }

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
