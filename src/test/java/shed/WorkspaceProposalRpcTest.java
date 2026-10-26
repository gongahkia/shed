package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceProposalRpcTest {
    @TempDir Path temporary;

    @Test void connectedDeletionShowsDiskAndPreservedDraftSeparatelyAndNeverGrantsTheAgentApply() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), history = temporary.resolve("history");
        Path file = Files.writeString(root.resolve("source"), "disk\r\n"); FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            javax.swing.SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human draft\n"));
            var queries = new WorkspaceQueryService(root, queries(root, false).policy(), editor);
            var service = new WorkspaceProposalService(queries, editor, history, "agent");
            try (var server = new WorkspaceBridgeServer(queries, service, temporary.resolve("connections"));
                 var client = new WorkspaceBridgeClient(server.descriptor(), root)) {
                var before = editor.snapshots().getFirst();
                var params = new LinkedHashMap<String, Object>(Map.of("workspaceId", queries.workspaceId(), "requestId", "delete", "taskId", "t", "turnId", "u",
                    "changes", List.of(Map.of("kind", "delete", "document", before.documentId(), "expectedVersion", before.version()))));
                var proposal = result(client.exchange(WorkspaceRpcTest.request(1, "proposals/propose", params))); String id = (String) proposal.get("id");
                assertEquals("DELETE", MiniJson.asObject(MiniJson.asArray(proposal.get("documents")).getFirst()).get("kind"));
                var read = new LinkedHashMap<String, Object>(Map.of("workspaceId", queries.workspaceId(), "id", id, "text", "before"));
                assertEquals("disk\n", result(client.exchange(WorkspaceRpcTest.request(2, "proposals/read", read))).get("content"));
                read.put("text", "draft"); assertEquals("human draft\n", result(client.exchange(WorkspaceRpcTest.request(3, "proposals/read", read))).get("content"));
                var approval = Map.<String, Object>of("workspaceId", queries.workspaceId(), "id", id, "revision", 0);
                assertEquals("PERMISSION_DENIED", code(client.exchange(WorkspaceRpcTest.request(4, "proposals/apply", approval))));
                params.put("changes", List.of(Map.of("kind", "delete", "document", before.documentId(), "expectedVersion", before.version(), "text", "")));
                assertEquals("INVALID_REQUEST", code(client.exchange(WorkspaceRpcTest.request(5, "proposals/propose", params))));
                var reviewer = new WorkspaceProposalService(new WorkspaceQueryService(root, queries(root, true).policy(), editor), editor, history, "human");
                var applied = reviewer.apply(id, 0); assertFalse(Files.exists(file)); assertEquals("human draft\n", buffer.getContent());
                reviewer.undo(id, ((Number) applied.get("revision")).intValue()); assertEquals("disk\r\n", Files.readString(file));
            }
        }
    }

    @Test void creationIsExplicitStrictlyDecodedAndRequiresHumanApplyPermission() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        var queries = queries(root, false); var service = new WorkspaceProposalService(queries, null, storage, "agent");
        var rpc = new WorkspaceRpc(queries, service);
        var params = new LinkedHashMap<String, Object>(Map.of("workspaceId", queries.workspaceId(), "requestId", "create", "taskId", "t", "turnId", "u",
            "changes", List.of(Map.of("kind", "create", "document", "new", "text", "created\n"))));
        var proposal = result(call(rpc, "proposals/propose", params)); String id = (String) proposal.get("id");
        assertEquals("CREATE", MiniJson.asObject(MiniJson.asArray(proposal.get("documents")).getFirst()).get("kind"));
        assertFalse(Files.exists(root.resolve("new")));
        assertEquals("PERMISSION_DENIED", code(call(rpc, "proposals/apply", Map.of("workspaceId", queries.workspaceId(), "id", id, "revision", 0))));
        var read = result(call(rpc, "proposals/read", Map.of("workspaceId", queries.workspaceId(), "id", id)));
        assertEquals("created\n", read.get("content"));
        params.put("changes", List.of(Map.of("kind", "create", "document", "new", "expectedVersion", "replace", "text", "created\n")));
        assertEquals("INVALID_REQUEST", code(call(rpc, "proposals/propose", params)));
        params.put("changes", List.of(Map.of("kind", "delete", "document", "new", "text", "")));
        assertEquals("INVALID_REQUEST", code(call(rpc, "proposals/propose", params)));
        new WorkspaceProposalService(queries(root, true), null, storage, "human").apply(id, 0);
        assertEquals("created\n", Files.readString(root.resolve("new")));
    }

    @Test void restorationUsesTheInspectedEvidenceAndNeverGrantsProposalClientsApplyPermission() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        Files.writeString(root.resolve("a"), "old\r\n"); Files.writeString(root.resolve("b"), "old\r\n");
        var queries = queries(root, true); var service = new WorkspaceProposalService(queries, null, storage, "human");
        String id = WorkspaceProposalRecoveryTest.interruptedApply(root, storage, service, List.of("a", "b"));
        Files.writeString(root.resolve("a"), "new\r\n");
        var reviewer = new WorkspaceRpc(queries, service);
        var evidence = result(call(reviewer, "proposals/recovery", Map.of("workspaceId", queries.workspaceId(), "id", id)));
        assertEquals(true, evidence.get("restoreAvailable")); assertNull(evidence.get("verifiedOutcome"));
        var params = Map.<String, Object>of("workspaceId", queries.workspaceId(), "id", id, "revision", 1, "fingerprint", evidence.get("fingerprint"));
        var agentQueries = queries(root, false);
        var agent = new WorkspaceRpc(agentQueries, new WorkspaceProposalService(agentQueries, null, storage, "agent"));
        assertEquals("PERMISSION_DENIED", code(call(agent, "proposals/restore", params)));
        assertEquals("FAILED", result(call(reviewer, "proposals/restore", params)).get("status"));
        assertEquals("old\r\n", Files.readString(root.resolve("a"))); assertEquals("old\r\n", Files.readString(root.resolve("b")));
    }

    @Test void recoveryEvidenceIsReadableButReconciliationRequiresAnInspectedRevisionAndApplyPermission() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        Files.writeString(root.resolve("a.txt"), "old\r\n");
        var queries = queries(root, true); var service = new WorkspaceProposalService(queries, null, storage, "human");
        String id = WorkspaceProposalRecoveryTest.interruptedApply(root, storage, service, List.of("a.txt"));
        Files.writeString(root.resolve("a.txt"), "new\r\n");
        var readerQueries = queries(root, false);
        var reader = new WorkspaceRpc(readerQueries, new WorkspaceProposalService(readerQueries, null, storage, "agent"));
        var evidence = result(call(reader, "proposals/recovery", Map.of("workspaceId", queries.workspaceId(), "id", id)));
        var params = Map.<String, Object>of("workspaceId", queries.workspaceId(), "id", id, "revision", 1, "fingerprint", evidence.get("fingerprint"));
        assertEquals("PERMISSION_DENIED", code(call(reader, "proposals/reconcile", params)));
        var reviewer = new WorkspaceRpc(queries, service);
        assertEquals("APPLIED", result(call(reviewer, "proposals/reconcile", params)).get("status"));
        assertEquals("new\r\n", Files.readString(root.resolve("a.txt")));
    }

    @Test void connectedProposalWorkflowPersistsReviewAndNeverGrantsTheClientApply() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        Path file = Files.writeString(root.resolve("a.txt"), "first\nmiddle\nlast\n");
        var queries = queries(root, false);
        var service = new WorkspaceProposalService(queries, null, storage, "agent");
        try (var server = new WorkspaceBridgeServer(queries, service, temporary.resolve("connections"));
             var client = new WorkspaceBridgeClient(server.descriptor(), root)) {
            var info = result(client.exchange(WorkspaceRpcTest.request(1, "workspace/info", Map.of())));
            assertTrue(MiniJson.asArray(info.get("operations")).contains("proposals/propose"));
            assertFalse(MiniJson.asArray(info.get("operations")).contains("proposals/apply"));
            var params = proposal(queries, "r1", "FIRST\nmiddle\nLAST\n");
            var draft = result(client.exchange(WorkspaceRpcTest.request(2, "proposals/propose", params)));
            String id = (String) draft.get("id");
            assertEquals("agent", draft.get("actor")); assertEquals("PROPOSED", draft.get("status"));
            assertEquals("first\nmiddle\nlast\n", Files.readString(file));
            var review = Map.<String, Object>of("workspaceId", queries.workspaceId(), "id", id, "revision", 0);
            assertEquals("PERMISSION_DENIED", code(client.exchange(WorkspaceRpcTest.request(3, "proposals/apply", review))));
            var revise = new LinkedHashMap<>(review); revise.put("selectedHunks", List.of(0));
            assertEquals(1L, result(client.exchange(WorkspaceRpcTest.request(4, "proposals/revise", revise))).get("revision"));
            var read = Map.<String, Object>of("workspaceId", queries.workspaceId(), "id", id);
            var preview = result(client.exchange(WorkspaceRpcTest.request(5, "proposals/read", read)));
            assertEquals("FIRST\nmiddle\nlast\n", preview.get("content"));
            var hunks = MiniJson.asArray(preview.get("hunks"));
            assertEquals(true, MiniJson.asObject(hunks.getFirst()).get("selected"));
            assertEquals(false, MiniJson.asObject(hunks.getLast()).get("selected"));

            var reviewer = new WorkspaceProposalService(queries(root, true), null, storage, "human");
            reviewer.apply(id, 1);
            assertEquals("FIRST\nmiddle\nlast\n", Files.readString(file));
            // A reconnect/retry returns the persisted outcome, even though the old base is now stale.
            assertEquals("APPLIED", result(client.exchange(WorkspaceRpcTest.request(6, "proposals/propose", params))).get("status"));
            assertEquals("APPLIED", MiniJson.asObject(result(client.exchange(WorkspaceRpcTest.request(7, "proposals/read", read))).get("proposal")).get("status"));
        }
    }

    @Test void reviewPagesAreBoundedVersionedAndPreserveGraphemes() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); Files.writeString(root.resolve("a.txt"), "old");
        var queries = queries(root, false);
        var rpc = new WorkspaceRpc(queries, new WorkspaceProposalService(queries, null, temporary.resolve("history"), "agent"));
        var draft = result(call(rpc, "proposals/propose", proposal(queries, "r", "👩‍💻\n" + "a".repeat(70_000))));
        var read = new LinkedHashMap<String, Object>(Map.of("workspaceId", queries.workspaceId(), "id", draft.get("id"), "limit", 5));
        var page = result(call(rpc, "proposals/read", read));
        assertEquals("👩‍💻", page.get("content")); assertEquals(5L, page.get("nextOffset")); assertEquals(true, page.get("truncated"));
        read.put("limit", 4); assertEquals("LIMIT_EXCEEDED", code(call(rpc, "proposals/read", read)));
        read.put("offset", 2); assertEquals("INVALID_REQUEST", code(call(rpc, "proposals/read", read)));
        read.put("offset", 0); read.put("limit", 65_537); assertEquals("INVALID_REQUEST", code(call(rpc, "proposals/read", read)));
        result(call(rpc, "proposals/revise", Map.of("workspaceId", queries.workspaceId(), "id", draft.get("id"), "revision", 0, "text", "")));
        read.put("limit", 100); read.put("expectedRevision", 0);
        assertEquals("STALE_VERSION", code(call(rpc, "proposals/read", read)));
        read.put("expectedRevision", 1); assertEquals("", result(call(rpc, "proposals/read", read)).get("content"));
    }

    @Test void protocolRejectsAmbiguousReviewAndActorOrWorkspaceSpoofing() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); Files.writeString(root.resolve("a.txt"), "old");
        var queries = queries(root, true);
        var rpc = new WorkspaceRpc(queries, new WorkspaceProposalService(queries, null, temporary.resolve("history"), "human"));
        var params = proposal(queries, "r", "new"); params.put("actor", "admin");
        assertEquals("INVALID_REQUEST", code(call(rpc, "proposals/propose", params)));
        params.remove("actor"); params.put("workspaceId", "other");
        assertEquals("NOT_FOUND", code(call(rpc, "proposals/propose", params)));
        params.put("workspaceId", queries.workspaceId());
        var draft = result(call(rpc, "proposals/propose", params));
        var review = new LinkedHashMap<String, Object>(Map.of("workspaceId", queries.workspaceId(), "id", draft.get("id")));
        assertEquals("INVALID_REQUEST", code(call(rpc, "proposals/apply", review)));
        review.put("revision", 0.5); assertEquals("INVALID_REQUEST", code(call(rpc, "proposals/apply", review)));
        review.put("revision", 0); review.put("selectedHunks", List.of(0, 0));
        assertEquals("INVALID_REQUEST", code(call(rpc, "proposals/revise", review)));
        review.put("selectedHunks", List.of(9)); assertEquals("INVALID_REQUEST", code(call(rpc, "proposals/revise", review)));
        assertEquals("old", Files.readString(root.resolve("a.txt")));
    }

    @Test void readOnlyListingDoesNotCreatePrivateStateAndFilteredPagesAdvanceTheirCursor() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); Files.writeString(root.resolve("a.txt"), "old");
        Path storage = temporary.resolve("history");
        var readOnly = new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly());
        var rpc = new WorkspaceRpc(readOnly, new WorkspaceProposalService(readOnly, null, storage, "reader"));
        assertEquals(List.of(), result(call(rpc, "proposals/list", Map.of("workspaceId", readOnly.workspaceId()))).get("proposals"));
        assertEquals("PERMISSION_DENIED", code(call(rpc, "proposals/propose", proposal(readOnly, "r", "new"))));
        assertFalse(Files.exists(storage));
        var queries = queries(root, false); var agent = new WorkspaceProposalService(queries, null, storage, "agent");
        for (int i = 0; i < 3; i++) agent.propose("r" + i, "task", "turn", List.of(new WorkspaceProposalService.Input("a.txt",
            queries.snapshot(queries.workspaceId(), "a.txt").version(), "new" + i)));
        var page = agent.page(0, 1); assertEquals(1, page.proposals().size()); assertEquals(1, page.nextOffset());
        assertEquals(1, agent.page(page.nextOffset(), 1).offset());
        var filteredQueries = new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ), List.of("**/README.md"), List.of("a.txt"), true), WorkspaceDocumentSource.diskOnly());
        var filtered = new WorkspaceProposalService(filteredQueries, null, storage, "reader");
        assertTrue(filtered.page(0, 1).proposals().isEmpty()); assertNull(filtered.page(0, 1).nextOffset());
    }

    private WorkspaceQueryService queries(Path root, boolean apply) {
        var caps = apply ? Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY)
            : Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE);
        return new WorkspaceQueryService(root, new AgentPolicy(caps, List.of("**/README.md"), List.of(), true), WorkspaceDocumentSource.diskOnly());
    }
    private Map<String, Object> proposal(WorkspaceQueryService queries, String request, String text) {
        return new LinkedHashMap<>(Map.of("workspaceId", queries.workspaceId(), "requestId", request, "taskId", "task", "turnId", "turn", "changes",
            List.of(Map.of("document", "a.txt", "expectedVersion", queries.snapshot(queries.workspaceId(), "a.txt").version(), "text", text))));
    }
    private static String call(WorkspaceRpc rpc, String method, Map<String, Object> params) { return rpc.handle(WorkspaceRpcTest.request(1, method, params)); }
    private static Map<String, Object> result(String json) {
        var envelope = MiniJson.asObject(MiniJson.parseStrict(json)); assertFalse(envelope.containsKey("error"), json);
        return MiniJson.asObject(envelope.get("result"));
    }
    private static Object code(String json) { return MiniJson.asObject(MiniJson.asObject(MiniJson.asObject(MiniJson.parseStrict(json)).get("error")).get("data")).get("code"); }
}
