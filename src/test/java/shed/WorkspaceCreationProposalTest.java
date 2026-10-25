package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceCreationProposalTest {
    @TempDir Path temporary;

    @Test void mixedCreationAndTextChangesAreReviewedPersistedAndUndoneTogether() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), state = temporary.resolve("state");
        Path existing = Files.writeString(root.resolve("existing"), "old\r\n");
        var queries = queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, state, "human");
        var inputs = List.of(WorkspaceProposalService.Input.create("new/source", "draft\n"),
            new WorkspaceProposalService.Input("existing", queries.snapshot(queries.workspaceId(), "existing").version(), "changed\n"));
        var proposal = service.propose("r", "task", "turn", inputs); String id = (String) proposal.get("id");
        assertFalse(Files.exists(root.resolve("new"))); assertEquals("old\r\n", Files.readString(existing));
        assertEquals("CREATE", service.preview(id, 0).document().get("kind"));
        assertEquals("", service.preview(id, 0).before()); assertEquals("draft\n", service.preview(id, 0).proposed());
        var revised = service.revise(id, revision(proposal), 0, "reviewed\n", null);
        var applied = service.apply(id, revision(revised));
        assertEquals("reviewed\n", Files.readString(root.resolve("new/source")));
        assertEquals("changed\r\n", Files.readString(existing)); assertEquals(false, applied.get("pendingSaveAtApplication"));
        assertEquals("APPLIED", service.apply(id, revision(revised)).get("status"));
        assertEquals("APPLIED", service.propose("r", "task", "turn", inputs).get("status"));
        service = new WorkspaceProposalService(queries, null, state, "human");
        assertEquals("UNDONE", service.undo(id, revision(applied)).get("status"));
        assertFalse(Files.exists(root.resolve("new/source"))); assertEquals("old\r\n", Files.readString(existing));
        assertEquals("UNDONE", service.undo(id, revision(applied)).get("status"));
    }

    @Test void emptyFilesAndDeselectedTextStillHaveAnExplicitCreationOperation() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        var service = new WorkspaceProposalService(queries(root, WorkspaceDocumentSource.diskOnly()), null, temporary.resolve("state"), "human");
        var proposal = service.propose("empty", "task", "turn", List.of(WorkspaceProposalService.Input.create("empty", "draft")));
        String id = (String) proposal.get("id"); var reviewed = service.revise(id, 0, 0, null, Set.of());
        var applied = service.apply(id, revision(reviewed)); assertEquals(0, Files.size(root.resolve("empty")));
        service.undo(id, revision(applied)); assertFalse(Files.exists(root.resolve("empty")));
    }

    @Test void anOccupiedOrProtectedTargetFailsBeforeAnyOtherFileIsMutated() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        Path existing = Files.writeString(root.resolve("existing"), "old");
        var queries = queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, temporary.resolve("state"), "human");
        var proposal = service.propose("r", "task", "turn", List.of(
            new WorkspaceProposalService.Input("existing", queries.snapshot(queries.workspaceId(), "existing").version(), "agent"),
            WorkspaceProposalService.Input.create("new", "agent")));
        Files.writeString(root.resolve("new"), "human");
        assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> service.apply((String) proposal.get("id"), 0)).code());
        assertEquals("old", Files.readString(existing)); assertEquals("human", Files.readString(root.resolve("new")));
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class, () -> service.propose("protected", "t", "u",
            List.of(WorkspaceProposalService.Input.create("README.md", "agent")))).code());
        assertFalse(Files.exists(root.resolve("README.md")));
        assertEquals(WorkspaceApiException.Code.INVALID_REQUEST, assertThrows(WorkspaceApiException.class, () -> service.propose("duplicates", "t", "u",
            List.of(WorkspaceProposalService.Input.create("same", "a"), WorkspaceProposalService.Input.create("./same", "b")))).code());
        assertEquals(WorkspaceApiException.Code.INVALID_REQUEST, assertThrows(WorkspaceApiException.class, () -> service.propose("ancestor", "t", "u",
            List.of(WorkspaceProposalService.Input.create("parent", "a"), WorkspaceProposalService.Input.create("parent/child", "b")))).code());
        assertFalse(Files.exists(root.resolve("parent")));
    }

    @Test void undoPreservesChangedFilesAndDetachesAnUnchangedOpenFileWithoutClosingIt() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); List<FileBuffer> buffers = new ArrayList<>();
        try (var editor = new EditorWorkspaceDocuments(() -> buffers, () -> List.of(root), () -> root, root)) {
            var queries = queries(root, editor); var service = new WorkspaceProposalService(queries, editor, temporary.resolve("state"), "human");
            var proposal = service.propose("r", "task", "turn", List.of(WorkspaceProposalService.Input.create("new", "created\n")));
            String id = (String) proposal.get("id"); var applied = service.apply(id, 0);
            FileBuffer buffer = new FileBuffer(root.resolve("new").toFile()); SwingUtilities.invokeAndWait(() -> buffers.add(buffer));
            var before = editor.snapshots().getFirst();
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human work\n"));
            assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> service.undo(id, revision(applied))).code());
            assertEquals("human work\n", buffer.getContent()); assertTrue(Files.exists(root.resolve("new")));
            var dirty = editor.snapshots().getFirst();
            editor.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(dirty, "created\n", WorkspaceDiskBinding.capture(queries.workspaceId(), root.resolve("new")))));
            var undone = service.undo(id, revision(applied));
            assertEquals("UNDONE", undone.get("status")); assertEquals(true, undone.get("pendingSaveAtApplication"));
            assertEquals(before.documentId(), editor.snapshots().getFirst().documentId());
            assertTrue(buffer.isScratch()); assertTrue(buffer.isModified()); assertEquals("created\n", buffer.getContent());
            assertFalse(Files.exists(root.resolve("new"))); assertThrows(java.io.IOException.class, buffer::save);
        }
    }

    @Test void aTabOpenedDuringCreationInvalidatesTheBatchAndRollsBackTheNewFile() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); List<FileBuffer> buffers = new ArrayList<>();
        try (var editor = new EditorWorkspaceDocuments(() -> buffers, () -> List.of(root), () -> root, root)) {
            WorkspaceDocumentEditor racing = new WorkspaceDocumentEditor() {
                public List<WorkspaceDocument> snapshots() { return editor.snapshots(); }
                public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unbound) {
                    try { FileBuffer newlyOpened = new FileBuffer(root.resolve("new").toFile()); SwingUtilities.invokeAndWait(() -> buffers.add(newlyOpened)); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                    return editor.applyTexts(changes, unbound);
                }
            };
            var service = new WorkspaceProposalService(queries(root, racing), racing, temporary.resolve("state"), "human");
            var proposal = service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.create("new", "created")));
            assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class, () -> service.apply((String) proposal.get("id"), 0)).code());
            assertFalse(Files.exists(root.resolve("new"))); assertEquals("created", buffers.getFirst().getContent());
        }
    }

    @Test void undoRefusesAnIdenticalReplacementFileAndAnUncertainDetachCanBeRecovered() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), state = temporary.resolve("state");
        List<FileBuffer> buffers = new ArrayList<>();
        try (var editor = new EditorWorkspaceDocuments(() -> buffers, () -> List.of(root), () -> root, root)) {
            var queries = queries(root, editor); var service = new WorkspaceProposalService(queries, editor, state, "human");
            String replaced = (String) service.propose("replaced", "t", "u", List.of(WorkspaceProposalService.Input.create("replaced", "same"))).get("id");
            var applied = service.apply(replaced, 0);
            Files.move(root.resolve("replaced"), root.resolve("retained-original")); Files.writeString(root.resolve("replaced"), "same");
            assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> service.undo(replaced, revision(applied))).code());
            assertEquals("same", Files.readString(root.resolve("replaced")));

            String id = (String) service.propose("detach", "t", "u", List.of(WorkspaceProposalService.Input.create("new", "created\n"))).get("id");
            var created = service.apply(id, 0);
            FileBuffer buffer = new FileBuffer(root.resolve("new").toFile()); SwingUtilities.invokeAndWait(() -> buffers.add(buffer));
            WorkspaceDocumentEditor uncertain = new WorkspaceDocumentEditor() {
                public List<WorkspaceDocument> snapshots() { return editor.snapshots(); }
                public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unbound) {
                    editor.applyTexts(changes, unbound);
                    throw new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Fixture: editor outcome uncertain");
                }
            };
            var interrupted = new WorkspaceProposalService(queries, uncertain, state, "human");
            assertEquals(WorkspaceApiException.Code.RECOVERY_REQUIRED, assertThrows(WorkspaceApiException.class, () -> interrupted.undo(id, revision(created))).code());
            assertTrue(buffer.isScratch()); assertTrue(Files.exists(root.resolve("new")), "Filesystem rollback restored the original while the editor detached");
            var inspection = service.recovery(id); assertNull(inspection.get("verifiedOutcome")); assertEquals(true, inspection.get("restoreAvailable"));
            var restored = service.restore(id, revision(MiniJson.asObject(inspection.get("proposal"))), (String) inspection.get("fingerprint"));
            assertEquals("APPLIED", restored.get("status")); assertFalse(buffer.isScratch()); assertFalse(buffer.isModified());
            assertEquals("created\n", buffer.getContent()); assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());
            service.undo(id, revision(restored)); assertTrue(buffer.isScratch()); assertFalse(Files.exists(root.resolve("new")));
        }
    }

    @Test void aHumanEditDuringUndoRestoresTheFileAndLeavesTheAppliedProposalRetryable() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), state = temporary.resolve("state");
        List<FileBuffer> buffers = new ArrayList<>();
        try (var editor = new EditorWorkspaceDocuments(() -> buffers, () -> List.of(root), () -> root, root)) {
            var queries = queries(root, editor); var service = new WorkspaceProposalService(queries, editor, state, "human");
            String id = (String) service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.create("new", "created\n"))).get("id");
            var applied = service.apply(id, 0);
            FileBuffer buffer = new FileBuffer(root.resolve("new").toFile()); SwingUtilities.invokeAndWait(() -> buffers.add(buffer));
            WorkspaceDocumentEditor racing = new WorkspaceDocumentEditor() {
                public List<WorkspaceDocument> snapshots() { return editor.snapshots(); }
                public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unbound) {
                    assertFalse(Files.exists(root.resolve("new")));
                    try { SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human draft\n")); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                    return editor.applyTexts(changes, unbound);
                }
            };
            var interrupted = new WorkspaceProposalService(queries, racing, state, "human");
            assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class, () -> interrupted.undo(id, revision(applied))).code());
            assertEquals("created\n", Files.readString(root.resolve("new"))); assertEquals("human draft\n", buffer.getContent());
            assertTrue(buffer.hasFilePath()); assertTrue(buffer.isModified());
            var retry = service.preview(id, 0).proposal(); assertEquals("APPLIED", retry.get("status"));
            var current = editor.snapshots().getFirst();
            editor.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(current, "created\n", WorkspaceDiskBinding.capture(queries.workspaceId(), root.resolve("new")))));
            assertEquals("UNDONE", service.undo(id, revision(retry)).get("status")); assertFalse(Files.exists(root.resolve("new")));
        }
    }

    @Test void realProcessInterruptionsSupportInspectionRestorationAndUndoRetry() throws Exception {
        for (String phase : List.of("apply", "undo")) {
            Path root = Files.createDirectory(temporary.resolve(phase)), state = temporary.resolve(phase + "-state");
            String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            var process = new ProcessBuilder(executable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                "shed.WorkspaceCreateCrashFixture", root.toString(), state.toString(), phase).redirectErrorStream(true).start();
            try { assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(77, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
            var service = new WorkspaceProposalService(queries(root, WorkspaceDocumentSource.diskOnly()), null, state, "human");
            var proposal = service.list(0, 10).getFirst(); String id = (String) proposal.get("id");
            var inspection = service.recovery(id);
            assertEquals(phase.equals("apply") ? "APPLIED" : "UNDONE", inspection.get("verifiedOutcome"));
            assertEquals(true, inspection.get("restoreAvailable"));
            var restored = service.restore(id, revision(proposal), (String) inspection.get("fingerprint"));
            if (phase.equals("apply")) {
                assertEquals("FAILED", restored.get("status")); assertFalse(Files.exists(root.resolve("new/source")));
                var rebased = service.rebase(id, revision(restored)); service.apply(id, revision(rebased));
                assertEquals("created\n", Files.readString(root.resolve("new/source")));
            } else {
                assertEquals("APPLIED", restored.get("status")); assertEquals("created\n", Files.readString(root.resolve("new/source")));
                service.undo(id, revision(restored)); assertFalse(Files.exists(root.resolve("new/source")));
            }
        }
    }

    private static WorkspaceQueryService queries(Path root, WorkspaceDocumentSource source) {
        return new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY),
            List.of("**/README.md"), List.of(), true), source);
    }
    private static int revision(Map<String, Object> value) { return ((Number) value.get("revision")).intValue(); }
}
