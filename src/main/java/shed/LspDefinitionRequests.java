package shed;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

/** EDT-owned definition request state; transport waits use the existing job service. */
final class LspDefinitionRequests implements AutoCloseable {
    record Context(FileBuffer buffer, EditorPane pane, LspClient client, String path, String uri,
                   long revision, Integer version, int dot, int mark, int line, int character) {
        static Context capture(FileBuffer buffer, EditorPane pane, LspClient client, String uri, Integer version)
            throws BadLocationException {
            requireEdt();
            var area = pane.getTextArea();
            int dot = area.getCaretPosition();
            int line = area.getLineOfOffset(dot);
            return new Context(buffer, pane, client, buffer.getFilePath(), uri, buffer.documentRevision(), version,
                dot, area.getCaret().getMark(), line, dot - area.getLineStartOffset(line));
        }

        boolean current(FileBuffer activeBuffer, EditorPane activePane, LspClient boundClient, String boundUri,
                        Integer boundVersion, boolean closing) {
            requireEdt();
            return !closing && activeBuffer == buffer && activePane == pane && !pane.isDisposed()
                && pane.getBuffer() == buffer && pane.getTextArea().getDocument() == buffer.getDocument()
                && buffer.documentRevision() == revision && Objects.equals(path, buffer.getFilePath())
                && boundClient == client && client.isAlive() && Objects.equals(uri, boundUri)
                && Objects.equals(version, boundVersion) && pane.getTextArea().getCaretPosition() == dot
                && pane.getTextArea().getCaret().getMark() == mark;
        }
    }

    private record Request(Context context, BooleanSupplier current) { }
    private AsyncJobService jobs;
    private Consumer<LspClient.Location> navigate;
    private Consumer<String> message;
    private Request active;
    private Request pending;
    private int jobId = -1;
    private long generation;
    private boolean closed;

    LspDefinitionRequests(AsyncJobService jobs, Consumer<LspClient.Location> navigate, Consumer<String> message) {
        this.jobs = Objects.requireNonNull(jobs);
        this.navigate = Objects.requireNonNull(navigate);
        this.message = Objects.requireNonNull(message);
    }

    String request(Context context, BooleanSupplier current) {
        requireEdt();
        if (closed) return "LSP unavailable";
        generation++;
        active = null;
        pending = new Request(context, current);
        // Keep at most one job and one latest replacement, even when the worker pool is busy.
        if (jobId >= 0) jobs.cancel(jobId);
        else launch();
        return "Loading definition…";
    }

    private void launch() {
        Request request = pending;
        pending = null;
        if (request == null || !request.current().getAsBoolean()) return;
        active = request;
        long ticket = generation;
        // Only immutable transport input enters the worker; it does not retain a pane or document.
        LspClient client = request.context().client();
        String uri = request.context().uri();
        int line = request.context().line(), character = request.context().character();
        jobId = jobs.submit("LSP definition", token -> {
            if (token.isCancelled()) return null;
            LspClient.Location location = client.definition(uri, line, character);
            return token.isCancelled() ? null : location;
        }, (job, location, error) -> complete(ticket, job, location, error));
    }

    private void complete(long ticket, AsyncJobService.JobSnapshot job, LspClient.Location location, Exception error) {
        requireEdt();
        jobId = -1;
        Request request = active;
        active = null;
        if (closed) return;
        if (ticket == generation && request != null && request.current().getAsBoolean()
            && job.getStatus() != AsyncJobService.Status.CANCELLED) {
            if (error != null) message.accept("LSP definition failed: " + error.getMessage());
            else if (location == null) message.accept("No definition found");
            else navigate.accept(location);
        }
        if (jobId < 0 && pending != null) launch();
    }

    void sourceChanged(FileBuffer buffer) {
        requireEdt();
        if ((active != null && active.context().buffer() == buffer) || (pending != null && pending.context().buffer() == buffer)) cancel();
    }

    void cancel() {
        requireEdt();
        if (jobId < 0 && active == null && pending == null) return;
        generation++;
        active = pending = null;
        if (jobId >= 0 && jobs != null) jobs.cancel(jobId);
    }

    @Override public void close() {
        requireEdt();
        if (closed) return;
        cancel();
        closed = true;
        // A queued completion must not retain the disposed workbench through callbacks or its job service.
        navigate = null;
        message = null;
        jobs = null;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Definition state belongs to the EDT");
    }
}
