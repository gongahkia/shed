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
import javax.swing.SwingUtilities;

/** Swing adapter only: captures buffer state on the EDT and materializes immutable text on its caller. */
final class EditorWorkspaceDocuments implements WorkspaceDocumentSource, AutoCloseable {
    private final Supplier<List<FileBuffer>> buffers;
    private final Supplier<List<Path>> roots;
    private final Supplier<Path> activeRoot;
    private final Path fallbackRoot;
    private final WeakHashMap<FileBuffer, Identity> identities = new WeakHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    EditorWorkspaceDocuments(Supplier<List<FileBuffer>> buffers, Supplier<List<Path>> roots,
                             Supplier<Path> activeRoot, Path fallbackRoot) {
        this.buffers = Objects.requireNonNull(buffers); this.roots = Objects.requireNonNull(roots);
        this.activeRoot = Objects.requireNonNull(activeRoot); this.fallbackRoot = fallbackRoot.toAbsolutePath().normalize();
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
            VersionedTextSnapshot text = buffer.textSnapshot();
            String signature = Objects.toString(path, "scratch") + "\n" + buffer.isModified() + "\n" + buffer.isLargeFile()
                + "\n" + buffer.getEncoding() + "\n" + buffer.getLineEndingLabel() + "\n" + buffer.hasByteOrderMark();
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
