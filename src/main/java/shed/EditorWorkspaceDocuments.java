package shed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Swing adapter only: captures buffer state on the EDT and materializes immutable text on its caller. */
final class EditorWorkspaceDocuments implements WorkspaceDocumentEditor, AutoCloseable {
    private final Supplier<List<FileBuffer>> buffers;
    private final Supplier<List<Path>> roots;
    private final Supplier<Path> activeRoot;
    private final Path fallbackRoot;
    private final Consumer<Runnable> batch;
    private final WeakHashMap<FileBuffer, Identity> identities = new WeakHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    EditorWorkspaceDocuments(Supplier<List<FileBuffer>> buffers, Supplier<List<Path>> roots,
                             Supplier<Path> activeRoot, Path fallbackRoot) {
        this(buffers, roots, activeRoot, fallbackRoot, Runnable::run);
    }

    EditorWorkspaceDocuments(Supplier<List<FileBuffer>> buffers, Supplier<List<Path>> roots,
                             Supplier<Path> activeRoot, Path fallbackRoot, Consumer<Runnable> batch) {
        this.buffers = Objects.requireNonNull(buffers); this.roots = Objects.requireNonNull(roots);
        this.activeRoot = Objects.requireNonNull(activeRoot); this.fallbackRoot = fallbackRoot.toAbsolutePath().normalize();
        this.batch = Objects.requireNonNull(batch);
    }

    @Override public boolean connected() { return !closed.get(); }
    @Override public void close() { closed.set(true); }

    @Override public List<WorkspaceDocument> snapshots() {
        if (closed.get()) throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_CONNECTED, "Editor document connection is closed");
        List<Captured> captured = onEdt(this::capture);
        List<WorkspaceDocument> result = new ArrayList<>();
        for (Captured item : captured) {
            Path root = item.path() == null ? item.scratchRoot() : owningRoot(item.path(), item.roots());
            String workspaceId = WorkspacePaths.id(WorkspacePaths.root(root));
            result.add(new WorkspaceDocument(workspaceId, item.id(), item.path() == null ? null : item.path().toUri().toString(),
                item.version(), item.text().text(), item.dirty(), !item.complete(), item.complete(), item.encoding(),
                item.lineEnding(), item.byteOrderMark(), WorkspaceDocument.Source.EDITOR));
        }
        return List.copyOf(result);
    }

    private List<Captured> capture() {
        if (closed.get()) throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_CONNECTED, "Editor document connection is closed");
        List<Path> configured = List.copyOf(roots.get());
        Path selected = activeRoot.get();
        List<Captured> captured = new ArrayList<>();
        for (FileBuffer buffer : List.copyOf(buffers.get())) {
            Identity identity = identities.computeIfAbsent(buffer, ignored -> new Identity(selected == null ? fallbackRoot : selected));
            Path path = buffer.hasFilePath() ? buffer.getFile().toPath().toAbsolutePath().normalize() : null;
            if (!buffer.isLargeFile()) buffer.reconcileDocumentText();
            VersionedTextSnapshot text = buffer.textSnapshot();
            String signature = Objects.toString(path, "scratch") + "\n" + buffer.isModified() + "\n" + buffer.isLargeFile()
                + "\n" + buffer.getEncoding() + "\n" + buffer.getLineEndingLabel() + "\n" + buffer.hasByteOrderMark()
                + "\n" + buffer.documentRevision();
            if (identity.text != text || !signature.equals(identity.signature)) {
                identity.revision++;
                identity.text = text; identity.signature = signature;
            }
            captured.add(new Captured(identity.id, "editor-" + identity.id + "-" + identity.revision, path,
                identity.scratchRoot, configured, text, buffer.isModified(), !buffer.isLargeFile(), buffer.getEncoding(),
                buffer.getLineEndingLabel(), buffer.hasByteOrderMark()));
        }
        return List.copyOf(captured);
    }

    @Override public List<WorkspaceDocument> applyTexts(List<TextChange> changes) {
        List<TextChange> requested = List.copyOf(changes);
        return onEdtMutation(() -> {
            List<Captured> before = capture();
            var current = new java.util.HashMap<String, Captured>();
            for (Captured item : before) current.put(item.id(), item);
            var targets = new java.util.LinkedHashMap<FileBuffer, TextChange>();
            var seen = new java.util.HashSet<String>();
            for (TextChange change : requested) {
                var expected = change.expected();
                Captured actual = current.get(expected.documentId());
                if (!seen.add(expected.documentId())) throw new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST, "Duplicate document mutation");
                if (actual == null || !actual.version().equals(expected.version()))
                    throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, "Editor document changed before application");
                if (!Objects.equals(expected.uri(), actual.path() == null ? null : actual.path().toUri().toString())
                    || expected.source() != WorkspaceDocument.Source.EDITOR)
                    throw new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "Editor document identity changed");
                if (!actual.complete()) throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED, "Projected documents cannot be edited");
                FileBuffer target = identities.entrySet().stream().filter(entry -> entry.getValue().id.equals(actual.id()))
                    .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
                targets.put(target, change);
            }
            batch.accept(() -> {
                var transactions = new ArrayList<FileBuffer.TextTransaction>();
                try {
                    for (FileBuffer buffer : targets.keySet()) transactions.add(buffer.beginTextTransaction());
                    int index = 0;
                    for (TextChange change : targets.values()) transactions.get(index++).replace(change.text());
                    for (var transaction : transactions) transaction.commit();
                } finally {
                    RuntimeException failed = null;
                    for (int i = transactions.size() - 1; i >= 0; i--) {
                        try { transactions.get(i).close(); } catch (RuntimeException recovery) { failed = recovery; }
                    }
                    if (failed != null) throw new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Editor rollback requires recovery");
                }
            });
            var after = new java.util.HashMap<String, Captured>();
            for (Captured item : capture()) after.put(item.id(), item);
            List<WorkspaceDocument> result = new ArrayList<>();
            for (TextChange change : requested) {
                WorkspaceDocument expected = change.expected(); Captured actual = after.get(expected.documentId());
                result.add(new WorkspaceDocument(expected.workspaceId(), actual.id(), expected.uri(), actual.version(),
                    actual.text().text(), actual.dirty(), false, true, actual.encoding(), actual.lineEnding(), actual.byteOrderMark(), WorkspaceDocument.Source.EDITOR));
            }
            return List.copyOf(result);
        });
    }

    private static Path owningRoot(Path file, List<Path> roots) {
        Path configured = WorkspaceRootResolver.configuredRoot(file, roots);
        if (configured != null) return configured;
        // Loose files use their nearest repository, or their parent when there is no repository.
        for (Path parent = file.getParent(); parent != null; parent = parent.getParent())
            if (Files.exists(parent.resolve(".git"))) return parent;
        return file.getParent();
    }

    private static <T> T onEdt(Callable<T> capture) {
        if (SwingUtilities.isEventDispatchThread()) {
            try { return capture.call(); }
            catch (WorkspaceApiException error) { throw error; }
            catch (Exception error) { throw new WorkspaceApiException(WorkspaceApiException.Code.IO_ERROR, "Editor snapshot failed"); }
        }
        FutureTask<T> request = new FutureTask<>(capture);
        SwingUtilities.invokeLater(request);
        try { return request.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            request.cancel(false); Thread.currentThread().interrupt();
            throw new WorkspaceApiException(WorkspaceApiException.Code.CANCELLED, "Editor snapshot was cancelled");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof WorkspaceApiException error) throw error;
            throw new WorkspaceApiException(WorkspaceApiException.Code.IO_ERROR, "Editor snapshot failed");
        } catch (java.util.concurrent.TimeoutException timeout) {
            request.cancel(false);
            throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_CONNECTED, "Editor did not answer the snapshot request");
        }
    }

    /** Queued mutations can be cancelled; an executing atomic batch must return its real outcome. */
    private static <T> T onEdtMutation(Callable<T> mutation) {
        if (SwingUtilities.isEventDispatchThread()) return onEdt(mutation);
        var phase = new java.util.concurrent.atomic.AtomicInteger();
        FutureTask<T> request = new FutureTask<>(mutation);
        SwingUtilities.invokeLater(() -> {
            if (phase.compareAndSet(0, 1)) {
                try { request.run(); } finally { phase.set(2); }
            }
        });
        boolean interrupted = false, started = false;
        try {
            while (true) {
                try { return started ? request.get() : request.get(10, TimeUnit.SECONDS); }
                catch (InterruptedException cancelled) {
                    interrupted = true;
                    if (phase.compareAndSet(0, 3)) {
                        request.cancel(false);
                        throw new WorkspaceApiException(WorkspaceApiException.Code.CANCELLED, "Queued editor edit was cancelled");
                    }
                    started = true;
                } catch (java.util.concurrent.TimeoutException timeout) {
                    if (phase.compareAndSet(0, 3)) {
                        request.cancel(false);
                        throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_CONNECTED, "Editor did not start the queued edit");
                    }
                    started = true;
                } catch (java.util.concurrent.ExecutionException failed) {
                    if (failed.getCause() instanceof WorkspaceApiException error) throw error;
                    throw new WorkspaceApiException(WorkspaceApiException.Code.IO_ERROR, "Editor edit failed");
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static final class Identity {
        final String id = "buffer-" + UUID.randomUUID();
        final Path scratchRoot;
        VersionedTextSnapshot text;
        String signature;
        long revision;
        Identity(Path scratchRoot) { this.scratchRoot = scratchRoot.toAbsolutePath().normalize(); }
    }

    private record Captured(String id, String version, Path path, Path scratchRoot, List<Path> roots,
                            VersionedTextSnapshot text, boolean dirty, boolean complete, String encoding,
                            String lineEnding, boolean byteOrderMark) { }
}
