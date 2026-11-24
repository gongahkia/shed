package shed;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A0's role-separated nonce/HMAC attachment, isolated from the legacy bearer protocol. */
final class WorkspaceStatusProtocol {
    static final int FRAME_BYTES = 4096;
    static final int PAYLOAD_BYTES = 2048;
    static final int MAX_QUERIES = 128;
    static final int AUTH_MILLIS = 3000;
    static final int IDLE_MILLIS = 5000;
    private WorkspaceStatusProtocol() { }

    static final class Session implements AutoCloseable {
        private final WorkspaceStatusAttachment.Descriptor target;
        private final byte[] key;
        private final String clientNonce, serverNonce, sessionId;
        Session(WorkspaceStatusAttachment.Descriptor target, byte[] key, String clientNonce, String serverNonce, String sessionId) {
            this.target = target; this.key = key; this.clientNonce = clientNonce; this.serverNonce = serverNonce; this.sessionId = sessionId;
        }
        String proof(String role, long sequence, String payload) {
            return signature(key, target, role, clientNonce, serverNonce, sessionId, sequence, payload);
        }
        String pack(String payload, String role, long sequence) {
            size(payload);
            return MiniJson.stringify(Map.of("sessionId", sessionId, "sequence", sequence, "payload", payload, "proof", proof(role, sequence, payload)));
        }
        String unpack(String frame, String role, long sequence) throws IOException {
            Map<String, Object> value = WorkspaceStatusAttachment.object(frame.getBytes(StandardCharsets.UTF_8));
            if (!value.keySet().equals(Set.of("sessionId", "sequence", "payload", "proof"))
                || !sessionId.equals(value.get("sessionId")) || !Long.valueOf(sequence).equals(value.get("sequence"))
                || !(value.get("payload") instanceof String payload)) throw denied();
            size(payload);
            if (!matches(proof(role, sequence, payload), value.get("proof"))) throw denied();
            return payload;
        }
        @Override public void close() { Arrays.fill(key, (byte) 0); }
        boolean expired() { return System.currentTimeMillis() >= target.expiresAt(); }
        int frameBytes() { return EditorControl.SCOPE.equals(scope()) ? 65536 : FRAME_BYTES; }
        void size(String payload) {
            int maximum = EditorControl.SCOPE.equals(scope()) ? 32768 : PAYLOAD_BYTES;
            if (payload.getBytes(StandardCharsets.UTF_8).length > maximum)
                throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Attachment payload exceeds its limit");
        }
        String read(InputStream input) throws IOException {
            byte[] frame = WorkspaceFrames.readComplete(input, frameBytes());
            if (frame == null) throw new java.io.EOFException("Attachment disconnected");
            return WorkspaceFrames.decode(frame);
        }
        void write(OutputStream output, String frame) throws IOException { WorkspaceFrames.write(output, frame, frameBytes()); }
        boolean acceptsStatus(Map<String, Object> value) {
            return value != null && value.keySet().equals(Set.of("schemaVersion", "protocolVersion", "instanceId", "windowId", "readiness", "authenticated", "capabilities"))
                && Long.valueOf(1).equals(value.get("schemaVersion")) && Long.valueOf(2).equals(value.get("protocolVersion"))
                && target.instanceId().equals(value.get("instanceId")) && target.windowId().equals(value.get("windowId"))
                && "ready".equals(value.get("readiness")) && Boolean.TRUE.equals(value.get("authenticated")) && List.of("status").equals(value.get("capabilities"));
        }
        String scope() { return target.scope(); }
        String projectId() { return target.projectId(); }
        boolean acceptsMetadata(Map<String, Object> value) {
            if (!("editor-metadata".equals(scope()) || "editor-navigation".equals(scope()) || EditorControl.SCOPE.equals(scope())) || value == null || !value.keySet().equals(Set.of("schemaVersion", "protocolVersion",
                "instanceId", "windowId", "capabilities", "projectId", "paneId", "documentId", "documentLength", "documentRevision",
                "dirty", "caretOffset", "selectionStart", "selectionEnd", "coordinateUnit", "available", "valid"))) return false;
            if (!Long.valueOf(1).equals(value.get("schemaVersion")) || !Long.valueOf(2).equals(value.get("protocolVersion"))
                || !target.instanceId().equals(value.get("instanceId")) || !target.windowId().equals(value.get("windowId"))
                || !target.projectId().equals(value.get("projectId")) || !List.of("status", "editor-metadata").equals(value.get("capabilities"))
                || !"utf-16".equals(value.get("coordinateUnit")) || !Boolean.TRUE.equals(value.get("available")) || !Boolean.TRUE.equals(value.get("valid"))
                || !(value.get("dirty") instanceof Boolean) || !(value.get("paneId") instanceof String pane) || !pane.matches("pane-[a-f0-9-]{36}")
                || !(value.get("documentId") instanceof String document) || !document.matches("buffer-[a-f0-9-]{36}")) return false;
            try {
                int length = integer(value.get("documentLength")), caret = integer(value.get("caretOffset")),
                    start = integer(value.get("selectionStart")), end = integer(value.get("selectionEnd"));
                if (!(value.get("documentRevision") instanceof Long revision)) return false;
                EditorMetadata.validateCoordinates(length, revision, caret, start, end);
                return true;
            } catch (RuntimeException invalid) { return false; }
        }
        private static int integer(Object value) {
            if (!(value instanceof Long number) || number < 0 || number > Integer.MAX_VALUE) throw denied();
            return number.intValue();
        }
        boolean acceptsNavigation(Map<String, Object> value, boolean mutation) {
            if (!("editor-navigation".equals(scope()) || EditorControl.SCOPE.equals(scope())) || value == null || !(value.get("contextRevision") instanceof Long revision) || revision < 0
                || !(EditorControl.SCOPE.equals(scope()) ? List.of("status", "editor-metadata", "editor-navigation", EditorControl.SCOPE)
                    : List.of("status", "editor-metadata", "editor-navigation")).equals(value.get("capabilities"))) return false;
            var base = new java.util.LinkedHashMap<>(value); base.remove("contextRevision");
            base.put("capabilities", List.of("status", "editor-metadata"));
            if (mutation) {
                if (!Boolean.TRUE.equals(base.remove("applied")) || !(base.remove("operation") instanceof String operation) || !EditorNavigation.MUTATIONS.contains(operation)
                    || !(base.remove("visibility") instanceof String visibility) || !Set.of("caret", "selection", "start", "range").contains(visibility)) return false;
            }
            return acceptsMetadata(base);
        }

        boolean acceptsControl(String method, Map<String, Object> value) {
            if (!EditorControl.SCOPE.equals(scope()) || value == null || !EditorControl.OPERATIONS.contains(method)
                    || !method.equals(value.get("operation"))
                    || !List.of("status", "editor-metadata", "editor-navigation", EditorControl.SCOPE).equals(value.get("capabilities"))) return false;
            var base = new java.util.LinkedHashMap<>(value);
            base.remove("operation"); Object outcome = base.remove("outcome");
            base.put("capabilities", List.of("status", "editor-metadata", "editor-navigation", EditorControl.SCOPE));
            switch (method) {
                case "editor/file/open" -> { if (!"applied".equals(outcome)) return false; }
                case "editor/agent/activity" -> { if (!"shown".equals(outcome)) return false; }
                case "editor/agent/progress", "editor/agent/state", "editor/agent/cancel" -> {
                    if (!"activity".equals(outcome) || !validActivity(base.remove("activity"))) return false;
                }
                case "editor/edits/propose" -> {
                    if (!"proposed".equals(outcome) || !(base.remove("proposalId") instanceof String id)
                            || !id.matches("proposal-[a-f0-9]{32}") || !Boolean.TRUE.equals(base.remove("reviewRequired"))) return false;
                }
                case "editor/source/read" -> {
                    if (!"read".equals(outcome) || !(base.remove("content") instanceof String content) || content.length() > EditorControl.MAX_READ
                            || !(base.remove("offset") instanceof Long offset) || !(base.remove("nextOffset") instanceof Long end)
                            || offset < 0 || end != offset + content.length() || !(base.remove("truncated") instanceof Boolean)) return false;
                }
                default -> { return false; }
            }
            return acceptsNavigation(base, false);
        }
    }

    private static boolean validActivity(Object value) {
        var m=MiniJson.asObject(value); if(m==null)return false;
        if(m.equals(Map.of("phase","idle")))return true;
        if(!m.keySet().equals(Set.of("sessionId","agent","phase","completed","total")))return false;
        try {new AgentActivityState().update((String)m.get("sessionId"),(String)m.get("agent"),(String)m.get("phase"),(Long)m.get("completed"),(Long)m.get("total"));return true;}
        catch(RuntimeException invalid){return false;}
    }

    static Session accept(WorkspaceStatusAttachment attachment, InputStream input, OutputStream output) throws IOException {
        Map<String, Object> hello = params(read(input), "connection/hello");
        if (!Long.valueOf(2).equals(hello.get("protocolVersion"))) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.UNSUPPORTED_PROTOCOL);
        var d = attachment.descriptor;
        if (!hello.keySet().equals(Set.of("protocolVersion", "instanceId", "windowId", "attachmentId", "clientNonce"))
            || !d.instanceId().equals(hello.get("instanceId")) || !d.windowId().equals(hello.get("windowId"))
            || !d.attachmentId().equals(hello.get("attachmentId"))) throw denied();
        String clientNonce = nonce(hello.get("clientNonce"));
        Session session = new Session(d, attachment.key(), clientNonce, randomNonce(), UUID.randomUUID().toString());
        boolean accepted = false;
        try {
            write(output, response(Map.of("protocolVersion", 2, "serverNonce", session.serverNonce, "sessionId", session.sessionId,
                "proof", session.proof("server", 0, ""))));
            Map<String, Object> answer = params(read(input), "connection/authenticate");
            if (!answer.keySet().equals(Set.of("proof")) || !matches(session.proof("client", 0, ""), answer.get("proof"))) throw denied();
            if (attachment.expired()) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_EXPIRED);
            write(output, response(Map.of("authenticated", true, "proof", session.proof("finish", 0, ""))));
            accepted = true;
            return session;
        } finally { if (!accepted) session.close(); }
    }

    static Session connect(WorkspaceStatusAttachment.Descriptor target, byte[] key, InputStream input, OutputStream output) throws IOException {
        String clientNonce = randomNonce();
        write(output, request("connection/hello", Map.of("protocolVersion", 2, "instanceId", target.instanceId(),
            "windowId", target.windowId(), "attachmentId", target.attachmentId(), "clientNonce", clientNonce)));
        Map<String, Object> challenge = result(read(input));
        if (!challenge.keySet().equals(Set.of("protocolVersion", "serverNonce", "sessionId", "proof"))
            || !Long.valueOf(2).equals(challenge.get("protocolVersion"))) throw denied();
        String serverNonce = nonce(challenge.get("serverNonce"));
        if (!(challenge.get("sessionId") instanceof String sessionId) || !validUuid(sessionId)) throw denied();
        Session session = new Session(target, key, clientNonce, serverNonce, sessionId);
        boolean accepted = false;
        try {
            // verify the server before sending any proof of client key possession
            if (!matches(session.proof("server", 0, ""), challenge.get("proof"))) throw denied();
            write(output, request("connection/authenticate", Map.of("proof", session.proof("client", 0, ""))));
            Map<String, Object> done = result(read(input));
            if (!done.keySet().equals(Set.of("authenticated", "proof")) || !Boolean.TRUE.equals(done.get("authenticated"))
                || !matches(session.proof("finish", 0, ""), done.get("proof"))) throw denied();
            accepted = true;
            return session;
        } finally { if (!accepted) session.close(); }
    }

    static String signature(byte[] key, WorkspaceStatusAttachment.Descriptor d, String role, String clientNonce,
                            String serverNonce, String sessionId, long sequence, String payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream transcript = new DataOutputStream(bytes)) {
                // each field is a big-endian byte length and UTF-8 bytes, in this fixed order
                for (String field : new String[]{"shed-status-v2", role, "2", d.instanceId(), d.windowId(), d.attachmentId(),
                    Integer.toString(d.port()), Long.toString(d.expiresAt()), d.scope(), clientNonce, serverNonce, sessionId,
                    Long.toString(sequence), payload}) {
                    byte[] utf8 = field.getBytes(StandardCharsets.UTF_8); transcript.writeInt(utf8.length); transcript.write(utf8);
                }
                // status transcripts remain byte-for-byte v2; metadata additionally binds its opaque project grant
                if (d.projectId() != null) {
                    byte[] utf8 = d.projectId().getBytes(StandardCharsets.UTF_8); transcript.writeInt(utf8.length); transcript.write(utf8);
                }
            }
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(bytes.toByteArray()));
        } catch (GeneralSecurityException | IOException unavailable) { throw new IllegalStateException("Status authentication unavailable"); }
    }

    static String read(InputStream input) throws IOException {
        byte[] frame = WorkspaceFrames.readComplete(input, FRAME_BYTES);
        if (frame == null) throw new java.io.EOFException("Attachment disconnected");
        return WorkspaceFrames.decode(frame);
    }
    static void write(OutputStream output, String frame) throws IOException { WorkspaceFrames.write(output, frame, FRAME_BYTES); }
    static void size(String payload) {
        if (payload.getBytes(StandardCharsets.UTF_8).length > PAYLOAD_BYTES)
            throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Status payload exceeds its limit");
    }
    static String request(String method, Map<String, Object> params) {
        return MiniJson.stringify(Map.of("jsonrpc", "2.0", "id", "attach", "method", method, "params", params));
    }
    private static String response(Map<String, Object> result) {
        return MiniJson.stringify(Map.of("jsonrpc", "2.0", "id", "attach", "result", result));
    }
    private static Map<String, Object> params(String frame, String method) throws IOException {
        Map<String, Object> value = WorkspaceStatusAttachment.object(frame.getBytes(StandardCharsets.UTF_8));
        if (!value.keySet().equals(Set.of("jsonrpc", "id", "method", "params")) || !"2.0".equals(value.get("jsonrpc"))
            || !"attach".equals(value.get("id")) || !method.equals(value.get("method"))) throw denied();
        Map<String, Object> params = MiniJson.asObject(value.get("params"));
        if (params == null) throw denied();
        return params;
    }
    private static Map<String, Object> result(String frame) throws IOException {
        Map<String, Object> value = WorkspaceStatusAttachment.object(frame.getBytes(StandardCharsets.UTF_8));
        if (!"2.0".equals(value.get("jsonrpc")) || !"attach".equals(value.get("id"))) throw denied();
        if (value.keySet().equals(Set.of("jsonrpc", "id", "error"))) {
            var error = MiniJson.asObject(value.get("error")); var data = error == null ? null : MiniJson.asObject(error.get("data"));
            if (data != null) {
                if ("UNSUPPORTED_PROTOCOL".equals(data.get("code"))) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.UNSUPPORTED_PROTOCOL);
                if ("SESSION_EXPIRED".equals(data.get("code"))) throw WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.SESSION_EXPIRED);
            }
            throw denied();
        }
        var result = MiniJson.asObject(value.get("result"));
        if (!value.keySet().equals(Set.of("jsonrpc", "id", "result")) || result == null) throw denied();
        return result;
    }
    private static boolean matches(String expected, Object supplied) {
        return supplied instanceof String text && text.matches("[a-f0-9]{64}")
            && MessageDigest.isEqual(HexFormat.of().parseHex(expected), HexFormat.of().parseHex(text));
    }
    private static String nonce(Object value) {
        if (!(value instanceof String text) || !text.matches("[a-f0-9]{64}")) throw denied();
        return text;
    }
    private static boolean validUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException invalid) { return false; }
    }
    private static String randomNonce() { return HexFormat.of().formatHex(WorkspaceStatusAttachment.random()); }
    private static WorkspaceApiException denied() { return WorkspaceStatusAttachment.failure(WorkspaceApiException.Code.UNAUTHORIZED); }
}
