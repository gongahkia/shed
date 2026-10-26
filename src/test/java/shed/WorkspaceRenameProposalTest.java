package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRenameProposalTest {
    @TempDir Path temporary;

    @Test void mixedProposalPreservesBytesIdentityAndPermissionsAndHasDurableUndo() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        byte[] bytes = TextFileCodec.encodeRaw("one\r\ntwo\n", "UTF-16LE", true);
        Path source = Files.write(root.resolve("source"), bytes), target = root.resolve("nested/renamed");
        if (root.getFileSystem().supportedFileAttributeViews().contains("posix"))
            Files.setPosixFilePermissions(source, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-----"));
        var identity = WorkspaceEditTransaction.resourceIdentity(source);
        Files.writeString(root.resolve("other"), "before\r\n"); Files.writeString(root.resolve("deleted"), "remove");
        var queries = WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, history, "human");
        var inputs = List.of(WorkspaceProposalService.Input.rename("source", queries.snapshot(queries.workspaceId(), "source").version(), "nested/renamed"),
            new WorkspaceProposalService.Input("other", queries.snapshot(queries.workspaceId(), "other").version(), "after\n"),
            WorkspaceProposalService.Input.delete("deleted", queries.snapshot(queries.workspaceId(), "deleted").version()),
            WorkspaceProposalService.Input.create("created", "new"));
        var proposal = service.propose("r", "t", "u", inputs); String id = id(proposal);
        assertArrayEquals(bytes, Files.readAllBytes(source)); assertFalse(Files.exists(target));
        var preview = service.preview(id, 0); assertEquals("one\ntwo\n", preview.before()); assertEquals(preview.before(), preview.proposed());
        assertTrue(preview.hunks().isEmpty());
        assertEquals(WorkspaceApiException.Code.INVALID_REQUEST, assertThrows(WorkspaceApiException.class, () -> service.revise(id, 0, 0, "changed", null)).code());
        var applied = service.apply(id, 0); assertEquals("APPLIED", service.apply(id, 0).get("status"));
        assertFalse(Files.exists(source)); assertArrayEquals(bytes, Files.readAllBytes(target)); assertEquals(identity, WorkspaceEditTransaction.resourceIdentity(target));
        assertEquals("APPLIED", service.propose("r", "t", "u", inputs).get("status"));
        if (root.getFileSystem().supportedFileAttributeViews().contains("posix"))
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-----"), Files.getPosixFilePermissions(target));
        var reopened = new WorkspaceProposalService(queries, null, history, "human");
        assertEquals("UNDONE", reopened.undo(id, revision(applied)).get("status")); assertFalse(Files.exists(target));
        assertArrayEquals(bytes, Files.readAllBytes(source)); assertEquals(identity, WorkspaceEditTransaction.resourceIdentity(source));
        assertEquals("before\r\n", Files.readString(root.resolve("other"))); assertEquals("remove", Files.readString(root.resolve("deleted")));
        assertFalse(Files.exists(root.resolve("created")));
    }

    @Test void unsavedDraftFollowsRenameAndLaterEditorSavesAndDraftsSurviveUndo() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), source = Files.writeString(root.resolve("source"), "saved\r\n");
        FileBuffer buffer = new FileBuffer(source.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("reviewed draft\n"));
            var queries = WorkspaceDeletionProposalTest.queries(root, editor);
            var service = new WorkspaceProposalService(queries, editor, temporary.resolve("history"), "human");
            var before = editor.snapshots().getFirst();
            String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.rename(before.documentId(), before.version(), "renamed"))));
            assertEquals("saved\n", service.preview(id, 0).before()); assertEquals("reviewed draft\n", service.preview(id, 0).preservedDraft());
            var applied = service.apply(id, 0); Path target = root.resolve("renamed");
            assertEquals(target.toRealPath().toFile(), buffer.getFile()); assertEquals(before.documentId(), editor.snapshots().getFirst().documentId());
            assertEquals("saved\r\n", Files.readString(target)); assertEquals("reviewed draft\n", buffer.getContent()); assertTrue(buffer.isModified());
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("later saved work\n")); buffer.save();
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("latest unsaved work\n"));
            service.undo(id, revision(applied)); assertFalse(Files.exists(target));
            assertEquals(source.toRealPath().toFile(), buffer.getFile()); assertEquals("later saved work\r\n", Files.readString(source));
            assertEquals("latest unsaved work\n", buffer.getContent()); assertTrue(buffer.isModified());
            assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());
        }
    }

    @Test void externalReplacementOrOccupiedOriginalCannotBeOverwrittenByUndo() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), source = Files.writeString(root.resolve("source"), "original");
        FileBuffer buffer = new FileBuffer(source.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            var queries = WorkspaceDeletionProposalTest.queries(root, editor); var service = new WorkspaceProposalService(queries, editor, temporary.resolve("history"), "human");
            var before = editor.snapshots().getFirst(); String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.rename(before.documentId(), before.version(), "renamed"))));
            var applied = service.apply(id, 0); Path target = root.resolve("renamed"); Files.writeString(source, "human occupant");
            assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> service.undo(id, revision(applied))).code());
            assertEquals("human occupant", Files.readString(source)); Files.delete(source);
            Files.move(target, root.resolve("original-inode")); Files.writeString(target, "original");
            assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> service.undo(id, revision(applied))).code());
            assertFalse(Files.exists(source)); assertEquals("original", Files.readString(target));
        }
    }

    @Test void renameCannotSilentlyTransferAnOpenBufferToAnotherConfiguredWorkspace() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), nested = Files.createDirectory(root.resolve("nested"));
        Path source = Files.writeString(root.resolve("source"), "original"); FileBuffer buffer = new FileBuffer(source.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root, nested), () -> root, root)) {
            var queries = WorkspaceDeletionProposalTest.queries(root, editor); var service = new WorkspaceProposalService(queries, editor, temporary.resolve("history"), "human");
            var before = editor.snapshots().getFirst(); String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.rename(before.documentId(), before.version(), "nested/renamed"))));
            assertEquals(WorkspaceApiException.Code.OUTSIDE_WORKSPACE, assertThrows(WorkspaceApiException.class, () -> service.apply(id, 0)).code());
            assertEquals("original", Files.readString(source)); assertFalse(Files.exists(nested.resolve("renamed")));
            assertEquals(before.workspaceId(), editor.snapshots().getFirst().workspaceId()); assertEquals(source.toFile(), buffer.getFile());
        }
    }

    @Test void replacingAReviewedParentWithAnAliasRequiresAnotherReview() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), parent = Files.createDirectory(root.resolve("destination"));
        Files.writeString(root.resolve("source"), "original"); Files.createDirectory(root.resolve("different"));
        var queries = WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, temporary.resolve("history"), "human");
        String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.rename("source", queries.snapshot(queries.workspaceId(), "source").version(), "destination/renamed"))));
        Files.move(parent, root.resolve("old-parent"));
        try { Files.createSymbolicLink(parent, root.resolve("different")); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) { org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable"); }
        assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class, () -> service.apply(id, 0)).code());
        assertEquals("original", Files.readString(root.resolve("source"))); assertFalse(Files.exists(root.resolve("different/renamed")));
    }

    @Test void concurrentDestinationClaimRollsBackTheBatchWithoutOverwritingTheWriter() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); Files.writeString(root.resolve("source"), "original");
        var queries = WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, temporary.resolve("history"), "human", step -> {
            if (step.kind().equals("RENAME_FILE")) Files.writeString(step.target(), "concurrent writer");
        });
        String id = id(service.propose("r", "t", "u", List.of(
            WorkspaceProposalService.Input.create("created", "new"),
            WorkspaceProposalService.Input.rename("source", queries.snapshot(queries.workspaceId(), "source").version(), "renamed"))));
        assertEquals(WorkspaceApiException.Code.IO_ERROR, assertThrows(WorkspaceApiException.class, () -> service.apply(id, 0)).code());
        assertEquals("original", Files.readString(root.resolve("source"))); assertEquals("concurrent writer", Files.readString(root.resolve("renamed")));
        assertFalse(Files.exists(root.resolve("created"))); assertEquals("FAILED", service.list(0, 10).getFirst().get("status"));
    }

    @Test void bothPathsAreAuthorizedAndChangesRequireExplicitRebase() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); Path source = Files.writeString(root.resolve("source"), "original");
        var queries = WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, temporary.resolve("history"), "human");
        String version = queries.snapshot(queries.workspaceId(), "source").version();
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class, () -> service.propose("protected", "t", "u",
            List.of(WorkspaceProposalService.Input.rename("source", version, "README.md")))).code());
        assertEquals(WorkspaceApiException.Code.INVALID_REQUEST, assertThrows(WorkspaceApiException.class, () -> service.propose("overlap", "t", "u", List.of(
            WorkspaceProposalService.Input.rename("source", version, "nested/renamed"), WorkspaceProposalService.Input.create("nested", "other")))).code());
        String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.rename("source", version, "renamed"))));
        Files.writeString(source, "human update");
        assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class, () -> service.apply(id, 0)).code());
        var rebased = service.rebase(id, 0); assertEquals("human update", service.preview(id, 0).before());
        var tightened = new WorkspaceQueryService(root, new AgentPolicy(queries.policy().capabilities(), List.of("renamed"), List.of(), true), WorkspaceDocumentSource.diskOnly());
        var reviewer = new WorkspaceProposalService(tightened, null, temporary.resolve("history"), "human");
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class, () -> reviewer.apply(id, revision(rebased))).code());
        var applied = service.apply(id, revision(rebased)); Files.writeString(root.resolve("renamed"), "later in-place update");
        if (WorkspaceEditTransaction.resourceIdentity(root.resolve("renamed")).get("key").equals("")) {
            // Java's Windows provider does not expose a stable file key. Without an editor
            // binding, changed bytes cannot be proven to belong to the original file.
            assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class,
                () -> service.undo(id, revision(applied))).code());
            assertFalse(Files.exists(source)); assertEquals("later in-place update", Files.readString(root.resolve("renamed")));
        } else {
            service.undo(id, revision(applied)); assertEquals("later in-place update", Files.readString(source));
        }
    }

    @Test void uncertainEditorOutcomeRestoresTheBindingInBothDirections() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        Path source = Files.writeString(root.resolve("source"), "saved\r\n"); FileBuffer buffer = new FileBuffer(source.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human draft\n"));
            var queries = WorkspaceDeletionProposalTest.queries(root, editor);
            WorkspaceDocumentEditor uncertain = new WorkspaceDocumentEditor() {
                public List<WorkspaceDocument> snapshots() { return editor.snapshots(); }
                public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unbound) {
                    editor.applyTexts(changes, unbound); throw new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Uncertain fixture");
                }
            };
            var interrupted = new WorkspaceProposalService(queries, uncertain, history, "human");
            var service = new WorkspaceProposalService(queries, editor, history, "human");
            var before = editor.snapshots().getFirst(); String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.rename(before.documentId(), before.version(), "renamed"))));
            assertEquals(WorkspaceApiException.Code.RECOVERY_REQUIRED, assertThrows(WorkspaceApiException.class, () -> interrupted.apply(id, 0)).code());
            var inspection = service.recovery(id); assertNull(inspection.get("verifiedOutcome")); assertEquals(true, inspection.get("restoreAvailable"));
            var restored = service.restore(id, revision(MiniJson.asObject(inspection.get("proposal"))), (String) inspection.get("fingerprint"));
            assertEquals("FAILED", restored.get("status")); assertEquals(source.toRealPath().toFile(), buffer.getFile());
            var rebased = service.rebase(id, revision(restored)); var applied = service.apply(id, revision(rebased));
            assertEquals(WorkspaceApiException.Code.RECOVERY_REQUIRED, assertThrows(WorkspaceApiException.class, () -> interrupted.undo(id, revision(applied))).code());
            inspection = service.recovery(id); assertNull(inspection.get("verifiedOutcome")); assertEquals(true, inspection.get("restoreAvailable"));
            restored = service.restore(id, revision(MiniJson.asObject(inspection.get("proposal"))), (String) inspection.get("fingerprint"));
            assertEquals("APPLIED", restored.get("status")); assertEquals(root.resolve("renamed").toRealPath().toFile(), buffer.getFile());
            service.undo(id, revision(restored)); assertEquals("saved\r\n", Files.readString(source));
            assertEquals("human draft\n", buffer.getContent()); assertTrue(buffer.isModified());
        }
    }

    @Test void recoveryRecognizesAnOriginalEditorOpenedThroughAWorkspaceAlias() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), alias = temporary.resolve("workspace-alias"), history = temporary.resolve("history");
        Files.writeString(root.resolve("source"), "saved");
        try { Files.createSymbolicLink(alias, root); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) { org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable"); }
        FileBuffer buffer = new FileBuffer(alias.resolve("source").toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            var queries = WorkspaceDeletionProposalTest.queries(root, editor);
            WorkspaceDocumentEditor uncertain = new WorkspaceDocumentEditor() {
                public List<WorkspaceDocument> snapshots() { return editor.snapshots(); }
                public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unbound) {
                    throw new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Fixture: caller cannot establish whether editor ran");
                }
            };
            var service = new WorkspaceProposalService(queries, uncertain, history, "human");
            var before = editor.snapshots().getFirst(); String id = id(service.propose("r", "t", "u", List.of(WorkspaceProposalService.Input.rename(before.documentId(), before.version(), "renamed"))));
            assertEquals(WorkspaceApiException.Code.RECOVERY_REQUIRED, assertThrows(WorkspaceApiException.class, () -> service.apply(id, 0)).code());
            var inspection = service.recovery(id); assertEquals("FAILED", inspection.get("verifiedOutcome")); assertEquals(true, inspection.get("restoreAvailable"));
            assertEquals("saved", buffer.getContent()); assertEquals("saved", Files.readString(root.resolve("source"))); assertFalse(Files.exists(root.resolve("renamed")));
        }
    }

    @Test void actualCrashesDuringApplyUndoAndLinkedRestoreRemainRecoverable() throws Exception {
        for (String phase : List.of("apply", "undo", "linked-apply", "linked-undo", "restore-linked")) {
            Path root = Files.createDirectory(temporary.resolve(phase)), history = temporary.resolve(phase + "-history");
            byte[] bytes = TextFileCodec.encodeRaw("saved\r\n", "UTF-16LE", true); Files.write(root.resolve("source"), bytes);
            crash(root, history, phase.equals("restore-linked") ? "apply" : phase);
            if (phase.equals("restore-linked")) crash(root, history, phase);
            var service = new WorkspaceProposalService(WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly()), null, history, "human");
            var proposal = service.list(0, 10).getFirst(); String id = id(proposal); var inspection = service.recovery(id);
            assertEquals(true, inspection.get("restoreAvailable"), phase + " " + inspection);
            if (phase.contains("linked")) assertNull(inspection.get("verifiedOutcome"));
            else assertEquals(phase.equals("undo") ? "UNDONE" : "APPLIED", inspection.get("verifiedOutcome"));
            var restored = service.restore(id, revision(proposal), (String) inspection.get("fingerprint"));
            assertEquals(restored, service.restore(id, revision(proposal), (String) inspection.get("fingerprint")));
            if (phase.contains("undo")) {
                assertEquals("APPLIED", restored.get("status")); assertFalse(Files.exists(root.resolve("source")));
                service.undo(id, revision(restored));
            } else {
                assertEquals("FAILED", restored.get("status"));
                var rebased = service.rebase(id, revision(restored)); var applied = service.apply(id, revision(rebased));
                service.undo(id, revision(applied));
            }
            assertArrayEquals(bytes, Files.readAllBytes(root.resolve("source"))); assertFalse(Files.exists(root.resolve("renamed")));
        }
    }

    @Test void changedRecoveryEvidenceCannotOverwriteHumanWork() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        Files.writeString(root.resolve("source"), "original"); crash(root, history, "linked-apply");
        var service = new WorkspaceProposalService(WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly()), null, history, "human");
        var proposal = service.list(0, 10).getFirst(); String id = id(proposal); var inspection = service.recovery(id);
        Files.delete(root.resolve("renamed")); Files.writeString(root.resolve("renamed"), "human replacement");
        assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class,
            () -> service.restore(id, revision(proposal), (String) inspection.get("fingerprint"))).code());
        assertEquals(false, service.recovery(id).get("restoreAvailable"));
        assertEquals("original", Files.readString(root.resolve("source"))); assertEquals("human replacement", Files.readString(root.resolve("renamed")));
    }

    @Test void reconciliationChecksDestinationPermissionsAndPreservesUndoEvidence() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        Files.writeString(root.resolve("source"), "original"); crash(root, history, "apply");
        var queries = WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, history, "human");
        var proposal = service.list(0, 10).getFirst(); String id = id(proposal); var inspection = service.recovery(id);
        var tightened = new WorkspaceQueryService(root, new AgentPolicy(queries.policy().capabilities(), List.of("renamed"), List.of(), true), WorkspaceDocumentSource.diskOnly());
        var reviewer = new WorkspaceProposalService(tightened, null, history, "human");
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class,
            () -> reviewer.reconcile(id, revision(proposal), (String) inspection.get("fingerprint"))).code());
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class,
            () -> reviewer.restore(id, revision(proposal), (String) inspection.get("fingerprint"))).code());
        var reconciled = service.reconcile(id, revision(proposal), (String) inspection.get("fingerprint"));
        assertEquals("APPLIED", reconciled.get("status")); service.undo(id, revision(reconciled));
        assertEquals("original", Files.readString(root.resolve("source"))); assertFalse(Files.exists(root.resolve("renamed")));
    }

    @Test void ambiguousLinkOwnershipDoesNotAuthorizeRemovingAnAlias() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        Files.writeString(root.resolve("source"), "original"); crash(root, history, "unrecorded-link");
        var service = new WorkspaceProposalService(WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly()), null, history, "human");
        var proposal = service.list(0, 10).getFirst(); String id = id(proposal); var inspection = service.recovery(id);
        assertTrue(Files.isSameFile(root.resolve("source"), root.resolve("renamed")));
        assertNull(inspection.get("verifiedOutcome")); assertEquals(false, inspection.get("restoreAvailable"));
        assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class,
            () -> service.restore(id, revision(proposal), (String) inspection.get("fingerprint"))).code());
        assertEquals("original", Files.readString(root.resolve("source"))); assertEquals("original", Files.readString(root.resolve("renamed")));
    }

    static void crash(Path root, Path history, String phase) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var process = new ProcessBuilder(executable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            "shed.WorkspaceRenameCrashFixture", root.toString(), history.toString(), phase).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(77, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static String id(Map<String, Object> value) { return (String) value.get("id"); }
    private static int revision(Map<String, Object> value) { return ((Number) value.get("revision")).intValue(); }
}
