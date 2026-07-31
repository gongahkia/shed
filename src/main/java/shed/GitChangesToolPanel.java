package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    GitChangesToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(header(), BorderLayout.NORTH);
        panel.add(content(), BorderLayout.CENTER);
        panel.add(actions(), BorderLayout.SOUTH);
        changeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        changeTable.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) loadDiff(); });
        diff.setEditable(false);
        AccessibilitySupport.describe(changeTable, "Git changes", "Staged, unstaged, and untracked repository files.");
    }

    @Override public JPanel component() { return panel; }

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
        panel.add(fileActions, BorderLayout.WEST);
        JPanel commitActions = new JPanel(new BorderLayout(4, 0));
        commitActions.add(commit, BorderLayout.CENTER);
        commitActions.add(button("Commit", this::commit), BorderLayout.EAST);
        panel.add(commitActions, BorderLayout.CENTER);
        return panel;
    }

    @Override public void refresh() {
        long request = ++statusGeneration;
        state.setText("Refreshing Git status…");
        editor.asyncJobService.submit("Git changes refresh", token -> editor.treeGitController.loadGitChangesWorkbench(), (job, result, error) -> {
            if (request != statusGeneration || !panel.isDisplayable()) return;
            if (error != null || result == null) {
                snapshot = null; visibleChanges.clear(); changes.setRowCount(0);
                state.setText(error == null ? job.getErrorMessage() : error.getMessage());
                return;
            }
            render(result);
        });
    }

    private void render(GitChangesWorkbenchModel.Snapshot result) {
        snapshot = result; selectedDiff = null; visibleChanges.clear(); changes.setRowCount(0); diff.setText("");
        repository.setText(result.root().isBlank() ? "Repository: unavailable" : "Repository: " + result.root());
        state.setText(result.detail() + (result.branch().isBlank() ? "" : "  Branch: " + result.branch()));
        for (GitChangesWorkbenchModel.Change change : result.changes()) {
            if (!change.indexStatus().isBlank()) addChange("Staged", change.indexStatus(), change);
            if (!change.worktreeStatus().isBlank()) addChange("Changes", change.worktreeStatus(), change);
            if (change.indexStatus().isBlank() && change.worktreeStatus().isBlank()) addChange("Other", "", change);
        }
    }

    private void addChange(String area, String status, GitChangesWorkbenchModel.Change change) {
        visibleChanges.add(change);
        changes.addRow(new Object[] {area, status, change.displayPath()});
    }

    private void loadDiff() {
        GitChangesWorkbenchModel.Change change = selected();
        if (snapshot == null || change == null) return;
        long request = ++diffGeneration;
        state.setText("Loading diff…");
        editor.asyncJobService.submit("Git diff: " + change.path(), token -> editor.treeGitController.loadGitChangesWorkbenchDiff(snapshot, change),
            (job, result, error) -> {
                if (request != diffGeneration || !panel.isDisplayable()) return;
                if (error != null || result == null) { state.setText(error == null ? job.getErrorMessage() : error.getMessage()); return; }
                selectedDiff = result; diff.setText(result.content()); diff.setCaretPosition(0); state.setText(result.detail());
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
        try { editor.openFile(root.toPath().resolve(change.path()).toFile()); message("Opened " + change.path()); }
        catch (IOException error) { message("Open failed: " + error.getMessage()); }
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
