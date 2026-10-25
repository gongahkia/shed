package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceBridgeTest {
    @TempDir Path temporary;

    @Test void liveHeadlessCliReadsUnsavedBuffersAndRejectsStaleVersionsWithoutSavingOrOpeningWindows() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        Path file = Files.writeString(root.resolve("source.txt"), "disk\n");
        AtomicReference<FileBuffer> buffer = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { buffer.set(new FileBuffer(file.toFile())); buffer.get().setContent("unsaved 👩‍💻\n"); }
            catch (IOException error) { throw new RuntimeException(error); }
        });
        try (EditorWorkspaceDocuments source = new EditorWorkspaceDocuments(() -> List.of(buffer.get()), () -> List.of(root), () -> root, root);
             WorkspaceBridgeServer server = new WorkspaceBridgeServer(new WorkspaceQueryService(root, AgentPolicy.readOnly(), source), temporary.resolve("connections"))) {
            WorkspaceDocument snapshot = source.snapshots().getFirst();
            String request = WorkspaceRpcTest.request(1, "documents/read", Map.of("workspaceId", server.workspaceId(),
                "document", snapshot.documentId(), "expectedVersion", snapshot.version()));
            var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
            assertEquals(0, WorkspaceCli.run(args(root, server.descriptor(), request), new ByteArrayInputStream(new byte[0]), output, errors));
            var result = result(output.toString(StandardCharsets.UTF_8));
            assertEquals("unsaved 👩‍💻\n", result.get("content")); assertEquals("EDITOR", result.get("source")); assertEquals(true, result.get("dirty"));
            assertEquals("", errors.toString(StandardCharsets.UTF_8)); assertEquals("disk\n", Files.readString(file));
            output.reset();
            String searchRequest = WorkspaceRpcTest.request(3, "search/text", Map.of("workspaceId", server.workspaceId(), "query", "unsaved"));
            assertEquals(0, WorkspaceCli.run(args(root, server.descriptor(), searchRequest), new ByteArrayInputStream(new byte[0]), output, errors));
            var matches = WorkspaceSearchQueryServiceTest.matches(result(output.toString(StandardCharsets.UTF_8)));
            assertEquals(1, matches.size()); assertEquals(snapshot.documentId(), matches.getFirst().get("documentId"));
            assertEquals(snapshot.version(), matches.getFirst().get("version")); assertEquals("EDITOR", matches.getFirst().get("source"));
            assertEquals("disk\n", Files.readString(file));
            SwingUtilities.invokeAndWait(() -> buffer.get().replaceContentUndoably("newer human text\n"));
            output.reset();
            assertEquals(65, WorkspaceCli.run(args(root, server.descriptor(), request), new ByteArrayInputStream(new byte[0]), output, errors));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("STALE_VERSION"));

            String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            Process process = new ProcessBuilder(executable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                "shed.Texteditor", "--workspace-api", "--root", root.toString(), "--connect", server.descriptor().toString(),
                "--request", WorkspaceRpcTest.request(2, "documents/read", Map.of("workspaceId", server.workspaceId(), "document", snapshot.documentId()))).start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Headless connected CLI must exit");
                assertEquals(0, process.exitValue(), new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("newer human text\n", result(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).get("content"));
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }

    @Test void authenticationAndWorkspaceIdentityFailBeforeReadingEditorState() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        AtomicInteger captures = new AtomicInteger();
        var queries = new WorkspaceQueryService(root, AgentPolicy.readOnly(), () -> { captures.incrementAndGet(); return List.of(); });
        try (WorkspaceBridgeServer server = new WorkspaceBridgeServer(queries, temporary.resolve("connections"))) {
            try (Socket socket = new Socket(WorkspaceBridgeServer.loopback(), server.port())) {
                socket.setSoTimeout(5000);
                WorkspaceFrames.write(socket.getOutputStream(), WorkspaceRpcTest.request(1, "documents/list", Map.of("workspaceId", server.workspaceId())), 4096);
                String denied = WorkspaceFrames.decode(WorkspaceFrames.read(new BufferedInputStream(socket.getInputStream()), 4096));
                assertTrue(denied.contains("PERMISSION_DENIED")); assertEquals(0, captures.get());
                assertEquals(-1, socket.getInputStream().read());
            }
            Path other = Files.createDirectory(temporary.resolve("other"));
            assertEquals(WorkspaceApiException.Code.NOT_FOUND,
                assertThrows(WorkspaceApiException.class, () -> new WorkspaceBridgeClient(server.descriptor(), other)).code());
            Map<String, Object> data = MiniJson.asObject(MiniJson.parseStrict(Files.readString(server.descriptor())));
            String secret = (String) data.get("token");
            assertFalse(WorkspaceBridgeEndpoint.read(server.descriptor()).toString().contains(secret));
            data.put("token", (secret.charAt(0) == '0' ? "1" : "0") + secret.substring(1));
            Path wrongToken = PrivateWorkspaceFiles.create(server.descriptor().getParent(), "wrong.json", MiniJson.stringify(data).getBytes(StandardCharsets.UTF_8));
            assertEquals(WorkspaceApiException.Code.PERMISSION_DENIED,
                assertThrows(WorkspaceApiException.class, () -> new WorkspaceBridgeClient(wrongToken, root)).code());
            assertEquals(0, captures.get());
        }
    }

    @Test void notificationsMalformedMessagesAndLimitsDoNotDesynchronizeAuthenticatedStreams() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        try (WorkspaceBridgeServer server = diskServer(root); WorkspaceBridgeClient client = new WorkspaceBridgeClient(server.descriptor(), root)) {
            assertNull(client.exchange("{\"jsonrpc\":\"2.0\",\"method\":\"workspace/info\"}"));
            assertTrue(client.exchange("{bad}").contains("-32700"));
            assertTrue(client.exchange("{\"method\":\"missing-version\"}").contains("-32600"));
            assertEquals(server.workspaceId(), result(client.exchange(WorkspaceRpcTest.request("after", "workspace/info", Map.of()))).get("workspaceId"));
            assertTrue(client.exchange(WorkspaceRpcTest.request(2, "documents/write", Map.of())).contains("UNSUPPORTED"));
        }
        try (WorkspaceBridgeServer server = diskServer(root);
             Socket socket = new Socket(WorkspaceBridgeServer.loopback(), server.port())) {
            socket.setSoTimeout(5000);
            var input = new BufferedInputStream(socket.getInputStream());
            WorkspaceFrames.write(socket.getOutputStream(), WorkspaceBridgeEndpoint.read(server.descriptor()).authenticationRequest(), 4096);
            assertNotNull(WorkspaceFrames.read(input, 4096));
            socket.getOutputStream().write(" ".repeat(WorkspaceFrames.MAX_REQUEST_BYTES + 1).getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            assertTrue(WorkspaceFrames.decode(WorkspaceFrames.read(input, 4096)).contains("LIMIT_EXCEEDED"));
            assertEquals(-1, input.read());
        }
    }

    @Test void closingRevokesExistingClientsAndRemovesOnlyItsOwnDescriptor() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        WorkspaceBridgeServer first = diskServer(root);
        try (WorkspaceBridgeServer second = diskServer(root); WorkspaceBridgeClient client = new WorkspaceBridgeClient(first.descriptor(), root)) {
            Path descriptor = first.descriptor(); first.close(); first.close();
            assertFalse(first.running()); assertFalse(Files.exists(descriptor)); assertTrue(Files.exists(second.descriptor()));
            assertThrows(IOException.class, () -> client.exchange(WorkspaceRpcTest.request(1, "workspace/info", Map.of())));
            try (WorkspaceBridgeClient other = new WorkspaceBridgeClient(second.descriptor(), root)) {
                assertEquals(second.workspaceId(), result(other.exchange(WorkspaceRpcTest.request(2, "workspace/info", Map.of()))).get("workspaceId"));
            }
        } finally { first.close(); }
    }

    @Test void connectionStorageCannotBeExposedAsWorkspaceContent() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        assertThrows(IOException.class, () -> new WorkspaceBridgeServer(
            new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()), root.resolve("credentials")));
        try (var files = Files.list(root.resolve("credentials"))) { assertEquals(0, files.count()); }
    }

    @Test void authenticationHasAnAbsoluteDeadlineEvenWhenAnUnauthenticatedClientKeepsSendingBytes() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        try (WorkspaceBridgeServer server = new WorkspaceBridgeServer(
                 new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()), temporary.resolve("connections"), 500);
             Socket socket = new Socket(WorkspaceBridgeServer.loopback(), server.port());
             var writers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            socket.setSoTimeout(2500);
            var drip = writers.submit(() -> {
                try {
                    socket.getOutputStream().write('{'); socket.getOutputStream().flush();
                    for (int i = 0; i < 30; i++) {
                        Thread.sleep(100); socket.getOutputStream().write(' '); socket.getOutputStream().flush();
                    }
                } catch (IOException disconnected) { }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            });
            try {
                // A read-idle timeout alone would be extended by this drip and fail here.
                try { assertEquals(-1, socket.getInputStream().read()); }
                catch (java.net.SocketException resetByPeer) { /* Unread request bytes can cause an OS-level reset. */ }
            } finally { drip.cancel(true); }
        }
    }

    private WorkspaceBridgeServer diskServer(Path root) throws IOException {
        return new WorkspaceBridgeServer(new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()), temporary.resolve("connections"));
    }
    private static String[] args(Path root, Path descriptor, String request) {
        return new String[]{"--workspace-api", "--root", root.toString(), "--connect", descriptor.toString(), "--request", request};
    }
    private static Map<String, Object> result(String json) { return MiniJson.asObject(MiniJson.asObject(MiniJson.parseStrict(json)).get("result")); }
}
