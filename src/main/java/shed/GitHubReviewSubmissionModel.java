package shed;

import java.util.ArrayList;
import java.util.List;

final class GitHubReviewSubmissionModel {
    enum Action {
        COMMENT("Comment", "--comment", true),
        APPROVE("Approve", "--approve", false),
        REQUEST_CHANGES("Request changes", "--request-changes", true);

        private final String label;
        private final String flag;
        private final boolean bodyRequired;

        Action(String label, String flag, boolean bodyRequired) {
            this.label = label;
            this.flag = flag;
            this.bodyRequired = bodyRequired;
        }

        String flag() { return flag; }
        boolean bodyRequired() { return bodyRequired; }
        @Override public String toString() { return label; }
    }

    enum State { ACKNOWLEDGED, FAILED, UNKNOWN }

    record Request(GitHubReviewDraftStore.Target target, Action action, String body) {
        Request {
            if (target == null || action == null) throw new IllegalArgumentException("review target and action are required");
            body = body == null ? "" : body;
            if (action.bodyRequired() && body.isBlank()) throw new IllegalArgumentException(action + " requires a review body");
        }
    }

    record Result(State state, String detail, String serverResult) {
        Result {
            state = state == null ? State.UNKNOWN : state;
            detail = detail == null ? "" : detail;
            serverResult = serverResult == null ? "" : serverResult;
        }

        boolean acknowledged() { return state == State.ACKNOWLEDGED; }
    }

    private GitHubReviewSubmissionModel() { }

    static List<String> command(Request request) {
        if (request == null) throw new IllegalArgumentException("review request is required");
        List<String> command = new ArrayList<>(List.of("gh", "pr", "review", request.target().pullRequest(), "--repo", request.target().repository(), request.action().flag()));
        if (!request.body().isBlank()) { command.add("--body"); command.add(request.body()); }
        return List.copyOf(command);
    }

    static Result fromResult(CommandResult result) {
        if (result == null) return new Result(State.UNKNOWN, "gh pr review returned no result; no automatic retry will run.", "");
        String output = output(result);
        if (result.exitCode == 0) {
            String detail = output.endsWith("\n[shed: output truncated]")
                ? "gh acknowledged the review, but its output was truncated."
                : "gh acknowledged the review.";
            return new Result(State.ACKNOWLEDGED, detail, output);
        }
        if (result.exitCode < 0) return new Result(State.UNKNOWN,
            "gh pr review has no acknowledgement; no automatic retry will run (" + fallback(output, "process failure") + ").", output);
        return new Result(State.FAILED, "gh pr review failed with exit " + result.exitCode + "; the local draft is retained.", output);
    }

    static Result unavailable(String detail) {
        return new Result(State.UNKNOWN, detail, "");
    }

    static Result alreadyAcknowledged() {
        return new Result(State.ACKNOWLEDGED, "This exact review was already acknowledged locally; no duplicate gh command was run.", "");
    }

    private static String output(CommandResult result) {
        if (result.stderr.isBlank()) return result.stdout;
        if (result.stdout.isBlank()) return result.stderr;
        return "stdout:\n" + result.stdout + "\nstderr:\n" + result.stderr;
    }

    private static String fallback(String output, String fallback) {
        String value = output == null ? "" : output.strip();
        return value.isEmpty() ? fallback : value;
    }
}
