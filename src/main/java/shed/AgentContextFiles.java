package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Short-lived, owner-only snapshots for agents whose read tool cannot read editor buffers. */
final class AgentContextFiles implements AutoCloseable {
    private final Path directory;
    private final List<Snapshot> active = new ArrayList<>();
    private boolean closed;

    AgentContextFiles() throws IOException {
        directory = PrivateWorkspaceFiles.directory(Path.of(System.getProperty("java.io.tmpdir"), "shed-agent-context-" + UUID.randomUUID()));
    }
    Path directory() { return directory; }

    synchronized Snapshot capture(List<WorkspaceDocument> documents) throws IOException {
        return capture(documents, () -> false);
    }

    synchronized Snapshot capture(List<WorkspaceDocument> documents, java.util.function.BooleanSupplier cancelled) throws IOException {
        return capture(documents, cancelled, false);
    }

    synchronized Snapshot captureReferences(List<WorkspaceDocument> documents, java.util.function.BooleanSupplier cancelled) throws IOException {
        return capture(documents, cancelled, true);
    }

    private Snapshot capture(List<WorkspaceDocument> documents, java.util.function.BooleanSupplier cancelled, boolean references) throws IOException {
        if (closed) throw new IOException("Agent context is closed");
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        var dirty = references ? documents : documents.stream().filter(WorkspaceDocument::dirty).toList();
        long total = 0;
        for (var document : dirty) {
            total += document.content().length();
            if (!document.complete() || total > 32L * 1024 * 1024)
                throw new IllegalArgumentException("Unsaved context is incomplete or exceeds 32 Mi characters. Save or narrow the open workspace before retrying.");
        }
        if (dirty.isEmpty()) return new Snapshot(null, List.of(), "", 0);
        Path batch = PrivateWorkspaceFiles.directory(directory.resolve(UUID.randomUUID().toString()));
        var files = new ArrayList<Path>();
        try {
            var index = new ArrayList<Map<String, Object>>();
            for (var document : dirty) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                Path file = PrivateWorkspaceFiles.create(batch, "buffer-" + index.size() + ".txt", document.content().getBytes(StandardCharsets.UTF_8));
                files.add(file);
                index.add(Map.of("document", document.uri() == null ? document.documentId() : document.uri(),
                    "version", document.version(), "snapshot", file.toString(), "characters", document.content().length()));
            }
            Path manifest = PrivateWorkspaceFiles.create(batch, "index.json", MiniJson.stringify(Map.of(references ? "requestedContext" : "unsavedDocuments", index)).getBytes(StandardCharsets.UTF_8));
            files.add(manifest);
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            String instruction = "\n\nFull unsaved editor snapshots are available in " + manifest + ". "
                + "Read that index before searching or editing open files. Read the snapshot files as needed (including later lines); "
                + "they contain the COMPLETE unsaved text, overriding saved files and abbreviated excerpts. "
                + "These are read-only context copies. Cite and propose changes using the ORIGINAL document paths in the index, never the snapshot paths. "
                + "Do not edit the snapshots. They expire after this action.";
            if (references) instruction = "\n\nComplete requested context snapshots are in " + manifest
                + ". Read this index and every referenced snapshot before answering. Use the original paths when citing code. These copies expire after this action.";
            var snapshot = new Snapshot(batch, List.copyOf(files), instruction, dirty.size()); active.add(snapshot); return snapshot;
        } catch (IOException | RuntimeException error) { remove(batch, files); throw error; }
    }

    final class Snapshot implements AutoCloseable {
        private final Path batch;
        private final List<Path> files;
        private final String instruction;
        private final int count;
        private boolean released;
        Snapshot(Path batch, List<Path> files, String instruction, int count) {
            this.batch = batch; this.files = files; this.instruction = instruction; this.count = count;
        }
        String instruction() { return instruction; }
        int count() { return count; }
        @Override public void close() {
            synchronized (AgentContextFiles.this) {
                if (released) return; released = true; active.remove(this); if (batch != null) remove(batch, files);
            }
        }
    }

    private static void remove(Path batch, List<Path> files) {
        try { for (Path file : files) Files.deleteIfExists(file); Files.deleteIfExists(batch); }
        catch (IOException error) { System.getLogger("shed.agent").log(System.Logger.Level.WARNING, "Could not remove temporary agent context: " + batch); }
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        for (var snapshot : List.copyOf(active)) snapshot.close();
        remove(directory, List.of());
    }
}
