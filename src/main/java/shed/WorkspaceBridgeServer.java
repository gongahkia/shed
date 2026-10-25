package shed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, bounded, authenticated loopback bridge. Construction belongs on a worker. */
final class WorkspaceBridgeServer implements AutoCloseable {
    static final int MAX_CLIENTS = 8;
    private final WorkspaceRpc rpc;
    private final ServerSocket listener;
    private final ExecutorService clients = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("shed-workspace-client-", 0).factory());
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(MAX_CLIENTS);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task ->
        Thread.ofPlatform().daemon(true).name("shed-workspace-deadline").unstarted(task));
    private final int authenticationTimeoutMillis;
    private final WorkspaceBridgeEndpoint endpoint;
    private final Path descriptor;
    private final byte[] descriptorBytes;

    WorkspaceBridgeServer(WorkspaceQueryService workspace, Path privateDirectory) throws IOException {
        this(workspace, privateDirectory, 3000);
    }

    WorkspaceBridgeServer(WorkspaceQueryService workspace, Path privateDirectory, int authenticationTimeoutMillis) throws IOException {
        this(workspace, null, privateDirectory, authenticationTimeoutMillis);
    }

    WorkspaceBridgeServer(WorkspaceQueryService workspace, WorkspaceProposalService proposals, Path privateDirectory) throws IOException {
        this(workspace, proposals, privateDirectory, 3000);
    }

    private WorkspaceBridgeServer(WorkspaceQueryService workspace, WorkspaceProposalService proposals, Path privateDirectory, int authenticationTimeoutMillis) throws IOException {
        if (authenticationTimeoutMillis < 1 || authenticationTimeoutMillis > 3000) throw new IllegalArgumentException("Invalid authentication deadline");
        this.authenticationTimeoutMillis = authenticationTimeoutMillis;
        deadlines.setRemoveOnCancelPolicy(true);
        this.rpc = new WorkspaceRpc(workspace, proposals);
        this.listener = new ServerSocket(0, MAX_CLIENTS, loopback());
        try {
            Path directory = PrivateWorkspaceFiles.directory(privateDirectory);
            if (directory.startsWith(workspace.root()))
                throw new IOException("Private connection storage must be outside the exposed workspace");
            this.endpoint = new WorkspaceBridgeEndpoint(workspace, listener.getLocalPort());
            this.descriptorBytes = endpoint.serialized();
            this.descriptor = PrivateWorkspaceFiles.create(directory,
                "editor-" + endpoint.instanceId + ".json", descriptorBytes);
            Thread.ofPlatform().daemon(true).name("shed-workspace-listener").start(this::accept);
        } catch (IOException | RuntimeException failure) {
            listener.close(); clients.shutdownNow(); deadlines.shutdownNow(); throw failure;
        }
    }

    Path descriptor() { return descriptor; }
    String workspaceId() { return endpoint.workspaceId; }
    boolean running() { return !closed.get() && !listener.isClosed(); }
    int port() { return listener.getLocalPort(); }
    static InetAddress loopback() throws IOException { return InetAddress.getByAddress(new byte[]{127, 0, 0, 1}); }

    private void accept() {
        while (!closed.get()) {
            try {
                Socket connection = listener.accept();
                if (closed.get() || !connection.getInetAddress().isLoopbackAddress() || !slots.tryAcquire()) { connection.close(); continue; }
                connections.add(connection);
                try { clients.submit(() -> serve(connection)); }
                catch (RuntimeException rejected) { connections.remove(connection); slots.release(); connection.close(); }
            } catch (IOException stopped) {
                if (!closed.get()) {
                    try { close(); } catch (IOException ignored) { }
                }
                return;
            }
        }
    }

    private void serve(Socket connection) {
        try (connection) {
            try {
                serveFrames(connection);
            } catch (WorkspaceApiException limit) {
                WorkspaceFrames.write(connection.getOutputStream(), WorkspaceRpc.error(null, -32000, limit.getMessage(), limit.toMap()),
                    WorkspaceFrames.MAX_RESPONSE_BYTES);
            }
        } catch (IOException disconnected) {
            // Connection EOF, idle expiry and revocation have no editor/UI side effect.
        } finally { connections.remove(connection); slots.release(); }
    }

    private void serveFrames(Socket connection) throws IOException {
        connection.setSoTimeout(authenticationTimeoutMillis);
        connection.setTcpNoDelay(true);
        BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
        var output = connection.getOutputStream();
        ScheduledFuture<?> authenticationDeadline = deadline(connection, authenticationTimeoutMillis);
        try {
            byte[] first = WorkspaceFrames.read(input, 4096);
            if (first == null) return;
            if (!endpoint.authenticates(WorkspaceFrames.decode(first))) {
                WorkspaceFrames.write(output, WorkspaceRpc.error("connect", -32000, "Workspace connection authentication failed",
                    Map.of("code", "PERMISSION_DENIED")), WorkspaceFrames.MAX_RESPONSE_BYTES);
                return;
            }
            WorkspaceFrames.write(output, endpoint.authenticated(), WorkspaceFrames.MAX_RESPONSE_BYTES);
        } finally { authenticationDeadline.cancel(false); }
        connection.setSoTimeout(60_000);
        while (!closed.get()) {
            ScheduledFuture<?> frameDeadline = deadline(connection, 60_000);
            try {
                byte[] request = WorkspaceFrames.read(input, WorkspaceFrames.MAX_REQUEST_BYTES);
                if (request == null) return;
                String response;
                try { response = rpc.handle(WorkspaceFrames.decode(request)); }
                catch (java.nio.charset.CharacterCodingException malformed) {
                    response = WorkspaceRpc.error(null, -32700, "Request is not valid UTF-8", Map.of("code", "INVALID_REQUEST"));
                }
                if (response != null) WorkspaceFrames.write(output, response, WorkspaceFrames.MAX_RESPONSE_BYTES);
            } finally { frameDeadline.cancel(false); }
        }
    }

    private ScheduledFuture<?> deadline(Socket connection, int milliseconds) {
        return deadlines.schedule(() -> {
            try { connection.close(); } catch (IOException ignored) { }
        }, milliseconds, TimeUnit.MILLISECONDS);
    }

    @Override public void close() throws IOException {
        if (closed.getAndSet(true)) return;
        IOException failure = null;
        try { listener.close(); } catch (IOException error) { failure = error; }
        for (Socket connection : connections) {
            try { connection.close(); } catch (IOException error) { if (failure == null) failure = error; }
        }
        clients.shutdownNow(); deadlines.shutdownNow();
        try { PrivateWorkspaceFiles.deleteIfMatching(descriptor, descriptorBytes); }
        catch (IOException error) { if (failure == null) failure = error; }
        if (failure != null) throw failure;
    }
}
