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
        return applyTexts(changes, List.of());
    }

    @Override public boolean matchesSavedDisk(WorkspaceDocument expected, WorkspaceDiskBinding binding) {
        if (expected.source() != WorkspaceDocument.Source.EDITOR || expected.uri() == null) return false;
        if (SwingUtilities.isEventDispatchThread()) throw new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST,
            "Disk-identity inspection must be prepared off the event thread");
        if (!canonical(Path.of(java.net.URI.create(expected.uri()))).equals(canonical(binding.file()))) return false;
        return onEdt(() -> {
            Captured actual = capture().stream().filter(item -> item.id().equals(expected.documentId())).findFirst().orElse(null);
            if (actual == null || !actual.version().equals(expected.version()))
                throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, "Editor document changed during disk-identity inspection");
            FileBuffer buffer = identities.entrySet().stream().filter(item -> item.getValue().id.equals(expected.documentId()))
                .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            return buffer.matchesSavedDisk(binding);
        });
    }

    @Override public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unboundPaths) {
        List<TextChange> requested = List.copyOf(changes);
        boolean resourceBatch = !unboundPaths.isEmpty() || requested.stream().anyMatch(change -> change.detach() || change.diskBinding() != null);
        // Resolve aliases on the worker, then validate that the EDT still has this registry.
        // A new path appearing after capture requires a retry instead of a filesystem lookup on the EDT.
        var canonicalPaths = new java.util.HashMap<Path, Path>();
        var detachedWorkspaces = new java.util.HashMap<String, Path>();
        var preparedRoots = new java.util.HashMap<String, List<Path>>();
        if (resourceBatch) {
            if (SwingUtilities.isEventDispatchThread()) throw new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST,
                "Resource binding batches must be prepared off the event thread");
            for (Captured item : onEdt(this::capture)) {
                preparedRoots.put(item.id(), item.roots());
                if (item.path() != null) {
                    canonicalPaths.put(item.path(), canonical(item.path()));
                    detachedWorkspaces.put(item.id(), WorkspacePaths.root(owningRoot(item.path(), item.roots())));
                }
            }
            for (TextChange change : requested) if (change.diskBinding() != null) {
                canonicalPaths.put(change.diskBinding().file(), canonical(change.diskBinding().file()));
                var configured = preparedRoots.get(change.expected().documentId());
                if (configured == null) throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, "Editor closed during resource preflight");
                String destinationWorkspace = WorkspacePaths.id(WorkspacePaths.root(owningRoot(change.diskBinding().file(), configured)));
                if (!destinationWorkspace.equals(change.expected().workspaceId()))
                    throw new WorkspaceApiException(WorkspaceApiException.Code.OUTSIDE_WORKSPACE, "A binding change cannot transfer a document into another workspace");
            }
            for (Path path : unboundPaths) canonicalPaths.put(path.toAbsolutePath().normalize(), canonical(path));
        }
        var unbound = unboundPaths.stream().map(path -> canonicalPaths.get(path.toAbsolutePath().normalize()))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
                if (resourceBatch && !actual.roots().equals(preparedRoots.get(actual.id())))
                    throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, "Workspace roots changed during resource preflight");
                if (!Objects.equals(expected.uri(), actual.path() == null ? null : actual.path().toUri().toString())
                    || expected.source() != WorkspaceDocument.Source.EDITOR)
                    throw new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "Editor document identity changed");
                if (!actual.complete()) throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED, "Projected documents cannot be edited");
                FileBuffer target = identities.entrySet().stream().filter(entry -> entry.getValue().id.equals(actual.id()))
                    .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
                targets.put(target, change);
            }
            // Validate the resulting registry, including untouched tabs. Swaps are valid when both
            // participants move in this batch; binding a second tab to an occupied path is not.
            var destinations = new java.util.HashSet<Path>();
            var detachedRoots = new java.util.HashMap<String, Path>();
            for (Captured item : before) {
                TextChange change = requested.stream().filter(candidate -> candidate.expected().documentId().equals(item.id())).findFirst().orElse(null);
                Path destination = change != null && change.detach() ? null
                    : change != null && change.diskBinding() != null ? change.diskBinding().file() : item.path();
                if (destination != null && resourceBatch) {
                    destination = canonicalPaths.get(destination);
                    if (destination == null) throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION,
                        "Editor paths changed during resource preflight");
                }
                if (destination != null && !destinations.add(destination))
                    throw new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "An open document already owns the destination path");
                if (destination != null && unbound.contains(destination))
                    throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, "A document opened at a resource-operation path; review it again");
                if (change != null && change.detach() && item.path() != null)
                    detachedRoots.put(item.id(), detachedWorkspaces.get(item.id()));
            }
            batch.accept(() -> {
                var transactions = new ArrayList<FileBuffer.TextTransaction>();
                try {
                    for (FileBuffer buffer : targets.keySet()) transactions.add(buffer.beginTextTransaction());
                    int index = 0;
                    for (TextChange change : targets.values()) {
                        var transaction = transactions.get(index++);
                        transaction.replace(change.text());
                        if (change.diskBinding() != null) transaction.bind(change.diskBinding());
                        else if (change.detach()) transaction.detach();
                    }
                    for (var transaction : transactions) transaction.commit();
                } finally {
                    RuntimeException failed = null;
                    for (int i = transactions.size() - 1; i >= 0; i--) {
                        try { transactions.get(i).close(); } catch (RuntimeException recovery) { failed = recovery; }
                    }
                    if (failed != null) throw new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Editor rollback requires recovery");
                }
            });
            for (var target : targets.entrySet()) if (target.getValue().detach()) {
                Path root = detachedRoots.get(target.getValue().expected().documentId());
                if (root != null) identities.get(target.getKey()).scratchRoot = root;
            }
            var after = new java.util.HashMap<String, Captured>();
            for (Captured item : capture()) after.put(item.id(), item);
            List<WorkspaceDocument> result = new ArrayList<>();
            for (TextChange change : requested) {
                WorkspaceDocument expected = change.expected(); Captured actual = after.get(expected.documentId());
                result.add(new WorkspaceDocument(expected.workspaceId(), actual.id(), actual.path() == null ? null : actual.path().toUri().toString(), actual.version(),
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

    private static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return WorkspacePaths.resolve(WorkspacePaths.root(absolute.getRoot()), absolute.toString());
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
        Path scratchRoot;
        VersionedTextSnapshot text;
        String signature;
        long revision;
        Identity(Path scratchRoot) { this.scratchRoot = scratchRoot.toAbsolutePath().normalize(); }
    }

    private record Captured(String id, String version, Path path, Path scratchRoot, List<Path> roots,
                            VersionedTextSnapshot text, boolean dirty, boolean complete, String encoding,
                            String lineEnding, boolean byteOrderMark) { }
}
