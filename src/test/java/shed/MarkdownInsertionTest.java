package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.event.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownInsertionTest {
    @TempDir Path temp;
    private final MarkdownService service = new MarkdownService();
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private void assertLiteralLabel(String label, boolean image) {
        var insertion = service.insertion(label, image);
        Node paragraph = Parser.builder().build().parse(insertion.source()).getFirstChild();
        assertInstanceOf(Paragraph.class, paragraph);
        Node reference = paragraph.getFirstChild();
        assertTrue(image ? reference instanceof Image : reference instanceof Link);
        assertNull(reference.getNext());
        assertEquals(image ? "path" : "url", image ? ((Image) reference).getDestination() : ((Link) reference).getDestination());
        StringBuilder literal = new StringBuilder();
        reference.accept(new AbstractVisitor() {
            @Override public void visit(Text text) { literal.append(text.getLiteral()); }
            @Override public void visit(Emphasis emphasis) { fail("Selected markers must remain literal"); }
            @Override public void visit(Code code) { fail("Selected backticks must remain literal"); }
            @Override public void visit(HtmlInline html) { fail("Selected HTML must remain literal"); }
        });
        assertEquals(label, literal.toString());
        assertEquals(image ? "path" : "url", insertion.source().substring(insertion.destinationStart(), insertion.destinationEnd()));
    }

    @Test void simpleLabelsAndDestinationOffsetsAreExact() {
        assertEquals("[Important documentation](url)", service.insertion("Important documentation", false).source());
        assertEquals("![Architecture diagram](path)", service.insertion("Architecture diagram", true).source());
        for (boolean image : List.of(false, true)) assertLiteralLabel("ordinary text", image);
        assertEquals("[text](url)", service.insertLinkTemplate()); assertEquals("![alt](path)", service.insertImageTemplate());
    }

    @Test void punctuationEntitiesAndExistingMarkupRemainLiteralUnderTheExistingParser() {
        String punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
        for (String label : List.of(punctuation, "a]b[c\\d", "**bold** _italic_ `code` ~~strike~~", "<em> &amp; &#93;",
                "[existing](target) ![image](path)", "file_name.md#part", " leading and trailing  ", "see (x) and $y$"))
            for (boolean image : List.of(false, true)) assertLiteralLabel(label, image);
        assertEquals("[a\\]b\\[c\\\\d](url)", service.insertion("a]b[c\\d", false).source());
    }

    @Test void unicodeEmojiCombiningAndWhitespaceAreNotNormalized() {
        for (String label : List.of("Héading 中文 שלום", "👩‍💻 e\u0301 😀", "  café\u00a0label  ", "a\u200db"))
            for (boolean image : List.of(false, true)) assertLiteralLabel(label, image);
    }

    @Test void deterministicMixedLabelsRoundTripWithoutNestedConstructs() {
        var random = new Random(52); String[] units = {"a", " ", "[", "]", "\\", "*", "_", "`", "&", ";", "!", "<", ">", "😀", "e\u0301", "中文"};
        for (int i = 0; i < 100; i++) {
            StringBuilder label = new StringBuilder("label ");
            for (int j = 0; j < 25; j++) label.append(units[random.nextInt(units.length)]);
            assertLiteralLabel(label.toString(), i % 2 == 0);
        }
    }

    @Test void multilineControlsBlankMalformedUnicodeAndOversizeSelectionsReject() {
        for (String label : Arrays.asList(null, "", "   ", "\t", "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u007fb",
                "a\u2028b", "a\u2029b", "\ud800", "\udc00", "literal \\(x\\)", "a".repeat(MarkdownService.MAX_INSERTION_LABEL + 1)))
            for (boolean image : List.of(false, true)) assertThrows(IllegalArgumentException.class, () -> service.insertion(label, image));
        assertLiteralLabel("a".repeat(MarkdownService.MAX_INSERTION_LABEL), false);
    }

    @Test void literalParenthesesAndDollarsDoNotBecomePreviewMath() {
        var insertion = service.insertion("see (x) and $y$", false);
        try (var assets = new MarkdownPreviewAssets()) {
            String html = MarkdownPreviewRenderer.render(insertion.source(), "label", new java.awt.Font(java.awt.Font.DIALOG, 0, 13),
                java.awt.Color.WHITE, java.awt.Color.BLACK, assets, null);
            assertTrue(html.contains("href=\"url\">see (x) and $y$</a>")); assertFalse(html.contains("alt=\"math\""));
        }
    }

    private static final class Fixture {
        final FileBuffer buffer;
        final JTextArea area = new JTextArea();
        final AtomicBoolean writable = new AtomicBoolean(true), active = new AtomicBoolean(true);
        final BoundedUndoManager undo;
        final SwingEditorSurface surface;
        int events;
        Fixture(FileBuffer buffer) {
            this.buffer = buffer; area.setDocument(buffer.getDocument()); undo = (BoundedUndoManager) buffer.getUndoManager();
            surface = new SwingEditorSurface(area, new JScrollPane(area), () -> undo, writable::get);
            buffer.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { change(e); }
                public void removeUpdate(DocumentEvent e) { change(e); }
                public void changedUpdate(DocumentEvent e) { change(e); }
                private void change(DocumentEvent e) { events++; buffer.applyDocumentChange(e); buffer.setModified(true); }
            });
        }
        MarkdownEdit edit() throws Exception { return new MarkdownEdit(buffer, area, surface, writable::get, area.getSelectionStart(), area.getSelectionEnd()); }
        String apply(MarkdownEdit edit, MarkdownService.Insertion insertion, int start) {
            return edit.applySelection(insertion.source(), new EditorSurface.Selection(start + insertion.destinationEnd(), start + insertion.destinationStart()), active::get);
        }
        void reject(MarkdownEdit edit, MarkdownService.Insertion insertion, int start) {
            String text = buffer.getContent(), saved = buffer.getSavedContent(); var snapshot = buffer.textSnapshot();
            long revision = buffer.documentRevision(), bytes = undo.retainedBytes(); int entries = undo.retainedEditCount(), count = events;
            int dot = area.getCaret().getDot(), mark = area.getCaret().getMark(); boolean dirty = buffer.isModified(), canUndo = undo.canUndo(), canRedo = undo.canRedo();
            assertFalse(apply(edit, insertion, start).isEmpty());
            assertEquals(text, buffer.getContent()); assertEquals(saved, buffer.getSavedContent()); assertSame(snapshot, buffer.textSnapshot());
            assertEquals(revision, buffer.documentRevision()); assertEquals(bytes, undo.retainedBytes()); assertEquals(entries, undo.retainedEditCount());
            assertEquals(count, events); assertEquals(dot, area.getCaret().getDot()); assertEquals(mark, area.getCaret().getMark());
            assertEquals(dirty, buffer.isModified()); assertEquals(canUndo, undo.canUndo()); assertEquals(canRedo, undo.canRedo());
        }
    }

    @Test void oneUndoAndRedoRestoreDirectedSelectionsAndPreservePriorHistory() throws Exception {
        for (boolean reverse : List.of(false, true)) for (boolean image : List.of(false, true)) edt(() -> {
            Fixture f = new Fixture(FileBuffer.createScratch("insert.md", "prefix label suffix"));
            f.buffer.getDocument().insertString(f.buffer.getDocument().getLength(), "!", null);
            f.area.setCaretPosition(reverse ? 12 : 7); f.area.moveCaretPosition(reverse ? 7 : 12);
            int dot = f.area.getCaret().getDot(), mark = f.area.getCaret().getMark(); String before = f.buffer.getContent();
            var insertion = service.insertion("label", image); long bytes = f.undo.retainedBytes();
            assertEquals("", f.apply(f.edit(), insertion, 7));
            assertEquals("prefix " + insertion.source() + " suffix!", f.buffer.getContent());
            assertEquals(image ? "path" : "url", f.area.getSelectedText()); assertEquals(2, f.undo.retainedEditCount());
            assertEquals(256L + 2L * (5 + insertion.source().length()), f.undo.retainedBytes() - bytes);
            f.undo.undo(); assertEquals(before, f.buffer.getContent()); assertEquals(dot, f.area.getCaret().getDot()); assertEquals(mark, f.area.getCaret().getMark());
            f.undo.undo(); assertEquals("prefix label suffix", f.buffer.getContent());
            f.undo.redo(); f.undo.redo(); assertEquals(image ? "path" : "url", f.area.getSelectedText());
            assertEquals("prefix " + insertion.source() + " suffix!", f.buffer.getContent()); return null;
        });
    }

    @Test void staleSelectionDocumentCapabilitiesFiltersAndUndoBudgetsRejectWithoutChanges() throws Exception {
        edt(() -> {
            Fixture f = new Fixture(FileBuffer.createScratch("insert.md", "label")); f.area.select(0, 5);
            var insertion = service.insertion("label", false); var old = f.edit();
            f.buffer.getDocument().insertString(5, "!", null); f.reject(old, insertion, 0);
            f.undo.undo(); f.reject(old, insertion, 0);
            f.area.select(0, 5); var current = f.edit();
            f.area.setCaretPosition(0); f.reject(current, insertion, 0); f.area.select(0, 5);
            f.writable.set(false); f.reject(current, insertion, 0); f.writable.set(true);
            f.buffer.getDocument().setDocumentFilter(new javax.swing.text.DocumentFilter()); f.reject(current, insertion, 0);
            f.buffer.getDocument().setDocumentFilter(null);
            f.undo.beginCompoundEdit(); try { f.reject(current, insertion, 0); } finally { f.undo.endCompoundEdit(); }
            f.undo.configure(new UndoHistoryPolicy(10, 200)); f.reject(current, insertion, 0);
            f.area.setDocument(new javax.swing.text.PlainDocument()); f.reject(current, insertion, 0);
            return null;
        });
    }

    @Test void invalidResultPositionsRejectAndTheEdtBoundaryIsRetained() throws Exception {
        Fixture f = edt(() -> new Fixture(FileBuffer.createScratch("insert.md", "label")));
        MarkdownEdit edit = edt(() -> { f.area.select(0, 5); return f.edit(); });
        edt(() -> {
            assertFalse(edit.applySelection("[😀](url)", new EditorSurface.Selection(2, 1), () -> true).isEmpty());
            assertFalse(edit.applySelection("[label](url)", new EditorSurface.Selection(100, 0), () -> true).isEmpty());
            assertEquals("label", f.buffer.getContent()); assertEquals(0, f.events); assertFalse(f.undo.canUndo()); return null;
        });
        assertThrows(IllegalStateException.class, () -> edit.applySelection("[label](url)", new EditorSurface.Selection(11, 8), () -> true));
    }

    @Test void undoDoesNotInstallSelectionsInAnotherDocumentOrAnInactiveOwner() throws Exception {
        edt(() -> {
            Fixture f = new Fixture(FileBuffer.createScratch("insert.md", "prefix label suffix")); f.area.select(7, 12);
            var insertion = service.insertion("label", false); assertEquals("", f.apply(f.edit(), insertion, 7));
            var other = new JTextArea("different document").getDocument(); f.area.setDocument(other); f.area.select(1, 4);
            f.undo.undo(); assertEquals("prefix label suffix", f.buffer.getContent());
            assertSame(other, f.area.getDocument()); assertEquals(4, f.area.getCaret().getDot()); assertEquals(1, f.area.getCaret().getMark());
            f.area.setDocument(f.buffer.getDocument()); f.active.set(false); f.area.setCaretPosition(0);
            f.undo.redo(); assertEquals("prefix " + insertion.source() + " suffix", f.buffer.getContent());
            assertNull(f.area.getSelectedText()); return null;
        });
    }

    @Test void codecRecoveryAndEofRemainExactForBothCommands() throws Exception {
        int counter = 0;
        for (boolean image : List.of(false, true)) for (String newline : List.of("\n", "\r\n", "\r\nextra\n")) for (String eof : List.of("", "\r\n")) {
            String label = "café [doc] 😀 e\u0301", raw = "\ufeffprefix" + newline + label + eof;
            Path file = temp.resolve("insert-" + counter++ + ".md"); Files.write(file, raw.getBytes(StandardCharsets.UTF_8));
            Fixture f = edt(() -> new Fixture(new FileBuffer(file.toFile()))); var insertion = service.insertion(label, image);
            edt(() -> {
                int start = f.buffer.getContent().indexOf(label); f.area.select(start, start + label.length());
                assertEquals("", f.apply(f.edit(), insertion, start)); f.undo.undo(); f.undo.redo();
                assertEquals(image ? "path" : "url", f.area.getSelectedText()); return null;
            });
            Path journal = temp.resolve("journal-" + counter);
            RecoveryJournal.write(journal, new RecoveryJournal.Workspace(temp.toString(), file.toString(), 0),
                List.of(new RecoveryJournal.Entry("insert", file.getFileName().toString(), file.toString(), f.buffer.snapshotContent())));
            assertEquals(f.buffer.getContent(), RecoveryJournal.read(journal).entries().getFirst().content());
            f.buffer.save(); assertArrayEquals(raw.replace(label, insertion.source()).getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
            assertEquals(f.buffer.getContent(), new FileBuffer(file.toFile()).getContent());
        }
    }
}
