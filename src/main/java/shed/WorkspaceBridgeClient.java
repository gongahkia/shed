package shed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Headless client. Descriptor paths are explicit; no broadcasts, remote hosts or automatic attach. */
final class WorkspaceBridgeClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedInputStream input;
    private final WorkspaceStatusProtocol.Session statusSession;
    private final ScheduledThreadPoolExecutor deadlines;
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private long sequence;

    WorkspaceBridgeClient(Path descriptor, Path requestedRoot) throws IOException {
        this.statusSession = null; this.deadlines = null;
        WorkspaceBridgeEndpoint endpoint = WorkspaceBridgeEndpoint.read(descriptor);
        if (!endpoint.root.equals(WorkspacePaths.root(requestedRoot)))
            throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_FOUND, "Connection belongs to a different workspace");
        this.socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(WorkspaceBridgeServer.loopback(), endpoint.port), 3000);
            socket.setSoTimeout(30_000); socket.setTcpNoDelay(true);
            this.input = new BufferedInputStream(socket.getInputStream());
            WorkspaceFrames.write(socket.getOutputStream(), endpoint.authenticationRequest(), 4096);
            byte[] reply = WorkspaceFrames.read(input, 4096);
            if (reply == null || !endpoint.confirms(WorkspaceFrames.decode(reply)))
                throw new WorkspaceApiException(WorkspaceApiException.Code.PERMISSION_DENIED, "Workspace connection authentication failed");
        } catch (IOException | RuntimeException failure) { socket.close(); throw failure; }
    }

    static WorkspaceBridgeClient status(Path descriptor, Path credential) throws IOException {
        return authenticated(descriptor, credential, "status");
    }

    static WorkspaceBridgeClient metadata(Path descriptor, Path credential) throws IOException {
        return authenticated(descriptor, credential, "editor-metadata");
    }
    static WorkspaceBridgeClient navigation(Path descriptor, Path credential) throws IOException {
        return authenticated(descriptor, credential, "editor-navigation");
    }
    static WorkspaceBridgeClient control(Path descriptor, Path credential) throws IOException {
        return authenticated(descriptor, credential, EditorControl.SCOPE);
    }

    private static WorkspaceBridgeClient authenticated(Path descriptor, Path credential, String scope) throws IOException {
        var endpoint = WorkspaceStatusAttachment.Descriptor.read(descriptor);
        if (!scope.equals(endpoint.scope())) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.UNAUTHORIZED);
        if (System.currentTimeMillis() >= endpoint.expiresAt()) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_EXPIRED);
        byte[] key = WorkspaceStatusAttachment.readKey(credential, endpoint);
        try { return new WorkspaceBridgeClient(endpoint, key); }
        finally { Arrays.fill(key, (byte) 0); }
    }

    String metadataRequest() {
        if (statusSession == null || !("editor-metadata".equals(statusSession.scope()) || "editor-navigation".equals(statusSession.scope()))) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.UNAUTHORIZED);
        return MiniJson.stringify(Map.of("jsonrpc", "2.0", "id", "metadata", "method", "editor/metadata",
            "params", Map.of("schemaVersion", 1, "projectId", statusSession.projectId())));
    }
    String navigationContextRequest() {
        if (statusSession == null || !("editor-navigation".equals(statusSession.scope()) || EditorControl.SCOPE.equals(statusSession.scope()))) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.UNAUTHORIZED);
        return MiniJson.stringify(Map.of("jsonrpc", "2.0", "id", "context", "method", EditorNavigation.CONTEXT,
            "params", Map.of("schemaVersion", 1, "projectId", statusSession.projectId())));
    }

    private WorkspaceBridgeClient(WorkspaceStatusAttachment.Descriptor endpoint, byte[] key) throws IOException {
        this.socket = new Socket();
        this.deadlines = new ScheduledThreadPoolExecutor(1, task -> Thread.ofPlatform().daemon(true).name("shed-status-client-deadline").unstarted(task));
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setKeepAliveTime(100, TimeUnit.MILLISECONDS);
        deadlines.allowCoreThreadTimeOut(true);
        ScheduledFuture<?> timeout = deadline();
        byte[] sessionKey = key.clone();
        try {
            socket.connect(new InetSocketAddress(WorkspaceBridgeServer.loopback(), endpoint.port()), WorkspaceStatusProtocol.AUTH_MILLIS);
            socket.setSoTimeout(WorkspaceStatusProtocol.AUTH_MILLIS); socket.setTcpNoDelay(true);
            this.input = new BufferedInputStream(socket.getInputStream());
            this.statusSession = WorkspaceStatusProtocol.connect(endpoint, sessionKey, input, socket.getOutputStream());
        } catch (IOException | RuntimeException failed) {
            Arrays.fill(sessionKey, (byte) 0);
            try { socket.close(); } finally { deadlines.shutdownNow(); }
            if (timedOut.get()) throw new WorkspaceApiException(WorkspaceApiException.Code.TIMEOUT, "Attachment timed out");
            throw failed;
        } finally { timeout.cancel(false); }
    }

    synchronized String exchange(String request) throws IOException {
        if (statusSession != null) return statusExchange(request);
        WorkspaceFrames.write(socket.getOutputStream(), request, WorkspaceFrames.MAX_REQUEST_BYTES);
        if (!WorkspaceRpc.expectsResponse(request)) return null;
        byte[] response = WorkspaceFrames.read(input, WorkspaceFrames.MAX_RESPONSE_BYTES);
        if (response == null) throw new IOException("Workspace connection closed before answering");
        return WorkspaceFrames.decode(response);
    }

    private String statusExchange(String request) throws IOException {
        if (socket.isClosed()) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_INVALID);
        if (statusSession.expired()) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_EXPIRED);
        statusSession.size(request);
        Map<String, Object> original;
        try { original = MiniJson.asObject(MiniJson.parseStrict(request)); }
        catch (IllegalArgumentException invalid) { throw WorkspaceApiException.invalidRequest("Invalid status request"); }
        Object id = original == null ? null : original.get("id");
        if (!(id instanceof Long || id instanceof String text && text.length() <= 256))
            throw WorkspaceApiException.invalidRequest("Status requires a bounded request ID");
        long next = sequence + 1;
        if (next > WorkspaceStatusProtocol.MAX_QUERIES) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_INVALID);
        String frame = statusSession.pack(request, "request", next);
        if (frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > statusSession.frameBytes())
            throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Status frame exceeds its limit");
        ScheduledFuture<?> timeout = deadline();
        sequence = next;
        try {
            statusSession.write(socket.getOutputStream(), frame);
            String payload = statusSession.unpack(statusSession.read(input), "response", sequence);
            Map<String, Object> response = WorkspaceStatusAttachment.object(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!"2.0".equals(response.get("jsonrpc")) || !id.equals(response.get("id"))
                || !(response.keySet().equals(Set.of("jsonrpc", "id", "result")) || response.keySet().equals(Set.of("jsonrpc", "id", "error"))))
                throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_INVALID);
            if (response.containsKey("result") && !acceptsResult(original, MiniJson.asObject(response.get("result"))))
                throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_INVALID);
            if (response.containsKey("result") && "editor/metadata".equals(original.get("method"))) {
                var params = MiniJson.asObject(original.get("params")); var result = MiniJson.asObject(response.get("result"));
                if (params == null || !result.get("projectId").equals(params.get("projectId"))
                    || params.containsKey("expectedPaneId") && !result.get("paneId").equals(params.get("expectedPaneId"))
                    || params.containsKey("expectedDocumentId") && !result.get("documentId").equals(params.get("expectedDocumentId")))
                    throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_INVALID);
            }
            return payload;
        } catch (IOException failed) {
            if (timedOut.get()) throw new WorkspaceApiException(WorkspaceApiException.Code.TIMEOUT, "Status request timed out");
            if (statusSession.expired()) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_EXPIRED);
            if (failed instanceof java.io.EOFException) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_INVALID);
            throw failed;
        } finally { timeout.cancel(false); }
    }

    private boolean acceptsResult(Map<String, Object> request, Map<String, Object> result) {
        String method = (String) request.get("method");
        if ("editor/status".equals(method)) return statusSession.acceptsStatus(result);
        if ("editor/metadata".equals(method)) return statusSession.acceptsMetadata(result);
        if (EditorNavigation.CONTEXT.equals(method)) return statusSession.acceptsNavigation(result, false);
        if (EditorControl.OPERATIONS.contains(method)) return statusSession.acceptsControl(method, result);
        if (!EditorNavigation.MUTATIONS.contains(method) || !statusSession.acceptsNavigation(result, true) || !method.equals(result.get("operation"))) return false;
        var params = MiniJson.asObject(request.get("params"));
        boolean identity = params != null && result.get("projectId").equals(params.get("projectId")) && result.get("paneId").equals(params.get("paneId"))
            && result.get("documentId").equals(params.get("documentId")) && result.get("documentRevision").equals(params.get("documentRevision"))
            && params.get("contextRevision") instanceof Long expected && (long) result.get("contextRevision") > expected;
        if (!identity) return false;
        Object start = params.get("editor/caret/set".equals(method) ? "offset" : "start");
        Object end = "editor/caret/set".equals(method) ? start : params.get("end");
        if ("editor/reveal".equals(method)) return result.get("caretOffset").equals(params.get("caretOffset"))
            && result.get("selectionStart").equals(params.get("selectionStart")) && result.get("selectionEnd").equals(params.get("selectionEnd"));
        return result.get("caretOffset").equals(end) && result.get("selectionStart").equals(start) && result.get("selectionEnd").equals(end);
    }

    private ScheduledFuture<?> deadline() {
        return deadlines.schedule(() -> {
            timedOut.set(true);
            try { socket.close(); } catch (IOException ignored) { }
        }, WorkspaceStatusProtocol.AUTH_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override public void close() throws IOException {
        try { socket.close(); }
        finally {
            if (statusSession != null) statusSession.close();
            if (deadlines != null) deadlines.shutdownNow();
        }
    }
}
