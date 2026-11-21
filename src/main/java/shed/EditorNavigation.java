package shed;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/** context-checked presentation only; source samples are bounded, private and request-local. */
final class EditorNavigation implements AutoCloseable {
    static final String CONTEXT = "editor/navigation/context";
    static final Set<String> MUTATIONS = Set.of("editor/caret/set", "editor/selection/set", "editor/reveal");
    static final int MAX_BOUNDARY_UNITS = 16_384;
    interface Host extends EditorMetadata.Host {
        State state();
        Prepared prepare(Command command);
        Applied apply(Prepared prepared, BooleanSupplier authorized);
    }
    record State(EditorMetadata.Context metadata, long interaction) { }
    record Command(String method, String instance, String window, String project, String pane, String document,
                   long revision, long interaction, int caret, int selectionStart, int selectionEnd, int start, int end) { }
    record Boundary(String text, int offset) { }
    record Prepared(State before, Command command, List<Boundary> boundaries) { }
    record Applied(State after, String visibility) { }
    private final EditorMetadata grant;
    private final EditorInteraction interaction;
    private final AtomicReference<Host> host;
    private final AtomicReference<Lease> active = new AtomicReference<>();
    private volatile long lastEdtNanos;

    EditorNavigation(EditorMetadata grant, Host host, EditorInteraction interaction) {
        this.grant = grant; this.host = new AtomicReference<>(host); this.interaction = interaction;
    }
    private final class Lease {
        final AtomicInteger pending = new AtomicInteger();
        final AtomicBoolean workerDone = new AtomicBoolean();
        void release() { if (workerDone.get() && pending.get() == 0) active.compareAndSet(this, null); }
    }
    Map<String, Object> dispatch(String method, Map<String, Object> params, Object requestId, BooleanSupplier connection) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Navigation dispatch belongs off the EDT");
        grant.requireLive();
        boolean context = CONTEXT.equals(method);
        if (!context && !MUTATIONS.contains(method)) throw fail(WorkspaceApiException.Code.UNSUPPORTED);
        if (context) {
            if (!params.keySet().equals(Set.of("schemaVersion", "projectId"))) throw fail(WorkspaceApiException.Code.INVALID_REQUEST);
            version(params); project(params);
        }
        Command command = context ? null : parse(method, params);
        var lease = new Lease();
        if (!active.compareAndSet(null, lease)) throw fail(WorkspaceApiException.Code.BUSY);
        try {
            State first = ui(lease, () -> owner().state(), false);
            grant.authorize(first.metadata());
            if (context) {
                State current = ui(lease, () -> owner().state(), false);
                if (!first.equals(current)) throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
                grant.requireLive(); return result(current);
            }
            verify(command, first);
            // reject a response that could exceed the wire bound before any presentation change
            var maximum = new java.util.LinkedHashMap<>(result(new State(first.metadata(), Long.MAX_VALUE)));
            maximum.put("operation", method); maximum.put("applied", true); maximum.put("visibility", "selection");
            for (String field : List.of("documentLength", "caretOffset", "selectionStart", "selectionEnd")) maximum.put(field, Integer.MAX_VALUE);
            maximum.put("documentRevision", Long.MAX_VALUE); maximum.put("dirty", false);
            WorkspaceStatusProtocol.size(WorkspaceRpc.result(requestId, maximum));
            Prepared prepared = ui(lease, () -> owner().prepare(command), false);
            if (!prepared.before().equals(first)) throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
            for (Boundary boundary : prepared.boundaries())
                if (!GraphemeBoundary.isBoundary(boundary.text(), boundary.offset())) throw fail(WorkspaceApiException.Code.INVALID_OFFSET);
            grant.authorize(prepared.before().metadata());
            Applied applied = ui(lease, () -> {
                grant.requireLive();
                if (!connection.getAsBoolean()) throw fail(WorkspaceApiException.Code.SESSION_INVALID);
                return owner().apply(prepared, () -> {
                    grant.requireLive(); return connection.getAsBoolean();
                });
            }, true);
            var response = new java.util.LinkedHashMap<>(result(applied.after()));
            response.put("operation", method); response.put("applied", true); response.put("visibility", applied.visibility());
            return response;
        } finally { lease.workerDone.set(true); lease.release(); }
    }

    Map<String, Object> result(State state) {
        var value = new java.util.LinkedHashMap<>(grant.result(state.metadata()));
        value.put("capabilities", List.of("status", "editor-metadata", "editor-navigation"));
        value.put("contextRevision", state.interaction()); return value;
    }
    private Host owner() {
        Host current = host.get();
        if (current == null) throw fail(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
        return current;
    }
    private <T> T ui(Lease lease, Callable<T> operation, boolean mutation) {
        var stage = new AtomicInteger(); // 0 queued, 1 running, 2 completed, 3 cancelled before start
        var released = new AtomicBoolean();
        Runnable complete = () -> { if (released.compareAndSet(false, true)) { lease.pending.decrementAndGet(); lease.release(); } };
        var task = new FutureTask<T>(() -> {
            if (!stage.compareAndSet(0, 1)) throw fail(WorkspaceApiException.Code.CANCELLED);
            long start = System.nanoTime();
            try { return operation.call(); }
            finally { lastEdtNanos = System.nanoTime() - start; stage.set(2); complete.run(); }
        });
        lease.pending.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            try { task.run(); } finally { complete.run(); }
        });
        try { return task.get(EditorMetadata.EDT_WAIT_MILLIS, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) {
            if (stage.compareAndSet(0, 3)) { task.cancel(false); throw fail(WorkspaceApiException.Code.TIMEOUT); }
            if (!mutation) { task.cancel(false); throw fail(WorkspaceApiException.Code.TIMEOUT); }
            try { return task.get(1000, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException slow) { throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN); }
            catch (java.util.concurrent.ExecutionException failed) { throw failure(failed); }
        } catch (InterruptedException interrupted) {
            boolean cancelled = stage.compareAndSet(0, 3);
            if (cancelled) task.cancel(false);
            Thread.currentThread().interrupt(); throw fail(mutation && !cancelled ? WorkspaceApiException.Code.OUTCOME_UNKNOWN : WorkspaceApiException.Code.CANCELLED);
        } catch (java.util.concurrent.ExecutionException failed) { throw failure(failed); }
    }
    private static WorkspaceApiException failure(java.util.concurrent.ExecutionException failed) {
        return failed.getCause() instanceof WorkspaceApiException known ? known : fail(WorkspaceApiException.Code.INVALID_CONTEXT);
    }
    long lastEdtNanos() { return lastEdtNanos; }
    @Override public void close() { host.set(null); interaction.close(); }

    static Prepared prepare(EditorPane pane, State state, Command command, boolean eligible) {
        verify(command, state);
        if (!eligible) throw fail(WorkspaceApiException.Code.UNSUPPORTED_MODE);
        var area = pane.getTextArea(); int length = state.metadata().length();
        if (command.start() < 0 || command.end() < command.start() || command.end() > length) throw fail(WorkspaceApiException.Code.INVALID_OFFSET);
        // stock complex/wrapped layout outside this bounded workload remains unqualified
        if (length > MAX_BOUNDARY_UNITS && (area.getLineWrap() || Boolean.TRUE.equals(area.getDocument().getProperty("i18n"))))
            throw fail(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
        var samples = new ArrayList<Boundary>();
        for (int position : new int[]{command.start(), command.end()}) {
            if (position == 0 || position == length) { samples.add(new Boundary("", 0)); continue; }
            try {
                String pair = area.getDocument().getText(position - 1, 2);
                if (pair.charAt(0) < 128 && pair.charAt(1) < 128) { samples.add(new Boundary(pair, 1)); continue; }
                var lines = area.getDocument().getDefaultRootElement(); var line = lines.getElement(lines.getElementIndex(position));
                int start = line.getStartOffset(), end = Math.min(length, line.getEndOffset());
                if (end - start > MAX_BOUNDARY_UNITS) throw fail(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
                samples.add(new Boundary(area.getDocument().getText(start, end - start), position - start));
            } catch (javax.swing.text.BadLocationException invalid) { throw fail(WorkspaceApiException.Code.INVALID_OFFSET); }
        }
        return new Prepared(state, command, List.copyOf(samples));
    }

    static Applied apply(EditorPane pane, Prepared prepared, State current, boolean eligible, EditorInteraction interaction, BooleanSupplier authorized,
                         java.util.function.Supplier<State> after) {
        if (!current.equals(prepared.before())) throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
        verify(prepared.command(), current);
        if (!eligible || pane.isDisposed() || pane.isHiddenByFocusMode()) throw fail(WorkspaceApiException.Code.UNSUPPORTED_MODE);
        Rectangle reveal = null; String visibility = "caret";
        Command command = prepared.command();
        if ("editor/reveal".equals(command.method())) {
            try {
                Rectangle2D first = pane.getTextArea().modelToView2D(command.start()), last = pane.getTextArea().modelToView2D(command.end());
                var extent = pane.getScrollPane().getViewport().getExtentSize();
                if (!geometry(first) || !geometry(last) || extent.width <= 0 || extent.height <= 0) throw fail(WorkspaceApiException.Code.GEOMETRY_UNAVAILABLE);
                reveal = first.createUnion(last).getBounds(); visibility = "range";
                if (reveal.height > extent.height || reveal.width > extent.width) { reveal = first.getBounds(); visibility = "start"; }
            } catch (javax.swing.text.BadLocationException unavailable) { throw fail(WorkspaceApiException.Code.GEOMETRY_UNAVAILABLE); }
        }
        if (!current.equals(after.get())) throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
        if (!authorized.getAsBoolean()) throw fail(WorkspaceApiException.Code.SESSION_INVALID);
        interaction.changed(); // consume even a no-op command's context token
        try {
            if (reveal != null) pane.getTextArea().scrollRectToVisible(reveal);
            else if ("editor/selection/set".equals(command.method())) { pane.surface().select(command.end(), command.start()); visibility = "selection"; }
            else pane.surface().select(command.start(), command.start());
            State result = after.get(); var before = current.metadata(); var actual = result.metadata();
            if (!before.documentId().equals(actual.documentId()) || !before.paneId().equals(actual.paneId()) || before.revision() != actual.revision()
                || before.length() != actual.length() || before.dirty() != actual.dirty()) throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN);
            if (reveal != null) {
                if (before.caret() != actual.caret() || before.start() != actual.start() || before.end() != actual.end()) throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN);
            } else if (actual.caret() != command.end() || actual.start() != command.start() || actual.end() != command.end()) throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN);
            return new Applied(result, visibility);
        } catch (RuntimeException partial) { throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN); }
    }
    private static boolean geometry(Rectangle2D rect) {
        return rect != null && Double.isFinite(rect.getX()) && Double.isFinite(rect.getY()) && rect.getX() >= 0 && rect.getY() >= 0
            && rect.getWidth() >= 0 && rect.getHeight() > 0 && rect.getMaxX() < Integer.MAX_VALUE && rect.getMaxY() < Integer.MAX_VALUE;
    }
    static void verify(Command c, State state) {
        var m = state.metadata();
        if (!c.pane().equals(m.paneId())) throw fail(WorkspaceApiException.Code.PANE_UNAVAILABLE);
        if (!c.document().equals(m.documentId())) throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
        if (c.revision() != m.revision()) throw fail(WorkspaceApiException.Code.STALE_REVISION);
        if (c.interaction() != state.interaction() || c.caret() != m.caret() || c.selectionStart() != m.start() || c.selectionEnd() != m.end())
            throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
    }
    private Command parse(String method, Map<String, Object> p) {
        var fields = new java.util.HashSet<>(Set.of("schemaVersion", "instanceId", "windowId", "projectId", "paneId", "documentId", "documentRevision", "contextRevision", "caretOffset", "selectionStart", "selectionEnd"));
        fields.addAll(method.equals("editor/caret/set") ? Set.of("offset") : Set.of("start", "end"));
        if (!p.keySet().equals(fields)) throw fail(WorkspaceApiException.Code.INVALID_REQUEST);
        version(p); project(p); grant.requireIdentity(id(p,"instanceId"), id(p,"windowId"));
        int start = offset(p, method.equals("editor/caret/set") ? "offset" : "start"), end = method.equals("editor/caret/set") ? start : offset(p,"end");
        if (end < start) throw fail(WorkspaceApiException.Code.INVALID_OFFSET);
        return new Command(method, id(p,"instanceId"), id(p,"windowId"), id(p,"projectId"), id(p,"paneId"), id(p,"documentId"),
            number(p,"documentRevision"), number(p,"contextRevision"), offset(p,"caretOffset"), offset(p,"selectionStart"), offset(p,"selectionEnd"),start,end);
    }
    private void project(Map<String, Object> p) { if (!grant.projectId.equals(id(p,"projectId"))) throw fail(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED); }
    private static void version(Map<String, Object> p) { if (!Long.valueOf(1).equals(p.get("schemaVersion"))) throw fail(WorkspaceApiException.Code.UNSUPPORTED_PROTOCOL); }
    private static String id(Map<String, Object> p, String name) {
        if (!(p.get(name) instanceof String value) || value.isBlank() || value.length()>64) throw fail(WorkspaceApiException.Code.INVALID_REQUEST);
        return value;
    }
    private static long number(Map<String, Object> p, String key) {
        if (!(p.get(key) instanceof Long value) || value < 0) throw fail(WorkspaceApiException.Code.INVALID_REQUEST); return value;
    }
    private static int offset(Map<String, Object> p, String key) {
        if (!(p.get(key) instanceof Long value) || value < 0 || value > Integer.MAX_VALUE) throw fail(WorkspaceApiException.Code.INVALID_OFFSET); return value.intValue();
    }
    private static WorkspaceApiException fail(WorkspaceApiException.Code code) { return new WorkspaceApiException(code,"Editor navigation unavailable: "+code.name()); }
}
