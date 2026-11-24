package shed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
import java.util.concurrent.atomic.AtomicReference;

/** Explicit, bounded, authenticated loopback bridge. Construction belongs on a worker. */
final class WorkspaceBridgeServer implements AutoCloseable {
    static final int MAX_CLIENTS = 8;
    private final WorkspaceRpc rpc;
    private final ServerSocket listener;
    private final ExecutorService clients = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("shed-workspace-client-", 0).factory());
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(MAX_CLIENTS);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicBoolean observed = new AtomicBoolean();
    private final AtomicReference<Runnable> closeObserver = new AtomicReference<>();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task ->
        Thread.ofPlatform().daemon(true).name("shed-workspace-deadline").unstarted(task));
    private final int authenticationTimeoutMillis;
    private final WorkspaceBridgeEndpoint endpoint;
    private final Path descriptor;
    private final byte[] descriptorBytes;
    private final WorkspaceStatusAttachment statusAttachment;
    private final ApplicationStatus applicationStatus;
    private final EditorMetadata metadata;

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
        this(workspace, proposals, privateDirectory, authenticationTimeoutMillis, null, List.of(), WorkspaceStatusAttachment.LIFETIME, Clock.systemUTC(), null);
    }

    static WorkspaceBridgeServer status(ApplicationStatus application, Path directory, List<Path> roots) throws IOException {
        return status(application, directory, roots, WorkspaceStatusAttachment.LIFETIME, Clock.systemUTC());
    }

    static WorkspaceBridgeServer status(ApplicationStatus application, Path directory, List<Path> roots, Duration lifetime, Clock clock) throws IOException {
        return new WorkspaceBridgeServer(null, null, directory, WorkspaceStatusProtocol.AUTH_MILLIS, application, roots, lifetime, clock, null);
    }

    static WorkspaceBridgeServer metadata(ApplicationStatus application, EditorMetadata metadata, Path directory, List<Path> roots) throws IOException {
        return metadata(application, metadata, directory, roots, WorkspaceStatusAttachment.LIFETIME, Clock.systemUTC());
    }

    static WorkspaceBridgeServer metadata(ApplicationStatus application, EditorMetadata metadata, Path directory, List<Path> roots, Duration lifetime, Clock clock) throws IOException {
        return new WorkspaceBridgeServer(null, null, directory, WorkspaceStatusProtocol.AUTH_MILLIS, application, roots,
            lifetime, clock, java.util.Objects.requireNonNull(metadata));
    }

    private WorkspaceBridgeServer(WorkspaceQueryService workspace, WorkspaceProposalService proposals, Path privateDirectory,
                                  int authenticationTimeoutMillis, ApplicationStatus application, List<Path> roots,
                                  Duration lifetime, Clock clock, EditorMetadata metadata) throws IOException {
        if (authenticationTimeoutMillis < 1 || authenticationTimeoutMillis > 3000) throw new IllegalArgumentException("Invalid authentication deadline");
        this.authenticationTimeoutMillis = authenticationTimeoutMillis;
        this.applicationStatus = application;
        this.metadata = metadata;
        deadlines.setRemoveOnCancelPolicy(true);
        this.rpc = application == null ? new WorkspaceRpc(workspace, proposals) : new WorkspaceRpc(application, metadata);
        Path directory = application == null ? PrivateWorkspaceFiles.directory(privateDirectory) : WorkspaceStatusAttachment.privateDirectory(privateDirectory);
        this.listener = new ServerSocket(0, MAX_CLIENTS, loopback());
        WorkspaceStatusAttachment created = null;
        try {
            if (application == null) {
                if (directory.startsWith(workspace.root())) throw new IOException("Private connection storage must be outside the exposed workspace");
                this.endpoint = new WorkspaceBridgeEndpoint(workspace, listener.getLocalPort());
                this.descriptorBytes = endpoint.serialized();
                this.descriptor = PrivateWorkspaceFiles.create(directory, "editor-" + endpoint.instanceId + ".json", descriptorBytes);
                this.statusAttachment = null;
            } else {
                this.endpoint = null; this.descriptorBytes = null;
                created = new WorkspaceStatusAttachment(application, listener.getLocalPort(), directory, roots, lifetime, clock, metadata);
                this.statusAttachment = created; this.descriptor = created.discoveryFile();
                deadlines.schedule(() -> {
                    try { close(); } catch (IOException cleanup) {
                        System.getLogger("shed.workspace").log(System.Logger.Level.WARNING, "Expired status attachment could not remove its private files");
                    }
                }, lifetime.toMillis(), TimeUnit.MILLISECONDS);
            }
            Thread.ofPlatform().daemon(true).name("shed-workspace-listener").start(this::accept);
        } catch (IOException | RuntimeException failure) {
            if (created != null) try { created.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (metadata != null) metadata.close();
            listener.close(); clients.shutdownNow(); deadlines.shutdownNow(); throw failure;
        }
    }

    Path descriptor() { return descriptor; }
    String workspaceId() { return endpoint == null ? null : endpoint.workspaceId; }
    Path credential() { return statusAttachment == null ? null : statusAttachment.credentialFile(); }
    boolean statusOnly() { return statusAttachment != null && metadata == null; }
    boolean metadataEnabled() { return metadata != null; }
    boolean navigationEnabled() { return metadata != null && metadata.navigation != null; }
    boolean controlEnabled() { return metadata != null && metadata.control != null; }
    void revokeObservation() { if (metadata != null) metadata.close(); }
    ApplicationStatus statusApplication() { return applicationStatus; }
    int connectionCount() { return connections.size(); }
    void onClosed(Runnable observer) {
        java.util.Objects.requireNonNull(observer);
        if (!observed.compareAndSet(false, true)) throw new IllegalStateException("Bridge close observer is already registered");
        closeObserver.set(observer);
        if (terminated.get()) notifyClosed();
    }
    private void notifyClosed() {
        Runnable observer = closeObserver.getAndSet(null);
        if (observer != null) observer.run();
    }
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
        if (statusAttachment != null) { serveStatus(connection); return; }
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

    private void serveStatus(Socket connection) throws IOException {
        connection.setSoTimeout(WorkspaceStatusProtocol.AUTH_MILLIS);
        connection.setTcpNoDelay(true);
        BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
        var output = connection.getOutputStream();
        WorkspaceStatusProtocol.Session session;
        ScheduledFuture<?> authenticationDeadline = deadline(connection, WorkspaceStatusProtocol.AUTH_MILLIS);
        try { session = WorkspaceStatusProtocol.accept(statusAttachment, input, output); }
        catch (WorkspaceApiException rejected) {
            WorkspaceStatusProtocol.write(output, WorkspaceRpc.error("attach", -32000, "Status attachment rejected", Map.of("code", rejected.code().name())));
            return;
        } finally { authenticationDeadline.cancel(false); }
        try (session) {
            connection.setSoTimeout(WorkspaceStatusProtocol.IDLE_MILLIS);
            for (long sequence = 1; sequence <= WorkspaceStatusProtocol.MAX_QUERIES && !closed.get(); sequence++) {
                ScheduledFuture<?> frameDeadline = deadline(connection, WorkspaceStatusProtocol.IDLE_MILLIS);
                try {
                    String request = session.unpack(session.read(input), "request", sequence);
                    if (statusAttachment.expired()) {
                        session.write(output, session.pack(WorkspaceRpc.error(statusRequestId(request), -32000,
                            "Attachment has expired", Map.of("code", "SESSION_EXPIRED")), "response", sequence));
                        return;
                    }
                    String response;
                    if (navigationEnabled()) {
                        var requestActive = new AtomicBoolean(true);
                        try { response = rpc.handle(request, () -> requestActive.get() && !closed.get() && !connection.isClosed() && !statusAttachment.expired()); }
                        finally { requestActive.set(false); }
                    } else response = rpc.handle(request);
                    boolean uncertain = navigationEnabled() && navigationMutation(request) && navigationMayHaveApplied(response);
                    if (statusAttachment.expired() || closed.get()) {
                        response = WorkspaceRpc.error(statusRequestId(request), -32000, "Attachment is no longer valid",
                            uncertain ? Map.of("code", "OUTCOME_UNKNOWN", "outcome", "unknown") : Map.of("code", "SESSION_EXPIRED"));
                    } else if (metadata != null && (requestMethodIsMetadata(request) || navigationMutation(request) || requestMethod(request).equals(EditorNavigation.CONTEXT))) {
                        try { metadata.validateAuthorization(); }
                        catch (WorkspaceApiException revoked) {
                            response = WorkspaceRpc.error(statusRequestId(request), -32000, "Attachment authorization is unavailable",
                                uncertain ? Map.of("code", "OUTCOME_UNKNOWN", "outcome", "unknown") : Map.of("code", revoked.code().name()));
                        }
                    }
                    if (metadata != null) {
                        try { session.size(response); }
                        catch (WorkspaceApiException oversized) {
                            response = WorkspaceRpc.error(statusRequestId(request), -32000, "Observation response exceeds its limit", Map.of("code", "LIMIT_EXCEEDED"));
                        }
                    }
                    session.write(output, session.pack(response, "response", sequence));
                } finally { frameDeadline.cancel(false); }
            }
        }
    }

    private static Object statusRequestId(String request) {
        try {
            var value = MiniJson.asObject(MiniJson.parseStrict(request)); Object id = value == null ? null : value.get("id");
            return id instanceof Long || id instanceof String text && text.length() <= 256 ? id : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static boolean requestMethodIsMetadata(String request) {
        try {
            var value = MiniJson.asObject(MiniJson.parseStrict(request));
            return value != null && "editor/metadata".equals(value.get("method"));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static String requestMethod(String request) {
        try { var value = MiniJson.asObject(MiniJson.parseStrict(request)); return value != null && value.get("method") instanceof String method ? method : ""; }
        catch (IllegalArgumentException invalid) { return ""; }
    }
    private static boolean navigationMutation(String request) {
        String method = requestMethod(request);
        return EditorNavigation.MUTATIONS.contains(method)
            || EditorControl.OPERATIONS.contains(method) && !method.equals("editor/source/read");
    }
    private static boolean navigationMayHaveApplied(String response) {
        var value = MiniJson.asObject(MiniJson.parseStrict(response));
        if (value.containsKey("result")) return true;
        var error = MiniJson.asObject(value.get("error")); var data = error == null ? null : MiniJson.asObject(error.get("data"));
        return data != null && "OUTCOME_UNKNOWN".equals(data.get("code"));
    }

    private ScheduledFuture<?> deadline(Socket connection, int milliseconds) {
        return deadlines.schedule(() -> {
            try { connection.close(); } catch (IOException ignored) { }
        }, milliseconds, TimeUnit.MILLISECONDS);
    }

    @Override public void close() throws IOException {
        if (closed.getAndSet(true)) return;
        if (metadata != null) metadata.close();
        IOException failure = null;
        try { listener.close(); } catch (IOException error) { failure = error; }
        for (Socket connection : connections) {
            try { connection.close(); } catch (IOException error) { if (failure == null) failure = error; }
        }
        clients.shutdownNow();
        try {
            if (statusAttachment != null) statusAttachment.close();
            else PrivateWorkspaceFiles.deleteIfMatching(descriptor, descriptorBytes);
        }
        catch (IOException error) { if (failure == null) failure = error; }
        finally { deadlines.shutdownNow(); terminated.set(true); notifyClosed(); }
        if (failure != null) throw failure;
    }
}
