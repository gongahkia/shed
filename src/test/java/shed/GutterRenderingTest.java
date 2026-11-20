package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class GutterRenderingTest {
    static final String MIXED = "# Source alpha\nUnicode: e\u0301, क्ष, 👩\u200d💻, 中文\n".repeat(10);

    @Test void impliedEndMappingPaintsExactlyLikeDocumentEnd() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MappedArea area = new MappedArea(MIXED);
            LineNumberPanel gutter = gutter(area);
            area.bottom = area.getDocument().getLength();
            BufferedImage expected = paint(gutter);
            area.bottom++;
            BufferedImage[] actual = new BufferedImage[1];
            String errors = withoutPaintErrors(() -> actual[0] = paint(gutter));
            assertEquals("", errors);
            assertPixelsEqual(expected, actual[0]);
        });
    }

    @Test void unavailableOrInvalidMappingsPaintOnlyBackground() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MappedArea area = new MappedArea("one\ntwo\n");
            LineNumberPanel gutter = gutter(area);
            for (int[] mapping : new int[][]{{-1, 0}, {0, -1}, {0, 999}, {999, 0}, {5, 0}}) {
                area.top = mapping[0]; area.bottom = mapping[1];
                assertEquals("", withoutPaintErrors(() -> assertBackgroundOnly(gutter, paint(gutter))));
            }
        });
    }

    @Test void temporarilyUnavailableLineBoundsRecoverWithoutNullDereference() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MappedArea area = new MappedArea("one\ntwo\n") {
                @Override public java.awt.geom.Rectangle2D modelToView2D(int offset) { return null; }
            };
            LineNumberPanel gutter = gutter(area);
            assertEquals("", withoutPaintErrors(() -> assertBackgroundOnly(gutter, paint(gutter))));
        });
    }

    @Test void impliedEndRetainsAllMarkerAndNumberModesAtBothDisplayScales() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String text : List.of("", "no newline", "one\ntwo\n", MIXED)) {
                MappedArea area = new MappedArea(text);
                LineNumberPanel gutter = gutter(area);
                gutter.updateDiffMarkers(new LineNumberPanel.DiffMarkers(Set.of(0), Set.of(1), Set.of(2)));
                gutter.updateGitDiffMarkers(Set.of(1), Set.of(2), Set.of(0));
                gutter.updateCoverageMarkers(Map.of(0, 0, 1, 2));
                gutter.updateBreakpointMarkers(Map.of(0, new BreakpointStore.Marker(BreakpointStore.State.VERIFIED, true),
                    1, new BreakpointStore.Marker(BreakpointStore.State.REJECTED, false)));
                gutter.updateDiagnosticMarkers(Map.of(0, 1, 1, 2, 2, 3));
                for (LineNumberMode mode : LineNumberMode.values()) for (int scale : new int[]{1, 2}) {
                    gutter.setMode(mode);
                    area.bottom = text.length();
                    BufferedImage expected = paint(gutter, scale);
                    area.bottom = text.length() + 1;
                    assertEquals("", withoutPaintErrors(() -> assertPixelsEqual(expected, paint(gutter, scale))));
                }
            }
        });
    }

    @Test void breakpointHitTestingUsesTheSameValidatedMapping() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MappedArea area = new MappedArea("one\ntwo\n");
            LineNumberPanel gutter = gutter(area);
            AtomicInteger selected = new AtomicInteger(-1);
            gutter.setBreakpointToggleListener(selected::set);
            area.bottom++;
            gutter.dispatchEvent(new java.awt.event.MouseEvent(gutter, java.awt.event.MouseEvent.MOUSE_PRESSED,
                1L, 0, 5, 60, 1, false));
            assertEquals(area.getLineCount() - 1, selected.get());
            for (int invalid : new int[]{-1, 999}) {
                selected.set(-1); area.bottom = invalid;
                gutter.dispatchEvent(new java.awt.event.MouseEvent(gutter, java.awt.event.MouseEvent.MOUSE_PRESSED,
                    1L, 0, 5, 60, 1, false));
                assertEquals(-1, selected.get());
            }
        });
    }

    @Test void eofLineInsertionRemovalAndUndoRestoreTheSameGutterPixels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FileBuffer buffer = FileBuffer.createScratch("[eof]", "one\ntwo\n");
            JTextArea area = new JTextArea(); area.setDocument(buffer.getDocument());
            area.setSize(219, 441); area.setCaretPosition(area.getDocument().getLength());
            LineNumberPanel gutter = gutter(area);
            var history = buffer.getUndoManager(); history.discardAllEdits();
            BufferedImage original = paint(gutter);
            area.append("last\n");
            assertEquals(4, area.getLineCount());
            long revision = buffer.documentRevision();
            BufferedImage appended = paint(gutter);
            assertEquals(revision, buffer.documentRevision());
            history.undo(); assertEquals("one\ntwo\n", area.getText());
            assertPixelsEqual(original, paint(gutter));
            history.redo(); assertEquals("one\ntwo\nlast\n", area.getText());
            assertPixelsEqual(appended, paint(gutter));
            int end = area.getDocument().getLength(); area.replaceRange("", end - 1, end);
            assertEquals(3, area.getLineCount());
            assertEquals("", withoutPaintErrors(() -> paint(gutter)));
            history.undo(); assertPixelsEqual(appended, paint(gutter));
            history.undo(); assertEquals("one\ntwo\n", area.getText());
            assertPixelsEqual(original, paint(gutter));
        });
    }

    @Test void editsUndoBufferSwitchAndRetiredSplitPreserveSourceOwnership() throws Exception {
        EditorPane[] retired = new EditorPane[1];
        FileBuffer[] owner = new FileBuffer[1];
        SwingUtilities.invokeAndWait(() -> {
            FileBuffer buffer = FileBuffer.createScratch("[gutter]", MIXED);
            owner[0] = buffer;
            JTextArea left = new JTextArea(), right = new JTextArea();
            left.setDocument(buffer.getDocument()); right.setDocument(buffer.getDocument());
            left.setSize(219, 441); right.setSize(219, 441);
            var lg = gutter(left); var rg = gutter(right);
            EditorPane pane = new EditorPane(right, rg, new JScrollPane(right), new SearchManager(right));
            pane.setBuffer(buffer); retired[0] = pane;
            var history = buffer.getUndoManager(); history.discardAllEdits();
            left.select(4, 12);
            long revision = buffer.documentRevision();
            var snapshot = buffer.textSnapshot(); boolean dirty = buffer.isModified();
            int listeners = buffer.getDocument().getDocumentListeners().length;
            for (int i = 0; i < 20; i++) {
                left.setSize(i % 2 == 0 ? 137 : 547, 437);
                assertEquals("", withoutPaintErrors(() -> { paint(lg); paint(rg); }));
            }
            assertSame(snapshot, buffer.textSnapshot()); assertEquals(revision, buffer.documentRevision());
            assertEquals(dirty, buffer.isModified()); assertFalse(history.canUndo());
            assertEquals(4, left.getSelectionStart()); assertEquals(12, left.getSelectionEnd());
            assertEquals(listeners, buffer.getDocument().getDocumentListeners().length);
            right.insert("new\n", 0); assertEquals("", withoutPaintErrors(() -> { paint(lg); paint(rg); }));
            pane.disposeSourceView(); pane.disposeSourceView();
            assertNotSame(buffer.getDocument(), right.getDocument());
            assertSame(buffer.getDocument(), left.getDocument());
            history.undo(); assertEquals(MIXED, left.getText());
            history.redo(); assertEquals("new\n" + MIXED, left.getText());
            history.undo(); assertEquals(MIXED, buffer.getContent());
            FileBuffer other = FileBuffer.createScratch("[other]", "different\n");
            left.setDocument(other.getDocument()); assertEquals("", withoutPaintErrors(() -> paint(lg)));
            left.setDocument(buffer.getDocument()); assertEquals(MIXED, left.getText());
            left.setDocument(new javax.swing.text.PlainDocument());
        });
        var latePaint = new java.util.concurrent.FutureTask<Void>(() -> {
            assertEquals("", withoutPaintErrors(() -> paint(retired[0].getLineNumberPanel()))); return null;
        });
        SwingUtilities.invokeLater(latePaint);
        latePaint.get(3, java.util.concurrent.TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(0, retired[0].getTextArea().getDocument().getLength());
            assertEquals(MIXED, owner[0].getContent());
        });
    }

    @Test void actualGeometrySurvivesScrollWrapFontAndPresentationInsets() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String text : List.of("", "end", "row\n".repeat(10001), "long\t" + "字e\u0301😀".repeat(500), MIXED)) {
                JTextArea area = new JTextArea(text);
                LineNumberPanel gutter = gutter(area);
                JScrollPane scroll = new JScrollPane(area);
                scroll.setRowHeaderView(gutter);
                for (int font : new int[]{12, 24}) for (boolean wrap : new boolean[]{false, true}) {
                    area.setFont(BundledEditorFont.atSize(font));
                    area.setLineWrap(wrap); area.setWrapStyleWord(true);
                    for (int width : new int[]{137, 800}) {
                        scroll.setSize(width, 300); scroll.doLayout();
                        area.setSize(Math.max(width, area.getPreferredSize().width), Math.max(300, area.getPreferredSize().height));
                        int padding = FocusModeController.centeredPadding(width, gutter.getWidth(), 80,
                            area.getFontMetrics(area.getFont()).charWidth(' '));
                        area.setBorder(BorderFactory.createEmptyBorder(0, padding, 0, padding));
                        for (int y : new int[]{0, Math.max(0, area.getHeight() - 300)}) {
                            scroll.getViewport().setViewPosition(new Point(0, y));
                            gutter.setSize(70, area.getHeight());
                            // Paint only the viewport clip, without allocating a document-height bitmap.
                            BufferedImage image = new BufferedImage(70, 300, BufferedImage.TYPE_INT_ARGB);
                            assertEquals("", withoutPaintErrors(() -> {
                                Graphics2D g = image.createGraphics();
                                try { g.translate(0, -y); g.setClip(0, y, 70, 300); gutter.paint(g); }
                                finally { g.dispose(); }
                            }));
                        }
                    }
                }
            }
        });
    }

    @Test void paintingUsesBoundedVisibleGeometryAndPreservesLegacyWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            class MeasuredDocument extends javax.swing.text.PlainDocument {
                long charactersRead;
                @Override public void getText(int offset, int length, javax.swing.text.Segment segment)
                        throws javax.swing.text.BadLocationException {
                    charactersRead += length; super.getText(offset, length, segment);
                }
            }
            try {
                MeasuredDocument document = new MeasuredDocument();
                document.insertString(0, "source row\n".repeat(100000), null);
                JTextArea area = new JTextArea();
                area.setUI(new javax.swing.plaf.basic.BasicTextAreaUI() {
                    @Override public javax.swing.text.View create(javax.swing.text.Element element) {
                        return SourcePlainView.adapt(super.create(element));
                    }
                });
                area.setDocument(document); area.setSize(800, 441); area.getPreferredSize();
                LineNumberPanel gutter = gutter(area);
                document.charactersRead = 0;
                assertEquals("", withoutPaintErrors(() -> paint(gutter)));
                assertTrue(document.charactersRead < 8192, "gutter read " + document.charactersRead + " characters");
                var font = gutter.getFont() != null ? gutter.getFont() : area.getFont();
                int expected = 24 + String.valueOf(area.getLineCount()).length() * gutter.getFontMetrics(font).charWidth('0');
                assertEquals(expected, gutter.getPreferredSize().width);
            } catch (javax.swing.text.BadLocationException error) { throw new AssertionError(error); }
        });
    }

    @Test void impliedEndMappingDoesNotMeasureOffscreenDocumentLines() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger geometryQueries = new AtomicInteger();
            MappedArea area = new MappedArea("source row\n".repeat(10000)) {
                @Override public java.awt.geom.Rectangle2D modelToView2D(int offset)
                        throws javax.swing.text.BadLocationException {
                    geometryQueries.incrementAndGet(); return super.modelToView2D(offset);
                }
            };
            area.bottom = area.getDocument().getDefaultRootElement().getEndOffset();
            LineNumberPanel gutter = gutter(area);
            assertEquals("", withoutPaintErrors(() -> paint(gutter)));
            assertTrue(geometryQueries.get() <= 64, "offscreen line geometry queries: " + geometryQueries.get());
        });
    }

    @Test void unlaidOutGeometrySkipsNumbersWithoutLoggingAndRecovers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea("body\n");
            LineNumberPanel gutter = gutter(area);
            assertEquals(-1, area.viewToModel2D(new Point(0, 0)));
            assertEquals("", withoutPaintErrors(() -> paint(gutter)));
            area.setSize(220, 441);
            try { assertNotNull(area.modelToView2D(0)); }
            catch (javax.swing.text.BadLocationException error) { throw new AssertionError(error); }
            assertEquals("", withoutPaintErrors(() -> paint(gutter)));
        });
    }

    @Test void actualMixedScriptViewMappingIsRecorded() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea(MIXED);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            area.setSize(219, 441);
            for (boolean wrap : new boolean[]{false, true}) {
                area.setLineWrap(wrap);
                try {
                    System.out.println("GUTTER_GEOMETRY wrap=" + wrap + " length=" + area.getDocument().getLength()
                        + " end=" + area.getDocument().getDefaultRootElement().getEndOffset()
                        + " map=" + area.viewToModel2D(new Point(0, 437))
                        + " view=" + area.getUI().getRootView(area).getView(0).getClass().getName());
                } catch (RuntimeException error) { throw error; }
            }
        });
    }

    static LineNumberPanel gutter(JTextArea area) {
        LineNumberPanel gutter = new LineNumberPanel(area);
        gutter.setSize(70, 441);
        return gutter;
    }

    static BufferedImage paint(LineNumberPanel gutter) {
        return paint(gutter, 1);
    }

    static BufferedImage paint(LineNumberPanel gutter, int scale) {
        BufferedImage image = new BufferedImage(gutter.getWidth() * scale, gutter.getHeight() * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { graphics.scale(scale, scale); gutter.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    static void assertBackgroundOnly(LineNumberPanel gutter, BufferedImage image) {
        int color = gutter.getBackground().getRGB();
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            assertEquals(color, image.getRGB(x, y), "unavailable geometry at " + x + "," + y);
    }

    static void assertPixelsEqual(BufferedImage expected, BufferedImage actual) {
        assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
            actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
    }

    static String withoutPaintErrors(Runnable action) {
        PrintStream previous = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            action.run();
        } finally { System.setErr(previous); }
        return errors.toString(StandardCharsets.UTF_8);
    }

    static class MappedArea extends JTextArea {
        int top = 0;
        int bottom;
        MappedArea(String text) {
            super(text);
            setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            setSize(219, 441);
            bottom = getDocument().getLength();
        }
        @Override public int viewToModel2D(Point2D point) { return point.getY() <= 0 ? top : bottom; }
    }
}
