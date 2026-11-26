package shed;

import java.io.IOException;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/** One bounded acknowledgement of disk bytes and the authoritative editor snapshot, never a reload owner. */
final class EditorFreshness {
    private EditorFreshness() { }

    static Map<String, Object> await(WorkspaceQueryService workspace, ProjectBoundFileAcquisition.RootPin root,
                                     String path, String sha256, int timeoutMillis, BooleanSupplier live) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Source synchronization belongs off the EDT");
        if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}") || timeoutMillis < 50 || timeoutMillis > 3000)
            throw WorkspaceApiException.invalidRequest("Expected a SHA-256 and a 50–3000 ms deadline");
        final TextFileCodec.Image expected;
        try {
            var relative = ProjectBoundFileAcquisition.checkedRelativePath(path);
            workspace.policy().checkPath(relative, AgentPolicy.Capability.READ);
            var disk = ProjectBoundFileAcquisition.acquire(root, path, EditorControl.MAX_DOCUMENT, () -> !live.getAsBoolean());
            if (!WorkspacePaths.hash(disk.bytes()).equalsIgnoreCase(sha256)) throw failure(WorkspaceApiException.Code.STALE_VERSION);
            expected = TextFileCodec.decode(disk.bytes(), false);
        } catch (IOException invalid) { throw failure(live.getAsBoolean() ? WorkspaceApiException.Code.PROTECTED_PATH : WorkspaceApiException.Code.CANCELLED); }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (live.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            WorkspaceDocument document = workspace.snapshot(workspace.workspaceId(), path);
            if (document.source() != WorkspaceDocument.Source.EDITOR) throw failure(WorkspaceApiException.Code.NO_ACTIVE_EDITOR);
            if (document.dirty()) throw failure(WorkspaceApiException.Code.CONFLICT);
            if (document.complete() && document.content().equals(expected.text()) && document.encoding().equals(expected.encoding())
                && (expected.lineEnding().equals("NONE") || document.lineEnding().equals(expected.lineEnding()))
                && document.byteOrderMark() == expected.bom()) {
                try {
                    var again = ProjectBoundFileAcquisition.acquire(root, path, EditorControl.MAX_DOCUMENT, () -> !live.getAsBoolean());
                    if (!WorkspacePaths.hash(again.bytes()).equalsIgnoreCase(sha256)) throw failure(WorkspaceApiException.Code.STALE_VERSION);
                } catch (IOException invalid) { throw failure(WorkspaceApiException.Code.PROTECTED_PATH); }
                if (!live.getAsBoolean()) throw failure(WorkspaceApiException.Code.CANCELLED);
                return Map.of("kind", "sync", "path", path, "sha256", sha256.toLowerCase(java.util.Locale.ROOT),
                    "documentId", document.documentId(), "version", document.version(), "synced", true);
            }
            if (System.nanoTime() >= deadline) throw failure(WorkspaceApiException.Code.TIMEOUT);
            try { Thread.sleep(40); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure(WorkspaceApiException.Code.CANCELLED); }
        }
        throw failure(WorkspaceApiException.Code.CANCELLED);
    }

    private static WorkspaceApiException failure(WorkspaceApiException.Code code) {
        return new WorkspaceApiException(code, "Source freshness could not be acknowledged");
    }
}
