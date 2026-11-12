package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

final class GitChangesToolPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final ToolWindowHost host;
    private final JPanel panel = new JPanel(new BorderLayout(6, 6));
    private final JLabel repository = new JLabel("Repository: resolving…");
    private final JLabel state = new JLabel("Refresh to load changes.");
    private final DefaultTableModel changes = new DefaultTableModel(new Object[] {"Area", "State", "Path"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable changeTable = new JTable(changes);
    private final JTextArea diff = new JTextArea();
    private final JTextField commit = new JTextField();
    private final List<GitChangesWorkbenchModel.Change> visibleChanges = new ArrayList<>();
    private GitChangesWorkbenchModel.Snapshot snapshot;
    private GitChangesWorkbenchModel.Diff selectedDiff;
    private long statusGeneration;
    private long diffGeneration;
    private final javax.swing.Timer autoRefreshTimer;
    private boolean statusLoading;
    private boolean closed;

    GitChangesToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        WorkbenchStyle.emptyText(changeTable, "No changes to display");
        this.host = host;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(header(), BorderLayout.NORTH);
        panel.add(content(), BorderLayout.CENTER);
        panel.add(actions(), BorderLayout.SOUTH);
        changeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        changeTable.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) loadDiff(); });
        diff.setEditable(false);
        AccessibilitySupport.describe(changeTable, "Git changes", "Staged, unstaged, and untracked repository files.");
        autoRefreshTimer = new javax.swing.Timer(editor.configManager.getGitAutoRefreshIntervalMs(), event -> refreshIfChanged());
        autoRefreshTimer.start();
    }

    @Override public JPanel component() { return panel; }
    @Override public void focus() { changeTable.requestFocusInWindow(); }

    @Override public void close() {
        closed = true; autoRefreshTimer.stop(); statusGeneration++; diffGeneration++;
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        JPanel labels = new JPanel(new java.awt.GridLayout(2, 1));
        labels.add(repository); labels.add(state);
        panel.add(labels, BorderLayout.CENTER);
        panel.add(button("Refresh", this::refresh), BorderLayout.EAST);
        return panel;
    }

    private java.awt.Component content() {
        JScrollPane files = new JScrollPane(changeTable);
        files.setBorder(BorderFactory.createTitledBorder("Changes"));
        JScrollPane patch = new JScrollPane(diff);
        patch.setBorder(BorderFactory.createTitledBorder("Diff"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, files, patch);
        split.setResizeWeight(0.36);
        return split;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        JPanel fileActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fileActions.add(button("Open", this::open));
        fileActions.add(button("Stage", this::stage));
        fileActions.add(button("Unstage", this::unstage));
        fileActions.add(button("Revert", this::revert));
        fileActions.add(button("Branch…", this::switchBranch));
        fileActions.add(button("Worktrees…", () -> editor.treeGitController.showGitRepositoryToolsDocument()));
        fileActions.add(button("Stashes…", () -> editor.treeGitController.showGitRepositoryToolsDocument()));
        panel.add(fileActions, BorderLayout.NORTH);
        JPanel commitActions = new JPanel(new BorderLayout(4, 0));
        JLabel commitLabel = new JLabel("Commit message"); commitLabel.setLabelFor(commit);
        commitActions.add(commitLabel, BorderLayout.WEST);
        commitActions.add(commit, BorderLayout.CENTER);
        AccessibilitySupport.describe(commit, "Commit message", "Write a message, then choose Commit explicitly. Opening Git does not commit changes.");
        commitActions.add(button("Commit", this::commit), BorderLayout.EAST);
        panel.add(commitActions, BorderLayout.CENTER);
        return panel;
    }

    @Override public void refresh() {
        requestRefresh(false);
    }

    private void refreshIfChanged() {
        int interval = editor.configManager.getGitAutoRefreshIntervalMs();
        if (autoRefreshTimer.getDelay() != interval) {
            autoRefreshTimer.setDelay(interval);
            autoRefreshTimer.setInitialDelay(interval);
        }
        if (!editor.configManager.getGitAutoRefreshEnabled() || !panel.isShowing() || !host.isSelected(ToolWindowHost.Tab.GIT)) return;
        requestRefresh(true);
    }

    private void requestRefresh(boolean automatic) {
        if (closed || statusLoading) return;
        statusLoading = true;
        long request = ++statusGeneration;
        if (!automatic) WorkbenchStyle.message(state, "Refreshing Git status…", null);
        editor.asyncJobService.submit("Git changes refresh", token -> editor.treeGitController.loadGitChangesWorkbench(), (job, result, error) -> {
            if (closed || request != statusGeneration) return;
            statusLoading = false;
            if (!panel.isDisplayable()) return;
            if (error != null || result == null) {
                snapshot = null; diffGeneration++; selectedDiff = null; visibleChanges.clear(); changes.setRowCount(0);
                ToolOutput.update(diff, "", true);
                WorkbenchStyle.message(state, "Git refresh failed: " + (error == null ? job.getErrorMessage() : error.getMessage()), "error");
                return;
            }
            if (automatic && Objects.equals(snapshot, result)) return;
            render(result);
        });
    }

    private void render(GitChangesWorkbenchModel.Snapshot result) {
        GitChangesWorkbenchModel.Change previous = selected();
        String selectedPath = previous == null ? null : previous.path();
        snapshot = result; diffGeneration++; selectedDiff = null; visibleChanges.clear(); changes.setRowCount(0); ToolOutput.update(diff, "", true);
        repository.setText(result.root().isBlank() ? "Repository: unavailable" : "Repository: " + result.root());
        repository.setToolTipText(result.root());
        WorkbenchStyle.message(state, result.detail() + (result.branch().isBlank() ? "" : "  Branch: " + result.branch()), result.state() == GitChangesWorkbenchModel.State.ERROR ? "error" : null);
        WorkbenchStyle.emptyText(changeTable, result.available() ? "Working tree is clean" : result.detail());
        for (GitChangesWorkbenchModel.Change change : result.changes()) {
            if (!change.indexStatus().isBlank()) addChange("Staged", change.indexStatus(), change);
            if (!change.worktreeStatus().isBlank()) addChange("Changes", change.worktreeStatus(), change);
            if (change.indexStatus().isBlank() && change.worktreeStatus().isBlank()) addChange("Other", "", change);
        }
        if (selectedPath != null) {
            for (int row = 0; row < visibleChanges.size(); row++) {
                if (selectedPath.equals(visibleChanges.get(row).path())) {
                    changeTable.setRowSelectionInterval(row, row);
                    break;
                }
            }
        }
    }

    private void addChange(String area, String status, GitChangesWorkbenchModel.Change change) {
        visibleChanges.add(change);
        changes.addRow(new Object[] {area, status, change.displayPath()});
    }

    private void loadDiff() {
        GitChangesWorkbenchModel.Change change = selected();
        long request = ++diffGeneration;
        GitChangesWorkbenchModel.Snapshot captured = snapshot;
        if (captured == null || change == null) return;
        WorkbenchStyle.message(state, "Loading diff…", null);
        editor.asyncJobService.submit("Git diff: " + change.path(), token -> editor.treeGitController.loadGitChangesWorkbenchDiff(captured, change),
            (job, result, error) -> {
                if (closed || request != diffGeneration || snapshot != captured || !panel.isDisplayable()) return;
                if (error != null || result == null) { WorkbenchStyle.message(state, "Diff failed: " + (error == null ? job.getErrorMessage() : error.getMessage()), "error"); return; }
                selectedDiff = result; ToolOutput.update(diff, result.content(), true); WorkbenchStyle.message(state, result.detail(), result.available() ? null : "error");
            });
    }

    private GitChangesWorkbenchModel.Change selected() {
        int row = changeTable.getSelectedRow();
        return row < 0 || row >= visibleChanges.size() ? null : visibleChanges.get(row);
    }

    private File root() { return snapshot == null || snapshot.root().isBlank() ? null : new File(snapshot.root()); }

    private void open() {
        GitChangesWorkbenchModel.Change change = selected(); File root = root();
        if (change == null || root == null) { message("Select a changed file."); return; }
        editor.openFileAsync(root.toPath().resolve(change.path()).toFile(),
            error -> message(error == null ? "Opened " + change.path() : "Open failed: " + error.getMessage()));
    }

    private void stage() { runForSelection("Stage", (root, change) -> editor.treeGitController.runGitAdd(root, change.path())); }
    private void unstage() { runForSelection("Unstage", (root, change) -> editor.treeGitController.runGitRestoreStaged(root, change.path())); }
    private void revert() {
        GitChangesWorkbenchModel.Change change = selected();
        if (change == null) { message("Select a changed file."); return; }
        if (JOptionPane.showConfirmDialog(panel, "Discard working-tree changes in '" + change.displayPath() + "'?", "Revert Changes",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
            runForSelection("Revert", (root, selected) -> editor.treeGitController.runGitRestoreWorktree(root, selected.path()));
        }
    }

    private void runForSelection(String label, GitAction action) {
        GitChangesWorkbenchModel.Change change = selected(); File root = root();
        if (change == null || root == null) { message("Select a changed file."); return; }
        message(action.run(root, change));
    }

    private void commit() {
        File root = root();
        if (root == null) { message("Repository is unavailable."); return; }
        message(editor.treeGitController.runGitCommit(root, commit.getText()));
        commit.setText("");
    }

    private void switchBranch() {
        File root = root();
        if (root == null) { message("Repository is unavailable."); return; }
        List<String> branches = editor.treeGitController.localBranchesForPanel(root);
        if (branches.isEmpty()) { message("No local branches."); return; }
        JComboBox<String> picker = new JComboBox<>(branches.toArray(String[]::new));
        if (JOptionPane.showConfirmDialog(panel, picker, "Switch Branch", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            message(editor.treeGitController.runGitSwitch(root, String.valueOf(picker.getSelectedItem())));
        }
    }

    private void message(String result) { editor.showMessage(result); refresh(); }
    private static JButton button(String text, Runnable action) { JButton button = new JButton(text); button.addActionListener(event -> action.run()); return button; }
    @FunctionalInterface private interface GitAction { String run(File root, GitChangesWorkbenchModel.Change change); }
}
