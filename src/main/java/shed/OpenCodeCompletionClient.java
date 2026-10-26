package shed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Dedicated ACP connection: fresh conversation per request, no tools, no local inference integration. */
final class OpenCodeCompletionClient implements AutoCloseable {
    static final int MAX_PREFIX = 8192, MAX_SUFFIX = 4096, MAX_COMPLETION = 4096;
    private final InlineCompletionSettings settings;
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("shed-complete-", 0).factory());
    private final Set<CompletableFuture<String>> requests = ConcurrentHashMap.newKeySet();
    private AgentSessionService session;
    private volatile Path directory;
    private boolean closed;

    OpenCodeCompletionClient(InlineCompletionSettings settings) { this.settings = settings; }

    CompletableFuture<String> complete(String prefix, String suffix) {
        if (!settings.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("Inline completion is disabled"));
        if (prefix == null || suffix == null || prefix.length() > MAX_PREFIX || suffix.length() > MAX_SUFFIX)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Inline completion context exceeds its limit"));
        var result = new CompletableFuture<String>(); var used = new AtomicReference<AgentSessionService>();
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Inline completion is closed"));
            requests.add(result);
            result.orTimeout(settings.timeout().toMillis(), TimeUnit.MILLISECONDS).whenComplete((text, error) -> {
                requests.remove(result);
                if (error != null) invalidate(used.get());
            });
            worker.execute(() -> {
                if (result.isDone()) return;
                try {
                    AgentSessionService connection = connect(); used.set(connection);
                    if (result.isDone()) { invalidate(connection); return; }
                    if (connection.snapshot().state() == AgentSessionService.State.NEW) connection.initialize().get();
                    connection.freshSession().get();
                    if (result.isDone()) { invalidate(connection); return; }
                    String wire = "Complete the code between prefix and suffix. Return exactly one JSON object with a string field completion. "
                        + "Include only text to insert; do not repeat surrounding code. Use an empty string when uncertain. "
                        + "No commentary, Markdown fences, tools or file changes. Preserve indentation.\n"
                        + MiniJson.stringify(Map.of("prefix", prefix, "suffix", suffix));
                    var reply = connection.actionPrompt("Complete at cursor", wire, false).get();
                    if (!reply.stopReason().equals("end_turn")) throw new IllegalArgumentException("Incomplete agent suggestion");
                    result.complete(parse(reply.text()));
                } catch (Exception error) { result.completeExceptionally(error); invalidate(used.get()); }
            });
        }
        return result;
    }

    private AgentSessionService connect() throws Exception {
        synchronized (this) {
            if (closed) throw new IllegalStateException("Inline completion is closed");
            if (session != null) return session;
        }
        // Process startup and filesystem work run outside the monitor used by EDT cancellation.
        if (directory == null) directory = PrivateWorkspaceFiles.directory(Path.of(System.getProperty("java.io.tmpdir"), "shed-completion-" + java.util.UUID.randomUUID()));
        var policy = new AgentPolicy(Set.of(AgentPolicy.Capability.START_AGENT), List.of("**/README.md"), List.of(), true);
        var created = new AgentSessionService(settings.provider(), new WorkspaceQueryService(directory, policy, WorkspaceDocumentSource.diskOnly()));
        synchronized (this) {
            if (!closed) { session = created; return created; }
        }
        created.close(); throw new IllegalStateException("Inline completion is closed");
    }

    private void invalidate(AgentSessionService failed) {
        if (failed == null) return;
        synchronized (this) { if (session == failed) session = null; }
        // Cancellation originates on the EDT; process shutdown must never block editing.
        Thread.ofVirtual().name("shed-complete-stop").start(failed::close);
    }

    static String parse(String response) {
        var value = AgentAnswer.json(response);
        if (!value.keySet().equals(Set.of("completion")) || !(value.get("completion") instanceof String text)
            || text.length() > MAX_COMPLETION || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("Agent returned an invalid code suggestion");
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    @Override public void close() {
        AgentSessionService previous;
        synchronized (this) {
            if (closed) return; closed = true; previous = session; session = null;
        }
        requests.forEach(request -> request.cancel(true)); worker.shutdownNow();
        Thread.ofVirtual().name("shed-complete-close").start(() -> {
            if (previous != null) previous.close();
            try { worker.awaitTermination(30, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            Path owned = directory;
            if (owned != null) try { Files.deleteIfExists(owned); }
            catch (java.io.IOException error) { System.getLogger("shed.agent").log(System.Logger.Level.WARNING, "Could not remove completion context directory"); }
        });
    }
}
