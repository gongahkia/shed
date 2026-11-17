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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownHeadingEditPlanTest {
    @TempDir Path temp;
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static MarkdownSemanticSnapshot semantic(String text) {
        return new MarkdownSemanticParser().parse(new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(1), 0, VersionedTextSnapshot.of(text)));
    }
    private static MarkdownHeadingEditPlan plan(MarkdownSemanticSnapshot snapshot, int dot, int mark, boolean demote) {
        var result = MarkdownHeadingEditPlan.plan(snapshot, dot, mark, demote);
        assertEquals(MarkdownHeadingEditPlan.Status.READY, result.status(), result.message());
        return result.edit().orElseThrow();
    }
    private static final class Fixture {
        final FileBuffer buffer;
        final DocumentWorkspace.Id id;
        final JTextArea area = new JTextArea(), split = new JTextArea();
        final AtomicBoolean writable = new AtomicBoolean(true);
        final SwingEditorSurface surface;
        final BoundedUndoManager undo;
        final List<FileBuffer.DocumentTextChange> changes = new ArrayList<>();
        int events;
        Fixture(FileBuffer buffer, long id) {
            this.buffer = buffer; this.id = new DocumentWorkspace.Id(id);
            area.setDocument(buffer.getDocument()); split.setDocument(buffer.getDocument());
            undo = (BoundedUndoManager) buffer.getUndoManager();
            surface = new SwingEditorSurface(area, new JScrollPane(area), () -> undo, writable::get);
            buffer.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { changed(e); }
                public void removeUpdate(DocumentEvent e) { changed(e); }
                public void changedUpdate(DocumentEvent e) { changed(e); }
                private void changed(DocumentEvent e) { events++; changes.add(buffer.applyDocumentChange(e)); buffer.setModified(true); }
            });
        }
        MarkdownSemanticSnapshot.Source source() { return MarkdownSemanticSnapshot.Source.capture(id, buffer); }
        String apply(MarkdownHeadingEditPlan plan) { return MarkdownEdit.applyPlan(plan, source(), buffer, area, surface, writable::get); }
        void reject(MarkdownHeadingEditPlan plan) {
            String text = buffer.getContent(), saved = buffer.getSavedContent(); var snapshot = buffer.textSnapshot();
            long revision = buffer.documentRevision(), bytes = undo.retainedBytes();
            int entries = undo.retainedEditCount(), count = events, dot = area.getCaret().getDot(), mark = area.getCaret().getMark();
            boolean dirty = buffer.isModified(), canUndo = undo.canUndo(), canRedo = undo.canRedo();
            assertFalse(apply(plan).isEmpty());
            assertEquals(text, buffer.getContent()); assertEquals(saved, buffer.getSavedContent());
            assertSame(snapshot, buffer.textSnapshot()); assertEquals(revision, buffer.documentRevision());
            assertEquals(entries, undo.retainedEditCount()); assertEquals(bytes, undo.retainedBytes());
            assertEquals(dirty, buffer.isModified()); assertEquals(canUndo, undo.canUndo()); assertEquals(canRedo, undo.canRedo());
            assertEquals(count, events); assertEquals(dot, area.getCaret().getDot()); assertEquals(mark, area.getCaret().getMark());
        }
    }

    @Test void plansOnlyExactAtxMarkersAcrossIndentContainersFormattingUnicodeAndEof() {
        for (String text : List.of("#", "## empty\n", "  ### *formatted* 😀 e\u0301 ###", "> ## quoted\n", "- ## listed\n", "préface\r\n## Unicode שלום 😀\r\n")) {
            var s = semantic(text);
            int at = text.indexOf('#');
            var edit = plan(s, at, at, true);
            assertEquals("#".repeat(edit.expected().length()), edit.range().text(s.source().text()));
            String after = text.substring(0, edit.range().start()) + edit.replacement() + text.substring(edit.range().end());
            assertEquals(text.substring(0, at) + "#" + text.substring(at), after);
            assertEquals(text.endsWith("\n"), after.endsWith("\n"));
        }
        var levels = semantic("# a\n## b\n### c\n###### f");
        assertEquals(MarkdownHeadingEditPlan.Status.BOUNDARY, MarkdownHeadingEditPlan.plan(levels, 0, 0, false).status());
        int last = levels.source().text().text().indexOf("######");
        assertEquals(MarkdownHeadingEditPlan.Status.BOUNDARY, MarkdownHeadingEditPlan.plan(levels, last, last, true).status());
        assertEquals("#", plan(levels, 4, 4, false).replacement());
    }

    @Test void completeNoMatchNeverReinterpretsCodeEscapesOrInactiveMarkers() {
        for (String text : List.of("```md\n## fake\n```", "~~~\n## unclosed", "\\## escaped", "    ## indented code", "####### too many", "ordinary")) {
            int dot = Math.max(0, text.indexOf('#'));
            var result = MarkdownHeadingEditPlan.plan(semantic(text), dot, dot, true);
            assertEquals(MarkdownHeadingEditPlan.Status.NOT_HEADING, result.status(), text); assertTrue(result.edit().isEmpty());
        }
    }

    @Test void setextAndUnavailableOrInvalidMarkerRangesAreExplicitlyRejected() {
        for (String text : List.of("Title\n===", "first\nsecond\n---\n")) {
            var s = semantic(text);
            for (int dot : new int[]{0, text.lastIndexOf('\n') < 0 ? 0 : text.indexOf('\n') + 1})
                assertEquals(MarkdownHeadingEditPlan.Status.UNSUPPORTED, MarkdownHeadingEditPlan.plan(s, dot, dot, true).status());
        }
        var s = semantic("## 😀"); var h = s.headings().getFirst();
        for (var marker : List.of(Optional.<MarkdownSourceRange>empty(), Optional.of(new MarkdownSourceRange(3, 4)), Optional.of(new MarkdownSourceRange(0, 99)))) {
            var modified = new MarkdownSemanticSnapshot(s.source(), s.status(), s.coverage(), s.coveredRange(), s.blocks(),
                List.of(new MarkdownSemanticSnapshot.Heading(h.level(), h.text(), h.anchor(), h.location(), marker)),
                s.codeBlocks(), s.inlines(), s.links(), s.references(), s.tasks(), s.limitations(), s.detail());
            assertEquals(MarkdownHeadingEditPlan.Status.UNVERIFIED, MarkdownHeadingEditPlan.plan(modified, 0, 0, true).status());
        }
        assertEquals(MarkdownHeadingEditPlan.Status.UNVERIFIED, MarkdownHeadingEditPlan.plan(s, 4, 4, true).status());
    }

    @Test void failedCancelledProjectedAndOverCapSourcesDoNotProduceEdits() {
        var source = semantic("## x").source();
        for (var status : List.of(MarkdownSemanticSnapshot.Status.UNSUPPORTED, MarkdownSemanticSnapshot.Status.FAILED, MarkdownSemanticSnapshot.Status.CANCELLED)) {
            var unavailable = MarkdownSemanticSnapshot.unavailable(source, status, "injected");
            var result = MarkdownHeadingEditPlan.plan(unavailable, 0, 0, true);
            assertTrue(result.edit().isEmpty()); assertNotEquals(MarkdownHeadingEditPlan.Status.NOT_HEADING, result.status());
        }
        var projected = new MarkdownSemanticSnapshot.Source(source.documentId(), 0, source.text(), false);
        assertEquals(MarkdownHeadingEditPlan.Status.UNSUPPORTED, MarkdownHeadingEditPlan.plan(new MarkdownSemanticParser().parse(projected), 0, 0, true).status());
        var large = semantic("## first\n" + "body\n".repeat(210000));
        assertEquals(MarkdownHeadingEditPlan.Status.UNSUPPORTED, MarkdownHeadingEditPlan.plan(large, 0, 0, true).status());
    }

    @Test void acceptedPlanUsesCompoundUndoPreservesPriorHistoryAndSplitSelections() throws Exception {
        Fixture f = edt(() -> new Fixture(FileBuffer.createScratch("head.md", "## café 😀\nbody"), 1));
        var source = edt(() -> { f.area.select(3, 7); f.split.select(12, 14); return f.source(); });
        var edit = plan(new MarkdownSemanticParser().parse(source), 7, 3, true);
        edt(() -> {
            assertEquals("", f.apply(edit)); assertEquals("### café 😀\nbody", f.buffer.getContent());
            assertEquals(8, f.area.getCaret().getDot()); assertEquals(4, f.area.getCaret().getMark());
            assertEquals(13, f.split.getSelectionStart()); assertEquals(15, f.split.getSelectionEnd());
            assertEquals(1, f.undo.retainedEditCount()); assertTrue(f.buffer.isModified());
            assertEquals("## café 😀\nbody", f.buffer.getSavedContent());
            f.undo.undo(); assertEquals("## café 😀\nbody", f.buffer.getContent());
            f.undo.redo(); assertEquals("### café 😀\nbody", f.buffer.getContent());
            return null;
        });
        var secondSource = edt(() -> { f.area.setCaretPosition(0); return f.source(); });
        var second = plan(new MarkdownSemanticParser().parse(secondSource), 0, 0, false);
        edt(() -> {
            assertEquals("", f.apply(second)); assertEquals(2, f.undo.retainedEditCount());
            f.undo.undo(); assertEquals("### café 😀\nbody", f.buffer.getContent());
            f.undo.undo(); assertEquals("## café 😀\nbody", f.buffer.getContent()); return null;
        });
    }

    @Test void anySourceRevisionChangeIncludingUndoRedoRejectsTheOldPlanWithoutMutation() throws Exception {
        for (boolean elsewhere : List.of(false, true)) {
            Fixture f = edt(() -> new Fixture(FileBuffer.createScratch("revision.md", "## heading\nbody"), 1));
            var s = edt(f::source); var edit = plan(new MarkdownSemanticParser().parse(s), 0, 0, true);
            edt(() -> {
                f.buffer.getDocument().insertString(elsewhere ? f.buffer.getDocument().getLength() : 0, "x", null);
                f.area.setCaretPosition(0); f.reject(edit);
                f.undo.undo(); f.area.setCaretPosition(0); f.reject(edit);
                f.undo.redo(); f.area.setCaretPosition(0); f.reject(edit); return null;
            });
        }
    }

    @Test void foreignIdentityClosedReadOnlySelectionAndUndoBudgetCannotApplyPlans() throws Exception {
        Fixture a = edt(() -> new Fixture(FileBuffer.createScratch("a.md", "## same"), 1));
        Fixture b = edt(() -> new Fixture(FileBuffer.createScratch("b.md", "## same"), 2));
        var source = edt(a::source); var edit = plan(new MarkdownSemanticParser().parse(source), 0, 0, true);
        edt(() -> {
            b.reject(edit);
            a.writable.set(false); a.reject(edit); a.writable.set(true);
            a.area.select(1, 2); a.reject(edit); a.area.setCaretPosition(0);
            a.area.setDocument(new javax.swing.text.PlainDocument()); a.reject(edit); a.area.setDocument(a.buffer.getDocument());
            a.undo.configure(new UndoHistoryPolicy(10, 64)); a.reject(edit);
            return null;
        });
    }

    @Test void codecEofAndRecoverySnapshotsKeepTheExactSourceAcrossSaveAndReopen() throws Exception {
        int counter = 0;
        for (String newline : List.of("\n", "\r\n")) for (boolean eof : List.of(false, true)) {
            Path file = temp.resolve("codec-" + counter++ + ".md");
            String raw = "\ufeffpréface" + newline + "## *café* 😀" + (eof ? newline : "");
            Files.write(file, raw.getBytes(StandardCharsets.UTF_8));
            Fixture f = edt(() -> new Fixture(new FileBuffer(file.toFile()), 1));
            var source = edt(() -> { f.area.setCaretPosition(f.buffer.getContent().indexOf("##")); return f.source(); });
            int at = source.text().text().indexOf("##"); var edit = plan(new MarkdownSemanticParser().parse(source), at, at, true);
            edt(() -> {
                assertEquals("", f.apply(edit)); String changed = f.buffer.getContent();
                assertEquals(raw.substring(1).replace("\r\n", "\n").replace("##", "###"), changed);
                f.undo.undo(); assertEquals(source.text().text(), f.buffer.getContent());
                f.undo.redo(); assertEquals(changed, f.buffer.snapshotContent()); return null;
            });
            Path journal = temp.resolve("recovery-" + counter);
            RecoveryJournal.write(journal, new RecoveryJournal.Workspace(temp.toString(), file.toString(), 0),
                List.of(new RecoveryJournal.Entry("heading", file.getFileName().toString(), file.toString(), f.buffer.snapshotContent())));
            assertEquals(f.buffer.getContent(), RecoveryJournal.read(journal).entries().getFirst().content());
            f.buffer.save();
            assertArrayEquals(raw.replace("##", "###").getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
            assertEquals(f.buffer.getContent(), new FileBuffer(file.toFile()).getContent());
        }
    }

    @Test void actualMarkerEventsPreserveCheckpointSuffixReuseAndLexerResultsThroughUndoRedo() throws Exception {
        String text = "## first\n" + "plain `code`\n".repeat(5000);
        Fixture f = edt(() -> new Fixture(FileBuffer.createScratch("highlight.md", text), 1));
        var source = edt(f::source); var edit = plan(new MarkdownSemanticParser().parse(source), 0, 0, true);
        var grammar = new GrammarHighlightService(); var cache = new GrammarHighlightService.MarkdownCache();
        grammar.highlightMarkdown(source.text(), text.length() - 200, text.length(), cache, cache.bind(source.text()), () -> false);
        edt(() -> { assertEquals("", f.apply(edit)); return null; });
        for (int step = 0; step < 3; step++) {
            var change = edt(() -> f.changes.getLast());
            assertTrue(change.incremental()); assertEquals(2, change.offset());
            assertTrue(change.removedLength() <= 1); assertTrue(change.insertedText().length() <= 1);
            cache.changed(change.before(), change.after(), change.offset(), change.removedLength());
            var after = change.after(); int start = after.lineStartOffset(after.lineCount() - 10);
            var highlighted = grammar.highlightMarkdown(after, start, after.length(), cache, cache.bind(after), () -> false);
            var expected = grammar.highlightSnapshot(after.text(), FileType.MARKDOWN).stream().filter(t -> t.start() >= start).toList();
            assertEquals(expected, highlighted.tokens()); assertTrue(highlighted.scannedCharacters() < 20000);
            assertTrue(cache.checkpointCount() <= GrammarHighlightService.MarkdownCache.MAX_CHECKPOINTS);
            if (step == 0) edt(() -> { f.undo.undo(); return null; });
            if (step == 1) edt(() -> { f.undo.redo(); return null; });
        }
    }
}
