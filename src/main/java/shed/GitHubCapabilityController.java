package shed;

import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.swing.JOptionPane;

final class GitHubCapabilityController {
    private final Texteditor editor;

    GitHubCapabilityController(Texteditor editor) {
        this.editor = editor;
    }

    String handle(String argument) {
        String subcommand = argument == null || argument.isBlank() ? "status" : argument.trim().toLowerCase();
        if ("prs".equals(subcommand) || "pulls".equals(subcommand)) return showPullRequests();
        if ("consent".equals(subcommand) || "enable".equals(subcommand)) return requestConsent();
        if ("disable".equals(subcommand)) return revokeConsent();
        if (!"status".equals(subcommand) && !"check".equals(subcommand)) return "Usage: :github [status|prs|consent|disable]";
        int jobId = editor.asyncJobService.submit("GitHub CLI capability check", token -> inspect(token), (job, report, error) -> {
            if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
                editor.showMessage("GitHub CLI capability check cancelled");
            } else if (error != null || report == null) {
                editor.showMessage("GitHub CLI capability check failed: " + (error == null ? job.getErrorMessage() : error.getMessage()));
            } else {
                editor.showScratchBuffer("[github status]", report.format());
                editor.showMessage(report.ready() ? "GitHub CLI capability check completed" : "GitHub CLI capability check requires remediation");
            }
        });
        return "GitHub CLI capability check started (local-only) job " + jobId;
    }

    private String requestConsent() {
        GitHubReviewConsent.State current = editor.configManager.getGitHubReviewConsent();
        if (current.enabled()) return "GitHub review integration already enabled; use :github disable to revoke consent.";
        String review = "Enable GitHub review integration?\n\n"
            + "Later review commands may invoke the user-installed gh CLI for explicit actions only.\n"
            + "This dialog and :github status do not call the GitHub API or modify Git.\n"
            + "No telemetry or background network activity is enabled.\n"
            + "You can revoke consent at any time with :github disable or the settings GUI.";
        int choice = JOptionPane.showConfirmDialog(editor, review, "GitHub Review Consent", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return "GitHub review consent not granted.";
        try {
            editor.configManager.setAndPersist("github.review.consent.granted", "true");
            editor.configManager.setAndPersist("github.review.enabled", "true");
            return "GitHub review integration enabled by explicit consent.";
        } catch (IOException error) {
            return "Unable to persist GitHub review consent: " + error.getMessage();
        }
    }

    private String revokeConsent() {
        try {
            editor.configManager.setAndPersist("github.review.enabled", "false");
            editor.configManager.setAndPersist("github.review.consent.granted", "false");
            return "GitHub review integration disabled and consent revoked.";
        } catch (IOException error) {
            return "Unable to revoke GitHub review consent: " + error.getMessage();
        }
    }

    private String showPullRequests() {
        if (!editor.configManager.getGitHubReviewEnabled()) return "GitHub review requires explicit consent; run :github consent first.";
        GitHubPullRequestDialog.showFor(editor, this::loadPullRequests);
        return "GitHub pull-request discovery opened";
    }

    private GitHubPullRequestModel.Snapshot loadPullRequests(AsyncJobService.JobToken token) {
        File root = editor.resolveGitRoot();
        if (root == null) return GitHubPullRequestModel.unavailable("Current workspace is not a Git repository.");
        CommandResult remote = run(root, List.of("git", "remote", "get-url", "origin"), token);
        String repository = GitHubCapabilityModel.repository(remote.stdout);
        if (remote.exitCode != 0 || repository.isBlank()) return GitHubPullRequestModel.unavailable("Origin is not a GitHub repository.");
        if (!editor.configManager.getGitHubReviewEnabled()) return GitHubPullRequestModel.unavailable("GitHub review consent was revoked before discovery.");
        String template = "{{range .}}{{.number}}{{\"\\x00\"}}{{.title}}{{\"\\x00\"}}{{.author.login}}{{\"\\x00\"}}{{.updatedAt}}{{\"\\x00\"}}{{.isDraft}}{{\"\\x00\"}}{{.url}}{{\"\\x00\"}}{{end}}";
        CommandResult result = run(root, List.of("gh", "pr", "list", "--repo", repository, "--state", "open", "--limit", "100", "--json",
            "number,title,author,updatedAt,isDraft,url", "--template", template), token);
        return GitHubPullRequestModel.fromResult(repository, result);
    }

    private GitHubCapabilityModel.Report inspect(AsyncJobService.JobToken token) {
        File root = editor.resolveGitRoot();
        CommandResult version = run(List.of("gh", "--version"), token);
        CommandResult auth = run(List.of("gh", "auth", "status"), token);
        CommandResult remote = root == null ? new CommandResult(1, "", "no Git repository") : run(root, List.of("git", "remote", "get-url", "origin"), token);
        CommandResult prList = run(List.of("gh", "pr", "list", "--help"), token);
        CommandResult prView = run(List.of("gh", "pr", "view", "--help"), token);
        CommandResult api = run(List.of("gh", "api", "--help"), token);
        return GitHubCapabilityModel.inspect(editor.configManager.getGitHubReviewEnabled(), version, auth, remote, prList, prView, api);
    }

    private CommandResult run(List<String> command, AsyncJobService.JobToken token) {
        return run(new File("."), command, token);
    }

    private CommandResult run(File root, List<String> command, AsyncJobService.JobToken token) {
        return editor.runExternalCommand(command, root, null, token, editor.configManager.getProcessTimeoutMs(),
            editor.configManager.getProcessOutputMaxBytes(), true);
    }
}
