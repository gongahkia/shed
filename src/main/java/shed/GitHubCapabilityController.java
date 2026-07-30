package shed;

import java.io.File;
import java.util.List;

final class GitHubCapabilityController {
    private final Texteditor editor;

    GitHubCapabilityController(Texteditor editor) {
        this.editor = editor;
    }

    String handle(String argument) {
        String subcommand = argument == null || argument.isBlank() ? "status" : argument.trim().toLowerCase();
        if (!"status".equals(subcommand) && !"check".equals(subcommand)) return "Usage: :github [status]";
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
