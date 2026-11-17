package shed;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Explicit-request coordinator using the existing pool. EDT-owned; one job, one newest pending
 * source, eight subscribers maximum and one weak cached result. No timer or document listener. */
final class MarkdownAnalysisService implements AutoCloseable {
    interface Analyzer {
        MarkdownHeadingAnalysis analyze(MarkdownSemanticSnapshot.Source source, BooleanSupplier cancelled);
    }
    record Diagnostics(long submitted, long reused, long staleRejected, long superseded, int running, int pending, int subscribers) { }
    private record Subscriber(BooleanSupplier current, Consumer<MarkdownHeadingAnalysis> completion) { }
    private static final int MAX_SUBSCRIBERS = 8;
    private static final class Request {
        final MarkdownSemanticSnapshot.Source source;
        final Map<Object, Subscriber> subscribers = new IdentityHashMap<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long ticket;
        int job = -1;
        Request(MarkdownSemanticSnapshot.Source source, long ticket) { this.source = source; this.ticket = ticket; }
    }
    private final AsyncJobService jobs;
    private final Analyzer analyzer;
    private Request running, pending;
    private WeakReference<MarkdownHeadingAnalysis> cached = new WeakReference<>(null);
    private boolean closed;
    private long sequence, submitted, reused, staleRejected, superseded;

    MarkdownAnalysisService(AsyncJobService jobs) { this(jobs, MarkdownHeadingAnalysis::analyze); }
    MarkdownAnalysisService(AsyncJobService jobs, Analyzer analyzer) { this.jobs = jobs; this.analyzer = analyzer; }

    void request(Object owner, MarkdownSemanticSnapshot.Source source, BooleanSupplier current, Consumer<MarkdownHeadingAnalysis> completion) {
        edt();
        if (closed || !current.getAsBoolean()) return;
        remove(owner);
        prune(running); prune(pending);
        MarkdownHeadingAnalysis retained = cached.get();
        if (retained != null && retained.source().matches(source)) { reused++; completion.accept(retained); return; }
        if (subscriberCount() >= MAX_SUBSCRIBERS) { completion.accept(cancelled(source)); return; }
        if (running != null && !running.cancelled.get() && running.source.matches(source)) {
            reused++; running.subscribers.put(owner, new Subscriber(current, completion)); return;
        }
        if (pending != null && pending.source.matches(source)) {
            reused++; pending.subscribers.put(owner, new Subscriber(current, completion)); return;
        }
        Request obsolete = pending;
        if (pending != null) { superseded++; pending = null; }
        Request request = new Request(source, ++sequence);
        request.subscribers.put(owner, new Subscriber(current, completion));
        if (running == null) start(request);
        else {
            pending = request;
            if (running.subscribers.isEmpty()) cancelRunning();
        }
        if (obsolete != null) for (var subscriber : obsolete.subscribers.values())
            if (!closed && subscriber.current().getAsBoolean()) subscriber.completion().accept(cancelled(obsolete.source, "Heading request superseded"));
    }

    private void start(Request request) {
        running = request; submitted++;
        long ticket = request.ticket;
        // Completion captures ONLY a ticket/service. AsyncJobService keeps completion hooks in
        // bounded job history; capturing Request/Source there would retain obsolete documents.
        var source = request.source;
        var cancelled = request.cancelled;
        request.job = jobs.submit("Markdown headings", token -> {
            BooleanSupplier stop = () -> cancelled.get() || token.isCancelled();
            try { return analyzer.analyze(source, stop); }
            catch (RuntimeException error) {
                return MarkdownHeadingAnalysis.from(MarkdownSemanticSnapshot.unavailable(source,
                    stop.getAsBoolean() ? MarkdownSemanticSnapshot.Status.CANCELLED : MarkdownSemanticSnapshot.Status.FAILED,
                    "Heading worker failed"), stop, System.nanoTime());
            }
        }, (job, result, error) -> finish(ticket, result, error));
    }

    private void finish(long ticket, MarkdownHeadingAnalysis result, Exception error) {
        edt();
        if (closed || running == null || running.ticket != ticket) return;
        Request completed = running;
        running = null;
        if (error != null || result == null) {
            boolean cancelled = completed.cancelled.get() || error instanceof java.util.concurrent.CancellationException;
            result = new MarkdownHeadingAnalysis(MarkdownSemanticSnapshot.unavailable(completed.source,
                cancelled ? MarkdownSemanticSnapshot.Status.CANCELLED : MarkdownSemanticSnapshot.Status.FAILED,
                "Heading worker did not complete"), cancelled ? MarkdownHeadingAnalysis.Path.CANCELLED : MarkdownHeadingAnalysis.Path.FAILED,
                java.util.List.of(), 0);
        }
        boolean usable = !completed.cancelled.get() && result.path() != MarkdownHeadingAnalysis.Path.CANCELLED
            && result.source().matches(completed.source);
        if (usable && result.path() != MarkdownHeadingAnalysis.Path.FAILED) cached = new WeakReference<>(result);
        // Detach pending before callbacks: a callback may request/cancel work reentrantly.
        Request next = pending; pending = null;
        if (next != null) {
            prune(next);
            if (!next.subscribers.isEmpty()) start(next);
        }
        for (var subscriber : completed.subscribers.values()) {
            if (!closed && subscriber.current().getAsBoolean()) {
                subscriber.completion().accept(usable ? result : cancelled(completed.source, "Heading request cancelled"));
            } else staleRejected++;
        }
        completed.subscribers.clear();
    }

    void cancel(Object owner) {
        edt(); remove(owner);
        if (pending != null && pending.subscribers.isEmpty()) pending = null;
        if (running != null && running.subscribers.isEmpty()) cancelRunning();
    }
    void documentClosed(DocumentWorkspace.Id id) {
        edt();
        MarkdownHeadingAnalysis retained = cached.get();
        if (retained != null && retained.source().documentId().equals(id)) cached.clear();
        if (pending != null && pending.source.documentId().equals(id)) pending = null;
        if (running != null && running.source.documentId().equals(id)) { running.subscribers.clear(); cancelRunning(); }
    }
    private void remove(Object owner) {
        if (running != null) running.subscribers.remove(owner);
        if (pending != null) pending.subscribers.remove(owner);
    }
    private void cancelRunning() {
        running.cancelled.set(true);
        jobs.cancel(running.job); // Keep its slot until actual completion; interruption need not stop parsing immediately.
    }
    private void prune(Request request) {
        if (request != null) request.subscribers.values().removeIf(s -> {
            if (s.current().getAsBoolean()) return false;
            staleRejected++; return true;
        });
    }
    private int subscriberCount() { return (running == null ? 0 : running.subscribers.size()) + (pending == null ? 0 : pending.subscribers.size()); }
    private static MarkdownHeadingAnalysis cancelled(MarkdownSemanticSnapshot.Source source) {
        return cancelled(source, "Heading subscriber budget reached");
    }
    private static MarkdownHeadingAnalysis cancelled(MarkdownSemanticSnapshot.Source source, String detail) {
        return new MarkdownHeadingAnalysis(MarkdownSemanticSnapshot.unavailable(source, MarkdownSemanticSnapshot.Status.CANCELLED,
            detail), MarkdownHeadingAnalysis.Path.CANCELLED, java.util.List.of(), 0);
    }
    Diagnostics diagnostics() { edt(); return new Diagnostics(submitted, reused, staleRejected, superseded, running == null ? 0 : 1, pending == null ? 0 : 1, subscriberCount()); }
    void clear() {
        edt(); pending = null; cached.clear();
        if (running != null) { running.subscribers.clear(); cancelRunning(); }
    }
    @Override public void close() {
        edt(); closed = true; clear(); running = null;
    }
    private static void edt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Heading coordination requires EDT"); }
}
