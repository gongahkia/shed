package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceStatusContractTest {
    @TempDir Path temporary;
    static final WorkspaceStatusAttachment.Descriptor TARGET = new WorkspaceStatusAttachment.Descriptor(
        "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
        "00000000-0000-0000-0000-000000000003", 12345, 1700000000000L);
    static String request(String method) { return WorkspaceRpcTest.request("query", method, Map.of("schemaVersion", 1)); }
    static Map<String, Object> object(String json) { return MiniJson.asObject(MiniJson.parseStrict(json)); }
    static String code(String json) { return (String) MiniJson.asObject(MiniJson.asObject(object(json).get("error")).get("data")).get("code"); }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void statusContainsOnlyVersionedOpaqueLifecycleData() {
        var state = new ApplicationStatus(true); var other = new ApplicationStatus(true);
        var value = state.snapshot();
        assertEquals(Set.of("schemaVersion", "protocolVersion", "instanceId", "windowId", "readiness", "authenticated", "capabilities"), value.keySet());
        assertEquals(List.of("status"), value.get("capabilities")); assertEquals(true, value.get("authenticated"));
        assertEquals(state.instanceId, other.instanceId); assertNotEquals(state.windowId, other.windowId);
        assertTrue(MiniJson.stringify(value).getBytes(StandardCharsets.UTF_8).length < 512);
    }

    @Test void readinessAndShutdownDoNotReenterReadyState() {
        var state = new ApplicationStatus(false); var rpc = new WorkspaceRpc(state);
        assertEquals("APP_NOT_READY", code(rpc.handle(request("editor/status"))));
        state.ready(); assertTrue(object(rpc.handle(request("editor/status"))).containsKey("result"));
        state.stopping(); state.ready(); assertEquals("SESSION_INVALID", code(rpc.handle(request("editor/status"))));
    }

    @Test void statusGrantHasNoLegacyOrFutureOperationFallback() {
        var rpc = new WorkspaceRpc(new ApplicationStatus(true));
        for (String method : List.of("workspace/info", "documents/list", "documents/read", "files/list", "search/text", "proposals/list",
                "editor/state", "editor/cursor/set", "editor/selection/set", "editor/file/open", "editor/edit", "editor/save",
                "editor/diagnostics", "editor/preview", "editor/capture", "tools/run", "commands/execute"))
            assertEquals("UNSUPPORTED", code(rpc.handle(request(method))), method);
    }

    @Test void malformedNotificationsUnknownFieldsAndVersionsFailExplicitly() {
        var rpc = new WorkspaceRpc(new ApplicationStatus(true));
        for (String invalid : List.of("[]", "{bad}", "{\"id\":1,\"id\":2}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"editor/status\",\"params\":{\"schemaVersion\":1}}",
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"editor/status\"}"))
            assertEquals("INVALID_REQUEST", code(rpc.handle(invalid)));
        assertEquals("INVALID_REQUEST", code(rpc.handle(WorkspaceRpcTest.request(1, "editor/status", Map.of("schemaVersion", 1, "root", "private")))));
        assertEquals("UNSUPPORTED_PROTOCOL", code(rpc.handle(WorkspaceRpcTest.request(1, "editor/status", Map.of("schemaVersion", 2)))));
        assertEquals("query", object(rpc.handle(request("editor/status"))).get("id"));
    }

    @Test void discoveryHasNoSecretAndCredentialIsSeparatelyRequired() throws Exception {
        var dir = WorkspaceStatusAttachment.privateDirectory(temporary.resolve("private"));
        try (var grant = new WorkspaceStatusAttachment(new ApplicationStatus(true), 12345, dir, List.of(), Duration.ofMinutes(1), Clock.systemUTC())) {
            var descriptor = WorkspaceStatusAttachment.Descriptor.read(grant.discoveryFile());
            var discovery = object(Files.readString(grant.discoveryFile()));
            assertEquals(Set.of("schemaVersion", "instanceId", "windowId", "attachmentId", "port", "expiresAt", "scope"), discovery.keySet());
            assertFalse(discovery.containsKey("key")); assertFalse(discovery.containsKey("token"));
            byte[] key = WorkspaceStatusAttachment.readKey(grant.credentialFile(), descriptor);
            assertEquals(32, key.length); Arrays.fill(key, (byte) 0);
            assertThrows(WorkspaceApiException.class, () -> WorkspaceStatusAttachment.readKey(grant.discoveryFile(), descriptor));
        }
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
    }

    @Test void privatePermissionsSymlinkAncestorsAndExposedRootsAreRejected() throws Exception {
        var dir = WorkspaceStatusAttachment.privateDirectory(temporary.resolve("private"));
        try (var grant = new WorkspaceStatusAttachment(new ApplicationStatus(true), 12345, dir, List.of(), Duration.ofMinutes(1), Clock.systemUTC())) {
            if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(grant.credentialFile(), PosixFilePermissions.fromString("rw-r--r--"));
                assertThrows(WorkspaceApiException.class, () -> WorkspaceStatusAttachment.readKey(grant.credentialFile(), grant.descriptor));
                Files.setPosixFilePermissions(grant.credentialFile(), PosixFilePermissions.fromString("rw-------"));
            }
            Path link = Files.createSymbolicLink(temporary.resolve("alias"), dir);
            assertThrows(IOException.class, () -> WorkspaceStatusAttachment.privateDirectory(link.resolve("child")));
            assertThrows(WorkspaceApiException.class, () -> WorkspaceStatusAttachment.Descriptor.read(link.resolve(grant.discoveryFile().getFileName())));
        }
        assertThrows(IOException.class, () -> new WorkspaceStatusAttachment(new ApplicationStatus(true), 12345, dir,
            List.of(temporary), Duration.ofMinutes(1), Clock.systemUTC()));
    }

    @Test void cleanupRetainsReplacedFilesAndNeverDeletesTheirNeighbours() throws Exception {
        var dir = WorkspaceStatusAttachment.privateDirectory(temporary.resolve("private"));
        Path sentinel = Files.writeString(temporary.resolve("sentinel"), "synthetic external sentinel");
        var grant = new WorkspaceStatusAttachment(new ApplicationStatus(true), 12345, dir, List.of(), Duration.ofMinutes(1), Clock.systemUTC());
        Files.writeString(grant.discoveryFile(), "replacement");
        assertThrows(IOException.class, grant::close); grant.close();
        assertEquals("replacement", Files.readString(grant.discoveryFile())); assertFalse(Files.exists(grant.credentialFile()));
        assertEquals("synthetic external sentinel", Files.readString(sentinel));
    }

    @Test void newStatusFramesRequireATerminatingNewlineWithoutChangingLegacyFrames() throws Exception {
        assertThrows(java.io.EOFException.class, () -> WorkspaceFrames.readComplete(new ByteArrayInputStream(bytes("{}")), 4096));
        assertArrayEquals(bytes("{}"), WorkspaceFrames.read(new ByteArrayInputStream(bytes("{}")), 4096));
        assertThrows(WorkspaceApiException.class, () -> WorkspaceFrames.readComplete(new ByteArrayInputStream(bytes("x".repeat(4097))), 4096));
    }

    @Test void hmacTranscriptMatchesAnIndependentPythonReference() {
        byte[] key = new byte[32]; for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        // Python hmac/struct reference recorded in A1 evidence, independent of this encoder.
        assertEquals("bcaa3f346a53c9c316d81bca7aa2806f60f7e7c425b7997a46245842a7c846a3", WorkspaceStatusProtocol.signature(key, TARGET, "server", "a".repeat(64), "b".repeat(64),
            "00000000-0000-0000-0000-000000000004", 0, ""));
    }

    @Test void framesBindRolesSequencesSessionContextAndExactPayloadBytes() throws Exception {
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 7);
        try (var session = new WorkspaceStatusProtocol.Session(TARGET, key, "a".repeat(64), "b".repeat(64), "session")) {
            String frame = session.pack(request("editor/status"), "request", 1);
            assertEquals(request("editor/status"), session.unpack(frame, "request", 1));
            assertThrows(WorkspaceApiException.class, () -> session.unpack(frame, "request", 2));
            assertThrows(WorkspaceApiException.class, () -> session.unpack(frame, "response", 1));
            var tampered = object(frame); tampered.put("payload", request("documents/read"));
            assertThrows(WorkspaceApiException.class, () -> session.unpack(MiniJson.stringify(tampered), "request", 1));
            try (var other = new WorkspaceStatusProtocol.Session(TARGET, key.clone(), "c".repeat(64), "b".repeat(64), "session")) {
                assertThrows(WorkspaceApiException.class, () -> other.unpack(frame, "request", 1));
            }
        }
        assertArrayEquals(new byte[32], key);
    }

    @Test void anUnprovenServerReceivesNoClientProofOrSecret() throws Exception {
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 9);
        String reply = MiniJson.stringify(Map.of("jsonrpc", "2.0", "id", "attach", "result", Map.of("protocolVersion", 2,
            "serverNonce", "b".repeat(64), "sessionId", "00000000-0000-0000-0000-000000000004", "proof", "0".repeat(64)))) + "\n";
        var output = new ByteArrayOutputStream();
        assertThrows(WorkspaceApiException.class, () -> WorkspaceStatusProtocol.connect(TARGET, key, new ByteArrayInputStream(bytes(reply)), output));
        String sent = output.toString(StandardCharsets.UTF_8);
        assertEquals(1, sent.lines().count()); assertTrue(sent.contains("connection/hello"));
        assertFalse(sent.contains("connection/authenticate")); assertFalse(sent.contains("09".repeat(32)));
    }

    @Test void statusArgumentsCannotSelectLegacyRequestsOrPrintClientPathsOnFailure() {
        for (String[] args : List.of(new String[]{"--workspace-api", "--status"},
                new String[]{"--workspace-api", "--status", "--connect", "synthetic-private", "--credential", "synthetic-key", "--request", "documents/read"})) {
            var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
            assertEquals(64, WorkspaceCli.run(args, new ByteArrayInputStream(new byte[0]), output, errors));
            assertEquals("INVALID_REQUEST", code(output.toString(StandardCharsets.UTF_8)));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("synthetic-private")); assertEquals(0, errors.size());
        }
        var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
        assertEquals(64, WorkspaceCli.run(new String[]{"--workspace-api", "--root", "--status", "--unsupported"},
            new ByteArrayInputStream(new byte[0]), output, errors));
        assertEquals(0, output.size()); assertTrue(errors.toString(StandardCharsets.UTF_8).contains("INVALID_REQUEST"));
    }

    @Test void everyIdentityNonceRoleAndExpiryFieldIsBoundToTheProof() {
        byte[] key = new byte[32];
        String original = WorkspaceStatusProtocol.signature(key, TARGET, "server", "a", "b", "session", 0, "");
        for (var target : List.of(new WorkspaceStatusAttachment.Descriptor(TARGET.windowId(), TARGET.windowId(), TARGET.attachmentId(), TARGET.port(), TARGET.expiresAt()),
                new WorkspaceStatusAttachment.Descriptor(TARGET.instanceId(), TARGET.instanceId(), TARGET.attachmentId(), TARGET.port(), TARGET.expiresAt()),
                new WorkspaceStatusAttachment.Descriptor(TARGET.instanceId(), TARGET.windowId(), TARGET.windowId(), TARGET.port(), TARGET.expiresAt()),
                new WorkspaceStatusAttachment.Descriptor(TARGET.instanceId(), TARGET.windowId(), TARGET.attachmentId(), TARGET.port() + 1, TARGET.expiresAt()),
                new WorkspaceStatusAttachment.Descriptor(TARGET.instanceId(), TARGET.windowId(), TARGET.attachmentId(), TARGET.port(), TARGET.expiresAt() + 1)))
            assertNotEquals(original, WorkspaceStatusProtocol.signature(key, target, "server", "a", "b", "session", 0, ""));
        for (String[] values : List.of(new String[]{"client", "a", "b", "session"}, new String[]{"server", "c", "b", "session"},
                new String[]{"server", "a", "c", "session"}, new String[]{"server", "a", "b", "other"}))
            assertNotEquals(original, WorkspaceStatusProtocol.signature(key, TARGET, values[0], values[1], values[2], values[3], 0, ""));
    }

    @Test void receivedStatusMustMatchTheAuthenticatedIdentityAndExactPrivacySchema() {
        var value = new java.util.LinkedHashMap<String, Object>(Map.of("schemaVersion", 1L, "protocolVersion", 2L,
            "instanceId", TARGET.instanceId(), "windowId", TARGET.windowId(), "readiness", "ready", "authenticated", true, "capabilities", List.of("status")));
        try (var session = new WorkspaceStatusProtocol.Session(TARGET, new byte[32], "a", "b", "session")) {
            assertTrue(session.acceptsStatus(value)); value.put("documents", List.of("not authorized")); assertFalse(session.acceptsStatus(value));
            value.remove("documents"); value.put("windowId", "other"); assertFalse(session.acceptsStatus(value));
            value.put("windowId", TARGET.windowId()); value.put("capabilities", List.of("status", "read")); assertFalse(session.acceptsStatus(value));
        }
    }
}
