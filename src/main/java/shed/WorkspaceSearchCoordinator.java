package shed;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

final class WorkspaceSearchCoordinator {
    private final Texteditor editor;
    private long activeRequest;
    private int activeJob = -1;
    private WorkspaceIndexService indexService;
    private WorkspaceSearchQuery query;
    private final List<WorkspaceTextSearchService.Match> matches = new ArrayList<>();
    private boolean running;
    private String status = "Search workspace files. Ignore rules are respected.";
    private long version;

    record Snapshot(WorkspaceSearchQuery query, List<WorkspaceTextSearchService.Match> matches, boolean running, String status, long version) { }

    WorkspaceSearchCoordinator(Texteditor editor) { this.editor = editor; }

    Snapshot snapshot() { return new Snapshot(query, List.copyOf(matches), running, status, version); }

    void close() { cancel(); if (indexService != null) indexService.close(); }

    void cancel() {
        activeRequest++;
        if (activeJob >= 0) editor.asyncJobService.cancel(activeJob);
        activeJob = -1;
        if (running) status = "Cancelled · " + matches.size() + " matches retained";
        running = false;
        changed();
    }

    void invalidate() {
        cancel();
        matches.clear();
        status = "Query changed — press Enter or Search";
        changed();
    }

    void show(SearchPattern initial) {
        if (initial != null && (query == null || !query.pattern().equals(initial))) {
            invalidate();
            query = new WorkspaceSearchQuery(initial, query == null ? "" : query.include(), query == null ? "" : query.exclude());
        }
        editor.showToolWindow(ToolWindowHost.Tab.SEARCH);
        if (editor.toolWindowHost != null && editor.toolWindowHost.searchPanel() != null)
            editor.toolWindowHost.searchPanel().focusQuery(initial);
    }

    String search(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (!validQuery(value)) return "Usage: :grep <text>";
        WorkspaceSearchQuery next = WorkspaceSearchQuery.literal(value);
        show(next.pattern());
        return search(next);
    }

    String search(WorkspaceSearchQuery next) {
        cancel();
        query = next;
        if (editor.toolWindowHost != null) editor.toolWindowHost.searchPanel().setQuery(next);
        matches.clear();
        List<Path> roots = workspaceRoots();
        if (roots.isEmpty()) { status = "Workspace search requires a directory"; changed(); return status; }
        String title = "grep " + next.pattern().query();
        long request = ++activeRequest;
        running = true;
        status = "Discovering files…";
        changed();
        boolean persistent = editor.configManager.getWorkspaceIndexEnabled();
        editor.problemsController.clearQuickfixSource("workspace-search");
        editor.updateQuickfixEntries(title, List.of());
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        WorkspaceIndexService index = workspaceIndexService();
        activeJob = editor.asyncJobService.submit("workspace search: " + next.pattern().query(), token -> {
            token.onCancel(cancellation::cancel);
            return new WorkspaceTextSearchService(index).search(persistent, roots, next, cancellation, new WorkspaceTextSearchService.Observer() {
                private long lastProgress;
                public void onMatches(List<WorkspaceTextSearchService.Match> batch) {
                    SwingUtilities.invokeLater(() -> {
                        if (editor.closingDown || request != activeRequest) return;
                        matches.addAll(batch);
                        status = "Searching… " + matches.size() + " matches";
                        editor.updateQuickfixEntries(title, entries(matches));
                        changed();
                    });
                }
                public void onProgress(int filesRead, int total) {
                    long now = System.nanoTime();
                    if (now - lastProgress < 100_000_000L) return;
                    lastProgress = now;
                    SwingUtilities.invokeLater(() -> {
                        if (editor.closingDown || request != activeRequest) return;
                        status = "Searching… " + filesRead + "/" + total + " files · " + matches.size() + " matches";
                        changed();
                    });
                }
            });
        }, (job, result, error) -> {
            if (editor.closingDown || request != activeRequest) return;
            running = false;
            activeJob = -1;
            if (result != null) { matches.clear(); matches.addAll(result.matches()); }
            if (job.getStatus() == AsyncJobService.Status.CANCELLED) status = "Cancelled · " + matches.size() + " matches";
            else if (error != null || result == null) status = "Search failed: " + (error == null ? "unknown error" : error.getMessage());
            else if (result.state() == WorkspaceTextSearchService.State.FAILED) status = "Search failed: " + result.message();
            else status = matches.size() + " matches · " + result.filesRead() + " files searched"
                + (result.truncated() ? " · Result limit reached — narrow your search" : "")
                + (result.unreadableFiles() > 0 ? " · " + result.unreadableFiles() + " unreadable files" : "");
            editor.updateQuickfixEntries(title, entries(matches));
            changed();
            editor.showMessage(status);
        });
        return "Started workspace search job " + activeJob;
    }

    private void changed() {
        version++;
        if (editor.toolWindowHost != null) editor.toolWindowHost.refresh(ToolWindowHost.Tab.SEARCH);
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

    private WorkspaceIndexService workspaceIndexService() {
        if (indexService == null) {
            indexService = WorkspaceIndexService.withAdditionalIgnore(Path.of(editor.configManager.getShedDirectoryPath(), "workspace-index-search"),
                editor.workspaceController.searchExclusionMatcher());
        }
        return indexService;
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
