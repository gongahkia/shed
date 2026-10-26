package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentReviewPanelTest {
    @TempDir Path temporary;
    private AsyncJobService jobs;
    private AgentReviewPanel panel;
    private final AtomicReference<AgentReviewPanel.Scope> current = new AtomicReference<>();

    @AfterEach void close() { if (jobs != null) jobs.shutdownNow(); }

    @Test void renameReviewShowsBothPathsAndPreservesTheDraftThroughApplyAndUndo() throws Exception {
        var base = scope("project"); Path file = Files.writeString(base.root().resolve("source"), "saved\r\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(base.root()), base::root, base.root())) {
            edt(() -> buffer.replaceContentUndoably("human draft\n"));
            var scope = new AgentReviewPanel.Scope(base.root(), base.sessions(), editor); current.set(scope);
            var before = editor.snapshots().getFirst();
            scope.service().propose("rename", "task", "turn", List.of(WorkspaceProposalService.Input.rename(before.documentId(), before.version(), "renamed")));
            open(); ready();
            edt(() -> {
                assertEquals("Rename · source → renamed", combo().getSelectedItem());
                assertEquals("saved\n", text("before").getText()); assertEquals("saved\n", text("proposed").getText());
                assertEquals("human draft\n", text("preservedDraft").getText());
                assertTrue(text("details").getText().contains("Rename to"));
                assertFalse(text("proposed").isEditable()); assertFalse(button("save").isEnabled());
                assertEquals(0, table().getRowCount()); button("apply").doClick();
            });
            await(() -> button("undo").isEnabled()); assertFalse(Files.exists(file));
            assertEquals(base.root().resolve("renamed").toRealPath().toFile(), buffer.getFile());
            edt(() -> { buffer.replaceContentUndoably("later human work\n"); button("undo").doClick(); });
            await(() -> text("details").getText().contains("UNDONE"));
            assertEquals(file.toRealPath().toFile(), buffer.getFile()); assertEquals("later human work\n", buffer.getContent());
            assertTrue(buffer.isModified()); assertEquals("saved\r\n", Files.readString(file));
        }
    }

    @Test void deletionReviewShowsTheDiskFileAndPreservedDraftAndSupportsApplyAndUndo() throws Exception {
        var base = scope("project"); Path file = Files.writeString(base.root().resolve("source"), "saved\r\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(base.root()), base::root, base.root())) {
            edt(() -> buffer.replaceContentUndoably("human draft\n"));
            var scope = new AgentReviewPanel.Scope(base.root(), base.sessions(), editor); current.set(scope);
            var before = editor.snapshots().getFirst();
            scope.service().propose("delete", "task", "turn", List.of(WorkspaceProposalService.Input.delete(before.documentId(), before.version())));
            open(); ready();
            edt(() -> {
                assertEquals("Delete · source", combo().getSelectedItem());
                assertEquals("saved\n", text("before").getText()); assertEquals("human draft\n", text("preservedDraft").getText());
                assertTrue(text("details").getText().contains("Deletes the disk file"));
                assertFalse(text("proposed").isEditable()); assertFalse(button("save").isEnabled());
                assertEquals(0, table().getRowCount()); button("apply").doClick();
            });
            await(() -> button("undo").isEnabled()); assertFalse(Files.exists(file)); assertTrue(buffer.isScratch());
            edt(() -> { buffer.replaceContentUndoably("later human work\n"); button("undo").doClick(); });
            await(() -> text("details").getText().contains("UNDONE"));
            assertFalse(buffer.isScratch()); assertEquals("later human work\n", buffer.getContent()); assertTrue(buffer.isModified());
            assertEquals("saved\r\n", Files.readString(file));
        }
    }

    @Test void newFilesAreClearlyLabeledAndCanBeReviewedAppliedAndUndoneWithoutAFrame() throws Exception {
        var base = scope("project");
        try (var editor = new EditorWorkspaceDocuments(List::of, () -> List.of(base.root()), base::root, base.root())) {
            var scope = new AgentReviewPanel.Scope(base.root(), base.sessions(), editor); current.set(scope);
            scope.service().propose("new", "task", "turn", List.of(WorkspaceProposalService.Input.create("new.txt", "draft\n")));
            open(); ready();
            edt(() -> {
                assertEquals("Create · new.txt", combo().getSelectedItem());
                assertTrue(text("details").getText().contains("Creates a new file"));
                assertEquals("", text("before").getText());
                text("proposed").setText("reviewed\n"); button("save").doClick();
            });
            ready(); assertFalse(Files.exists(scope.root().resolve("new.txt")));
            edt(() -> button("apply").doClick()); await(() -> button("undo").isEnabled());
            assertEquals("reviewed\n", Files.readString(scope.root().resolve("new.txt")));
            edt(() -> button("undo").doClick()); await(() -> text("details").getText().contains("UNDONE"));
            assertFalse(Files.exists(scope.root().resolve("new.txt")));
        }
    }

    @Test void partiallyAppliedChangesCanBeRestoredThroughTheRecoveryControls() throws Exception {
        var scope = scope("project"); current.set(scope);
        Path first = Files.writeString(scope.root().resolve("a"), "old\r\n"), second = Files.writeString(scope.root().resolve("b"), "old\r\n");
        Path storage = scope.sessions().resolve("agent-workspace").resolve(WorkspacePaths.id(scope.root().toRealPath()));
        WorkspaceProposalRecoveryTest.interruptedApply(scope.root(), storage, scope.service(), List.of("a", "b"));
        Files.writeString(first, "new\r\n");
        open(); await(() -> button("inspect").isEnabled());
        edt(() -> button("inspect").doClick()); await(() -> button("restore").isEnabled());
        edt(() -> { assertFalse(button("reconcile").isEnabled()); button("restore").doClick(); });
        await(() -> label().getText().contains("Original snapshots restored"));
        assertEquals("old\r\n", Files.readString(first)); assertEquals("old\r\n", Files.readString(second));
        edt(() -> { assertFalse(button("apply").isEnabled()); assertTrue(button("rebase").isEnabled()); });
    }

    @Test void interruptedProposalCanBeInspectedAndItsVerifiedOutcomeRecordedThroughHumanControls() throws Exception {
        var scope = scope("project"); current.set(scope);
        Path file = Files.writeString(scope.root().resolve("source"), "old\r\n");
        String workspaceId = WorkspacePaths.id(scope.root().toRealPath());
        Path storage = scope.sessions().resolve("agent-workspace").resolve(workspaceId);
        WorkspaceProposalRecoveryTest.interruptedApply(scope.root(), storage, scope.service(), List.of("source"));
        Files.writeString(file, "new\r\n");
        open(); await(() -> button("inspect").isEnabled());
        edt(() -> { assertFalse(button("apply").isEnabled()); assertFalse(button("reconcile").isEnabled()); button("inspect").doClick(); });
        await(() -> button("reconcile").isEnabled());
        edt(() -> { assertTrue(text("recoveryDetails").getText().contains("APPLIED")); button("reconcile").doClick(); });
        await(() -> button("undo").isEnabled());
        assertEquals("new\r\n", Files.readString(file));
    }

    @Test void humanCanSelectHunksEditTextApplyEveryDocumentAndUndoWithoutAFrame() throws Exception {
        var scope = scope("project"); current.set(scope);
        Path file = Files.writeString(scope.root().resolve("first"), "first\nmiddle\nlast\n");
        Path second = Files.writeString(scope.root().resolve("second"), "two\n");
        var service = scope.service();
        String id = (String) service.propose("r", "task", "turn", List.of(input(scope, "first", "FIRST\nmiddle\nLAST\n"), input(scope, "second", "TWO\n"))).get("id");
        open(); ready();
        edt(() -> {
            assertEquals(2, combo().getItemCount()); assertEquals(2, table().getRowCount());
            table().setValueAt(false, 1, 0);
            assertFalse(button("apply").isEnabled()); assertFalse(combo().isEnabled());
            button("save").doClick();
        });
        ready();
        assertEquals("FIRST\nmiddle\nlast\n", service.preview(id, 0).selected());
        edt(() -> combo().setSelectedIndex(1)); ready();
        edt(() -> { text("proposed").setText("Edited by reviewer\n"); button("save").doClick(); }); ready();
        assertEquals("Edited by reviewer\n", service.preview(id, 1).selected());
        edt(() -> button("apply").doClick());
        await(() -> button("undo").isEnabled());
        assertEquals("FIRST\nmiddle\nlast\n", Files.readString(file)); assertEquals("Edited by reviewer\n", Files.readString(second));
        Files.writeString(file, "FIRST\nhuman middle\nlast\n");
        edt(() -> button("undo").doClick());
        await(() -> text("details").getText().contains("UNDONE"));
        assertEquals("first\nhuman middle\nlast\n", Files.readString(file)); assertEquals("two\n", Files.readString(second));
        assertEquals("UNDONE", service.preview(id, 0).proposal().get("status"));
    }

    @Test void staleReviewDoesNotOverwriteHumanEditsAndExplicitRebaseRequiresReviewAgain() throws Exception {
        var scope = scope("project"); current.set(scope);
        Path file = Files.writeString(scope.root().resolve("first"), "first\nmiddle\nlast\n");
        scope.service().propose("r", "task", "turn", List.of(input(scope, "first", "FIRST\nmiddle\nlast\n")));
        open(); ready();
        Files.writeString(file, "first\nhuman middle\nlast\n");
        edt(() -> button("apply").doClick());
        await(() -> label().getText().startsWith("STALE_VERSION"));
        assertEquals("first\nhuman middle\nlast\n", Files.readString(file));
        edt(() -> button("rebase").doClick()); ready();
        edt(() -> {
            assertEquals("FIRST\nhuman middle\nlast\n", text("proposed").getText());
            assertEquals("first\nhuman middle\nlast\n", text("before").getText());
            button("apply").doClick();
        });
        await(() -> button("undo").isEnabled());
        assertEquals("FIRST\nhuman middle\nlast\n", Files.readString(file));
    }

    @Test void unsavedReviewSurvivesAutomaticRefreshAndRequiresExplicitDiscardBeforeWorkspaceSwitch() throws Exception {
        var first = scope("first"); var second = scope("second"); current.set(first);
        Files.writeString(first.root().resolve("source"), "old");
        var draft = first.service().propose("r", "first task", "turn", List.of(input(first, "source", "agent")));
        open(); ready();
        edt(() -> {
            text("proposed").setText("local review edit"); panel.refresh();
            assertEquals("local review edit", text("proposed").getText());
            assertFalse(button("apply").isEnabled()); assertFalse(button("refresh").isEnabled());
            current.set(second); panel.refresh();
            assertEquals("local review edit", text("proposed").getText());
            assertTrue(label().getText().contains("previous workspace")); button("discard").doClick();
        });
        await(() -> label().getText().startsWith("No proposals yet"));
        edt(() -> { assertEquals("", text("proposed").getText()); assertFalse(button("apply").isEnabled()); });
        assertEquals("agent", first.service().preview((String) draft.get("id"), 0).proposed());
        assertEquals("old", Files.readString(first.root().resolve("source")));
    }

    @Test void delayedLoadingNeverPublishesThePreviousWorkspacesProposal() throws Exception {
        var first = scope("first"); var second = scope("second"); current.set(first);
        for (var scope : List.of(first, second)) {
            Files.writeString(scope.root().resolve("source"), "old");
            scope.service().propose("r", scope.root().getFileName().toString(), "turn", List.of(input(scope, "source", scope.root().getFileName().toString())));
        }
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        executor.submit(() -> { blocked.countDown(); release.await(10, TimeUnit.SECONDS); return null; });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        jobs = new AsyncJobService(executor, 50, null);
        try {
            open(); edt(() -> { current.set(second); panel.refresh(); });
        } finally { release.countDown(); }
        ready();
        edt(() -> { assertEquals("second", text("proposed").getText()); assertTrue(text("details").getText().contains("second / turn")); });
        assertEquals("old", Files.readString(first.root().resolve("source")));
    }

    private AgentReviewPanel.Scope scope(String name) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name));
        WorkspaceDocumentEditor source = new WorkspaceDocumentEditor() {
            public List<WorkspaceDocument> snapshots() { return List.of(); }
            public boolean connected() { return false; }
        };
        return new AgentReviewPanel.Scope(root, temporary.resolve("sessions"), source);
    }
    private WorkspaceProposalService.Input input(AgentReviewPanel.Scope scope, String file, String text) {
        var queries = new WorkspaceQueryService(scope.root(), AgentPolicy.readOnly(), scope.documents());
        return new WorkspaceProposalService.Input(file, queries.snapshot(queries.workspaceId(), file).version(), text);
    }
    private void open() throws Exception {
        if (jobs == null) jobs = new AsyncJobService(50);
        edt(() -> {
            panel = new AgentReviewPanel(jobs, current::get, () -> new Font(Font.DIALOG, Font.PLAIN, 13), () -> new Font(Font.MONOSPACED, Font.PLAIN, 13));
            panel.refresh();
        });
    }
    private void ready() throws Exception { await(() -> button("apply").isEnabled()); }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> done = new AtomicReference<>(); edt(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(10);
        }
        edt(() -> fail("Review did not reach expected state: " + label().getText()));
    }
    private JButton button(String name) { return (JButton) find(panel.component(), "agent." + name); }
    private JTextArea text(String name) { return (JTextArea) find(panel.component(), "agent." + name); }
    private JLabel label() { return (JLabel) find(panel.component(), "agent.status"); }
    private JComboBox<?> combo() { return (JComboBox<?>) find(panel.component(), "agent.documents"); }
    private JTable table() { return (JTable) find(panel.component(), "agent.hunks"); }
    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { Component found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static void edt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
}
