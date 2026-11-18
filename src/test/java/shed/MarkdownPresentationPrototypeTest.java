package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputMethodEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.AttributedString;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownPresentationPrototypeTest {
    @TempDir Path temp;
    static final String SAMPLE = "# Heading\n\n## Smaller\n\nBody **bold** and *italic* and `code` [link](note.md).\n\n```java\nint n = 42;\n```\n\n![image](picture.png)\n";
    static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var future = new FutureTask<>(action); SwingUtilities.invokeAndWait(future); return future.get();
    }
    private static final class Fixture implements AutoCloseable {
        final FileBuffer buffer;
        final JTextArea area = new JTextArea();
        final DocumentWorkspace.Id id = new DocumentWorkspace.Id(1);
        final DocumentListener updates;
        final MarkdownPresentationPrototype prototype;
        int events;
        Fixture(FileBuffer buffer, boolean wrap) { this(buffer, wrap, true); }
        Fixture(FileBuffer buffer, boolean wrap, boolean ownsUpdates) {
            this.buffer = buffer;
            updates = new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { change(e); }
                public void removeUpdate(DocumentEvent e) { change(e); }
                public void changedUpdate(DocumentEvent e) { change(e); }
                private void change(DocumentEvent e) { events++; buffer.applyDocumentChange(e); buffer.setModified(true); }
            };
            if (ownsUpdates) buffer.getDocument().addDocumentListener(updates);
            area.setDocument(buffer.getDocument()); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            area.setForeground(Color.BLACK); area.setBackground(Color.WHITE); area.setSelectionColor(new Color(100, 150, 210)); area.setSelectedTextColor(Color.WHITE);
            area.setLineWrap(wrap); area.setWrapStyleWord(true); area.setSize(600, 700);
            prototype = new MarkdownPresentationPrototype(area, () -> new MarkdownPresentationPrototype.Binding(source(), buffer.getDocument()));
        }
        MarkdownSemanticSnapshot.Source source() { return MarkdownSemanticSnapshot.Source.capture(id, buffer); }
        public void close() { prototype.close(); buffer.getDocument().removeDocumentListener(updates); }
    }
    private Fixture fixture(String text, boolean wrap) throws Exception {
        return edt(() -> { var buffer = new FileBuffer((String) null); buffer.setContent(text); buffer.setModified(false); return new Fixture(buffer, wrap); });
    }
    private MarkdownPresentationFrame prepare(Fixture fixture) throws Exception {
        var ticket = edt(fixture.prototype::capture);
        var semantic = MarkdownHeadingAnalysis.analyze(ticket.source(), () -> false).semantic();
        return MarkdownPresentationFrame.prepare(semantic, ticket.settings(), ticket.generation(), () -> false);
    }
    private void install(Fixture fixture) throws Exception {
        var frame = prepare(fixture); assertEquals(MarkdownPresentationFrame.State.READY, frame.state, frame.detail);
        assertTrue(edt(() -> fixture.prototype.install(frame)));
    }
    private BufferedImage paint(JTextArea area) {
        var image = new BufferedImage(600, 700, BufferedImage.TYPE_INT_ARGB); var graphics = image.createGraphics();
        try { graphics.setClip(0, 0, 600, 700); area.paint(graphics); } finally { graphics.dispose(); } return image;
    }
    @Test void candidateAHasUniformGeometryAndStyledDocumentCannotShareTheSource() throws Exception {
        edt(() -> {
            var buffer = new FileBuffer((String) null); buffer.setContent(SAMPLE); var raw = new JTextArea(); raw.setDocument(buffer.getDocument()); raw.setSize(600, 700);
            assertEquals(raw.modelToView2D(0).getHeight(), raw.modelToView2D(SAMPLE.indexOf("Body")).getHeight());
            assertThrows(IllegalArgumentException.class, () -> new JTextPane().setDocument(buffer.getDocument()));
            var copy = new DefaultStyledDocument(); copy.insertString(0, SAMPLE, null); var edits = new AtomicInteger(); copy.addUndoableEditListener(e -> edits.incrementAndGet());
            var attributes = new SimpleAttributeSet(); StyleConstants.setFontSize(attributes, 28); copy.setCharacterAttributes(0, 9, attributes, false);
            assertEquals(1, edits.get()); assertEquals(SAMPLE, buffer.getContent()); assertNotSame(copy, buffer.getDocument()); return null;
        });
    }
    @Test void typographyStylesLinksFencesAndPlaceholderAreSourcePreserving() throws Exception {
        var fixture = fixture(SAMPLE, false); install(fixture);
        try {
            edt(() -> {
                var original = fixture.buffer.textSnapshot(); long revision = fixture.buffer.documentRevision();
                var undo = (BoundedUndoManager) fixture.buffer.getUndoManager(); int count = undo.retainedEditCount();
                Rectangle2D h1 = fixture.area.modelToView2D(0), h2 = fixture.area.modelToView2D(SAMPLE.indexOf("##")), body = fixture.area.modelToView2D(SAMPLE.indexOf("Body"));
                assertTrue(h1.getHeight() > h2.getHeight()); assertTrue(h2.getHeight() > body.getHeight());
                paint(fixture.area); fixture.area.select(0, fixture.area.getDocument().getLength());
                assertEquals(SAMPLE, fixture.area.getSelectedText()); assertEquals(0, fixture.events); assertSame(original, fixture.buffer.textSnapshot());
                assertEquals(revision, fixture.buffer.documentRevision()); assertEquals(count, undo.retainedEditCount()); assertFalse(fixture.buffer.isModified());
                assertTrue(fixture.prototype.metrics().cachedRows() <= MarkdownPresentationPrototype.CACHE_ROWS); return null;
            });
            var frame = prepare(fixture);
            assertTrue(frame.styles.stream().anyMatch(s -> s.kind() == MarkdownPresentationFrame.Kind.STRONG));
            assertTrue(frame.styles.stream().anyMatch(s -> s.kind() == MarkdownPresentationFrame.Kind.EMPHASIS));
            assertTrue(frame.styles.stream().anyMatch(s -> s.kind() == MarkdownPresentationFrame.Kind.CODE));
            assertTrue(frame.styles.stream().anyMatch(s -> s.kind() == MarkdownPresentationFrame.Kind.LINK));
            assertEquals(1, frame.rows.stream().filter(row -> row.object() != null).count());
        } finally { edt(() -> { fixture.close(); return null; }); }
    }
    @Test void sourceAndMouseOffsetsRoundTripAcrossEveryAsciiStyleAndWrappedRows() throws Exception {
        for (boolean wrap : List.of(false, true)) {
            var fixture = fixture(SAMPLE + ("long **text** and plain ".repeat(40)) + "\n", wrap); install(fixture);
            try {
                edt(() -> {
                    for (int offset = 0; offset <= SAMPLE.length(); offset++) {
                        Rectangle2D caret = fixture.area.modelToView2D(offset);
                        int hit = fixture.area.viewToModel2D(new java.awt.geom.Point2D.Double(caret.getX(), caret.getCenterY()));
                        assertEquals(offset, hit, "wrap=" + wrap + " offset=" + offset);
                    }
                    var start = fixture.area.modelToView2D(SAMPLE.length()); var end = fixture.area.modelToView2D(fixture.area.getDocument().getLength() - 1);
                    if (wrap) assertTrue(end.getY() > start.getY()); else assertEquals(start.getY(), end.getY()); return null;
                });
            } finally { edt(() -> { fixture.close(); return null; }); }
        }
    }
    @Test void caretAwareMarkersChangeInkWithoutChangingAdvanceOrHistory() throws Exception {
        var fixture = fixture(SAMPLE, false); install(fixture);
        try {
            edt(() -> {
                fixture.area.setCaretPosition(SAMPLE.indexOf("Body")); var before = fixture.area.modelToView2D(2); var image = paint(fixture.area);
                fixture.area.setCaretPosition(2); var after = fixture.area.modelToView2D(2); var active = paint(fixture.area);
                assertEquals(before, after); assertEquals(0, fixture.events); assertFalse(fixture.buffer.getUndoManager().canUndo());
                assertFalse(Arrays.equals(image.getRGB(0, 0, 600, 60, null, 0, 600), active.getRGB(0, 0, 600, 60, null, 0, 600))); return null;
            });
        } finally { edt(() -> { fixture.close(); return null; }); }
    }
    @Test void nativeTypingUndoRedoRevealSourceWithoutParsingOrApplyingOldDecorations() throws Exception {
        var fixture = fixture(SAMPLE, true); install(fixture); var old = prepare(fixture);
        try {
            edt(() -> {
                fixture.area.setCaretPosition(SAMPLE.indexOf("Body"));
                fixture.area.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new ActionEvent(fixture.area, ActionEvent.ACTION_PERFORMED, "x"));
                assertFalse(fixture.prototype.styled()); assertEquals("xBody", fixture.buffer.getContent().substring(SAMPLE.indexOf("Body"), SAMPLE.indexOf("Body") + 5));
                assertFalse(fixture.prototype.install(old)); fixture.buffer.getUndoManager().undo(); assertEquals(SAMPLE, fixture.buffer.getContent());
                assertFalse(fixture.prototype.install(old)); fixture.buffer.getUndoManager().redo(); assertTrue(fixture.buffer.getContent().contains("xBody"));
                assertEquals(3, fixture.events); return null;
            });
            install(fixture); assertTrue(edt(fixture.prototype::styled));
        } finally { edt(() -> { fixture.close(); return null; }); }
    }
    @Test void unicodeClustersUseValidUtf16MouseAndKeyboardBoundaries() throws Exception {
        String text = "# Hé😀\n\nLatin e\u0301 emoji 👩‍💻 中文\n"; var fixture = fixture(text, true); install(fixture);
        try {
            edt(() -> {
                for (int offset : List.of(0, 2, 4, 6, text.indexOf("中文"), text.length())) {
                    var caret = fixture.area.modelToView2D(offset); int hit = fixture.area.viewToModel2D(new java.awt.geom.Point2D.Double(caret.getX(), caret.getCenterY()));
                    new MarkdownSourceRange(hit, hit).validate(fixture.buffer.textSnapshot());
                }
                paint(fixture.area); fixture.area.setCaretPosition(text.indexOf("😀")); fixture.area.getActionMap().get(DefaultEditorKit.forwardAction).actionPerformed(new ActionEvent(fixture.area, 0, ""));
                assertEquals(text.indexOf("😀") + 2, fixture.area.getCaretPosition()); assertEquals(0, fixture.events); return null;
            });
        } finally { edt(() -> { fixture.close(); return null; }); }
    }
    @Test void tabsBidiLongLinesAndOverCapDocumentsUseStockSource() throws Exception {
        for (String text : List.of("# heading\n\tvalue", "# heading\nשלום and English", "# heading\n" + "x".repeat(4097), "x".repeat(1024 * 1024 + 1))) {
            var fixture = fixture(text, false); var frame = prepare(fixture);
            try {
                assertEquals(MarkdownPresentationFrame.State.UNSUPPORTED, frame.state);
                edt(() -> { assertFalse(fixture.prototype.install(frame)); assertFalse(fixture.prototype.styled()); assertEquals(text, fixture.area.getText());
                    assertNotNull(fixture.area.modelToView2D(Math.min(3, text.length()))); assertEquals(0, fixture.events); return null; });
            } finally { edt(() -> { fixture.close(); return null; }); }
        }
    }
    @Test void fontThemeResizeAndCompositionRejectPendingPresentation() throws Exception {
        var fixture = fixture(SAMPLE, true);
        try {
            for (String change : List.of("font", "theme", "width", "composition")) {
                var pending = prepare(fixture);
                edt(() -> {
                    switch (change) {
                        case "font" -> fixture.area.setFont(fixture.area.getFont().deriveFont(18f));
                        case "theme" -> fixture.area.setForeground(Color.BLUE);
                        case "width" -> fixture.area.setSize(420, 700);
                        case "composition" -> {
                            var event = new InputMethodEvent(fixture.area, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, new AttributedString("あ").getIterator(), 0, null, null);
                            for (var listener : fixture.area.getInputMethodListeners()) if (listener.getClass().getEnclosingClass() == MarkdownPresentationPrototype.class) listener.inputMethodTextChanged(event);
                        }
                    }
                    assertFalse(fixture.prototype.install(pending)); return null;
                });
            }
        } finally { edt(() -> { fixture.close(); return null; }); }
    }
    @Test void aForeignDocumentAndDisposedPaneCannotAcceptAFrame() throws Exception {
        var fixture = fixture(SAMPLE, false); var frame = prepare(fixture);
        edt(() -> {
            fixture.area.setDocument(new PlainDocument()); assertFalse(fixture.prototype.install(frame)); fixture.close(); assertFalse(fixture.prototype.install(frame)); return null;
        });
    }
    @Test void sharedDocumentHasIndependentCaretSelectionGeometryAndDisposal() throws Exception {
        var first = fixture(SAMPLE, false);
        var second = edt(() -> new Fixture(first.buffer, true, false)); install(first); install(second);
        try {
            edt(() -> {
                first.area.select(0, 3); second.area.select(25, 30); assertEquals(3, first.area.getCaretPosition()); assertEquals(30, second.area.getCaretPosition());
                assertSame(first.area.getDocument(), second.area.getDocument()); assertTrue(first.prototype.styled()); assertTrue(second.prototype.styled());
                first.prototype.close(); assertTrue(second.prototype.styled()); paint(second.area); assertEquals(0, first.events); return null;
            });
        } finally { edt(() -> { first.close(); second.close(); return null; }); }
    }
    @Test void clipboardUsesRawSourceAndPasteUsesTheSharedUndoHistory() throws Exception {
        var fixture = fixture(SAMPLE, false); install(fixture);
        try {
            edt(() -> {
                fixture.area.select(0, SAMPLE.length()); var clipboard = new java.awt.datatransfer.Clipboard("prototype-test");
                fixture.area.getTransferHandler().exportToClipboard(fixture.area, clipboard, TransferHandler.COPY);
                assertEquals(SAMPLE, clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
                fixture.area.select(0, 0); assertTrue(fixture.area.getTransferHandler().importData(fixture.area, new java.awt.datatransfer.StringSelection("new ")));
                assertEquals("new " + SAMPLE, fixture.buffer.getContent());
                fixture.buffer.getUndoManager().undo(); assertEquals(SAMPLE, fixture.buffer.getContent()); assertEquals(2, fixture.events); return null;
            });
        } finally { edt(() -> { fixture.close(); return null; }); }
    }
    @Test void saveReopenPreservesBomCrLfEofAndFormattingCreatesNoEvents() throws Exception {
        Path file = temp.resolve("document.md"); byte[] body = SAMPLE.replace("\n", "\r\n").stripTrailing().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3]; bytes[0] = (byte) 0xef; bytes[1] = (byte) 0xbb; bytes[2] = (byte) 0xbf; System.arraycopy(body, 0, bytes, 3, body.length); Files.write(file, bytes);
        var fixture = edt(() -> new Fixture(new FileBuffer(file.toFile()), false)); install(fixture);
        edt(() -> { paint(fixture.area); fixture.close(); fixture.buffer.save(); return null; });
        assertArrayEquals(bytes, Files.readAllBytes(file)); var reopened = new FileBuffer(file.toFile()); assertEquals(SAMPLE.stripTrailing(), reopened.getContent()); assertFalse(reopened.isModified());
    }
    @Test void formattingRestoresTheExistingUiCaretAndListenerCounts() throws Exception {
        var fixture = fixture(SAMPLE, false); install(fixture);
        edt(() -> {
            var document = fixture.buffer.getDocument(); int listeners = document.getDocumentListeners().length;
            fixture.prototype.close(); int after = document.getDocumentListeners().length;
            assertTrue(after <= listeners); assertEquals(0, fixture.prototype.metrics().cachedRows()); assertEquals(0, fixture.events);
            assertNotNull(fixture.area.getActionMap().get(DefaultEditorKit.forwardAction)); fixture.close(); return null;
        });
    }
    @Test void coordinateOnlyConcealmentHasExplicitAffinityAndNeverChangesSource() {
        var source = VersionedTextSnapshot.of("## 😀 **bold**"); var ranges = List.of(new MarkdownSourceRange(0, 2), new MarkdownSourceRange(6, 8), new MarkdownSourceRange(12, 14));
        var projection = new MarkdownMarkerProjection(source, ranges);
        assertEquals(8, projection.visibleLength()); assertEquals(0, projection.visible(1));
        assertEquals(0, projection.source(0, Position.Bias.Backward)); assertEquals(2, projection.source(0, Position.Bias.Forward));
        for (int offset : List.of(2, 3, 5, 8, 9, 10, 11, 14)) assertEquals(offset, projection.source(projection.visible(offset), Position.Bias.Forward));
        assertEquals("## 😀 **bold**", source.text());
        assertThrows(IllegalArgumentException.class, () -> new MarkdownMarkerProjection(source, List.of(new MarkdownSourceRange(4, 5))));
    }
    @Test void preparationIsWorkerOnlyAndCancellationPublishesNoGeometry() throws Exception {
        var fixture = fixture(SAMPLE, false); var ticket = edt(fixture.prototype::capture);
        var semantic = new MarkdownSemanticParser().parse(ticket.source());
        var cancelled = MarkdownPresentationFrame.prepare(semantic, ticket.settings(), ticket.generation(), () -> true);
        assertEquals(MarkdownPresentationFrame.State.CANCELLED, cancelled.state); assertTrue(cancelled.rows.isEmpty());
        edt(() -> { assertThrows(IllegalStateException.class, () -> MarkdownPresentationFrame.prepare(semantic, ticket.settings(), ticket.generation(), () -> false)); fixture.close(); return null; });
    }
    @Test void nativeVerticalMotionAndSelectionCrossStyledWrappedRows() throws Exception {
        var fixture = fixture(SAMPLE + "text ".repeat(200), true); install(fixture);
        try {
            edt(() -> {
                paint(fixture.area); fixture.area.setCaretPosition(2); var before = fixture.area.modelToView2D(2);
                fixture.area.getActionMap().get(DefaultEditorKit.downAction).actionPerformed(new ActionEvent(fixture.area, 0, ""));
                assertTrue(fixture.area.modelToView2D(fixture.area.getCaretPosition()).getY() > before.getY());
                fixture.area.getActionMap().get(DefaultEditorKit.selectionDownAction).actionPerformed(new ActionEvent(fixture.area, 0, ""));
                assertNotEquals(fixture.area.getCaret().getDot(), fixture.area.getCaret().getMark());
                paint(fixture.area); assertEquals(0, fixture.events); return null;
            });
        } finally { edt(() -> { fixture.close(); return null; }); }
    }
    @Test void outOfOrderPreparationCannotReplaceNewerCurrentGeometry() throws Exception {
        var fixture = fixture(SAMPLE, false); var ticket = edt(fixture.prototype::capture);
        var semantic = new MarkdownSemanticParser().parse(ticket.source());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> MarkdownPresentationFrame.prepare(semantic, ticket.settings(), ticket.generation(), () -> {
                entered.countDown(); try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new RuntimeException(error); } return false;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            edt(() -> { fixture.area.insert("x", SAMPLE.indexOf("Body")); return null; });
            install(fixture); release.countDown(); var obsolete = old.get(10, TimeUnit.SECONDS);
            edt(() -> { assertFalse(fixture.prototype.install(obsolete)); assertTrue(fixture.prototype.styled()); return null; });
        } finally { release.countDown(); executor.shutdownNow(); edt(() -> { fixture.close(); return null; }); }
    }
    @Test void scrollingRetainsOnlyTheBoundedCurrentRowCache() throws Exception {
        var fixture = fixture(("## heading\nBody **bold** *italic* `code`.\n\n").repeat(1000), false); install(fixture);
        try {
            edt(() -> {
                int height = fixture.area.getPreferredSize().height; fixture.area.setSize(600, height);
                for (int step = 0; step < 25; step++) {
                    var image = new BufferedImage(600, 700, BufferedImage.TYPE_INT_ARGB); var graphics = image.createGraphics();
                    try {
                        int top = step * Math.max(1, (height - 700) / 25); graphics.translate(0, -top); graphics.setClip(0, top, 600, 700); fixture.area.paint(graphics);
                    } finally { graphics.dispose(); }
                    assertTrue(fixture.prototype.metrics().cachedRows() <= MarkdownPresentationPrototype.CACHE_ROWS);
                    assertTrue(fixture.prototype.metrics().cachedUnits() <= MarkdownPresentationPrototype.CACHE_UNITS);
                }
                assertTrue(fixture.prototype.metrics().shaped() > 128); assertEquals(0, fixture.events); return null;
            });
        } finally { edt(() -> { fixture.close(); return null; }); }
    }
    @Test void placeholderHitTestingUsesItsVerifiedSourceAnchor() throws Exception {
        var fixture = fixture(SAMPLE, false); var frame = prepare(fixture); assertTrue(edt(() -> fixture.prototype.install(frame)));
        try {
            edt(() -> {
                var object = frame.rows.stream().filter(row -> row.object() != null).findFirst().orElseThrow();
                int offset = fixture.area.viewToModel2D(new java.awt.geom.Point2D.Float(fixture.area.getInsets().left + 20, fixture.area.getInsets().top + object.y() + 20));
                assertEquals(SAMPLE.indexOf("![image]"), offset); assertEquals(0, fixture.events); return null;
            });
        } finally { edt(() -> { fixture.close(); return null; }); }
    }
}
