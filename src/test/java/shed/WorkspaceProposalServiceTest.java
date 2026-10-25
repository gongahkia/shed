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

class WorkspaceProposalServiceTest {
    @TempDir Path temporary;

    @Test void persistentProposalsSupportHunkReviewIdempotentApplyAndUndoWithLaterHumanEdits() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("state");
        Path file = root.resolve("source");
        Files.write(file, TextFileCodec.encodeRaw("first\r\nmiddle\nlast\r", "UTF-16LE", true));
        var source = WorkspaceDocumentSource.diskOnly();
        var queries = new WorkspaceQueryService(root, policy(false), source);
        var agent = new WorkspaceProposalService(queries, null, storage, "external-agent");
        var base = queries.snapshot(queries.workspaceId(), file.toString());
        var input = List.of(new WorkspaceProposalService.Input(file.toString(), base.version(), "FIRST\nmiddle\nLAST\n"));
        var proposal = agent.propose("request-one", "task-one", "turn-one", input);
        String id = (String) proposal.get("id");
        assertEquals("first\r\nmiddle\nlast\r", TextFileCodec.decode(Files.readAllBytes(file), false).rawText());
        assertEquals(id, agent.propose("request-one", "task-one", "turn-one", input).get("id"));
        assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> agent.propose("request-one", "task-one", "turn-one",
            List.of(new WorkspaceProposalService.Input(file.toString(), base.version(), "different")))).code());
        assertEquals(WorkspaceApiException.Code.PERMISSION_DENIED, assertThrows(WorkspaceApiException.class, () -> agent.apply(id, 0)).code());

        var reviewer = new WorkspaceProposalService(new WorkspaceQueryService(root, policy(true), source), null, storage, "human");
        var reviewed = reviewer.revise(id, 0, 0, null, Set.of(0));
        assertEquals("FIRST\nmiddle\nlast\n", reviewer.preview(id, 0).selected());
        var applied = reviewer.apply(id, revision(reviewed));
        assertEquals("APPLIED", applied.get("status"));
        assertEquals("FIRST\r\nmiddle\nlast\r", TextFileCodec.decode(Files.readAllBytes(file), false).rawText());
        String appliedVersion = queries.snapshot(queries.workspaceId(), file.toString()).version();
        assertEquals("APPLIED", reviewer.apply(id, revision(reviewed)).get("status"));
        assertEquals(appliedVersion, queries.snapshot(queries.workspaceId(), file.toString()).version());
        Files.write(file, TextFileCodec.encodeRaw("FIRST\r\nhuman middle\nlast\r", "UTF-16LE", true));
        assertEquals("UNDONE", reviewer.undo(id, revision(applied)).get("status"));
        assertEquals("first\r\nhuman middle\nlast\r", TextFileCodec.decode(Files.readAllBytes(file), false).rawText());
        var reopened = new WorkspaceProposalService(new WorkspaceQueryService(root, policy(true), source), null, storage, "human");
        assertEquals("UNDONE", reopened.list(0, 10).getFirst().get("status"));
        assertEquals("UNDONE", reopened.undo(id, revision(applied)).get("status"));
    }

    @Test void undoRestoresDeletedNewlineStylesAndOverlappingHumanEditsRequireReview() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source"), "a\r\nb\n");
        var queries = new WorkspaceQueryService(root, policy(true), WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, temporary.resolve("state"), "human");
        var base = queries.snapshot(queries.workspaceId(), file.toString());
        var proposal = service.propose("join", "task", "turn", List.of(new WorkspaceProposalService.Input(file.toString(), base.version(), "ab\n")));
        String id = (String) proposal.get("id");
        var applied = service.apply(id, revision(proposal));
        assertEquals("ab\n", Files.readString(file));
        service.undo(id, revision(applied));
        assertEquals("a\r\nb\n", Files.readString(file));
        base = queries.snapshot(queries.workspaceId(), file.toString());
        proposal = service.propose("replace", "task", "turn", List.of(new WorkspaceProposalService.Input(file.toString(), base.version(), "agent\nb\n")));
        id = (String) proposal.get("id"); applied = service.apply(id, revision(proposal));
        Files.writeString(file, "newer human\nb\n");
        String selectedId = id; int selectedRevision = revision(applied);
        assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> service.undo(selectedId, selectedRevision)).code());
        assertEquals("newer human\nb\n", Files.readString(file));
    }

    @Test void staleProposalsHaveNoEffectsAndExplicitRebaseRetainsUnrelatedHumanChanges() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source"), "one\nmiddle\n");
        var queries = new WorkspaceQueryService(root, policy(true), WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, temporary.resolve("state"), "human");
        var before = queries.snapshot(queries.workspaceId(), file.toString());
        var proposed = service.propose("request", "task", "turn", List.of(new WorkspaceProposalService.Input(file.toString(), before.version(), "ONE\nmiddle\n")));
        String id = (String) proposed.get("id");
        Files.writeString(file, "one\nhuman middle\n");
        assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class, () -> service.apply(id, 0)).code());
        assertEquals("one\nhuman middle\n", Files.readString(file));
        var rebased = service.rebase(id, 0);
        service.apply(id, revision(rebased));
        assertEquals("ONE\nhuman middle\n", Files.readString(file));
    }

    @Test void liveEditorEditsRemainUnsavedAndMixedDiskEditorFailuresRollBackDiskWithoutErasingHumanWork() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        Path disk = Files.writeString(root.resolve("disk"), "disk original\n"), live = Files.writeString(root.resolve("live"), "saved live\n");
        FileBuffer buffer = new FileBuffer(live.toFile());
        SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human draft\nmiddle\n"));
        try (var source = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            var queries = new WorkspaceQueryService(root, policy(true), source);
            var service = new WorkspaceProposalService(queries, source, temporary.resolve("state"), "human");
            var before = source.snapshots().getFirst();
            var proposal = service.propose("live", "task", "turn", List.of(new WorkspaceProposalService.Input(before.documentId(), before.version(), "agent draft\nmiddle\n")));
            String id = (String) proposal.get("id");
            var applied = service.apply(id, revision(proposal));
            assertEquals(true, applied.get("pendingSaveAtApplication"));
            assertEquals("agent draft\nmiddle\n", buffer.getContent()); assertEquals("saved live\n", Files.readString(live));
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("agent draft\nhuman middle\n"));
            service.undo(id, revision(applied));
            assertEquals("human draft\nhuman middle\n", buffer.getContent()); assertEquals("saved live\n", Files.readString(live));

            WorkspaceDocumentEditor concurrent = new WorkspaceDocumentEditor() {
                public List<WorkspaceDocument> snapshots() { return source.snapshots(); }
                public List<WorkspaceDocument> applyTexts(List<TextChange> changes) {
                    try {
                        Files.writeString(disk, "newer human disk\n");
                        SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("newer human draft\n"));
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                    return source.applyTexts(changes);
                }
            };
            var failing = new WorkspaceProposalService(queries, concurrent, temporary.resolve("state"), "human");
            var diskBefore = queries.snapshot(queries.workspaceId(), disk.toString()); before = source.snapshots().getFirst();
            var mixed = failing.propose("mixed", "task", "turn", List.of(
                new WorkspaceProposalService.Input(disk.toString(), diskBefore.version(), "agent disk\n"),
                new WorkspaceProposalService.Input(before.documentId(), before.version(), "agent live\n")));
            String mixedId = (String) mixed.get("id");
            assertEquals(WorkspaceApiException.Code.RECOVERY_REQUIRED, assertThrows(WorkspaceApiException.class, () -> failing.apply(mixedId, 0)).code());
            assertEquals("newer human disk\n", Files.readString(disk)); assertEquals("newer human draft\n", buffer.getContent());
            assertEquals("RECOVERY_REQUIRED", failing.preview(mixedId, 0).proposal().get("status"));
        }
    }

    @Test void readOnlyAndProtectedPathsAreDeniedWithoutWorkspaceMutation() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("README.md"), "keep");
        Path storage = temporary.resolve("state");
        var readonly = new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly());
        var denied = new WorkspaceProposalService(readonly, null, storage, "agent");
        assertEquals(WorkspaceApiException.Code.PERMISSION_DENIED, assertThrows(WorkspaceApiException.class,
            () -> denied.propose("request", "task", "turn", List.of(new WorkspaceProposalService.Input(file.toString(), "unknown", "change")))).code());
        assertFalse(Files.exists(storage));
        var queries = new WorkspaceQueryService(root, policy(true), WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, storage, "human");
        var before = queries.snapshot(queries.workspaceId(), file.toString());
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class,
            () -> service.propose("request", "task", "turn", List.of(new WorkspaceProposalService.Input(file.toString(), before.version(), "change")))).code());
        assertEquals("keep", Files.readString(file));
    }

    private static AgentPolicy policy(boolean apply) {
        return new AgentPolicy(apply ? Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY)
            : Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE), List.of("**/README.md"), List.of(), true);
    }
    private static int revision(Map<String, Object> result) { return ((Number) result.get("revision")).intValue(); }
}
