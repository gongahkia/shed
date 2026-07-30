package shed;

final class GitHubReviewFailureModel {
    enum Kind { AUTHENTICATION, RATE_LIMIT, PERMISSION, NETWORK, CLI_VERSION, MALFORMED_OUTPUT, UNKNOWN }

    record Remediation(Kind kind, boolean retrySafe, String remediation, String fallback) {
        Remediation {
            kind = kind == null ? Kind.UNKNOWN : kind;
            remediation = remediation == null ? "" : remediation;
            fallback = fallback == null ? "" : fallback;
        }

        String format() {
            return label(kind) + ". Retry safe: " + (retrySafe ? "yes, after remediation." : "no; do not retry automatically.")
                + " Remediation: " + remediation + " Non-GitHub fallback: " + fallback;
        }
    }

    private GitHubReviewFailureModel() { }

    static Remediation fromResult(String command, CommandResult result, boolean write) {
        if (result == null) return unavailable(command + " returned no result.");
        String output = output(result).toLowerCase();
        if (authentication(output, result.exitCode)) return new Remediation(Kind.AUTHENTICATION, true,
            "Run `gh auth login`, then explicitly retry " + command + ".", fallback());
        if (rateLimit(output)) return new Remediation(Kind.RATE_LIMIT, true,
            "Wait for GitHub's rate-limit reset, then explicitly retry " + command + ".", fallback());
        if (permission(output)) return new Remediation(Kind.PERMISSION, true,
            "Grant the authenticated account repository review access, then explicitly retry " + command + ".", fallback());
        if (cli(output)) return new Remediation(Kind.CLI_VERSION, false,
            "Install or upgrade the user-installed GitHub CLI, then run :github status.", fallback());
        if (network(output, result.exitCode)) return new Remediation(Kind.NETWORK, !write,
            write ? "Verify the pull request on GitHub before any manual retry; the write outcome may be unknown."
                : "Check connectivity or GitHub service status, then explicitly retry " + command + ".", fallback());
        return new Remediation(Kind.UNKNOWN, !write,
            write ? "Inspect the captured gh result and verify server state before any manual retry."
                : "Inspect the captured gh result, correct the cause, then explicitly retry " + command + ".", fallback());
    }

    static Remediation malformed(String command) {
        return new Remediation(Kind.MALFORMED_OUTPUT, true,
            "Refresh " + command + " after checking the installed gh version and process.output.max.bytes.", fallback());
    }

    static Remediation unavailable(String reason) {
        return new Remediation(Kind.UNKNOWN, false, reason, fallback());
    }

    static Remediation cliVersion(String reason) {
        return new Remediation(Kind.CLI_VERSION, false, reason, fallback());
    }

    private static boolean authentication(String output, int exitCode) {
        return exitCode == 4 || output.contains("auth login") || output.contains("not logged") || output.contains("authentication required")
            || output.contains("bad credentials") || output.contains("http 401") || output.contains("status 401");
    }

    private static boolean rateLimit(String output) {
        return output.contains("rate limit") || output.contains("too many requests") || output.contains("http 429") || output.contains("status 429");
    }

    private static boolean permission(String output) {
        return output.contains("resource not accessible") || output.contains("insufficient permission") || output.contains("forbidden")
            || output.contains("http 403") || output.contains("status 403") || output.contains("write access");
    }

    private static boolean cli(String output) {
        return output.contains("unknown command") || output.contains("unknown flag") || output.contains("not a gh command")
            || output.contains("command not found") || output.contains("executable file not found") || output.contains("no such file or directory");
    }

    private static boolean network(String output, int exitCode) {
        return exitCode < 0 || output.contains("timed out") || output.contains("timeout") || output.contains("connection")
            || output.contains("network") || output.contains("dns") || output.contains("tls") || output.contains("server error") || output.contains("http 5");
    }

    private static String output(CommandResult result) {
        return result.stdout + "\n" + result.stderr;
    }

    private static String fallback() {
        return "Use :git workbench, :git diff, or :git log for local repository inspection.";
    }

    private static String label(Kind kind) {
        return switch (kind) {
            case AUTHENTICATION -> "GitHub authentication failure";
            case RATE_LIMIT -> "GitHub rate-limit failure";
            case PERMISSION -> "GitHub permission failure";
            case NETWORK -> "GitHub network failure";
            case CLI_VERSION -> "GitHub CLI-version failure";
            case MALFORMED_OUTPUT -> "GitHub CLI malformed-output failure";
            case UNKNOWN -> "GitHub review failure";
        };
    }
}
