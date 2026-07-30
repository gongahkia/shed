package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

final class GitHubPullRequestDialog extends JDialog {
    interface Loader { GitHubPullRequestModel.Snapshot load(AsyncJobService.JobToken token); }
    private final Texteditor editor;
    private final Loader loader;
    private final JLabel state = new JLabel("Loading pull requests…");
    private final JLabel repository = new JLabel("Repository: resolving…");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultListModel<GitHubPullRequestModel.PullRequest> pullRequests = new DefaultListModel<>();
    private final JList<GitHubPullRequestModel.PullRequest> list = new JList<>(pullRequests);
    private final JTextArea details = new JTextArea();
    private final JButton refresh = new JButton("Refresh");
    private final JButton cancel = new JButton("Cancel");
    private int jobId = -1;

    static void showFor(Texteditor editor, Loader loader) {
        GitHubPullRequestDialog dialog = new GitHubPullRequestDialog(editor, loader);
        dialog.setVisible(true);
        dialog.refresh();
    }

    private GitHubPullRequestDialog(Texteditor editor, Loader loader) {
        super(editor, "GitHub Pull Requests", false);
        this.editor = editor;
        this.loader = loader;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        header.add(repository, BorderLayout.NORTH);
        header.add(state, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        details.setEditable(false);
        list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) showDetails(list.getSelectedValue()); });
        JScrollPane entries = new JScrollPane(list);
        entries.setBorder(BorderFactory.createTitledBorder("Open pull requests"));
        JScrollPane detailPane = new JScrollPane(details);
        detailPane.setBorder(BorderFactory.createTitledBorder("Selected pull request"));
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, entries, detailPane);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        refresh.addActionListener(event -> refresh());
        cancel.addActionListener(event -> cancel());
        cancel.setEnabled(false);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel actions = new JPanel();
        actions.add(progress); actions.add(refresh); actions.add(cancel); actions.add(close);
        add(actions, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(760, 440));
        pack(); setLocationRelativeTo(editor);
    }

    private void refresh() {
        if (jobId >= 0) return;
        setBusy(true); state.setText("Loading pull requests…");
        int expected = editor.asyncJobService.submit("GitHub pull-request discovery", token -> loader.load(token), (job, snapshot, error) -> {
            if (!isDisplayable() || job.getId() != jobId) return;
            jobId = -1; setBusy(false);
            if (job.getStatus() == AsyncJobService.Status.CANCELLED) { state.setText("Pull-request discovery cancelled."); return; }
            if (error != null || snapshot == null) { state.setText(error == null ? job.getErrorMessage() : error.getMessage()); return; }
            repository.setText(snapshot.repository().isBlank() ? "Repository: unavailable" : "Repository: " + snapshot.repository());
            state.setText(snapshot.detail()); pullRequests.clear(); details.setText("");
            for (GitHubPullRequestModel.PullRequest pullRequest : snapshot.pullRequests()) pullRequests.addElement(pullRequest);
            if (!snapshot.pullRequests().isEmpty()) list.setSelectedIndex(0);
        });
        jobId = expected;
    }

    private void cancel() { if (jobId >= 0 && editor.asyncJobService.cancel(jobId)) cancel.setEnabled(false); }
    private void setBusy(boolean busy) { progress.setVisible(busy); refresh.setEnabled(!busy); cancel.setEnabled(busy); }
    private void showDetails(GitHubPullRequestModel.PullRequest pullRequest) {
        if (pullRequest == null) { details.setText(""); return; }
        details.setText("#" + pullRequest.number() + "\nAuthor: " + pullRequest.author() + "\nUpdated: " + pullRequest.updatedAt()
            + "\nDraft: " + pullRequest.draft() + "\nURL: " + pullRequest.url() + "\n\n" + pullRequest.title());
    }
}
