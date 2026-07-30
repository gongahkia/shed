package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTabbedPane;

final class GitHubPullRequestDialog extends JDialog {
    interface Loader {
        GitHubPullRequestModel.Snapshot load(AsyncJobService.JobToken token);
        GitHubPullRequestDetailModel.Detail detail(String repository, GitHubPullRequestModel.PullRequest pullRequest, AsyncJobService.JobToken token);
        GitHubReviewSubmissionModel.Result submit(GitHubReviewSubmissionModel.Request request, AsyncJobService.JobToken token);
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
    private final JComboBox<GitHubReviewSubmissionModel.Action> reviewAction = new JComboBox<>(GitHubReviewSubmissionModel.Action.values());
    private final JTextArea submissionResult = new JTextArea("No review submission attempted.");
    private final JButton refresh = new JButton("Refresh");
    private final JButton viewDetails = new JButton("View Details and Diff");
    private final JButton saveDraft = new JButton("Save Local Draft");
    private final JButton discardDraft = new JButton("Discard Local Draft");
    private final JButton submitReview = new JButton("Submit Review…");
    private final JButton cancel = new JButton("Cancel");
    private final Map<GitHubReviewDraftStore.Target, String> workingDrafts = new HashMap<>();
    private final Map<GitHubReviewDraftStore.Target, GitHubReviewSubmissionModel.Result> submissionResults = new HashMap<>();
    private final Set<GitHubReviewSubmissionModel.Request> acknowledgedReviews = new HashSet<>();
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
        AccessibilitySupport.describe(list, "GitHub pull requests", "Open pull requests available from the configured repository.");
        AccessibilitySupport.describe(details, "Selected pull request details", "Read-only details and diff summary for the selected pull request.");
        AccessibilitySupport.describe(draft, "Local review draft", "Editable local review draft; it is not submitted until Submit Review is chosen.");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
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
        JPanel submissionPanel = new JPanel(new BorderLayout(0, 4));
        submissionResult.setEditable(false);
        submissionPanel.add(new JLabel("Exact gh server result"), BorderLayout.NORTH);
        submissionPanel.add(new JScrollPane(submissionResult), BorderLayout.CENTER);
        JTabbedPane content = new JTabbedPane();
        content.addTab("Details and Diff", detailPane);
        content.addTab("Local Unsent Draft", draftPanel);
        content.addTab("Submission Result", submissionPanel);
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, entries, content);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        refresh.addActionListener(event -> refresh());
        viewDetails.addActionListener(event -> loadDetails());
        saveDraft.addActionListener(event -> saveDraft());
        discardDraft.addActionListener(event -> discardDraft());
        submitReview.addActionListener(event -> submitReview());
        cancel.addActionListener(event -> cancel());
        cancel.setEnabled(false);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel actions = new JPanel();
        actions.add(progress); actions.add(refresh); actions.add(viewDetails); actions.add(reviewAction); actions.add(saveDraft); actions.add(discardDraft);
        actions.add(submitReview); actions.add(cancel); actions.add(close);
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
            if (error != null || snapshot == null) {
                state.setText(GitHubReviewFailureModel.unavailable("GitHub pull-request discovery failed: "
                    + (error == null ? job.getErrorMessage() : error.getMessage())).format()); return;
            }
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
        draft.setEditable(editable); reviewAction.setEnabled(editable); saveDraft.setEnabled(editable); discardDraft.setEnabled(editable); submitReview.setEnabled(editable);
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
            if (error != null || detail == null) {
                state.setText(GitHubReviewFailureModel.unavailable("GitHub pull-request detail loading failed: "
                    + (error == null ? job.getErrorMessage() : error.getMessage())).format()); return;
            }
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
            renderSubmissionResult(submissionResults.get(selectedDraftTarget));
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

    private void submitReview() {
        if (jobId >= 0 || selectedDraftTarget == null) { state.setText("Select a pull request before submitting a review."); return; }
        GitHubReviewSubmissionModel.Request request;
        try {
            request = new GitHubReviewSubmissionModel.Request(selectedDraftTarget,
                (GitHubReviewSubmissionModel.Action) reviewAction.getSelectedItem(), draft.getText());
            if (acknowledgedReviews.contains(request) || draftStore.acknowledged(request.target(), request.action().name(), request.body())) {
                GitHubReviewSubmissionModel.Result acknowledged = GitHubReviewSubmissionModel.alreadyAcknowledged();
                submissionResults.put(request.target(), acknowledged); renderSubmissionResult(acknowledged);
                state.setText(acknowledged.detail()); return;
            }
        } catch (IOException | IllegalArgumentException error) {
            state.setText("Unable to verify review submission: " + error.getMessage()); return;
        }
        if (!confirmSubmission(request)) return;
        setBusy(true); state.setText("Submitting " + request.action() + " review for " + request.target().repository() + " PR #" + request.target().pullRequest() + "…");
        int expected = editor.asyncJobService.submit("GitHub pull-request review #" + request.target().pullRequest(), token -> loader.submit(request, token), (job, result, error) -> {
            if (!isDisplayable() || job.getId() != jobId) return;
            jobId = -1; setBusy(false);
            if (job.getStatus() == AsyncJobService.Status.CANCELLED) { state.setText("Review submission cancelled; no automatic retry will run."); return; }
            if (error != null || result == null) {
                GitHubReviewSubmissionModel.Result unavailable = GitHubReviewSubmissionModel.unavailable(
                    "Review submission returned no acknowledgement: " + (error == null ? job.getErrorMessage() : error.getMessage()));
                submissionResults.put(request.target(), unavailable); renderSubmissionResult(unavailable); state.setText(unavailable.detail()); return;
            }
            submissionResults.put(request.target(), result); renderSubmissionResult(result);
            if (!result.acknowledged()) { state.setText(result.detail() + " Local draft retained."); return; }
            acknowledgedReviews.add(request);
            workingDrafts.remove(request.target()); draft.setText("");
            try {
                draftStore.acknowledge(request.target(), request.action().name(), request.body());
                state.setText(result.detail() + " Local draft removed and acknowledgement recorded; no duplicate submission will be sent.");
            } catch (IOException storageError) {
                state.setText(result.detail() + " Local acknowledgement storage failed: " + storageError.getMessage() + ". Do not retry this review.");
            }
        });
        jobId = expected;
    }

    private boolean confirmSubmission(GitHubReviewSubmissionModel.Request request) {
        String message = "Submit a GitHub " + request.action() + " review?\n\n"
            + "Target: " + request.target().repository() + " PR #" + request.target().pullRequest() + "\n"
            + (request.body().isBlank() ? "Body: none\n" : "Body: current local draft\n")
            + "\nThis invokes the user-installed gh CLI once. It creates server-side review state and is not retried automatically.";
        return JOptionPane.showConfirmDialog(this, message, "Confirm GitHub Review Submission", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void renderSubmissionResult(GitHubReviewSubmissionModel.Result result) {
        if (result == null) { submissionResult.setText("No review submission attempted."); return; }
        submissionResult.setText("State: " + result.state() + "\n" + result.detail() + "\n\nExact gh server result:\n"
            + (result.serverResult().isBlank() ? "(no output)" : result.serverResult()));
        submissionResult.setCaretPosition(0);
    }

    private void preserveWorkingDraft() {
        if (selectedDraftTarget != null) workingDrafts.put(selectedDraftTarget, draft.getText());
    }

    private String currentRepository() {
        String value = repository.getText().replaceFirst("^Repository: ", "");
        return "unavailable".equals(value) ? "" : value;
    }
}
