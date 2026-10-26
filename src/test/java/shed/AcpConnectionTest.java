package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcpConnectionTest {
    @TempDir Path root;
    private final List<AcpConnection> connections = new ArrayList<>();
    @org.junit.jupiter.api.AfterEach void closeProcesses() throws Exception {
        for (var connection : connections) { connection.close(); connection.termination().get(10, TimeUnit.SECONDS); }
        FixtureCleanup.deleteEventually(root);
    }
    private AcpConnection start(List<String> command, Path directory, Map<String, String> environment, AcpConnection.Handler handler) throws java.io.IOException {
        var connection = AcpConnection.start(command, directory, environment, handler);
        connections.add(connection); return connection;
    }
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test void pendingPeerRequestDoesNotBlockOtherRequestsOrStructuredErrors() throws Exception {
        var permission = new CompletableFuture<Map<String, Object>>(); var entered = new CompletableFuture<Void>();
        try (var connection = start(command("multiplex"), root, Map.of(), new AcpConnection.Handler() {
            public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) { entered.complete(null); return permission; }
            public void notification(String method, Map<String, Object> params) { }
        })) {
            var pending = connection.request("pending", Map.of(), TIMEOUT); entered.get(10, TimeUnit.SECONDS);
            assertEquals("echo", connection.request("echo", Map.of(), TIMEOUT).get(10, TimeUnit.SECONDS).get("method"));
            assertEquals(Map.of(), connection.request("null-result", Map.of(), TIMEOUT).get(10, TimeUnit.SECONDS));
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> connection.request("error", Map.of(), TIMEOUT).get(10, TimeUnit.SECONDS));
            assertInstanceOf(AcpException.class, failure.getCause()); assertEquals(-32000, ((AcpException) failure.getCause()).code());
            assertEquals(Map.of("method", "login"), ((AcpException) failure.getCause()).data());
            permission.complete(Map.of("approved", false)); assertEquals(Map.of("approved", false), pending.get(10, TimeUnit.SECONDS).get("permission"));
        }
    }

    @Test void genericCancellationReleasesAnOutstandingPeerRequest() throws Exception {
        var permission = new CompletableFuture<Map<String, Object>>(); var entered = new CompletableFuture<Void>();
        try (var connection = start(command("echo"), root, Map.of(), new AcpConnection.Handler() {
            public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) { entered.complete(null); return permission; }
            public void notification(String method, Map<String, Object> params) { }
        })) {
            connection.request("pending", Map.of(), TIMEOUT); entered.get(10, TimeUnit.SECONDS);
            connection.request("cancel-peer-request", Map.of(), TIMEOUT).get(10, TimeUnit.SECONDS);
            await(permission::isCancelled); assertTrue(connection.diagnostics().running());
        }
    }

    @Test void invalidOrOversizedFramesStopTheProcessAndFailPendingWork() throws Exception {
        for (String mode : List.of("malformed", "oversized", "utf8")) try (var connection = start(command(mode), root, Map.of(), empty())) {
            var pending = connection.request("echo", Map.of(), TIMEOUT);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
            connection.termination().get(10, TimeUnit.SECONDS);
            assertFalse(ProcessHandle.of(connection.pid()).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(connection.diagnostics().failure().contains("protocol data"));
        }
    }

    @Test void deadlinesStopUnresponsiveAgentsAndBoundPendingRequests() throws Exception {
        try (var connection = start(command("hang"), root, Map.of(), empty())) {
            List<CompletableFuture<Map<String, Object>>> pending = new ArrayList<>();
            for (int i = 0; i < AcpConnection.MAX_PENDING; i++) pending.add(connection.request("hang", Map.of(), Duration.ofSeconds(2)));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> connection.request("overflow", Map.of(), TIMEOUT).get());
            connection.termination().get(10, TimeUnit.SECONDS); assertTrue(pending.stream().allMatch(CompletableFuture::isCompletedExceptionally));
            assertTrue(connection.diagnostics().failure().contains("timed out"));
        }
    }

    @Test void diagnosticsAreBoundedAndClosingStopsObservedChildren() throws Exception {
        try (var connection = start(command("stderr"), root, Map.of(), empty())) {
            connection.request("echo", Map.of(), TIMEOUT).get(10, TimeUnit.SECONDS);
            await(() -> connection.diagnostics().truncated()); assertTrue(connection.diagnostics().stderr().length() <= AcpConnection.MAX_DIAGNOSTICS);
        }
        var child = new CompletableFuture<Long>();
        var connection = start(command("descendant"), root, Map.of(), new AcpConnection.Handler() {
            public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) { return CompletableFuture.failedFuture(new AcpException(-32601, "unsupported")); }
            public void notification(String method, Map<String, Object> params) { child.complete((Long) params.get("pid")); }
        });
        long pid = child.get(10, TimeUnit.SECONDS);
        try {
            connection.close(); connection.termination().get(10, TimeUnit.SECONDS);
            await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally { connection.close(); ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly); }
    }

    @Test void peerCancellationCannotCreateUnboundedBlockedHandlerThreads() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1); var started = new java.util.concurrent.atomic.AtomicInteger();
        try (var connection = start(command("echo"), root, Map.of(), new AcpConnection.Handler() {
            public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) {
                started.incrementAndGet();
                while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { /* Deliberately uncooperative fixture. */ }
                return CompletableFuture.completedFuture(Map.of());
            }
            public void notification(String method, Map<String, Object> params) { }
        })) {
            connection.request("storm", Map.of(), TIMEOUT).get(10, TimeUnit.SECONDS);
            assertTrue(started.get() > 0); assertTrue(started.get() <= AcpConnection.MAX_PENDING);
            assertEquals("echo", connection.request("echo", Map.of(), TIMEOUT).get(10, TimeUnit.SECONDS).get("method"));
        } finally { release.countDown(); }
    }

    static List<String> command(String mode) {
        return javaCommand(AcpFixtureProcess.class.getName(), mode);
    }
    static List<String> javaCommand(String main, String... args) {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
            "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), main)); command.addAll(List.of(args)); return command;
    }
    static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }
    private static AcpConnection.Handler empty() {
        return new AcpConnection.Handler() {
            public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) { return CompletableFuture.failedFuture(new AcpException(-32601, "unsupported")); }
            public void notification(String method, Map<String, Object> params) { }
        };
    }
}
