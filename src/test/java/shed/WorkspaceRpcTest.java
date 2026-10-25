package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRpcTest {
    @TempDir Path root;

    @Test void handshakeAndExplicitWorkspaceQueriesPreserveCorrelationAndVersions() throws Exception {
        var service = new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly());
        var rpc = new WorkspaceRpc(service);
        var info = response(rpc.handle(request("handshake", "workspace/info", Map.of("schemaVersion", 1))));
        assertEquals("handshake", info.get("id"));
        var capabilities = MiniJson.asObject(info.get("result"));
        assertEquals(WorkspaceQueryService.OPERATIONS, capabilities.get("operations"));
        Files.writeString(root.resolve("a.txt"), "first");
        var params = Map.<String, Object>of("workspaceId", service.workspaceId(), "document", "a.txt");
        var result = MiniJson.asObject(response(rpc.handle(request(1, "documents/read", params))).get("result"));
        assertEquals("first", result.get("content"));
        Files.writeString(root.resolve("a.txt"), "human edit");
        var stale = Map.<String, Object>of("workspaceId", service.workspaceId(), "document", "a.txt", "expectedVersion", result.get("version"));
        assertEquals("STALE_VERSION", domainCode(rpc.handle(request(2, "documents/read", stale))));
        assertEquals("NOT_FOUND", domainCode(rpc.handle(request(3, "documents/list", Map.of("workspaceId", "other")))));
    }

    @Test void protocolErrorsAreStrictAndDoNotMasqueradeAsEmptySuccesses() {
        var rpc = new WorkspaceRpc(new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()));
        assertEquals(-32700L, errorCode(rpc.handle("{\"id\":1,\"id\":2}")));
        assertEquals(-32600L, errorCode(rpc.handle("{\"id\":1,\"method\":\"workspace/info\"}")));
        assertEquals(-32600L, errorCode(rpc.handle("[]")));
        assertEquals(-32601L, errorCode(rpc.handle(request(1, "documents/write", Map.of()))));
        assertEquals(-32602L, errorCode(rpc.handle(request(1, "documents/read", Map.of("workspaceId", "x", "document", "x", "limit", 1.5)))));
        assertEquals(-32602L, errorCode(rpc.handle(request(1, "workspace/info", Map.of("unexpected", true)))));
        assertEquals("UNSUPPORTED", domainCode(rpc.handle(request(1, "workspace/info", Map.of("schemaVersion", 2)))));
        assertNull(rpc.handle("{\"jsonrpc\":\"2.0\",\"method\":\"workspace/info\"}"));
        assertNull(rpc.handle("{\"jsonrpc\":\"2.0\",\"method\":\"unsupported\"}"));
    }

    @Test void adapterServesTheConnectedEditorsUnsavedStateWithoutAWindow() throws Exception {
        String workspace = WorkspacePaths.id(root.toRealPath());
        var document = new WorkspaceDocument(workspace, "unsaved", root.resolve("new.txt").toUri().toString(), "editor-v7",
            "not saved\u0001\ud800", true, false, true, "UTF-8", "LF", false, WorkspaceDocument.Source.EDITOR);
        var rpc = new WorkspaceRpc(new WorkspaceQueryService(root, AgentPolicy.readOnly(), () -> List.of(document)));
        var result = MiniJson.asObject(response(rpc.handle(request("query", "documents/read",
            Map.of("workspaceId", workspace, "document", "unsaved")))).get("result"));
        assertEquals(document.content(), result.get("content"));
        assertEquals(document.version(), result.get("version"));
        assertFalse(Files.exists(root.resolve("new.txt")));
    }

    static String request(Object id, String method, Map<String, Object> params) {
        return MiniJson.stringify(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }
    private static Map<String, Object> response(String json) { return MiniJson.asObject(MiniJson.parseStrict(json)); }
    private static Object errorCode(String json) { return MiniJson.asObject(response(json).get("error")).get("code"); }
    private static Object domainCode(String json) {
        return MiniJson.asObject(MiniJson.asObject(response(json).get("error")).get("data")).get("code");
    }
}
