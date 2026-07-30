package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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
import javax.swing.JTabbedPane;

final class GitHubPullRequestDialog extends JDialog {
    interface Loader {
        GitHubPullRequestModel.Snapshot load(AsyncJobService.JobToken token);
        GitHubPullRequestDetailModel.Detail detail(String repository, GitHubPullRequestModel.PullRequest pullRequest, AsyncJobService.JobToken token);
    }
    private final Texteditor editor;
    private final Loader loader;
    private final GitHubReviewDraftStore draftStore;
    private final JLabel state = new JLabel("Loading pull requests…");
    private final JLabel repository = new JLabel("Repository: resolving…");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultListModel<GitHubPullRequestModel.PullRequest> pullRequests = new DefaultListModel<>();
    private final JList<GitHubPullRequestModel.PullRequest> list = new JList<>(pullRequests);
    private final JTextArea details = new JTextArea();
    private final JLabel draftTarget = new JLabel("Select a pull request to create a local unsent draft.");
    private final JTextArea draft = new JTextArea();
    private final JButton refresh = new JButton("Refresh");
    private final JButton viewDetails = new JButton("View Details and Diff");
    private final JButton saveDraft = new JButton("Save Local Draft");
    private final JButton discardDraft = new JButton("Discard Local Draft");
    private final JButton cancel = new JButton("Cancel");
    private final Map<GitHubReviewDraftStore.Target, String> workingDrafts = new HashMap<>();
    private GitHubReviewDraftStore.Target selectedDraftTarget;
    private int jobId = -1;

    static void showFor(Texteditor editor, GitHubReviewDraftStore draftStore, Loader loader) {
        GitHubPullRequestDialog dialog = new GitHubPullRequestDialog(editor, draftStore, loader);
        dialog.setVisible(true);
        dialog.refresh();
    }

    private GitHubPullRequestDialog(Texteditor editor, GitHubReviewDraftStore draftStore, Loader loader) {
        super(editor, "GitHub Pull Requests", false);
        this.editor = editor;
        this.draftStore = draftStore;
        this.loader = loader;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        header.add(repository, BorderLayout.NORTH);
        header.add(state, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        details.setEditable(false);
        list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) showPullRequest(list.getSelectedValue()); });
        JScrollPane entries = new JScrollPane(list);
        entries.setBorder(BorderFactory.createTitledBorder("Open pull requests"));
        JScrollPane detailPane = new JScrollPane(details);
        detailPane.setBorder(BorderFactory.createTitledBorder("Selected pull request"));
        draft.setLineWrap(true); draft.setWrapStyleWord(true); draft.setEditable(false);
        JPanel draftPanel = new JPanel(new BorderLayout(0, 4));
        draftPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        draftPanel.add(draftTarget, BorderLayout.NORTH);
        draftPanel.add(new JScrollPane(draft), BorderLayout.CENTER);
        JTabbedPane content = new JTabbedPane();
        content.addTab("Details and Diff", detailPane);
        content.addTab("Local Unsent Draft", draftPanel);
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, entries, content);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        refresh.addActionListener(event -> refresh());
        viewDetails.addActionListener(event -> loadDetails());
        saveDraft.addActionListener(event -> saveDraft());
        discardDraft.addActionListener(event -> discardDraft());
        cancel.addActionListener(event -> cancel());
        cancel.setEnabled(false);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel actions = new JPanel();
        actions.add(progress); actions.add(refresh); actions.add(viewDetails); actions.add(saveDraft); actions.add(discardDraft); actions.add(cancel); actions.add(close);
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
    private void setBusy(boolean busy) {
        progress.setVisible(busy); refresh.setEnabled(!busy); viewDetails.setEnabled(!busy); cancel.setEnabled(busy);
        boolean editable = !busy && selectedDraftTarget != null;
        draft.setEditable(editable); saveDraft.setEnabled(editable); discardDraft.setEnabled(editable);
    }
    private void loadDetails() {
        GitHubPullRequestModel.PullRequest pullRequest = list.getSelectedValue();
        if (jobId >= 0 || pullRequest == null) { if (pullRequest == null) state.setText("Select a pull request first."); return; }
        String repo = repository.getText().replaceFirst("^Repository: ", "");
        if (repo.isBlank() || "unavailable".equals(repo)) { state.setText("Refresh pull requests first."); return; }
        setBusy(true); state.setText("Loading read-only details and diff for #" + pullRequest.number() + "…");
        int expected = editor.asyncJobService.submit("GitHub pull-request detail #" + pullRequest.number(), token -> loader.detail(repo, pullRequest, token), (job, detail, error) -> {
            if (!isDisplayable() || job.getId() != jobId) return;
            jobId = -1; setBusy(false);
            if (job.getStatus() == AsyncJobService.Status.CANCELLED) { state.setText("Pull-request detail loading cancelled."); return; }
            if (error != null || detail == null) { state.setText(error == null ? job.getErrorMessage() : error.getMessage()); return; }
            if (!detail.available()) { state.setText(detail.error()); return; }
            details.setText("#" + pullRequest.number() + " " + pullRequest.title() + "\nState: " + detail.state() + "\nAuthor: " + detail.author()
                + "\nUpdated: " + detail.updatedAt() + "\nChanges: +" + detail.additions() + " -" + detail.deletions() + " across " + detail.changedFiles()
                + " files\nURL: " + pullRequest.url() + "\n\n" + detail.body() + "\n\nFiles:\n" + String.join("\n", detail.files()) + "\n\nDiff:\n" + detail.patch());
            details.setCaretPosition(0); state.setText("Read-only details and diff loaded for #" + pullRequest.number() + ".");
        });
        jobId = expected;
    }
    private void showPullRequest(GitHubPullRequestModel.PullRequest pullRequest) {
        preserveWorkingDraft();
        if (pullRequest == null) {
            details.setText(""); selectedDraftTarget = null; draft.setText("");
            draftTarget.setText("Select a pull request to create a local unsent draft."); setBusy(jobId >= 0); return;
        }
        details.setText("#" + pullRequest.number() + "\nAuthor: " + pullRequest.author() + "\nUpdated: " + pullRequest.updatedAt()
            + "\nDraft: " + pullRequest.draft() + "\nURL: " + pullRequest.url() + "\n\n" + pullRequest.title());
        try {
            selectedDraftTarget = new GitHubReviewDraftStore.Target(currentRepository(), pullRequest.number());
            String body = workingDrafts.get(selectedDraftTarget);
            if (body == null) {
                GitHubReviewDraftStore.Draft saved = draftStore.load(selectedDraftTarget);
                body = saved == null ? "" : saved.body();
            }
            draft.setText(body);
            draftTarget.setText("Local unsent draft for " + selectedDraftTarget.repository() + " PR #" + selectedDraftTarget.pullRequest()
                + ". No server comment has been created.");
        } catch (IOException | IllegalArgumentException error) {
            selectedDraftTarget = null; draft.setText("");
            draftTarget.setText("Local draft unavailable: " + error.getMessage()); state.setText("Unable to load local draft: " + error.getMessage());
        }
        setBusy(jobId >= 0);
    }

    private void saveDraft() {
        if (selectedDraftTarget == null) { state.setText("Select a pull request before saving a local draft."); return; }
        try {
            draftStore.save(selectedDraftTarget, draft.getText());
            workingDrafts.put(selectedDraftTarget, draft.getText());
            state.setText("Saved local unsent draft for " + selectedDraftTarget.repository() + " PR #" + selectedDraftTarget.pullRequest()
                + "; no server comment was created.");
        } catch (IOException | IllegalArgumentException error) {
            state.setText("Unable to save local draft: " + error.getMessage());
        }
    }

    private void discardDraft() {
        if (selectedDraftTarget == null) { state.setText("Select a pull request before discarding a local draft."); return; }
        try {
            boolean discarded = draftStore.discard(selectedDraftTarget);
            workingDrafts.remove(selectedDraftTarget); draft.setText("");
            state.setText((discarded ? "Discarded" : "No saved") + " local draft for " + selectedDraftTarget.repository() + " PR #"
                + selectedDraftTarget.pullRequest() + "; no server comment was changed.");
        } catch (IOException error) {
            state.setText("Unable to discard local draft: " + error.getMessage());
        }
    }

    private void preserveWorkingDraft() {
        if (selectedDraftTarget != null) workingDrafts.put(selectedDraftTarget, draft.getText());
    }

    private String currentRepository() {
        String value = repository.getText().replaceFirst("^Repository: ", "");
        return "unavailable".equals(value) ? "" : value;
    }
}
