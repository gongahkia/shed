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

class MarkdownSubtreeEditPlanTest {
    @TempDir Path temp;
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static MarkdownSemanticSnapshot semantic(String text) {
        return new MarkdownSemanticParser().parse(new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(1), 0, VersionedTextSnapshot.of(text)));
    }
    private static MarkdownSubtreeEditPlan ready(MarkdownSemanticSnapshot semantic, int dot, int mark, boolean demote) {
        var result = MarkdownSubtreeEditPlan.plan(semantic, dot, mark, demote);
        assertEquals(MarkdownHeadingEditPlan.Status.READY, result.status(), result.message());
        return result.edit().orElseThrow();
    }
    private static String transformed(String text, MarkdownSubtreeEditPlan plan) {
        return text.substring(0, plan.range().start()) + plan.replacement() + text.substring(plan.range().end());
    }
    private static final class Fixture {
        final FileBuffer buffer;
        final DocumentWorkspace.Id id;
        final JTextArea area = new JTextArea(), split = new JTextArea();
        final AtomicBoolean writable = new AtomicBoolean(true);
        final SwingEditorSurface surface;
        final BoundedUndoManager undo;
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
                private void changed(DocumentEvent e) { events++; buffer.applyDocumentChange(e); buffer.setModified(true); }
            });
        }
        MarkdownSemanticSnapshot.Source source() { return MarkdownSemanticSnapshot.Source.capture(id, buffer); }
        String apply(MarkdownSubtreeEditPlan plan) { return MarkdownEdit.applyPlan(plan, source(), buffer, area, surface, writable::get); }
        void reject(MarkdownSubtreeEditPlan plan) {
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

    @Test void exactHierarchyUsesSemanticSiblingParentAndDuplicateBoundaries() {
        String text = "# Parent\n## Same\nbody\n### Same\n#### *Deep*\n## Same\n### outside\n# End\n";
        int at = text.indexOf("## Same");
        var plan = ready(semantic(text), at, at, false);
        assertEquals(3, plan.markers().size()); assertEquals(text.indexOf("\n## Same", at) + 1, plan.subtree().end());
        assertEquals("# Parent\n# Same\nbody\n## Same\n### *Deep*\n## Same\n### outside\n# End\n", transformed(text, plan));
        assertEquals(text.indexOf("#### *Deep*") + 4, plan.range().end());
        assertTrue(plan.range().end() < plan.subtree().end());
    }

    @Test void fencesAndUnclosedCodeNeverBecomeTargetsOrBoundaries() {
        for (String fence : List.of("```", "````", "~~~", "~~~~~")) {
            String text = "## Root\n" + fence + "markdown\n# false boundary\n### example\n" + fence + "\n### Real\n## Next";
            var plan = ready(semantic(text), 0, 0, true);
            assertEquals(2, plan.markers().size());
            assertEquals(text.replace("## Root", "### Root").replace("### Real", "#### Real"), transformed(text, plan));
        }
        String unclosed = "## Root\n~~~~\n# false boundary\n### example\n```\n### also code";
        var plan = ready(semantic(unclosed), 0, 0, false);
        assertEquals(1, plan.markers().size()); assertEquals(unclosed.length(), plan.subtree().end());
        assertEquals(unclosed.substring(1), transformed(unclosed, plan));
        String differing = "## Root\n````md\n```\n### still code\n````\n### Real";
        assertEquals(2, ready(semantic(differing), 0, 0, true).markers().size());
        String ordinary = "## Root\nprose ``` is ordinary\n### Real\n\\### escaped\n    ### indented code";
        assertEquals(2, ready(semantic(ordinary), 0, 0, true).markers().size());
    }

    @Test void eofEmptyHeadingsWhitespaceClosingHashesAndUnicodeRemainExact() {
        for (String ending : List.of("", "\n", "\r\n")) {
            String text = "  ## *Héading* 😀  ##\r\nbody e\u0301 中文\n   ###\t\r\n" + "#### child" + ending;
            int dot = text.indexOf("Héading"), mark = text.indexOf("*Héading");
            var plan = ready(semantic(text), dot, mark, false);
            assertEquals("  # *Héading* 😀  ##\r\nbody e\u0301 中文\n   ##\t\r\n" + "### child" + ending, transformed(text, plan));
            assertEquals(dot - 1, plan.mapPosition(dot)); assertEquals(mark - 1, plan.mapPosition(mark));
        }
        for (String text : List.of("##", "##\n", "## Empty\n\n"))
            assertEquals(text.substring(1), transformed(text, ready(semantic(text), 0, 0, false)));
    }

    @Test void levelViolationsRejectWholeOperationIncludingDeepDescendants() {
        for (String text : List.of("# Root\n## child", "###### Root", "## Root\n### child\n###### deepest")) {
            boolean demote = !text.startsWith("# Root");
            var result = MarkdownSubtreeEditPlan.plan(semantic(text), 0, 0, demote);
            assertEquals(MarkdownHeadingEditPlan.Status.BOUNDARY, result.status()); assertTrue(result.edit().isEmpty());
        }
    }

    @Test void setextAndContainerEditsRejectButVerifiedOutsideBoundariesAreRespected() {
        for (String text : List.of("Title\n===", "# Root\nNested\n---", "> ## quote\n> ### child", "- ## list\n  ### child",
                "## Root\n> ### quote", "## Root\n- item\n\n  ### nested", "## Root\n> # false sibling")) {
            int at = text.startsWith(">") || text.startsWith("-") ? text.indexOf('#') : 0;
            var result = MarkdownSubtreeEditPlan.plan(semantic(text), at, at, true);
            assertEquals(MarkdownHeadingEditPlan.Status.UNSUPPORTED, result.status(), text + " " + result.message());
        }
        String text = "## Root\n- ordinary list item\n\n### child\nNext\n===\n";
        var plan = ready(semantic(text), 0, 0, false);
        assertEquals(text.indexOf("Next"), plan.subtree().end());
        assertEquals(text.replace("## Root", "# Root").replace("### child", "## child"), transformed(text, plan));
    }

    @Test void completeNoMatchAndUnavailableAnalysisNeverUseRegexFallback() {
        for (String text : List.of("```md\n## fake\n```", "\\## escaped", "ordinary", "    ## code")) {
            int at = Math.max(0, text.indexOf("##"));
            assertEquals(MarkdownHeadingEditPlan.Status.NOT_HEADING, MarkdownSubtreeEditPlan.plan(semantic(text), at, at, true).status());
        }
        var source = semantic("## Root").source();
        for (var status : List.of(MarkdownSemanticSnapshot.Status.UNSUPPORTED, MarkdownSemanticSnapshot.Status.CANCELLED, MarkdownSemanticSnapshot.Status.FAILED)) {
            var unavailable = MarkdownSemanticSnapshot.unavailable(source, status, "test");
            assertTrue(MarkdownSubtreeEditPlan.plan(unavailable, 0, 0, true).edit().isEmpty());
        }
        var capped = semantic("## Root\n" + "body\n".repeat(220000));
        assertEquals(MarkdownSemanticSnapshot.Status.UNSUPPORTED, capped.status());
        assertTrue(MarkdownSubtreeEditPlan.plan(capped, 0, 0, true).edit().isEmpty());
        var projected = new MarkdownSemanticSnapshot.Source(source.documentId(), 0, source.text(), false);
        assertTrue(MarkdownSubtreeEditPlan.plan(MarkdownSemanticSnapshot.unavailable(projected,
            MarkdownSemanticSnapshot.Status.UNSUPPORTED, "projected"), 0, 0, true).edit().isEmpty());
    }

    @Test void replacementsAndHeadingCountsHaveExplicitAdmissionBounds() {
        String large = "## Root\n" + "body\n".repeat(14000) + "### child";
        assertTrue(MarkdownSubtreeEditPlan.plan(semantic(large), 0, 0, true).edit().isEmpty());
        String dense = "## Root\n" + "### child\n".repeat(MarkdownSubtreeEditPlan.MAX_HEADINGS);
        assertTrue(MarkdownSubtreeEditPlan.plan(semantic(dense), 0, 0, true).edit().isEmpty());
        String tail = "## Root\n" + ("body ".repeat(20) + "\n").repeat(9000);
        var plan = ready(semantic(tail), 0, 0, true);
        assertEquals(2, plan.expected().length()); assertEquals(tail.length(), plan.subtree().end());
    }

    @Test void invalidMarkersSelectionsAndNonMarkerProposalsReject() {
        var s = semantic("## Root\n### child"); var valid = ready(s, 0, 0, true);
        assertThrows(UnsupportedOperationException.class, () -> valid.markers().clear());
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSubtreeEditPlan(valid.source(), valid.subtree(), valid.markers(),
            valid.range(), valid.expected(), valid.replacement() + "changed", true, 0, 0));
        assertTrue(MarkdownSubtreeEditPlan.plan(s, 0, s.source().text().length(), true).edit().isEmpty());
        var bad = new MarkdownSemanticSnapshot(s.source(), s.status(), s.coverage(), s.coveredRange(), s.blocks(),
            List.of(new MarkdownSemanticSnapshot.Heading(2, "Root", "root", s.headings().getFirst().location(),
                Optional.of(new MarkdownSourceRange(3, 5)))), s.codeBlocks(), s.inlines(), s.links(), s.references(), s.tasks(), s.limitations(), s.detail());
        assertTrue(MarkdownSubtreeEditPlan.plan(bad, 0, 0, true).edit().isEmpty());
        var unicode = semantic("## 😀\n### child");
        assertTrue(MarkdownSubtreeEditPlan.plan(unicode, 4, 4, true).edit().isEmpty());
    }

    @Test void acceptedEditHasExactUndoBudgetCaretMappingAndSharedHistory() throws Exception {
        Fixture f = edt(() -> new Fixture(FileBuffer.createScratch("subtree.md", "## Parent\nbody\n### child"), 1));
        edt(() -> { f.buffer.getDocument().insertString(f.buffer.getDocument().getLength(), "!", null); f.area.select(3, 8); return null; });
        var source = edt(f::source);
        var plan = ready(new MarkdownSemanticParser().parse(source), 8, 3, false);
        edt(() -> {
            long before = f.undo.retainedBytes();
            assertEquals("", f.apply(plan)); assertEquals("# Parent\nbody\n## child!", f.buffer.getContent());
            assertEquals(f.area.getText(), f.split.getText()); assertEquals(2, f.undo.retainedEditCount());
            assertEquals(plan.undoBytes(), f.undo.retainedBytes() - before);
            assertEquals(7, f.area.getCaret().getDot()); assertEquals(2, f.area.getCaret().getMark());
            f.undo.undo(); assertEquals("## Parent\nbody\n### child!", f.buffer.getContent());
            f.undo.undo(); assertEquals("## Parent\nbody\n### child", f.buffer.getContent());
            f.undo.redo(); f.undo.redo(); assertEquals("# Parent\nbody\n## child!", f.buffer.getContent());
            return null;
        });
    }

    @Test void changedRevisionUndoRedoAndForeignDocumentsRejectWithoutAnyStateChange() throws Exception {
        Fixture f = edt(() -> new Fixture(FileBuffer.createScratch("subtree.md", "## Root\n### child"), 1));
        var plan = ready(new MarkdownSemanticParser().parse(edt(f::source)), 0, 0, true);
        edt(() -> {
            f.buffer.getDocument().insertString(f.buffer.getDocument().getLength(), "!", null); f.reject(plan);
            f.undo.undo(); f.reject(plan); f.undo.redo(); f.reject(plan);
            Fixture other = new Fixture(FileBuffer.createScratch("other.md", "## Root\n### child"), 2); other.reject(plan);
            f.area.setDocument(other.buffer.getDocument()); f.reject(plan); return null;
        });
    }

    @Test void capabilitiesSelectionFiltersAndHistoryLimitsRejectAtomically() throws Exception {
        Fixture f = edt(() -> new Fixture(FileBuffer.createScratch("subtree.md", "## Root\n### child"), 1));
        var plan = ready(new MarkdownSemanticParser().parse(edt(f::source)), 0, 0, true);
        edt(() -> {
            f.writable.set(false); f.reject(plan); f.writable.set(true);
            f.area.setCaretPosition(1); f.reject(plan); f.area.setCaretPosition(0);
            f.buffer.getDocument().setDocumentFilter(new javax.swing.text.DocumentFilter()); f.reject(plan);
            f.buffer.getDocument().setDocumentFilter(null);
            f.undo.beginCompoundEdit(); try { f.reject(plan); } finally { f.undo.endCompoundEdit(); }
            f.undo.configure(new UndoHistoryPolicy(10, 100)); f.reject(plan);
            return null;
        });
        assertThrows(IllegalStateException.class, () -> MarkdownEdit.applyPlan(plan, plan.source(), f.buffer, f.area, f.surface, () -> true));
    }

    @Test void formattingAndRejectedPlanningAreReadOnly() throws Exception {
        Fixture f = edt(() -> new Fixture(FileBuffer.createScratch("subtree.md", "## Root\n### child"), 1));
        var source = edt(f::source); var semantic = new MarkdownSemanticParser().parse(source);
        ready(semantic, 0, 0, true);
        edt(() -> {
            assertSame(source.text(), f.buffer.textSnapshot()); assertEquals(source.revision(), f.buffer.documentRevision());
            assertFalse(f.buffer.isModified()); assertEquals(0, f.events); assertFalse(f.undo.canUndo()); return null;
        });
    }

    @Test void codecSaveReopenAndRecoveryKeepBomTerminatorsAndEof() throws Exception {
        int counter = 0;
        for (String newline : List.of("\n", "\r\n", "\r\nbody\n")) for (String eof : List.of("", "\r\n")) {
            String raw = "\ufeff## Parent 😀" + newline + "### child e\u0301" + eof;
            Path file = temp.resolve("subtree-" + counter++ + ".md"); Files.write(file, raw.getBytes(StandardCharsets.UTF_8));
            Fixture f = edt(() -> new Fixture(new FileBuffer(file.toFile()), 1));
            var plan = ready(new MarkdownSemanticParser().parse(edt(f::source)), 0, 0, false);
            edt(() -> { assertEquals("", f.apply(plan)); f.undo.undo(); f.undo.redo(); return null; });
            Path journal = temp.resolve("recovery-" + counter);
            RecoveryJournal.write(journal, new RecoveryJournal.Workspace(temp.toString(), file.toString(), 0),
                List.of(new RecoveryJournal.Entry("subtree", file.getFileName().toString(), file.toString(), f.buffer.snapshotContent())));
            assertEquals(f.buffer.getContent(), RecoveryJournal.read(journal).entries().getFirst().content());
            f.buffer.save();
            assertArrayEquals(raw.replace("## Parent", "# Parent").replace("### child", "## child").getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
            assertEquals(f.buffer.getContent(), new FileBuffer(file.toFile()).getContent());
        }
    }

    @Test void paletteRegistersDistinctHeadingActionsWithoutShortcuts() {
        for (String[] action : new String[][]{{"Promote Markdown Heading", "promote"}, {"Demote Markdown Heading", "demote"},
                {"Promote Markdown Subtree", "promote-subtree"}, {"Demote Markdown Subtree", "demote-subtree"}}) {
            assertEquals("markdown heading " + action[1], PaletteController.surfaceActionCommand(action[0]));
            assertEquals(1, PaletteController.surfaceActionNames().stream().filter(action[0]::equals).count());
        }
    }
}
