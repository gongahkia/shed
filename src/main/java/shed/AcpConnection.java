package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded bidirectional ACP stdio. No reader waits for an agent-to-client request to finish. */
final class AcpConnection implements AutoCloseable {
    static final int MAX_FRAME = 1024 * 1024, MAX_PENDING = 32, MAX_DIAGNOSTICS = 65536;
    interface Handler {
        CompletionStage<Map<String, Object>> request(String method, Map<String, Object> params);
        void notification(String method, Map<String, Object> params);
    }
    record Diagnostics(String stderr, boolean truncated, boolean running, Integer exitCode, String failure) { }
    private final Process process;
    private final Handler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, CompletableFuture<Map<String, Object>>> outbound = new ConcurrentHashMap<>();
    private final Map<Object, CompletableFuture<Map<String, Object>>> inbound = new ConcurrentHashMap<>();
    private final java.util.Set<String> responded = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore handlerSlots = new java.util.concurrent.Semaphore(MAX_PENDING);
    private final java.util.Set<Thread> handlers = ConcurrentHashMap.newKeySet();
    private record Child(ProcessHandle handle, java.time.Instant started) {
        boolean stillOwned() { return started != null && handle.isAlive() && started.equals(handle.info().startInstant().orElse(null)); }
    }
    private final Map<Long, Child> descendants = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<String> writes = new ArrayBlockingQueue<>(64);
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(128), Thread.ofVirtual().name("shed-acp-events-", 0).factory());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("shed-acp-deadlines").factory());
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private final StringBuilder stderr = new StringBuilder();
    private boolean stderrTruncated;
    private volatile String failure = "";
    private Thread writer;

    static AcpConnection start(List<String> command, Path root, Map<String, String> environment, Handler handler) throws IOException {
        Objects.requireNonNull(handler);
        List<String> invocation = List.copyOf(command);
        if (invocation.isEmpty() || invocation.size() > 128 || invocation.getFirst().isBlank()
            || invocation.stream().anyMatch(value -> value.length() > 32768 || value.indexOf('\0') >= 0))
            throw new IllegalArgumentException("A bounded agent executable and argument list is required");
        ProcessBuilder builder = new ProcessBuilder(invocation).directory(WorkspacePaths.root(root).toFile());
        builder.environment().putAll(Map.copyOf(environment));
        return new AcpConnection(builder.start(), handler);
    }

    private AcpConnection(Process process, Handler handler) {
        this.process = process; this.handler = handler;
        deadlines.scheduleWithFixedDelay(this::captureDescendants, 100, 100, TimeUnit.MILLISECONDS);
        writer = Thread.ofVirtual().name("shed-acp-writer").start(this::writeLoop);
        Thread.ofVirtual().name("shed-acp-stderr").start(this::readDiagnostics);
        Thread.ofVirtual().name("shed-acp-reader").start(this::readLoop);
        process.onExit().thenRun(() -> {
            // A child may inherit stdout. Do not let it keep an exited agent connection alive forever.
            try { deadlines.schedule(() -> fail("Agent process exited"), 2, TimeUnit.SECONDS); }
            catch (RejectedExecutionException stopped) { /* Already closed. */ }
        });
    }

    CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(24)) > 0)
            throw new IllegalArgumentException("A bounded request timeout is required");
        String id = "shed-" + sequence.incrementAndGet();
        var result = new CompletableFuture<Map<String, Object>>();
        synchronized (outbound) {
            if (closed.get()) return CompletableFuture.failedFuture(disconnected());
            if (outbound.size() >= MAX_PENDING) return CompletableFuture.failedFuture(new AcpException(-32603, "Too many pending agent requests"));
            outbound.put(id, result);
        }
        try {
            var deadline = deadlines.schedule(() -> {
                if (!result.isDone()) fail("Agent request timed out: " + method);
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            result.whenComplete((value, error) -> { deadline.cancel(false); outbound.remove(id, result); responded.remove(id); });
            send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        } catch (RuntimeException error) { result.completeExceptionally(error); outbound.remove(id, result); }
        return result;
    }

    void notify(String method, Map<String, Object> params) { send(Map.of("jsonrpc", "2.0", "method", method, "params", params)); }
    CompletableFuture<Void> termination() { return termination.copy(); }
    long pid() { return process.pid(); }

    Diagnostics diagnostics() {
        synchronized (stderr) {
            return new Diagnostics(stderr.toString(), stderrTruncated, !closed.get() && process.isAlive(),
                process.isAlive() ? null : process.exitValue(), failure);
        }
    }

    private void send(Map<String, Object> message) {
        if (closed.get()) throw disconnected();
        String json = MiniJson.stringify(message);
        if (json.length() > MAX_FRAME || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FRAME)
            throw new AcpException(-32600, "Agent message exceeds its size limit");
        if (!writes.offer(json)) { fail("Agent is not consuming its bounded output queue"); throw disconnected(); }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) WorkspaceFrames.write(process.getOutputStream(), writes.take(), MAX_FRAME);
        } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
        catch (IOException | RuntimeException error) { fail("Agent input pipe failed"); }
    }

    private void readLoop() {
        try {
            var input = new java.io.BufferedInputStream(process.getInputStream());
            while (!closed.get()) {
                byte[] frame = WorkspaceFrames.read(input, MAX_FRAME);
                if (frame == null) { deliver(() -> fail("Agent output pipe closed")); return; }
                var message = MiniJson.asObject(MiniJson.parseStrict(WorkspaceFrames.decode(frame)));
                if (message == null || !"2.0".equals(message.get("jsonrpc"))) throw new IllegalArgumentException("Invalid JSON-RPC object");
                receive(message);
            }
        } catch (IOException | RuntimeException error) { fail("Agent sent invalid, oversized, or unreadable protocol data"); }
    }

    private void receive(Map<String, Object> message) {
        Object id = message.get("id");
        if (message.containsKey("method")) {
            if (!(message.get("method") instanceof String method) || method.isBlank() || method.length() > 256
                || message.containsKey("result") || message.containsKey("error")) throw new IllegalArgumentException("Invalid request");
            var params = message.containsKey("params") ? MiniJson.asObject(message.get("params")) : Map.<String, Object>of();
            if (params == null) throw new IllegalArgumentException("Expected object parameters");
            if (!message.containsKey("id")) {
                if (method.equals("$/cancel_request")) {
                    var pending = inbound.get(params.get("requestId"));
                    if (pending != null) pending.completeExceptionally(new AcpException(-32800, "Request cancelled"));
                } else deliver(() -> handler.notification(method, params));
                return;
            }
            if (!validId(id)) throw new IllegalArgumentException("Invalid request ID");
            incoming(id, method, params); return;
        }
        if (!(id instanceof String key) || message.containsKey("result") == message.containsKey("error"))
            throw new IllegalArgumentException("Invalid response");
        var pending = outbound.get(key);
        if (pending == null || !responded.add(key)) throw new IllegalArgumentException("Unknown or duplicate response ID");
        if (message.containsKey("error")) {
            var error = MiniJson.asObject(message.get("error"));
            if (error == null || !(error.get("code") instanceof Long code) || !(error.get("message") instanceof String text))
                throw new IllegalArgumentException("Invalid error response");
            deliver(() -> pending.completeExceptionally(new AcpException(code, text, error.get("data"))));
        } else {
            // Empty ACP responses (authenticate/load/write) are also emitted as null by peers.
            // Method-specific validation still requires fields for initialize/new/prompt.
            var value = message.get("result") == null ? Map.<String, Object>of() : MiniJson.asObject(message.get("result"));
            if (value == null) throw new IllegalArgumentException("Expected object result");
            deliver(() -> pending.complete(value));
        }
    }

    private void incoming(Object id, String method, Map<String, Object> params) {
        if (inbound.containsKey(id)) throw new IllegalArgumentException("Duplicate pending request ID");
        if (inbound.size() >= MAX_PENDING || !handlerSlots.tryAcquire()) {
            answer(id, null, new AcpException(-32603, "Too many pending client requests")); return;
        }
        var response = new CompletableFuture<Map<String, Object>>(); inbound.put(id, response);
        response.whenComplete((result, error) -> {
            inbound.remove(id, response); if (!closed.get()) answer(id, result, error);
        });
        // A permission decision or reviewed write may remain pending while other messages flow.
        Thread worker = Thread.ofVirtual().name("shed-acp-request").unstarted(() -> {
            try {
                if (closed.get() || response.isDone()) return;
                var operation = handler.request(method, params).toCompletableFuture();
                response.whenComplete((result, error) -> { if (error != null && !operation.isDone()) operation.cancel(true); });
                operation.whenComplete((result, error) -> {
                    if (error == null) response.complete(result); else response.completeExceptionally(error);
                });
            } catch (RuntimeException error) { response.completeExceptionally(error); }
            finally { handlers.remove(Thread.currentThread()); handlerSlots.release(); }
        });
        response.whenComplete((result, error) -> { if (error != null) worker.interrupt(); });
        handlers.add(worker); worker.start();
    }

    private void answer(Object id, Map<String, Object> result, Throwable error) {
        var message = new LinkedHashMap<String, Object>(); message.put("jsonrpc", "2.0"); message.put("id", id);
        if (error == null) message.put("result", result == null ? Map.of() : result);
        else {
            Throwable cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
            var detail = new LinkedHashMap<String, Object>();
            detail.put("code", cause instanceof AcpException domain ? domain.code() : -32603);
            detail.put("message", cause instanceof AcpException ? cause.getMessage() : "Client operation failed");
            if (cause instanceof AcpException domain && domain.data() != null) detail.put("data", domain.data());
            message.put("error", detail);
        }
        try { send(message); } catch (RuntimeException unavailable) { fail("Agent response could not be delivered"); }
    }

    private void deliver(Runnable event) {
        if (closed.get()) return;
        try { delivery.execute(() -> { if (!closed.get()) try { event.run(); } catch (RuntimeException invalid) { fail("Agent event could not be processed"); } }); }
        catch (RejectedExecutionException overloaded) { fail("Agent exceeded the bounded event queue"); }
    }

    private void readDiagnostics() {
        try (var input = new java.io.InputStreamReader(process.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            for (int size; (size = input.read(buffer)) != -1;) synchronized (stderr) {
                stderr.append(buffer, 0, size);
                if (stderr.length() > MAX_DIAGNOSTICS) { stderr.delete(0, stderr.length() - MAX_DIAGNOSTICS); stderrTruncated = true; }
            }
        } catch (IOException stopped) { /* Process exit/closure is authoritative. */ }
    }

    private void captureDescendants() {
        if (closed.get()) return;
        try {
            process.descendants().limit(257).forEach(child -> descendants.putIfAbsent(child.pid(), new Child(child, child.info().startInstant().orElse(null))));
            descendants.entrySet().removeIf(entry -> !entry.getValue().stillOwned());
            if (descendants.size() > 256) fail("Agent exceeded its tracked child-process limit");
        } catch (RuntimeException unavailable) { fail("Agent child-process tracking failed"); }
    }

    private void fail(String reason) { stop(reason); }
    void abort(String reason) { stop(reason); }
    private AcpException disconnected() { return new AcpException(-32603, failure.isEmpty() ? "Agent connection is closed" : failure); }
    @Override public void close() { stop("Agent connection stopped"); }
    private void stop(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        failure = reason;
        // Stop ownership before releasing blocked pipe readers/writers; never wait on a pipe lock here.
        try { process.descendants().limit(257).forEach(child -> descendants.putIfAbsent(child.pid(), new Child(child, child.info().startInstant().orElse(null)))); } catch (RuntimeException ignored) { }
        for (var child : new ArrayList<>(descendants.values()).reversed()) try { if (child.stillOwned()) child.handle().destroyForcibly(); } catch (RuntimeException ignored) { }
        try { if (process.isAlive()) process.destroyForcibly(); } catch (RuntimeException ignored) { }
        if (writer != null) writer.interrupt(); deadlines.shutdownNow(); delivery.shutdownNow(); writes.clear();
        List<CompletableFuture<Map<String, Object>>> pending;
        synchronized (outbound) { pending = List.copyOf(outbound.values()); outbound.clear(); }
        pending.forEach(future -> future.completeExceptionally(disconnected()));
        inbound.values().forEach(future -> future.completeExceptionally(new AcpException(-32800, "Connection closed"))); inbound.clear();
        handlers.forEach(Thread::interrupt);
        Thread.ofVirtual().name("shed-acp-release").start(() -> {
            try { process.waitFor(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            termination.complete(null);
        });
    }

    private static boolean validId(Object id) { return id instanceof String text && text.length() <= 256 || id instanceof Long; }
}
