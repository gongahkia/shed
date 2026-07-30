package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;

final class GitHistoryRemoteDialog extends JDialog {
    interface Loader {
        GitHistoryModel.Snapshot load(AsyncJobService.JobToken token);
        GitHistoryModel.RemoteResult run(GitHistoryModel.RemoteAction action, AsyncJobService.JobToken token);
    }

    private final Texteditor editor;
    private final Loader loader;
    private final JLabel repository = new JLabel("Repository: resolving…");
    private final JLabel state = new JLabel("Loading local Git history…");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultListModel<GitHistoryModel.Commit> commits = new DefaultListModel<>();
    private final JList<GitHistoryModel.Commit> history = new JList<>(commits);
    private final JTextArea commitDetails = readOnly();
    private final JTextArea operationOutput = readOnly();
    private final JButton refresh = new JButton("Refresh");
    private final JButton fetch = new JButton("Fetch");
    private final JButton pull = new JButton("Pull (FF only)");
    private final JButton push = new JButton("Push");
    private final JButton cancel = new JButton("Cancel");
    private GitHistoryModel.Snapshot snapshot;
    private int activeJobId = -1;

    static void showFor(Texteditor editor, Loader loader) {
        GitHistoryRemoteDialog dialog = new GitHistoryRemoteDialog(editor, loader);
        dialog.setVisible(true);
        dialog.refresh();
    }

    private GitHistoryRemoteDialog(Texteditor editor, Loader loader) {
        super(editor, "Git History and Remote Operations", false);
        this.editor = editor;
        this.loader = loader;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        updateRemoteActions();
        setPreferredSize(new Dimension(820, 560));
        pack();
        WorkbenchToolWindowPlacement.restore(editor, this, WorkbenchLayout.SurfaceType.GIT, "history");
    }

    private JPanel header() {
        JPanel text = new JPanel(new BorderLayout(0, 4));
        text.add(repository, BorderLayout.NORTH);
        state.setHorizontalAlignment(SwingConstants.LEFT);
        text.add(state, BorderLayout.SOUTH);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(text, BorderLayout.CENTER);
        panel.add(progress, BorderLayout.EAST);
        return panel;
    }

    private JSplitPane content() {
        history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        history.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showCommit(history.getSelectedValue());
        });
        JScrollPane historyScroll = new JScrollPane(history);
        historyScroll.setBorder(BorderFactory.createTitledBorder("Local history"));
        JScrollPane commitScroll = new JScrollPane(commitDetails);
        commitScroll.setBorder(BorderFactory.createTitledBorder("Selected commit"));
        JSplitPane upper = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, historyScroll, commitScroll);
        upper.setResizeWeight(0.46);
        JScrollPane outputScroll = new JScrollPane(operationOutput);
        outputScroll.setBorder(BorderFactory.createTitledBorder("Remote operation output"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, outputScroll);
        split.setResizeWeight(0.64);
        return split;
    }

    private JPanel actions() {
        refresh.addActionListener(event -> refresh());
        fetch.addActionListener(event -> startRemote(GitHistoryModel.RemoteAction.FETCH));
        pull.addActionListener(event -> startRemote(GitHistoryModel.RemoteAction.PULL));
        push.addActionListener(event -> startRemote(GitHistoryModel.RemoteAction.PUSH));
        cancel.addActionListener(event -> cancelActiveJob());
        cancel.setEnabled(false);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel panel = new JPanel();
        panel.add(refresh);
        panel.add(fetch);
        panel.add(pull);
        panel.add(push);
        panel.add(cancel);
        panel.add(close);
        return panel;
    }

    private void refresh() {
        if (activeJobId >= 0) return;
        setBusy(true);
        state.setText("Loading local Git history…");
        int jobId = editor.asyncJobService.submit("Git history refresh", token -> loader.load(token), (job, result, error) -> {
            if (!isDisplayable() || job.getId() != activeJobId) return;
            activeJobId = -1;
            setBusy(false);
            if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
                state.setText("Git history refresh cancelled.");
                return;
            }
            if (job.getStatus() != AsyncJobService.Status.SUCCEEDED || result == null) {
                state.setText(error == null ? job.getErrorMessage() : error.getMessage());
                return;
            }
            render(result);
        });
        activeJobId = jobId;
    }

    private void render(GitHistoryModel.Snapshot loaded) {
        snapshot = loaded;
        repository.setText(loaded.root().isBlank() ? "Repository: unavailable" : "Repository: " + loaded.root());
        state.setText(loaded.detail());
        commits.clear();
        commitDetails.setText("");
        for (GitHistoryModel.Commit commit : loaded.commits()) commits.addElement(commit);
        if (!loaded.commits().isEmpty()) history.setSelectedIndex(0);
        updateRemoteActions();
    }

    private void startRemote(GitHistoryModel.RemoteAction action) {
        if (activeJobId >= 0) return;
        if (!remoteActionsEnabled()) {
            state.setText("Remote actions disabled by git.remote.actions.enabled=false");
            return;
        }
        if (snapshot == null || !snapshot.available()) {
            state.setText("Refresh available Git history before remote actions.");
            return;
        }
        if (snapshot.remotes().isEmpty()) {
            state.setText("No configured Git remote is available for " + action.label() + ".");
            return;
        }
        if (action.requiresConfirmation() && !confirm(action)) return;
        setBusy(true);
        state.setText("Running " + action.label() + "; cancel is available.");
        operationOutput.setText("");
        int jobId = editor.asyncJobService.submit("Git remote " + action.label(), token -> loader.run(action, token), (job, result, error) -> {
            if (!isDisplayable() || job.getId() != activeJobId) return;
            activeJobId = -1;
            setBusy(false);
            if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
                state.setText(action.label() + " cancelled.");
                return;
            }
            if (job.getStatus() != AsyncJobService.Status.SUCCEEDED || result == null) {
                state.setText(action.label() + " failed: " + (error == null ? job.getErrorMessage() : error.getMessage()));
                return;
            }
            operationOutput.setText(result.detail());
            operationOutput.setCaretPosition(0);
            state.setText(result.succeeded() ? action.label() + " completed." : action.label() + " failed.");
            if (result.succeeded()) refresh();
        });
        activeJobId = jobId;
    }

    private boolean confirm(GitHistoryModel.RemoteAction action) {
        String message = switch (action) {
            case PULL -> "Run git pull --ff-only against the configured upstream? This can update the working tree.";
            case PUSH -> "Run git push to the configured remote? This publishes local commits.";
            default -> "Run " + action.label() + "?";
        };
        return JOptionPane.showConfirmDialog(this, message, "Confirm " + action.label(), JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void cancelActiveJob() {
        if (activeJobId < 0) return;
        if (editor.asyncJobService.cancel(activeJobId)) {
            state.setText("Cancellation requested…");
            cancel.setEnabled(false);
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        refresh.setEnabled(!busy);
        cancel.setEnabled(busy);
        if (busy) {
            fetch.setEnabled(false);
            pull.setEnabled(false);
            push.setEnabled(false);
        } else {
            updateRemoteActions();
        }
    }

    private void updateRemoteActions() {
        boolean enabled = remoteActionsEnabled() && snapshot != null && snapshot.available() && !snapshot.remotes().isEmpty();
        fetch.setEnabled(enabled);
        pull.setEnabled(enabled);
        push.setEnabled(enabled);
    }

    private boolean remoteActionsEnabled() {
        return editor.configManager.getGitRemoteActionsEnabled();
    }

    private void showCommit(GitHistoryModel.Commit commit) {
        if (commit == null) {
            commitDetails.setText("");
            return;
        }
        String decorations = commit.decorations().isBlank() ? "" : "\nRefs: " + commit.decorations();
        commitDetails.setText("Commit: " + commit.hash() + decorations + "\nAuthor: " + commit.author() + "\nDate: " + commit.timestamp()
            + "\n\n" + commit.subject() + "\n");
        commitDetails.setCaretPosition(0);
    }

    private static JTextArea readOnly() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        return area;
    }
}
