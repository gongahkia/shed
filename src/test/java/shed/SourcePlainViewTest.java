package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Random;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;

class SourcePlainViewTest {
    private static final class MeasuredDocument extends PlainDocument {
        long readCharacters;
        @Override public void getText(int start, int length, Segment result) throws BadLocationException {
            readCharacters += length;
            super.getText(start, length, result);
        }
    }

    private static JTextArea area(Document document, boolean optimized) {
        JTextArea area = new JTextArea();
        area.setUI(new BasicTextAreaUI() {
            @Override public View create(Element element) {
                View stock = super.create(element);
                return optimized ? SourcePlainView.adapt(stock) : stock;
            }
        });
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        area.setDocument(document);
        area.setSize(800, 500);
        return area;
    }

    private static void equalGeometry(JTextArea stock, JTextArea optimized) throws Exception {
        assertEquals(stock.getDocument().getText(0, stock.getDocument().getLength()),
            optimized.getDocument().getText(0, optimized.getDocument().getLength()));
        assertEquals(stock.getPreferredSize(), optimized.getPreferredSize());
        int length = stock.getDocument().getLength();
        for (int offset : new int[]{0, Math.min(1, length), length / 3, length / 2, Math.max(0, length - 1), length}) {
            Rectangle2D expected = stock.modelToView2D(offset), actual = optimized.modelToView2D(offset);
            assertEquals(expected, actual, "caret geometry at " + offset);
            if (expected != null) {
                Point point = new Point((int) expected.getX() + 2, (int) expected.getY() + 2);
                assertEquals(stock.viewToModel2D(point), optimized.viewToModel2D(point), "hit test at " + offset);
            }
        }
    }

    @Test void undoOfGrowthDoesNotReadTheDocumentAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                MeasuredDocument document = new MeasuredDocument();
                document.insertString(0, ("W".repeat(100) + "\n").repeat(10000), null);
                JTextArea optimized = area(document, true);
                optimized.getPreferredSize();
                document.insertString(0, "x", null);
                document.readCharacters = 0;
                document.remove(0, 1);
                optimized.getPreferredSize();
                assertTrue(document.readCharacters < 8192, "local undo read " + document.readCharacters + " characters");
                assertEquals(1010000, document.getLength());

                UndoManager history = new UndoManager(); document.addUndoableEditListener(history);
                // Line-start insertion/undo changes root Elements. A tied maximum must not move
                // the full scan from undo to the next newline operation.
                document.readCharacters = 0;
                document.insertString(101, "x", null); history.undo();
                document.insertString(101, "\n", null); history.undo();
                optimized.getPreferredSize();
                assertTrue(document.readCharacters < 8192, "line-boundary edits read " + document.readCharacters + " characters");

                MeasuredDocument control = new MeasuredDocument();
                control.insertString(0, document.getText(0, document.getLength()), null);
                JTextArea stock = area(control, false);
                stock.getPreferredSize();
                control.insertString(0, "x", null);
                control.readCharacters = 0;
                control.remove(0, 1);
                stock.getPreferredSize();
                assertTrue(control.readCharacters >= control.getLength(), "control no longer reproduces PlainView rescan");
                assertEquals(stock.getPreferredSize(), optimized.getPreferredSize());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void differentialEditingUndoAndGeometry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                PlainDocument document = new PlainDocument();
                document.insertString(0, "short\n" + "W".repeat(200) + "\nthird line\nlast", null);
                UndoManager history = new UndoManager();
                document.addUndoableEditListener(history);
                JTextArea stock = area(document, false), optimized = area(document, true);
                assertSame(stock.getDocument(), optimized.getDocument());
                equalGeometry(stock, optimized);
                // Simulate size queries by an earlier document listener before the view sees a change.
                document.addDocumentListener(new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) { optimized.getPreferredSize(); }
                    public void removeUpdate(javax.swing.event.DocumentEvent e) { optimized.getPreferredSize(); }
                    public void changedUpdate(javax.swing.event.DocumentEvent e) { optimized.getPreferredSize(); }
                });
                // A non-first line boundary can replace line Elements even without inserting a newline.
                document.insertString(6, "W", null); equalGeometry(stock, optimized);
                history.undo(); equalGeometry(stock, optimized);
                history.redo(); equalGeometry(stock, optimized);
                document.remove(6, 1); equalGeometry(stock, optimized);
                Random random = new Random(981452);
                for (int n = 0; n < 240; n++) {
                    if (n % 6 == 0 && history.canUndo()) {
                        history.undo(); equalGeometry(stock, optimized);
                        history.redo(); equalGeometry(stock, optimized);
                    }
                    int offset = random.nextInt(document.getLength() + 1);
                    if (n % 3 == 0 && offset < document.getLength()) {
                        document.remove(offset, Math.min(1 + random.nextInt(20), document.getLength() - offset));
                    } else {
                        document.insertString(offset, n % 5 == 0 ? "new\nlines\n" : "W".repeat(1 + random.nextInt(40)), null);
                    }
                    equalGeometry(stock, optimized);
                }
                // Delete the widest line and undo, then create a different, wider line.
                document.remove(0, document.getLength());
                document.insertString(0, "W".repeat(300) + "\nsmall\n", null);
                equalGeometry(stock, optimized);
                document.remove(0, 301); equalGeometry(stock, optimized);
                history.undo(); equalGeometry(stock, optimized);
                history.redo(); equalGeometry(stock, optimized);
                document.insertString(document.getLength(), "M".repeat(400), null);
                equalGeometry(stock, optimized);
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void fontsTabsWrappingResizingAndInternationalTextRetainStockGeometryAndPixels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (String text : new String[]{"short\nMMMMMMMMMMMMMMMMMMMM\nlast", "\t\twide\n123456789\nlast", "emoji 😀\ncombining e\u0301\n日本語", "English\nالعربية עברית\nend", "", "one line", "one line\n"}) {
                    PlainDocument document = new PlainDocument(); document.insertString(0, text, null);
                    PlainDocument control = new PlainDocument(); control.insertString(0, text, null);
                    // Tab size is a Document property. Independent documents ensure both components
                    // receive a real tabSize change, rather than the second setter being a no-op.
                    JTextArea stock = area(control, false), optimized = area(document, true);
                    for (Font font : new Font[]{new Font(Font.MONOSPACED, Font.PLAIN, 12), new Font(Font.DIALOG, Font.PLAIN, 20)}) {
                        stock.setFont(font); optimized.setFont(font);
                        for (int tabs : new int[]{2, 8}) {
                            stock.setTabSize(tabs); optimized.setTabSize(tabs);
                            for (boolean wrap : new boolean[]{false, true, false}) {
                                stock.setLineWrap(wrap); optimized.setLineWrap(wrap);
                                stock.setWrapStyleWord(wrap); optimized.setWrapStyleWord(wrap);
                                for (int width : new int[]{120, 800}) {
                                    stock.setSize(width, 500); optimized.setSize(width, 500);
                                    equalGeometry(stock, optimized);
                                    control.insertString(0, "x", null); document.insertString(0, "x", null); equalGeometry(stock, optimized);
                                    control.remove(0, 1); document.remove(0, 1); equalGeometry(stock, optimized);
                                }
                            }
                        }
                    }
                    stock.setForeground(Color.BLUE); optimized.setForeground(Color.BLUE);
                    stock.select(0, Math.min(5, document.getLength())); optimized.select(0, Math.min(5, document.getLength()));
                    assertArrayEquals(pixels(stock), pixels(optimized), "paint changed for " + text);
                    // Document reattachment must not keep a previous width witness or document.
                    PlainDocument next = new PlainDocument(); next.insertString(0, "next document", null);
                    stock.setDocument(next); optimized.setDocument(next); equalGeometry(stock, optimized);
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    private static int[] pixels(JTextArea area) {
        BufferedImage image = new BufferedImage(area.getWidth(), area.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { area.paint(graphics); } finally { graphics.dispose(); }
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    @Test void exhaustingWidthCandidatesAndBulkUndoRetainTheExactMaximum() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                PlainDocument document = new PlainDocument();
                StringBuilder text = new StringBuilder();
                for (int n = 160; n > 0; n--) text.append("W".repeat(n)).append('\n');
                document.insertString(0, text.toString(), null);
                UndoManager history = new UndoManager(); document.addUndoableEditListener(history);
                JTextArea stock = area(document, false), optimized = area(document, true);
                equalGeometry(stock, optimized);
                for (int n = 0; n < 100; n++) {
                    int end = document.getDefaultRootElement().getElement(0).getEndOffset();
                    document.remove(0, end);
                    equalGeometry(stock, optimized);
                }
                for (int n = 0; n < 100; n++) { history.undo(); equalGeometry(stock, optimized); }
                assertEquals(text.toString(), document.getText(0, document.getLength()));
                document.remove(0, document.getLength() / 2);
                // Stock PlainView can keep a removed widest Element after a bulk removal if it
                // first promotes an added line. A fresh stock view gives the actual text extent.
                equalGeometry(area(document, false), optimized);
                history.undo(); equalGeometry(stock, optimized);
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void tabsAndUnicodeAfterCachedEditsHandBackToStockMetrics() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (String inserted : new String[]{"\t", "café", "😀", "עברית"}) {
                    PlainDocument document = new PlainDocument();
                    document.insertString(0, "W".repeat(200) + "\n" + "M".repeat(100) + "\nlast", null);
                    JTextArea stock = area(document, false), optimized = area(document, true);
                    equalGeometry(stock, optimized);
                    // Remove the original widest Element before requiring the stock policy again.
                    document.remove(0, 201); equalGeometry(stock, optimized);
                    document.insertString(0, inserted, null); equalGeometry(stock, optimized);
                    document.remove(0, inserted.length()); equalGeometry(stock, optimized);
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void extremeLineExtentsUseStockGeometry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (int length : new int[]{900000, 1000001}) {
                    PlainDocument document = new PlainDocument();
                    document.insertString(0, "W".repeat(length), null);
                    JTextArea stock = area(document, false), optimized = area(document, true);
                    assertEquals(stock.getPreferredSize(), optimized.getPreferredSize());
                    document.insertString(0, "x", null);
                    assertEquals(stock.getPreferredSize(), optimized.getPreferredSize());
                    document.remove(0, 1);
                    assertEquals(stock.getPreferredSize(), optimized.getPreferredSize());
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }
}
