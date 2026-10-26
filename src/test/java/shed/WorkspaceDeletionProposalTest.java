package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceDeletionProposalTest {
    @TempDir Path temporary;

    @Test void deletionAndItsInverseRespectPermissionsAndProtectedPathsIncludingEmptyFiles() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        Path file = Files.writeString(root.resolve("empty"), ""); Files.writeString(root.resolve("README.md"), "keep");
        var queries = queries(root, WorkspaceDocumentSource.diskOnly()); var service = new WorkspaceProposalService(queries, null, history, "human");
        String protectedVersion = queries.snapshot(queries.workspaceId(), "README.md").version();
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class, () -> service.propose("protected", "t", "u",
            List.of(WorkspaceProposalService.Input.delete("README.md", protectedVersion)))).code());
        var readOnly = new WorkspaceProposalService(new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()), null,
            temporary.resolve("readonly-history"), "reader");
        assertEquals(WorkspaceApiException.Code.PERMISSION_DENIED, assertThrows(WorkspaceApiException.class, () -> readOnly.propose("r", "t", "u",
            List.of(WorkspaceProposalService.Input.delete("empty", "unknown")))).code());
        assertFalse(Files.exists(temporary.resolve("readonly-history")));
        String id = id(service.propose("empty", "t", "u", List.of(WorkspaceProposalService.Input.delete("empty", queries.snapshot(queries.workspaceId(), "empty").version()))));
        var applied = service.apply(id, 0); assertFalse(Files.exists(file));
        var tightened = new WorkspaceQueryService(root, new AgentPolicy(queries.policy().capabilities(), List.of("empty", "**/README.md"), List.of(), true), WorkspaceDocumentSource.diskOnly());
        var reviewer = new WorkspaceProposalService(tightened, null, history, "human");
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class, () -> reviewer.undo(id, revision(applied))).code());
        assertFalse(Files.exists(file)); service.undo(id, revision(applied)); assertEquals(0, Files.size(file));
        assertEquals("keep", Files.readString(root.resolve("README.md")));
    }

    @Test void mixedDeletionEditsAndCreationUndoRestoreOriginalBytesPermissionsAndIdentity() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        Path source = root.resolve("source"); byte[] original = TextFileCodec.encodeRaw("one\r\ntwo\n", "UTF-16LE", true);
        Files.write(source, original); Files.writeString(root.resolve("other"), "before\r\n");
        boolean posix = root.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rwxr-----"));
        var identity = WorkspaceEditTransaction.resourceIdentity(source);
        var queries = queries(root, WorkspaceDocumentSource.diskOnly()); var service = new WorkspaceProposalService(queries, null, history, "human");
        var inputs = List.of(WorkspaceProposalService.Input.delete("source", queries.snapshot(queries.workspaceId(), "source").version()),
            new WorkspaceProposalService.Input("other", queries.snapshot(queries.workspaceId(), "other").version(), "after\n"),
            WorkspaceProposalService.Input.create("created", "created\n"));
        var proposal = service.propose("r", "t", "u", inputs); String id = id(proposal);
        assertArrayEquals(original, Files.readAllBytes(source)); assertEquals("one\ntwo\n", service.preview(id, 0).before());
        assertTrue(service.preview(id, 0).hunks().isEmpty());
        assertEquals(WorkspaceApiException.Code.INVALID_REQUEST, assertThrows(WorkspaceApiException.class, () -> service.revise(id, 0, 0, null, Set.of())).code());
        var applied = service.apply(id, 0); assertFalse(Files.exists(source));
        assertEquals("APPLIED", service.propose("r", "t", "u", inputs).get("status"));
        assertEquals("APPLIED", service.apply(id, 0).get("status"));
        try (var paths = Files.list(root)) { assertEquals(Set.of("other", "created"), paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet())); }
        Path retained = retained(history, id); assertFalse(retained.startsWith(root.toRealPath()));
        assertEquals(identity, WorkspaceEditTransaction.resourceIdentity(retained));
        if (posix) assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(retained.getParent()));
        var reopened = new WorkspaceProposalService(queries, null, history, "human");
        assertEquals("UNDONE", reopened.undo(id, revision(applied)).get("status"));
        assertArrayEquals(original, Files.readAllBytes(source)); assertEquals(identity, WorkspaceEditTransaction.resourceIdentity(source));
        assertEquals("before\r\n", Files.readString(root.resolve("other"))); assertFalse(Files.exists(root.resolve("created")));
        if (posix) assertEquals(PosixFilePermissions.fromString("rwxr-----"), Files.getPosixFilePermissions(source));
        assertEquals("UNDONE", reopened.undo(id, revision(applied)).get("status"));
    }

    @Test void dirtyDraftIsReviewedSeparatelyPreservedOnDeleteAndReboundWithoutLosingLaterEdits() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source"), "saved\r\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("reviewed human draft\n"));
            var queries = queries(root, editor); var service = new WorkspaceProposalService(queries, editor, temporary.resolve("history"), "human");
            var before = editor.snapshots().getFirst(); String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.delete(before.documentId(), before.version()))));
            assertEquals("saved\n", service.preview(id, 0).before()); assertEquals("reviewed human draft\n", service.preview(id, 0).preservedDraft());
            var applied = service.apply(id, 0); assertTrue(buffer.isScratch()); assertTrue(buffer.isModified());
            assertEquals("reviewed human draft\n", buffer.getContent()); assertFalse(Files.exists(file));
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("later human work\n"));
            var undone = service.undo(id, revision(applied));
            assertEquals(before.documentId(), editor.snapshots().getFirst().documentId()); assertFalse(buffer.isScratch());
            assertEquals("later human work\n", buffer.getContent()); assertTrue(buffer.isModified());
            assertEquals(true, undone.get("pendingSaveAtApplication")); assertEquals("saved\r\n", Files.readString(file));
            assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());
            buffer.save(); assertEquals("later human work\r\n", Files.readString(file));
        }
    }

    @Test void staleDiskRequiresExplicitRebaseEvenWhenTheUnsavedBufferVersionIsUnchanged() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source"), "saved");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human draft"));
            var queries = queries(root, editor); var service = new WorkspaceProposalService(queries, editor, temporary.resolve("history"), "human");
            var before = editor.snapshots().getFirst(); String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.delete(before.documentId(), before.version()))));
            Files.writeString(file, "new disk contents");
            assertEquals(before.version(), editor.snapshots().getFirst().version());
            assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class, () -> service.apply(id, 0)).code());
            var rebased = service.rebase(id, 0); assertEquals("new disk contents", service.preview(id, 0).before());
            var applied = service.apply(id, revision(rebased)); service.undo(id, revision(applied));
            assertEquals("new disk contents", Files.readString(file)); assertEquals("human draft", buffer.getContent());
        }
    }

    @Test void occupiedDestinationsAndChangedRetainedOriginalsNeverGetOverwritten() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        Path file = Files.writeString(root.resolve("source"), "original"); var queries = queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, history, "human");
        String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.delete("source", queries.snapshot(queries.workspaceId(), "source").version()))));
        var applied = service.apply(id, 0); Files.writeString(file, "new occupant");
        assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> service.undo(id, revision(applied))).code());
        assertEquals("new occupant", Files.readString(file)); Files.delete(file);
        Path retained = retained(history, id); Files.writeString(retained, "writer changed the retained inode");
        assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> service.undo(id, revision(applied))).code());
        assertFalse(Files.exists(file)); assertEquals("writer changed the retained inode", Files.readString(retained));
    }

    @Test void undoDoesNotRetargetABufferTheHumanSavedElsewhereAfterDeletion() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source"), "original");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            var queries = queries(root, editor); var service = new WorkspaceProposalService(queries, editor, temporary.resolve("history"), "human");
            var before = editor.snapshots().getFirst(); String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.delete(before.documentId(), before.version()))));
            var applied = service.apply(id, 0); Path rescued = root.resolve("rescued");
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human saved elsewhere")); buffer.saveAs(rescued.toFile());
            service.undo(id, revision(applied));
            assertEquals(rescued.toFile(), buffer.getFile()); assertEquals("human saved elsewhere", Files.readString(rescued));
            assertEquals("original", Files.readString(file)); assertFalse(buffer.isModified());
        }
    }

    @Test void uncertainEditorDetachmentIsRecoverableWithoutLosingTheReviewedDirtyDraft() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        Path file = Files.writeString(root.resolve("source"), "saved\r\n"); FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human draft\n"));
            var queries = queries(root, editor);
            WorkspaceDocumentEditor uncertain = new WorkspaceDocumentEditor() {
                public List<WorkspaceDocument> snapshots() { return editor.snapshots(); }
                public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unbound) {
                    editor.applyTexts(changes, unbound);
                    throw new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Fixture: uncertain editor outcome");
                }
            };
            var interrupted = new WorkspaceProposalService(queries, uncertain, history, "human");
            var before = editor.snapshots().getFirst(); String id = id(interrupted.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.delete(before.documentId(), before.version()))));
            assertEquals(WorkspaceApiException.Code.RECOVERY_REQUIRED, assertThrows(WorkspaceApiException.class, () -> interrupted.apply(id, 0)).code());
            assertTrue(buffer.isScratch()); assertTrue(Files.exists(file));
            var service = new WorkspaceProposalService(queries, editor, history, "human"); var inspection = service.recovery(id);
            assertNull(inspection.get("verifiedOutcome")); assertEquals(true, inspection.get("restoreAvailable"));
            var restored = service.restore(id, revision(MiniJson.asObject(inspection.get("proposal"))), (String) inspection.get("fingerprint"));
            assertEquals("FAILED", restored.get("status")); assertFalse(buffer.isScratch()); assertTrue(buffer.isModified());
            assertEquals("human draft\n", buffer.getContent()); assertEquals("saved\r\n", Files.readString(file));
            var rebased = service.rebase(id, revision(restored)); var applied = service.apply(id, revision(rebased));
            assertEquals(WorkspaceApiException.Code.RECOVERY_REQUIRED, assertThrows(WorkspaceApiException.class, () -> interrupted.undo(id, revision(applied))).code());
            assertFalse(buffer.isScratch()); assertFalse(Files.exists(file));
            inspection = service.recovery(id); assertNull(inspection.get("verifiedOutcome")); assertEquals(true, inspection.get("restoreAvailable"));
            restored = service.restore(id, revision(MiniJson.asObject(inspection.get("proposal"))), (String) inspection.get("fingerprint"));
            assertEquals("APPLIED", restored.get("status")); assertTrue(buffer.isScratch()); assertEquals("human draft\n", buffer.getContent());
            service.undo(id, revision(restored)); assertFalse(buffer.isScratch()); assertTrue(buffer.isModified());
            assertEquals("saved\r\n", Files.readString(file)); assertEquals("human draft\n", buffer.getContent());
        }
    }

    @Test void actualProcessInterruptionsRestoreBothDeletionAndItsUndoAndPermitRetry() throws Exception {
        for (String phase : List.of("apply", "undo")) {
            Path root = Files.createDirectory(temporary.resolve(phase)), history = temporary.resolve(phase + "-history");
            byte[] bytes = TextFileCodec.encodeRaw("saved\r\n", "UTF-16LE", true); Files.write(root.resolve("source"), bytes);
            String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            var process = new ProcessBuilder(executable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                "shed.WorkspaceDeleteCrashFixture", root.toString(), history.toString(), phase).redirectErrorStream(true).start();
            try { assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(77, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
            var service = new WorkspaceProposalService(queries(root, WorkspaceDocumentSource.diskOnly()), null, history, "human");
            var proposal = service.list(0, 10).getFirst(); String id = id(proposal); var inspection = service.recovery(id);
            assertEquals(phase.equals("apply") ? "APPLIED" : "UNDONE", inspection.get("verifiedOutcome"));
            assertEquals(true, inspection.get("restoreAvailable"));
            var restored = service.restore(id, revision(proposal), (String) inspection.get("fingerprint"));
            if (phase.equals("apply")) {
                assertEquals("FAILED", restored.get("status")); assertArrayEquals(bytes, Files.readAllBytes(root.resolve("source")));
                var rebased = service.rebase(id, revision(restored)); restored = service.apply(id, revision(rebased));
            } else { assertEquals("APPLIED", restored.get("status")); assertFalse(Files.exists(root.resolve("source"))); }
            service.undo(id, revision(restored)); assertArrayEquals(bytes, Files.readAllBytes(root.resolve("source")));
        }
    }

    static WorkspaceQueryService queries(Path root, WorkspaceDocumentSource source) {
        return new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY),
            List.of("**/README.md"), List.of(), true), source);
    }
    private static Path retained(Path history, String id) throws Exception {
        try (var lease = new WorkspaceChangeJournal(history).open(false)) {
            return MiniJson.asArray(lease.read(id).data().get("resourceSteps")).stream().map(MiniJson::asObject)
                .filter(step -> "BACKUP".equals(step.get("kind"))).map(step -> Path.of(java.net.URI.create((String) step.get("target")))).findFirst().orElseThrow();
        }
    }
    private static String id(Map<String, Object> value) { return (String) value.get("id"); }
    private static int revision(Map<String, Object> value) { return ((Number) value.get("revision")).intValue(); }
}
