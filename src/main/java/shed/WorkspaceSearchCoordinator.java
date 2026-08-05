package shed;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

final class WorkspaceSearchCoordinator {
    private final Texteditor editor;
    private long activeRequest;

    WorkspaceSearchCoordinator(Texteditor editor) {
        this.editor = editor;
    }

    String search(String argument) {
        String query = argument == null ? "" : argument.trim();
        if (!validQuery(query)) {
            return "Usage: :grep <text>";
        }
        List<Path> roots = workspaceRoots();
        if (roots.isEmpty()) {
            return "Workspace search requires a directory";
        }
        String title = "grep " + query + (roots.size() == 1 ? "" : " (" + roots.size() + " folders)");
        long request = ++activeRequest;
        boolean persistentIndexEnabled = editor.configManager.getWorkspaceIndexEnabled();
        List<QuickfixService.Entry> partialEntries = new ArrayList<>();
        editor.problemsController.clearQuickfixSource("workspace-search");
        editor.updateQuickfixEntries(title, List.of());
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        int jobId = editor.asyncJobService.submit("workspace search: " + query,
            token -> {
                token.onCancel(cancellation::cancel);
                WorkspaceIndexService index = new WorkspaceIndexService(Path.of(editor.configManager.getShedDirectoryPath(), "workspace-index"));
                return new WorkspaceTextSearchService(index).search(persistentIndexEnabled, roots, query, cancellation,
                    matches -> SwingUtilities.invokeLater(() -> appendMatches(request, title, partialEntries, matches)));
            },
            (snapshot, result, error) -> complete(request, title, query, snapshot, result, error));
        return "Started workspace search job " + jobId;
    }

    private void appendMatches(long request, String title, List<QuickfixService.Entry> partialEntries,
                               List<WorkspaceTextSearchService.Match> matches) {
        if (editor.closingDown || request != activeRequest) {
            return;
        }
        partialEntries.addAll(entries(matches));
        editor.updateQuickfixEntries(title, partialEntries);
    }

    private void complete(long request, String title, String query, AsyncJobService.JobSnapshot snapshot,
                          WorkspaceTextSearchService.SearchResult result, Exception error) {
        if (editor.closingDown || request != activeRequest) {
            return;
        }
        int jobId = snapshot == null ? -1 : snapshot.getId();
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED
            || result != null && result.state() == WorkspaceTextSearchService.State.CANCELLED) {
            int matches = result == null ? 0 : result.matches().size();
            editor.showMessage("Workspace search job " + jobId + " cancelled after " + matches + " match(es)");
            return;
        }
        if (error != null || result == null) {
            String message = error == null ? "unknown error" : error.getMessage();
            editor.showMessage("Workspace search job " + jobId + " failed: " + (message == null ? "" : message));
            return;
        }
        if (result.state() == WorkspaceTextSearchService.State.FAILED) {
            editor.showMessage("Workspace search job " + jobId + " failed: " + result.message());
            return;
        }
        List<QuickfixService.Entry> entries = entries(result.matches());
        editor.updateQuickfixEntries(title, entries);
        if (entries.isEmpty()) {
            editor.showMessage("No matches for: " + query);
            return;
        }
        editor.openQuickfixList();
        String suffix = result.truncated() ? " (result limit reached)" : "";
        editor.showMessage("Workspace search job " + jobId + " complete (" + entries.size() + " matches)" + suffix);
    }

    private Path workspaceRoot() {
        FileBuffer current = editor.getCurrentBuffer();
        File base = current != null && current.hasFilePath() ? new File(current.getFilePath()).getParentFile() : editor.treeRoot;
        if (base == null || !base.isDirectory()) {
            base = new File(".");
        }
        File projectRoot = base;
        for (File cursor = base; cursor != null; cursor = cursor.getParentFile()) {
            if (new File(cursor, ".git").exists()) {
                projectRoot = cursor;
                break;
            }
        }
        return projectRoot.isDirectory() ? projectRoot.toPath() : null;
    }

    private List<Path> workspaceRoots() {
        List<Path> roots = editor.workspaceController.roots();
        if (!roots.isEmpty()) return roots;
        Path root = workspaceRoot();
        return root == null ? List.of() : List.of(root);
    }

    private static List<QuickfixService.Entry> entries(List<WorkspaceTextSearchService.Match> matches) {
        List<QuickfixService.Entry> entries = new ArrayList<>();
        for (WorkspaceTextSearchService.Match match : matches) {
            entries.add(new QuickfixService.Entry(match.filePath(), match.line(), match.column(), match.preview(), "workspace-search"));
        }
        return entries;
    }

    private static boolean validQuery(String value) {
        return !value.isEmpty() && value.indexOf('\0') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }
}
