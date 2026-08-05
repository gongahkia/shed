package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

final class GitRepositoryDialog extends JDialog {
    interface Loader {
        GitRepositoryModel.Snapshot load(AsyncJobService.JobToken token);
        String createWorktree(String path, String branch, boolean createBranch, AsyncJobService.JobToken token);
        String removeWorktree(GitRepositoryModel.Worktree worktree, AsyncJobService.JobToken token);
        String stashPush(String message, AsyncJobService.JobToken token);
        String stashApply(GitRepositoryModel.Stash stash, boolean pop, AsyncJobService.JobToken token);
        String stashDrop(GitRepositoryModel.Stash stash, AsyncJobService.JobToken token);
        String openWorktree(GitRepositoryModel.Worktree worktree);
    }

    private final Texteditor editor;
    private final Loader loader;
    private final JLabel repository = new JLabel("Repository: resolving…");
    private final JLabel state = new JLabel("Loading worktrees and stashes…");
    private final javax.swing.DefaultListModel<GitRepositoryModel.Worktree> worktrees = new javax.swing.DefaultListModel<>();
    private final javax.swing.DefaultListModel<GitRepositoryModel.Stash> stashes = new javax.swing.DefaultListModel<>();
    private final JList<GitRepositoryModel.Worktree> worktreeList = new JList<>(worktrees);
    private final JList<GitRepositoryModel.Stash> stashList = new JList<>(stashes);
    private int activeJob = -1;

    static void showFor(Texteditor editor, Loader loader) {
        GitRepositoryDialog dialog = new GitRepositoryDialog(editor, loader);
        dialog.setVisible(true);
        dialog.refresh();
    }

    private GitRepositoryDialog(Texteditor editor, Loader loader) {
        super(editor, "Git Worktrees and Stashes", false);
        this.editor = editor;
        this.loader = loader;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        worktreeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stashList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        AccessibilitySupport.describe(worktreeList, "Git worktrees", "Main and linked Git worktrees for this repository.");
        AccessibilitySupport.describe(stashList, "Git stashes", "Saved local Git stash entries.");
        setPreferredSize(new Dimension(860, 480));
        pack();
        WorkbenchToolWindowPlacement.restore(editor, this, WorkbenchLayout.SurfaceType.GIT, "repository-tools");
    }

    private JPanel header() {
        JPanel panel = new JPanel(new GridLayout(2, 1));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(repository);
        panel.add(state);
        return panel;
    }

    private JSplitPane content() {
        JScrollPane left = new JScrollPane(worktreeList);
        left.setBorder(BorderFactory.createTitledBorder("Worktrees"));
        JScrollPane right = new JScrollPane(stashList);
        right.setBorder(BorderFactory.createTitledBorder("Stashes"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.55);
        return split;
    }

    private JPanel actions() {
        JPanel panel = new JPanel();
        panel.add(button("Refresh", this::refresh));
        panel.add(button("Open Worktree", this::openWorktree));
        panel.add(button("New Worktree…", this::newWorktree));
        panel.add(button("Remove Worktree", this::removeWorktree));
        panel.add(button("Stash Changes…", this::stashChanges));
        panel.add(button("Apply Stash", () -> applyStash(false)));
        panel.add(button("Pop Stash", () -> applyStash(true)));
        panel.add(button("Drop Stash", this::dropStash));
        panel.add(button("Close", this::dispose));
        return panel;
    }

    private void refresh() {
        if (activeJob >= 0) return;
        state.setText("Loading worktrees and stashes…");
        activeJob = editor.asyncJobService.submit("Git repository tools refresh", loader::load, (job, result, error) -> {
            if (!isDisplayable() || job.getId() != activeJob) return;
            activeJob = -1;
            if (job.getStatus() != AsyncJobService.Status.SUCCEEDED || result == null) {
                state.setText(error == null ? job.getErrorMessage() : error.getMessage());
                return;
            }
            repository.setText(result.root().isBlank() ? "Repository: unavailable" : "Repository: " + result.root());
            state.setText(result.detail());
            worktrees.clear(); result.worktrees().forEach(worktrees::addElement);
            stashes.clear(); result.stashes().forEach(stashes::addElement);
        });
    }

    private void openWorktree() {
        GitRepositoryModel.Worktree worktree = worktreeList.getSelectedValue();
        if (worktree == null) { state.setText("Select a worktree."); return; }
        state.setText(loader.openWorktree(worktree));
    }

    private void newWorktree() {
        JTextField path = new JTextField();
        JTextField branch = new JTextField();
        JCheckBox create = new JCheckBox("Create a new branch", true);
        JPanel form = new JPanel(new GridLayout(3, 2, 6, 6));
        form.add(new JLabel("New folder path:")); form.add(path);
        form.add(new JLabel("Branch name:")); form.add(branch);
        form.add(new JLabel()); form.add(create);
        if (JOptionPane.showConfirmDialog(this, form, "Create Git Worktree", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (!confirm("Create a linked worktree and " + (create.isSelected() ? "new branch" : "check out the selected branch") + "?")) return;
        run("Create worktree", token -> loader.createWorktree(path.getText(), branch.getText(), create.isSelected(), token));
    }

    private void removeWorktree() {
        GitRepositoryModel.Worktree worktree = worktreeList.getSelectedValue();
        if (worktree == null) { state.setText("Select a worktree."); return; }
        if (worktree.main()) { state.setText("The main worktree cannot be removed."); return; }
        if (!confirm("Remove linked worktree '" + worktree.path() + "'? Git will refuse an unclean worktree.")) return;
        run("Remove worktree", token -> loader.removeWorktree(worktree, token));
    }

    private void stashChanges() {
        String message = JOptionPane.showInputDialog(this, "Optional stash message. Untracked files are included:", "Stash Changes", JOptionPane.QUESTION_MESSAGE);
        if (message == null) return;
        if (!confirm("Create a stash and reset tracked/untracked working-tree changes?")) return;
        run("Create stash", token -> loader.stashPush(message, token));
    }

    private void applyStash(boolean pop) {
        GitRepositoryModel.Stash stash = stashList.getSelectedValue();
        if (stash == null) { state.setText("Select a stash."); return; }
        String action = pop ? "apply and remove" : "apply";
        if (!confirm("" + action + " " + stash.reference() + " to the working tree?")) return;
        run(pop ? "Pop stash" : "Apply stash", token -> loader.stashApply(stash, pop, token));
    }

    private void dropStash() {
        GitRepositoryModel.Stash stash = stashList.getSelectedValue();
        if (stash == null) { state.setText("Select a stash."); return; }
        if (!confirm("Permanently drop " + stash.reference() + "?")) return;
        run("Drop stash", token -> loader.stashDrop(stash, token));
    }

    private void run(String label, AsyncAction action) {
        if (activeJob >= 0) return;
        state.setText(label + "…");
        activeJob = editor.asyncJobService.submit(label, action::run, (job, result, error) -> {
            if (!isDisplayable() || job.getId() != activeJob) return;
            activeJob = -1;
            state.setText(error == null ? (result == null ? job.getErrorMessage() : result) : error.getMessage());
            if (job.getStatus() == AsyncJobService.Status.SUCCEEDED && actionSucceeded(result)) {
                editor.refreshGitGutter();
                if (editor.toolWindowHost != null) editor.toolWindowHost.refresh(ToolWindowHost.Tab.GIT);
                refresh();
            }
        });
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, "Confirm Git Action", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private static boolean actionSucceeded(String result) {
        return result != null && !result.startsWith("Git error:") && !result.startsWith("Not inside a Git repository")
            && !result.startsWith("Select ") && !result.startsWith("The main worktree") && !result.startsWith("Worktree ")
            && !result.startsWith("Invalid ") && !result.startsWith("Stash ");
    }

    private static JButton button(String text, Runnable action) { JButton button = new JButton(text); button.addActionListener(event -> action.run()); return button; }
    @FunctionalInterface private interface AsyncAction { String run(AsyncJobService.JobToken token); }
}
