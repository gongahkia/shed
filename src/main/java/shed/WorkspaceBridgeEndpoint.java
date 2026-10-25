package shed;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private connection credentials; never included in status messages or toString. */
final class WorkspaceBridgeEndpoint {
    private static final Set<String> FIELDS = Set.of("schemaVersion", "workspaceId", "root", "instanceId", "port", "token");
    final Path root;
    final String workspaceId;
    final String instanceId;
    final int port;
    private final String token;

    WorkspaceBridgeEndpoint(WorkspaceQueryService workspace, int port) {
        this.root = workspace.root(); this.workspaceId = workspace.workspaceId();
        this.instanceId = UUID.randomUUID().toString(); this.port = port;
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random); this.token = HexFormat.of().formatHex(random);
    }

    private WorkspaceBridgeEndpoint(Path root, String workspaceId, String instanceId, int port, String token) {
        this.root = root; this.workspaceId = workspaceId; this.instanceId = instanceId; this.port = port; this.token = token;
    }

    byte[] serialized() {
        return MiniJson.stringify(Map.of("schemaVersion", 1, "workspaceId", workspaceId, "root", root.toUri().toString(),
            "instanceId", instanceId, "port", port, "token", token)).getBytes(StandardCharsets.UTF_8);
    }

    static WorkspaceBridgeEndpoint read(Path file) throws IOException {
        try {
            Map<String, Object> data = MiniJson.asObject(MiniJson.parseStrict(WorkspaceFrames.decode(PrivateWorkspaceFiles.read(file, 16 * 1024))));
            if (data == null || !data.keySet().equals(FIELDS) || !Long.valueOf(1).equals(data.get("schemaVersion"))
                || !(data.get("port") instanceof Long port) || port < 1 || port > 65535
                || !(data.get("root") instanceof String uri) || !uri.startsWith("file:")
                || !(data.get("workspaceId") instanceof String workspaceId)
                || !(data.get("instanceId") instanceof String instance) || !instance.matches("[a-f0-9-]{36}")
                || !(data.get("token") instanceof String token) || !token.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("invalid descriptor");
            Path root = WorkspacePaths.root(Path.of(URI.create(uri)));
            if (!workspaceId.equals(WorkspacePaths.id(root))) throw new IllegalArgumentException("workspace identity mismatch");
            return new WorkspaceBridgeEndpoint(root, workspaceId, instance, port.intValue(), token);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid workspace connection descriptor");
        }
    }

    String authenticationRequest() {
        return MiniJson.stringify(Map.of("jsonrpc", "2.0", "id", "connect", "method", "connection/authenticate",
            "params", Map.of("workspaceId", workspaceId, "instanceId", instanceId, "token", token)));
    }

    boolean authenticates(String json) {
        try {
            Map<String, Object> request = MiniJson.asObject(MiniJson.parseStrict(json));
            if (request == null || !request.keySet().equals(Set.of("jsonrpc", "id", "method", "params"))
                || !"2.0".equals(request.get("jsonrpc")) || !"connect".equals(request.get("id"))
                || !"connection/authenticate".equals(request.get("method"))) return false;
            Map<String, Object> params = MiniJson.asObject(request.get("params"));
            return params != null && params.keySet().equals(Set.of("workspaceId", "instanceId", "token"))
                && workspaceId.equals(params.get("workspaceId")) && instanceId.equals(params.get("instanceId"))
                && params.get("token") instanceof String supplied && MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    String authenticated() {
        return MiniJson.stringify(Map.of("jsonrpc", "2.0", "id", "connect",
            "result", Map.of("authenticated", true, "workspaceId", workspaceId, "instanceId", instanceId)));
    }

    boolean confirms(String json) {
        try {
            Map<String, Object> response = MiniJson.asObject(MiniJson.parseStrict(json));
            Map<String, Object> result = response == null ? null : MiniJson.asObject(response.get("result"));
            return response != null && "2.0".equals(response.get("jsonrpc")) && "connect".equals(response.get("id"))
                && !response.containsKey("error") && result != null && Boolean.TRUE.equals(result.get("authenticated"))
                && workspaceId.equals(result.get("workspaceId")) && instanceId.equals(result.get("instanceId"));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    @Override public String toString() { return "WorkspaceBridgeEndpoint[" + workspaceId + ", " + instanceId + "]"; }
}
