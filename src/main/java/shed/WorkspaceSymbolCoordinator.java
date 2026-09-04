package shed;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** Coordinates explicit local workspace-symbol fallback work outside the EDT. */
final class WorkspaceSymbolCoordinator {
    private final Texteditor editor;
    private long activeRequest;
    private WorkspaceIndexService.CancellationSource activeCancellation;

    WorkspaceSymbolCoordinator(Texteditor editor) {
        this.editor = editor;
    }

    void cancel() {
        activeRequest++;
        if (activeCancellation != null) activeCancellation.cancel();
        activeCancellation = null;
    }

    String search(String argument) {
        String query = argument == null ? "" : argument.trim();
        if (query.isEmpty() || query.indexOf('\0') >= 0 || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0) {
            return "Usage: :workspace symbols <query>";
        }
        List<Path> roots = workspaceRoots();
        if (roots.isEmpty()) return "Workspace symbols require a directory";
        cancel();
        long request = activeRequest;
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        activeCancellation = cancellation;
        boolean persistentIndexEnabled = editor.configManager.getWorkspaceIndexEnabled();
        int jobId = editor.asyncJobService.submit("local workspace symbols: " + query, token -> {
            token.onCancel(cancellation::cancel);
            WorkspaceIndexService index = new WorkspaceIndexService(Path.of(editor.configManager.getShedDirectoryPath(), "workspace-index"));
            return new WorkspaceSymbolService(index, editor.symbolService).search(persistentIndexEnabled, roots, query, cancellation);
        }, (snapshot, result, error) -> complete(request, query, snapshot, result, error));
        return "Loading local workspace symbols (job " + jobId + ")";
    }

    private void complete(long request, String query, AsyncJobService.JobSnapshot snapshot, WorkspaceSymbolService.SearchResult result,
                          Exception error) {
        if (editor.closingDown || request != activeRequest) return;
        activeCancellation = null;
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED
            || result != null && result.state() == WorkspaceSymbolService.State.CANCELLED) return;
        if (error != null || result == null || result.state() == WorkspaceSymbolService.State.FAILED) {
            String message = error != null ? error.getMessage() : result == null ? "unknown error" : result.message();
            editor.showMessage("Local workspace symbols failed: " + (message == null ? "" : message));
            return;
        }
        if (result.matches().isEmpty()) {
            editor.showMessage("No local workspace symbols found: " + query);
            return;
        }
        editor.showWorkspaceHeuristicSymbols(result.matches(), query, result.truncated());
    }

    private List<Path> workspaceRoots() {
        List<Path> roots = editor.workspaceController.roots();
        if (!roots.isEmpty()) return roots;
        FileBuffer current = editor.getCurrentBuffer();
        File base = current != null && current.hasFilePath() ? new File(current.getFilePath()).getParentFile() : editor.treeRoot;
        if (base == null || !base.isDirectory()) base = new File(".");
        File projectRoot = base;
        for (File cursor = base; cursor != null; cursor = cursor.getParentFile()) {
            if (new File(cursor, ".git").exists()) {
                projectRoot = cursor;
                break;
            }
        }
        return projectRoot.isDirectory() ? List.of(projectRoot.toPath()) : List.of();
    }
}
