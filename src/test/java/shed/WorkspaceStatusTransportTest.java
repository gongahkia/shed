package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** actual loopback transport and disposable state; no shell, language server or desktop. */
class WorkspaceStatusTransportTest {
    @TempDir Path temporary;
    static final class MutableClock extends Clock {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        public long millis() { return now.get(); }
    }
    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        assertTrue(condition.getAsBoolean(), "Lifecycle condition did not complete within its bound");
    }
    WorkspaceBridgeServer server() throws IOException {
        return WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("private"), List.of());
    }
    static Socket socket(WorkspaceBridgeServer server) throws IOException {
        Socket socket = new Socket(WorkspaceBridgeServer.loopback(), server.port()); socket.setSoTimeout(6000); return socket;
    }
    static String exchange(WorkspaceBridgeClient client) throws IOException {
        return client.exchange(WorkspaceStatusContractTest.request("editor/status"));
    }
    static String code(String response) { return WorkspaceStatusContractTest.code(response); }
    static Map<String, Object> object(String json) { return WorkspaceStatusContractTest.object(json); }
    static String hello(WorkspaceStatusAttachment.Descriptor d, String nonce) {
        return WorkspaceStatusProtocol.request("connection/hello", Map.of("protocolVersion", 2, "instanceId", d.instanceId(),
            "windowId", d.windowId(), "attachmentId", d.attachmentId(), "clientNonce", nonce));
    }
    static int cli(Path descriptor, Path credential, ByteArrayOutputStream output) {
        var errors = new ByteArrayOutputStream();
        int exit = WorkspaceCli.run(new String[]{"--workspace-api", "--status", "--connect", descriptor.toString(), "--credential", credential.toString()},
            new ByteArrayInputStream(new byte[0]), output, errors);
        assertEquals("", errors.toString(StandardCharsets.UTF_8)); return exit;
    }

    @Test void realTransportAuthenticatesSequentialClientsAndCorrelatesRepeatedStatus() throws Exception {
        try (var server = server()) {
            assertTrue(server.statusOnly()); assertNull(server.workspaceId());
            for (int i = 0; i < 4; i++) {
                try (var client = WorkspaceBridgeClient.status(server.descriptor(), server.credential())) {
                    for (int j = 0; j < 3; j++) {
                        var response = object(exchange(client)); assertEquals("query", response.get("id"));
                        var result = MiniJson.asObject(response.get("result"));
                        assertEquals(List.of("status"), result.get("capabilities")); assertEquals("ready", result.get("readiness"));
                    }
                }
                await(() -> server.connectionCount() == 0);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertEquals(0, cli(server.descriptor(), server.credential(), output));
            assertTrue(object(output.toString(StandardCharsets.UTF_8)).containsKey("result"));
        }
    }

    @Test void wrongCredentialsNeverReturnApplicationState() throws Exception {
        try (var server = server()) {
            byte[] original = Files.readAllBytes(server.credential());
            try {
                var changed = object(new String(original, StandardCharsets.UTF_8)); changed.put("key", "01".repeat(32));
                Files.writeString(server.credential(), MiniJson.stringify(changed));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                assertEquals(77, cli(server.descriptor(), server.credential(), output));
                assertEquals("UNAUTHORIZED", code(output.toString(StandardCharsets.UTF_8)));
                assertFalse(output.toString(StandardCharsets.UTF_8).contains("instanceId"));
                assertFalse(output.toString(StandardCharsets.UTF_8).contains(temporary.toString()));
            } finally { Files.write(server.credential(), original); }
            await(() -> server.connectionCount() == 0);
        }
    }

    @Test void explicitInstanceAndCredentialTargetsCannotBeMixed() throws Exception {
        try (var first = server(); var second = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("other"), List.of())) {
            assertThrows(WorkspaceApiException.class, () -> WorkspaceBridgeClient.status(first.descriptor(), second.credential()));
            var descriptor = object(Files.readString(first.descriptor()));
            descriptor.put("windowId", java.util.UUID.randomUUID().toString());
            Path wrong = PrivateWorkspaceFiles.create(first.descriptor().getParent(), "wrong.json", MiniJson.stringify(descriptor).getBytes(StandardCharsets.UTF_8));
            assertThrows(WorkspaceApiException.class, () -> WorkspaceBridgeClient.status(wrong, first.credential()));
            try (var client = WorkspaceBridgeClient.status(second.descriptor(), second.credential())) {
                assertEquals(object(Files.readString(second.descriptor())).get("windowId"), MiniJson.asObject(object(exchange(client)).get("result")).get("windowId"));
            }
        }
    }

    @Test void descriptorVersionMissingCredentialAndPermissionFailuresAreDistinct() throws Exception {
        try (var server = server()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertEquals(77, cli(server.descriptor(), temporary.resolve("missing-key"), output));
            var descriptor = object(Files.readString(server.descriptor())); descriptor.put("schemaVersion", 3);
            Path wrong = PrivateWorkspaceFiles.create(server.descriptor().getParent(), "future.json", MiniJson.stringify(descriptor).getBytes(StandardCharsets.UTF_8));
            output.reset(); assertEquals(76, cli(wrong, server.credential(), output)); assertEquals("UNSUPPORTED_PROTOCOL", code(output.toString(StandardCharsets.UTF_8)));
            if (server.credential().getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(server.credential(), PosixFilePermissions.fromString("rw-r--r--"));
                try { output.reset(); assertEquals(77, cli(server.descriptor(), server.credential(), output)); }
                finally { Files.setPosixFilePermissions(server.credential(), PosixFilePermissions.fromString("rw-------")); }
            }
            assertEquals(0, server.connectionCount());
        }
    }

    @Test void unreadyStatusIsNotSuccessAndStillDoesNotLeakPaths() throws Exception {
        var state = new ApplicationStatus(false);
        try (var server = WorkspaceBridgeServer.status(state, temporary.resolve("private"), List.of())) {
            var output = new ByteArrayOutputStream();
            assertEquals(75, cli(server.descriptor(), server.credential(), output)); assertEquals("APP_NOT_READY", code(output.toString(StandardCharsets.UTF_8)));
            state.ready(); output.reset(); assertEquals(0, cli(server.descriptor(), server.credential(), output));
        }
    }

    @Test void authenticatedStatusClientCannotReachLegacyOrFutureFeatures() throws Exception {
        try (var server = server(); var client = WorkspaceBridgeClient.status(server.descriptor(), server.credential())) {
            for (String method : List.of("documents/list", "documents/read", "workspace/info", "files/list", "search/text", "proposals/apply",
                    "editor/state", "editor/pane/select", "editor/cursor/set", "editor/selection/set", "editor/file/open", "editor/save",
                    "editor/diagnostics", "editor/capture", "editor/preview", "tools/run"))
                assertEquals("UNSUPPORTED", code(client.exchange(WorkspaceStatusContractTest.request(method))));
            assertTrue(object(exchange(client)).containsKey("result"));
        }
    }

    @Test void replayedClientProofFailsWithAFreshServerChallenge() throws Exception {
        try (var server = server(); var first = socket(server); var second = socket(server)) {
            var d = WorkspaceStatusAttachment.Descriptor.read(server.descriptor());
            String clientNonce = "a".repeat(64); WorkspaceStatusProtocol.write(first.getOutputStream(), hello(d, clientNonce));
            var challenge = MiniJson.asObject(object(WorkspaceStatusProtocol.read(first.getInputStream())).get("result"));
            try (var session = new WorkspaceStatusProtocol.Session(d, WorkspaceStatusAttachment.readKey(server.credential(), d),
                    clientNonce, (String) challenge.get("serverNonce"), (String) challenge.get("sessionId"))) {
                String proof = WorkspaceStatusProtocol.request("connection/authenticate", Map.of("proof", session.proof("client", 0, "")));
                WorkspaceStatusProtocol.write(first.getOutputStream(), proof);
                assertTrue(MiniJson.asObject(object(WorkspaceStatusProtocol.read(first.getInputStream())).get("result")).containsKey("authenticated"));
                WorkspaceStatusProtocol.write(second.getOutputStream(), hello(d, clientNonce));
                WorkspaceStatusProtocol.read(second.getInputStream());
                WorkspaceStatusProtocol.write(second.getOutputStream(), proof);
                assertEquals("UNAUTHORIZED", code(WorkspaceStatusProtocol.read(second.getInputStream())));
            }
        }
    }

    @Test void duplicatedOrTamperedSignedRequestsCloseTheSession() throws Exception {
        try (var server = server()) {
            for (boolean tamper : List.of(false, true)) try (var socket = socket(server)) {
                var d = WorkspaceStatusAttachment.Descriptor.read(server.descriptor());
                try (var session = WorkspaceStatusProtocol.connect(d, WorkspaceStatusAttachment.readKey(server.credential(), d), socket.getInputStream(), socket.getOutputStream())) {
                    String frame = session.pack(WorkspaceStatusContractTest.request("editor/status"), "request", 1);
                    WorkspaceStatusProtocol.write(socket.getOutputStream(), frame);
                    assertTrue(object(session.unpack(WorkspaceStatusProtocol.read(socket.getInputStream()), "response", 1)).containsKey("result"));
                    if (tamper) { var changed = object(frame); changed.put("sequence", 2); frame = MiniJson.stringify(changed); }
                    WorkspaceStatusProtocol.write(socket.getOutputStream(), frame);
                    String rejected = WorkspaceStatusProtocol.read(socket.getInputStream());
                    assertEquals("UNAUTHORIZED", code(rejected)); assertFalse(rejected.contains("readiness"));
                }
            }
        }
    }

    @Test void malformedOversizedTruncatedAndLegacyAuthenticationCannotReadStatus() throws Exception {
        try (var server = server()) {
            for (String request : List.of("{bad}\n", " ".repeat(4097), "{\"jsonrpc\":\"2.0\"}",
                    WorkspaceStatusProtocol.request("connection/authenticate", Map.of("token", "synthetic")) + "\n")) {
                try (var socket = socket(server)) {
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush(); socket.shutdownOutput();
                    String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertFalse(reply.contains("readiness")); assertFalse(reply.contains("instanceId"));
                }
            }
            try (var client = WorkspaceBridgeClient.status(server.descriptor(), server.credential())) {
                assertThrows(WorkspaceApiException.class, () -> client.exchange("x".repeat(2049)));
                assertTrue(object(exchange(client)).containsKey("result"));
            }
        }
    }

    @Test void shutdownRevokesConnectionsAndAStaleRecordCannotRetargetToAnotherAttachment() throws Exception {
        var first = server();
        try {
            var old = object(Files.readString(first.descriptor())); byte[] oldKey = Files.readAllBytes(first.credential());
            try (var client = WorkspaceBridgeClient.status(first.descriptor(), first.credential())) {
                assertTrue(object(exchange(client)).containsKey("result")); first.close();
                try { exchange(client); fail("Closed attachment accepted a request"); }
                catch (WorkspaceApiException | IOException expected) { }
            }
            assertFalse(Files.exists(first.descriptor())); assertFalse(Files.exists(first.credential()));
            var output = new ByteArrayOutputStream(); assertEquals(69, cli(first.descriptor(), first.credential(), output));
            try (var second = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("new"), List.of())) {
                old.put("port", second.port());
                Path stale = PrivateWorkspaceFiles.create(second.descriptor().getParent(), "stale.json", MiniJson.stringify(old).getBytes(StandardCharsets.UTF_8));
                Path key = PrivateWorkspaceFiles.create(second.descriptor().getParent(), "stale.key", oldKey);
                assertThrows(WorkspaceApiException.class, () -> WorkspaceBridgeClient.status(stale, key));
            }
        } finally { first.close(); }
    }

    @Test void logicalExpirationIsRejectedAndTimedExpirationRemovesOwnedFiles() throws Exception {
        var clock = new MutableClock();
        try (var server = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("clock"), List.of(), Duration.ofMinutes(1), clock)) {
            clock.now.addAndGet(61_000);
            var output = new ByteArrayOutputStream(); assertEquals(78, cli(server.descriptor(), server.credential(), output));
            assertEquals("SESSION_EXPIRED", code(output.toString(StandardCharsets.UTF_8)));
        }
        try (var server = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("timer"), List.of(), Duration.ofMillis(100), Clock.systemUTC())) {
            await(() -> !server.running() && !Files.exists(server.descriptor()) && !Files.exists(server.credential()));
        }
    }

    @Test void incompleteAuthenticationHasAnAbsoluteDeadlineAndReleasesItsSlot() throws Exception {
        try (var server = server(); var socket = socket(server)) {
            long start = System.nanoTime(); socket.getOutputStream().write('{'); socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(6));
            await(() -> server.connectionCount() == 0);
        }
    }

    @Test void connectionSlotsAndRepeatedAttachmentCleanupAreBounded() throws Exception {
        for (int cycle = 0; cycle < 12; cycle++) {
            var server = server(); var sockets = new ArrayList<Socket>();
            try {
                for (int i = 0; i < WorkspaceBridgeServer.MAX_CLIENTS; i++) sockets.add(socket(server));
                await(() -> server.connectionCount() == WorkspaceBridgeServer.MAX_CLIENTS);
                try (var extra = socket(server)) { assertEquals(-1, extra.getInputStream().read()); }
            } finally { for (var socket : sockets) socket.close(); server.close(); }
            await(() -> server.connectionCount() == 0);
            assertFalse(Files.exists(server.descriptor())); assertFalse(Files.exists(server.credential()));
        }
        try (var files = Files.list(temporary.resolve("private"))) { assertEquals(0, files.count()); }
    }

    @Test void failedInitializationLeavesNoCredentialAndPreservesExternalSentinel() throws Exception {
        Path sentinel = Files.writeString(temporary.resolve("sentinel"), "synthetic external");
        var shared = Files.createDirectory(temporary.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> WorkspaceBridgeServer.status(new ApplicationStatus(true), shared, List.of()));
        try (var files = Files.list(shared)) { assertEquals(0, files.count()); }
        assertEquals("synthetic external", Files.readString(sentinel));
    }

    @Test void aSilentPeerTimesOutWithoutReceivingAClientProof() throws Exception {
        try (var listener = new ServerSocket(0, 1, WorkspaceBridgeServer.loopback())) {
            listener.setSoTimeout(5000);
            var directory = WorkspaceStatusAttachment.privateDirectory(temporary.resolve("silent"));
            try (var grant = new WorkspaceStatusAttachment(new ApplicationStatus(true), listener.getLocalPort(), directory,
                     List.of(), Duration.ofMinutes(1), Clock.systemUTC());
                 var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var peer = worker.submit(() -> {
                    try (var socket = listener.accept()) {
                        socket.setSoTimeout(5000); WorkspaceStatusProtocol.read(socket.getInputStream());
                        assertEquals(-1, socket.getInputStream().read()); return true;
                    }
                });
                var output = new ByteArrayOutputStream();
                assertEquals(70, cli(grant.discoveryFile(), grant.credentialFile(), output)); assertEquals("TIMEOUT", code(output.toString(StandardCharsets.UTF_8)));
                assertTrue(peer.get(6, TimeUnit.SECONDS));
            }
        }
    }

    @Test void evenAnAuthenticatedServerCannotChangeResponseCorrelation() throws Exception {
        try (var listener = new ServerSocket(0, 1, WorkspaceBridgeServer.loopback())) {
            listener.setSoTimeout(5000);
            var directory = WorkspaceStatusAttachment.privateDirectory(temporary.resolve("correlation"));
            try (var grant = new WorkspaceStatusAttachment(new ApplicationStatus(true), listener.getLocalPort(), directory,
                     List.of(), Duration.ofMinutes(1), Clock.systemUTC());
                 var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var peer = worker.submit(() -> {
                    try (var socket = listener.accept()) {
                        socket.setSoTimeout(5000);
                        try (var session = WorkspaceStatusProtocol.accept(grant, socket.getInputStream(), socket.getOutputStream())) {
                            session.unpack(WorkspaceStatusProtocol.read(socket.getInputStream()), "request", 1);
                            WorkspaceStatusProtocol.write(socket.getOutputStream(), session.pack(MiniJson.stringify(Map.of("jsonrpc", "2.0",
                                "id", "wrong", "result", Map.of("readiness", "ready"))), "response", 1)); return true;
                        }
                    }
                });
                try (var client = WorkspaceBridgeClient.status(grant.discoveryFile(), grant.credentialFile())) {
                    assertEquals(WorkspaceApiException.Code.SESSION_INVALID, assertThrows(WorkspaceApiException.class, () -> exchange(client)).code());
                }
                assertTrue(peer.get(6, TimeUnit.SECONDS));
            }
        }
    }

    @Test void statusRoundTripDoesNotNeedTheEdtOrMutateAnUnrelatedSharedDocument() throws Exception {
        var buffer = FileBuffer.createScratch("[ungranted]", "exact 👩‍💻 source\n");
        String before = buffer.getContent(); long revision = buffer.documentRevision(); boolean dirty = buffer.isModified();
        boolean undo = buffer.getUndoManager().canUndo();
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var occupied = new java.util.concurrent.FutureTask<Void>(() -> { entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test EDT release timed out"); return null; });
        try (var server = server()) {
            javax.swing.SwingUtilities.invokeLater(occupied); assertTrue(entered.await(1, TimeUnit.SECONDS));
            try (var client = WorkspaceBridgeClient.status(server.descriptor(), server.credential())) {
                assertTrue(object(exchange(client)).containsKey("result"));
            } finally { release.countDown(); }
            occupied.get(1, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertEquals(before, buffer.getContent()); assertEquals(revision, buffer.documentRevision());
        assertEquals(dirty, buffer.isModified()); assertEquals(undo, buffer.getUndoManager().canUndo());
    }

    @Test void expirationReleasesTheOwningRegistryAndItsCloseObserverExactlyOnce() throws Exception {
        Set<WorkspaceBridgeServer> owned = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        try (var server = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("owned"), List.of(), Duration.ofMillis(100), Clock.systemUTC())) {
            owned.add(server); server.onClosed(() -> { callbacks.incrementAndGet(); owned.remove(server); });
            await(owned::isEmpty); assertEquals(1, callbacks.get()); server.close(); assertEquals(1, callbacks.get());
            assertThrows(IllegalStateException.class, () -> server.onClosed(() -> { }));
        }
    }

    @Test void expirationAfterAuthenticationIsCorrelatedAndCannotReturnStatus() throws Exception {
        var clock = new MutableClock();
        try (var server = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("later"), List.of(), Duration.ofMinutes(1), clock);
             var client = WorkspaceBridgeClient.status(server.descriptor(), server.credential())) {
            assertTrue(object(exchange(client)).containsKey("result")); clock.now.addAndGet(61_000);
            assertEquals("SESSION_EXPIRED", code(exchange(client)));
        }
    }

    @Test void queryLimitIsBoundedAndClosingTheClientInvalidatesItsSession() throws Exception {
        try (var server = server(); var client = WorkspaceBridgeClient.status(server.descriptor(), server.credential())) {
            for (int i = 0; i < WorkspaceStatusProtocol.MAX_QUERIES; i++) assertTrue(object(exchange(client)).containsKey("result"));
            assertEquals(WorkspaceApiException.Code.SESSION_INVALID, assertThrows(WorkspaceApiException.class, () -> exchange(client)).code());
            client.close(); assertEquals(WorkspaceApiException.Code.SESSION_INVALID, assertThrows(WorkspaceApiException.class, () -> exchange(client)).code());
        }
    }
}
