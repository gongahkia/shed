package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.DefaultListModel;
import javax.swing.table.DefaultTableModel;

final class GitChangesWorkbenchDialog extends JDialog {
    interface Loader {
        GitChangesWorkbenchModel.Snapshot status();
        GitChangesWorkbenchModel.Diff diff(GitChangesWorkbenchModel.Snapshot snapshot, GitChangesWorkbenchModel.Change change);
        String open(GitChangesWorkbenchModel.Snapshot snapshot, GitChangesWorkbenchModel.Change change, GitChangesWorkbenchModel.Diff diff,
            GitHunkNavigation.Hunk hunk);
    }

    private final Texteditor editor;
    private final Loader loader;
    private final JLabel state = new JLabel("Loading Git status…");
    private final JLabel repository = new JLabel("Repository: resolving…");
    private final DefaultTableModel changes = new DefaultTableModel(new Object[] { "Index", "Worktree", "Path" }, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable changeTable = new JTable(changes);
    private final DefaultListModel<GitHunkNavigation.Hunk> hunks = new DefaultListModel<>();
    private final JList<GitHunkNavigation.Hunk> hunkList = new JList<>(hunks);
    private final JTextArea diffText = new JTextArea();
    private final JButton refresh = new JButton("Refresh");
    private final JButton viewDiff = new JButton("View Diff");
    private final JButton openFile = new JButton("Open File");
    private final JButton goToHunk = new JButton("Go to Hunk");
    private int statusRequestId;
    private int diffRequestId;
    private GitChangesWorkbenchModel.Snapshot snapshot;
    private GitChangesWorkbenchModel.Diff diff;
    private GitChangesWorkbenchModel.Change diffChange;

    static void showFor(Texteditor editor, Loader loader) {
        GitChangesWorkbenchDialog dialog = new GitChangesWorkbenchDialog(editor, loader);
        dialog.setVisible(true);
        dialog.refresh();
    }

    private GitChangesWorkbenchDialog(Texteditor editor, Loader loader) {
        super(editor, "Git Changes", false);
        this.editor = editor;
        this.loader = loader;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(720, 460));
        pack();
        WorkbenchToolWindowPlacement.restore(editor, this, WorkbenchLayout.SurfaceType.GIT, "changes");
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(repository, BorderLayout.NORTH);
        state.setHorizontalAlignment(SwingConstants.LEFT);
        panel.add(state, BorderLayout.SOUTH);
        return panel;
    }

    private JSplitPane content() {
        changeTable.setFillsViewportHeight(true);
        JScrollPane changedFiles = new JScrollPane(changeTable);
        changedFiles.setBorder(BorderFactory.createTitledBorder("Changed files"));
        diffText.setEditable(false);
        diffText.setLineWrap(false);
        JScrollPane diffScroll = new JScrollPane(diffText);
        diffScroll.setBorder(BorderFactory.createTitledBorder("Diff"));
        JScrollPane hunkScroll = new JScrollPane(hunkList);
        hunkScroll.setBorder(BorderFactory.createTitledBorder("Hunks"));
        JSplitPane details = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, hunkScroll, diffScroll);
        details.setResizeWeight(0.24);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, changedFiles, details);
        split.setResizeWeight(0.35);
        return split;
    }

    private JPanel actions() {
        refresh.addActionListener(event -> refresh());
        viewDiff.addActionListener(event -> viewDiff());
        openFile.addActionListener(event -> openFile());
        goToHunk.addActionListener(event -> goToHunk());
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel panel = new JPanel();
        panel.add(refresh);
        panel.add(viewDiff);
        panel.add(openFile);
        panel.add(goToHunk);
        panel.add(close);
        viewDiff.setEnabled(diffEnabled());
        goToHunk.setEnabled(diffEnabled());
        return panel;
    }

    private void refresh() {
        int expectedRequest = ++statusRequestId;
        refresh.setEnabled(false);
        state.setText("Refreshing Git status…");
        editor.asyncJobService.submit("Git changes refresh", token -> loader.status(), (job, result, error) -> {
            if (!isDisplayable() || expectedRequest != statusRequestId) return;
            refresh.setEnabled(true);
            if (job.getStatus() != AsyncJobService.Status.SUCCEEDED || result == null) {
                repository.setText("Repository: unavailable");
                state.setText(error == null ? job.getErrorMessage() : error.getMessage());
                changes.setRowCount(0);
                snapshot = null;
                diff = null;
                diffChange = null;
                hunks.clear();
                diffText.setText("");
                return;
            }
            render(result);
        });
    }

    private void render(GitChangesWorkbenchModel.Snapshot result) {
        snapshot = result;
        diff = null;
        diffChange = null;
        repository.setText(snapshot.root().isBlank() ? "Repository: unavailable" : "Repository: " + snapshot.root());
        String branch = snapshot.branch().isBlank() ? "" : "  Branch: " + snapshot.branch();
        state.setText(snapshot.detail() + branch);
        changes.setRowCount(0);
        for (GitChangesWorkbenchModel.Change change : snapshot.changes()) {
            changes.addRow(new Object[] { change.indexStatus(), change.worktreeStatus(), change.displayPath() });
        }
        hunks.clear();
        diffText.setText("");
        viewDiff.setEnabled(diffEnabled());
        goToHunk.setEnabled(diffEnabled());
    }

    private void viewDiff() {
        if (!diffEnabled()) {
            state.setText("Git diff navigation disabled by git.diffs.enabled=false");
            return;
        }
        GitChangesWorkbenchModel.Change change = selectedChange();
        if (change == null) {
            state.setText("Select a changed file first.");
            return;
        }
        int expectedRequest = ++diffRequestId;
        viewDiff.setEnabled(false);
        state.setText("Loading diff for " + change.displayPath() + "…");
        editor.asyncJobService.submit("Git diff: " + change.path(), token -> loader.diff(snapshot, change), (job, result, error) -> {
            if (!isDisplayable() || expectedRequest != diffRequestId) return;
            viewDiff.setEnabled(diffEnabled());
            if (job.getStatus() != AsyncJobService.Status.SUCCEEDED || result == null) {
                state.setText(error == null ? job.getErrorMessage() : error.getMessage());
                return;
            }
            if (!diffEnabled()) {
                diff = null;
                diffChange = null;
                hunks.clear();
                diffText.setText("");
                state.setText("Git diff navigation disabled by git.diffs.enabled=false");
                return;
            }
            diff = result;
            diffChange = change;
            diffText.setText(result.content());
            hunks.clear();
            for (GitHunkNavigation.Hunk hunk : result.hunks()) hunks.addElement(hunk);
            state.setText(result.detail());
        });
    }

    private void openFile() {
        GitChangesWorkbenchModel.Change change = selectedChange();
        if (change == null) {
            state.setText("Select a changed file first.");
            return;
        }
        state.setText(loader.open(snapshot, change, diff, null));
    }

    private void goToHunk() {
        if (!diffEnabled()) {
            state.setText("Git diff navigation disabled by git.diffs.enabled=false");
            return;
        }
        GitChangesWorkbenchModel.Change change = selectedChange();
        GitHunkNavigation.Hunk hunk = hunkList.getSelectedValue();
        if (change == null || hunk == null) {
            state.setText("Select a changed file and hunk first.");
            return;
        }
        if (!change.equals(diffChange)) {
            state.setText("Load the selected file's current diff before navigating a hunk.");
            return;
        }
        state.setText(loader.open(snapshot, change, diff, hunk));
    }

    private GitChangesWorkbenchModel.Change selectedChange() {
        if (snapshot == null) return null;
        int row = changes.getRowCount() == 0 ? -1 : changeTable.getSelectedRow();
        return row < 0 || row >= snapshot.changes().size() ? null : snapshot.changes().get(row);
    }

    private boolean diffEnabled() {
        return editor.configManager.getGitDiffsEnabled();
    }
}
