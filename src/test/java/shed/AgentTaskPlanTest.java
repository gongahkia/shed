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

class AgentTaskPlanTest {
    @TempDir Path temporary;

    @Test void multiFileTaskCreatesOneReviewAndPreservesUnsavedWorkAndDiskFormat() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        Path a = Files.writeString(root.resolve("a"), "disk a\n"), b = Files.writeString(root.resolve("b"), "old b\r\n");
        FileBuffer buffer = new FileBuffer(a.toFile()); SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human draft + old a\n"));
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            var queries = queries(root, documents, false); var plan = AgentTaskPlan.capture(queries, () -> false);
            String prompt = plan.prompt("change both files"); assertTrue(prompt.contains("human draft + old a"));
            var proposals = service(queries, documents); var result = plan.propose(proposals, "r", "t", response(List.of(
                edit("a", "old a", "new a"), edit("b", "old b", "new b"), Map.of("kind", "create", "path", "c", "text", "new c\n"))));
            assertEquals(1, proposals.list(0, 10).size()); assertEquals("PROPOSED", proposals.reviewState(result.proposal()).status());
            assertEquals("human draft + old a\n", documents.snapshots().getFirst().content()); assertEquals("old b\r\n", Files.readString(b)); assertFalse(Files.exists(root.resolve("c")));
            var reviewer = service(queries(root, documents, true), documents); var applied = reviewer.apply(result.proposal(), 0);
            assertEquals("human draft + new a\n", documents.snapshots().getFirst().content()); assertEquals("disk a\n", Files.readString(a));
            assertEquals("new b\r\n", Files.readString(b)); assertEquals("new c\n", Files.readString(root.resolve("c")));
            reviewer.undo(result.proposal(), ((Number) applied.get("revision")).intValue());
            assertEquals("human draft + old a\n", documents.snapshots().getFirst().content()); assertEquals("old b\r\n", Files.readString(b)); assertFalse(Files.exists(root.resolve("c")));
        }
    }

    @Test void laterChangesRejectTheEntireTaskWithoutAProposalOrPartialWrites() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); Files.writeString(root.resolve("a"), "old a"); Files.writeString(root.resolve("b"), "old b");
        var queries = queries(root, WorkspaceDocumentSource.diskOnly(), false); var plan = AgentTaskPlan.capture(queries, () -> false);
        Files.writeString(root.resolve("b"), "later human work"); var proposals = service(queries, null);
        assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class,
            () -> plan.propose(proposals, "r", "t", response(List.of(edit("a", "old a", "new a"), edit("b", "old b", "new b"))))).code());
        assertTrue(proposals.list(0, 10).isEmpty()); assertEquals("old a", Files.readString(root.resolve("a"))); assertEquals("later human work", Files.readString(root.resolve("b")));
    }

    @Test void rejectsAmbiguousOverlappingUncapturedAndEscapingChanges() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); Files.writeString(root.resolve("a"), "one repeat repeat two");
        Files.writeString(root.resolve(".gitignore"), "ignored\n"); Files.writeString(root.resolve("ignored"), "hidden");
        var queries = queries(root, WorkspaceDocumentSource.diskOnly(), false); var plan = AgentTaskPlan.capture(queries, () -> false); var proposals = service(queries, null);
        for (var change : List.of(edit("a", "repeat", "x"), edit("a", "missing", "x"), edit("ignored", "hidden", "x"),
            Map.of("kind", "edit", "path", "a", "edits", List.of(Map.of("before", "one repeat", "after", "x"), Map.of("before", "one", "after", "y"))),
            Map.of("kind", "create", "path", "../escape", "text", "bad"), Map.of("kind", "create", "path", "README.md", "text", "bad"),
            Map.of("kind", "create", "path", "a", "text", "bad"))) {
            assertThrows(RuntimeException.class, () -> plan.propose(proposals, "r", "t", response(List.of(change))));
        }
        assertTrue(proposals.list(0, 10).isEmpty()); assertEquals("one repeat repeat two", Files.readString(root.resolve("a")));
    }

    @Test void reviewedRenameAndDeleteUseTheSharedUndoTransaction() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); Files.writeString(root.resolve("old"), "keep"); Files.writeString(root.resolve("remove"), "restore");
        var queries = queries(root, WorkspaceDocumentSource.diskOnly(), false); var plan = AgentTaskPlan.capture(queries, () -> false);
        var result = plan.propose(service(queries, null), "r", "t", response(List.of(
            Map.of("kind", "rename", "path", "old", "destination", "new"), Map.of("kind", "delete", "path", "remove"))));
        var reviewer = service(queries(root, WorkspaceDocumentSource.diskOnly(), true), null); var applied = reviewer.apply(result.proposal(), 0);
        assertEquals("keep", Files.readString(root.resolve("new"))); assertFalse(Files.exists(root.resolve("remove"))); assertFalse(Files.exists(root.resolve("old")));
        reviewer.undo(result.proposal(), ((Number) applied.get("revision")).intValue());
        assertEquals("keep", Files.readString(root.resolve("old"))); assertEquals("restore", Files.readString(root.resolve("remove")));
    }

    @Test void noChangeAnswersRemainUsefulAndCaptureIsCancellable() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); var queries = queries(root, WorkspaceDocumentSource.diskOnly(), false);
        var plan = AgentTaskPlan.capture(queries, () -> false); var proposals = service(queries, null);
        var result = plan.propose(proposals, "r", "t", "{\"summary\":\"Already implemented\",\"changes\":[]}");
        assertEquals("Already implemented", result.summary()); assertEquals("", result.proposal()); assertTrue(proposals.list(0, 10).isEmpty());
        assertThrows(WorkspaceApiException.class, () -> AgentTaskPlan.capture(queries, () -> true));
        assertThrows(IllegalArgumentException.class, () -> plan.prompt("x".repeat(8193)));
    }

    private Map<String, Object> edit(String path, String before, String after) { return Map.of("kind", "edit", "path", path, "edits", List.of(Map.of("before", before, "after", after))); }
    private String response(List<?> changes) { return MiniJson.stringify(Map.of("summary", "Proposed task changes", "changes", changes)); }
    private WorkspaceQueryService queries(Path root, WorkspaceDocumentSource documents, boolean apply) {
        return new WorkspaceQueryService(root, new AgentPolicy(apply ? Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY)
            : Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE), List.of("**/README.md"), List.of(), true), documents);
    }
    private WorkspaceProposalService service(WorkspaceQueryService queries, WorkspaceDocumentEditor editor) {
        return new WorkspaceProposalService(queries, editor, temporary.resolve("state"), queries.policy().capabilities().contains(AgentPolicy.Capability.APPLY) ? "human" : "task-agent");
    }
}
