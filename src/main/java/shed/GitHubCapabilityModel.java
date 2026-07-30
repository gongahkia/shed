package shed;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GitHubCapabilityModel {
    private static final Pattern VERSION = Pattern.compile("(?:gh version |v)(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern GITHUB_REMOTE = Pattern.compile("(?:github\\.com[:/])([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$");

    record Report(boolean enabled, boolean available, boolean supported, boolean authenticated, String repository, List<String> missing, String remediation) {
        Report {
            repository = repository == null ? "" : repository;
            missing = missing == null ? List.of() : List.copyOf(missing);
            remediation = remediation == null ? "" : remediation;
        }

        boolean ready() {
            return available && supported && authenticated && !repository.isBlank() && missing.isEmpty();
        }

        String format() {
            String state = ready() ? (enabled ? "ready" : "ready but disabled") : "unavailable";
            StringBuilder output = new StringBuilder("GitHub CLI capability report\n\nState: ").append(state)
                .append("\nRepository: ").append(repository.isBlank() ? "unavailable" : repository)
                .append("\nIntegration enabled: ").append(enabled);
            if (!missing.isEmpty()) output.append("\nMissing: ").append(String.join(", ", missing));
            if (!remediation.isBlank()) output.append("\nRemediation: ").append(remediation);
            output.append("\n\nThis explicit check uses local gh and Git commands only; it does not call the GitHub API or modify Git state.\n");
            return output.toString();
        }
    }

    private GitHubCapabilityModel() { }

    static Report inspect(boolean enabled, CommandResult version, CommandResult auth, CommandResult remote, CommandResult... commands) {
        if (version == null || version.exitCode != 0) {
            return new Report(enabled, false, false, false, "", List.of("gh CLI"), "Install GitHub CLI and ensure `gh` is on PATH.");
        }
        if (!supportedVersion(version.stdout)) {
            return new Report(enabled, true, false, false, "", List.of("supported gh version"), "Install GitHub CLI 2.0.0 or newer.");
        }
        if (auth == null || auth.exitCode != 0) {
            return new Report(enabled, true, true, false, "", List.of("GitHub authentication"), "Run `gh auth login`, then run :github status again.");
        }
        String repository = repository(remote == null ? "" : remote.stdout);
        if (repository.isBlank()) {
            return new Report(enabled, true, true, true, "", List.of("GitHub repository context"),
                "Set origin to a GitHub remote, for example `git remote add origin https://github.com/OWNER/REPO.git`.");
        }
        List<String> missing = new ArrayList<>();
        String[] names = { "gh pr list", "gh pr view", "gh api" };
        for (int index = 0; index < names.length; index++) {
            if (commands == null || index >= commands.length || commands[index] == null || commands[index].exitCode != 0) missing.add(names[index]);
        }
        if (!missing.isEmpty()) {
            return new Report(enabled, true, true, true, repository, missing, "Upgrade or reinstall GitHub CLI with pull-request and API commands.");
        }
        String remediation = enabled ? "GitHub review actions remain explicit; no background network work is enabled."
            : "Set `github.review.enabled=true` in the settings GUI or TOML before later review actions are enabled.";
        return new Report(enabled, true, true, true, repository, List.of(), remediation);
    }

    private static boolean supportedVersion(String output) {
        Matcher matcher = VERSION.matcher(output == null ? "" : output);
        return matcher.find() && Integer.parseInt(matcher.group(1)) >= 2;
    }

    private static String repository(String remote) {
        Matcher matcher = GITHUB_REMOTE.matcher((remote == null ? "" : remote).strip());
        return matcher.find() ? matcher.group(1) + "/" + matcher.group(2) : "";
    }
}
