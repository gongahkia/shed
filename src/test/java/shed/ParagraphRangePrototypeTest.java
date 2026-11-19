package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Random;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;

class ParagraphRangePrototypeTest {
    private static final String MIXED = "# Héading 😀\n中文 e\u0301 **क्ष** 👩‍💻 العربية עברית\n\tcode\t日本語 한국어\n";

    private static final class Reads extends PlainDocument {
        long characters, wholeReads;
        @Override public void getText(int offset, int length, Segment target) throws BadLocationException {
            super.getText(offset, length, target);
            characters += length;
            if (length >= getLength() && getLength() > 0) wholeReads++;
        }
        void reset() { characters = wholeReads = 0; }
    }

    private static JTextArea area(PlainDocument document, boolean prototype, boolean wrap, Font font) {
        var area = new JTextArea();
        area.putClientProperty(SourcePlainView.SOURCE_PROPERTY, Boolean.TRUE);
        area.setUI(prototype ? new ParagraphRangePrototype() : new WorkbenchStyle.TextAreaUI());
        area.setFont(font);
        area.setLineWrap(wrap);
        area.setWrapStyleWord(true);
        area.setTabSize(4);
        area.setDocument(document);
        area.setSize(310, 480);
        return area;
    }

    private static BufferedImage image(JTextArea area) {
        var image = new BufferedImage(310, 480, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { area.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    private static void equalPresentation(JTextArea stock, JTextArea candidate) throws Exception {
        assertEquals(stock.getPreferredSize(), candidate.getPreferredSize(), "scroll extent");
        int length = stock.getDocument().getLength();
        for (int i = 0; i <= length; i++) {
            Rectangle2D expected = stock.modelToView2D(i);
            assertEquals(expected, candidate.modelToView2D(i), "UTF-16 caret " + i);
        }
        for (int y = 0; y < 450; y += 13) for (int x = 0; x < 310; x += 17)
            assertEquals(stock.viewToModel2D(new Point(x, y)), candidate.viewToModel2D(new Point(x, y)), "hit " + x + "," + y);
        stock.select(3, Math.min(length, 35)); candidate.select(3, Math.min(length, 35));
        BufferedImage a = image(stock), b = image(candidate);
        try {
            assertArrayEquals(a.getRGB(0, 0, 310, 480, null, 0, 310), b.getRGB(0, 0, 310, 480, null, 0, 310), "selected pixels");
        } finally { a.flush(); b.flush(); }
    }

    @Test void geometryPixelsAndSourceMatchStockAcrossScriptsAndWrapping() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (boolean wrap : new boolean[]{false, true}) {
                    for (String text : new String[]{MIXED, "\ufeffक्ष\r\ne\u0301 👩‍💻\r\n中文\n", "English العربية\nעברית English\n", "क्ष😀e\u0301".repeat(90)}) {
                        var document = new PlainDocument(); document.insertString(0, text, null);
                        for (Font font : new Font[]{BundledEditorFont.atSize(16), new Font(Font.DIALOG, Font.PLAIN, 17)}) {
                            var stock = area(document, false, wrap, font);
                            var candidate = area(document, true, wrap, font);
                            equalPresentation(stock, candidate);
                            document.insertString(0, "x\n", null);
                            equalPresentation(stock, candidate);
                            document.remove(0, 2);
                            equalPresentation(stock, candidate);
                            assertEquals(text, document.getText(0, document.getLength()));
                            stock.setDocument(new PlainDocument()); candidate.setDocument(new PlainDocument());
                        }
                    }
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void seededEditsUndoAndDocumentSwitchKeepTheCanonicalSource() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var document = new PlainDocument(); document.insertString(0, MIXED.repeat(3), null);
                var undo = new UndoManager(); document.addUndoableEditListener(undo);
                var stock = area(document, false, true, BundledEditorFont.atSize(16));
                var candidate = area(document, true, true, BundledEditorFont.atSize(16));
                var random = new Random(7314);
                for (int i = 0; i < 12; i++) {
                    String before = document.getText(0, document.getLength());
                    int at = GraphemeBoundary.floor(before, random.nextInt(before.length() + 1));
                    document.insertString(at, i % 2 == 0 ? "क्ष\n" : "👩‍💻 ", null);
                    equalPresentation(stock, candidate);
                    undo.undo(); assertEquals(before, document.getText(0, document.getLength()));
                    undo.redo(); equalPresentation(stock, candidate);
                    undo.undo();
                }
                var next = new PlainDocument(); next.insertString(0, "ASCII\n😀 next\n", null);
                stock.setDocument(next); candidate.setDocument(next); equalPresentation(stock, candidate);
                assertSame(next, candidate.getDocument());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void rangeExperimentAvoidsRootReadsWithoutClaimingToAvoidInitialLayout() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                String text = "中文 e\u0301 क्ष 👩‍💻\n".repeat(500);
                var stockDocument = new Reads(); stockDocument.insertString(0, text, null);
                var candidateDocument = new Reads(); candidateDocument.insertString(0, text, null);
                var stock = area(stockDocument, false, false, BundledEditorFont.atSize(16));
                var candidate = area(candidateDocument, true, false, BundledEditorFont.atSize(16));
                stock.getPreferredSize(); candidate.getPreferredSize();
                stockDocument.reset(); candidateDocument.reset();
                stockDocument.insertString(0, "x", null); candidateDocument.insertString(0, "x", null);
                stock.getPreferredSize(); candidate.getPreferredSize();
                assertTrue(stockDocument.wholeReads >= 500, "stock counterexample disappeared");
                assertEquals(0, candidateDocument.wholeReads);
                assertTrue(candidateDocument.characters < text.length() * 50L);
                candidateDocument.reset(); candidateDocument.insertString(0, "x", null); candidate.getPreferredSize();
                assertEquals(0, candidateDocument.wholeReads);
                assertTrue(candidateDocument.characters < 5000, "warm local read cost " + candidateDocument.characters);
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void presentationAndSplitDetachmentDoNotEditSourceOrKeepDocumentListeners() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var buffer = FileBuffer.createPlainScratch("[range prototype]", MIXED);
                var document = buffer.getDocument();
                long revision = buffer.documentRevision();
                var snapshot = buffer.textSnapshot();
                int listeners = document.getDocumentListeners().length;
                int undoListeners = document.getUndoableEditListeners().length;
                int[] changes = {0};
                javax.swing.event.DocumentListener observer = new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent event) { changes[0]++; }
                    public void removeUpdate(javax.swing.event.DocumentEvent event) { changes[0]++; }
                    public void changedUpdate(javax.swing.event.DocumentEvent event) { changes[0]++; }
                };
                document.addDocumentListener(observer);
                var stock = area(document, false, true, BundledEditorFont.atSize(16));
                for (int i = 0; i < 5; i++) {
                    var candidate = area(document, true, true, BundledEditorFont.atSize(16));
                    candidate.setCaretPosition(8); stock.setCaretPosition(0);
                    assertEquals(8, candidate.getCaretPosition()); assertEquals(0, stock.getCaretPosition());
                    stock.setFont(BundledEditorFont.atSize(18)); candidate.setFont(BundledEditorFont.atSize(18));
                    stock.setTabSize(2); candidate.setTabSize(2);
                    equalPresentation(stock, candidate);
                    candidate.setDocument(new PlainDocument());
                    stock.setFont(BundledEditorFont.atSize(16)); stock.setTabSize(4);
                    assertSame(document, stock.getDocument());
                }
                stock.setDocument(new PlainDocument()); document.removeDocumentListener(observer);
                assertEquals(listeners, document.getDocumentListeners().length);
                assertEquals(undoListeners, document.getUndoableEditListeners().length);
                assertEquals(0, changes[0]); assertEquals(revision, buffer.documentRevision());
                assertSame(snapshot, buffer.textSnapshot()); assertFalse(buffer.isModified());
                assertFalse(buffer.getUndoManager().canUndo()); assertEquals(MIXED, buffer.getContent());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }
}
