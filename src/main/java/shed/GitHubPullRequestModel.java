package shed;

import java.util.ArrayList;
import java.util.List;

final class GitHubPullRequestModel {
    enum State { READY, EMPTY, UNAUTHENTICATED, ERROR, UNAVAILABLE }

    record PullRequest(String number, String title, String author, String updatedAt, boolean draft, String url) {
        PullRequest {
            number = number == null ? "" : number;
            title = title == null ? "" : title;
            author = author == null ? "" : author;
            updatedAt = updatedAt == null ? "" : updatedAt;
            url = url == null ? "" : url;
        }

        @Override public String toString() {
            return "#" + number + (draft ? " [draft] " : " ") + title;
        }
    }

    record Snapshot(State state, String repository, List<PullRequest> pullRequests, String detail) {
        Snapshot {
            repository = repository == null ? "" : repository;
            pullRequests = pullRequests == null ? List.of() : List.copyOf(pullRequests);
            detail = detail == null ? "" : detail;
        }
    }

    private GitHubPullRequestModel() { }

    static Snapshot unavailable(String detail) {
        return new Snapshot(State.UNAVAILABLE, "", List.of(), GitHubReviewFailureModel.unavailable(detail).format());
    }

    static Snapshot fromResult(String repository, CommandResult result) {
        if (result == null) return new Snapshot(State.ERROR, repository, List.of(),
            GitHubReviewFailureModel.unavailable("gh pr list returned no result.").format());
        if (result.exitCode != 0) {
            GitHubReviewFailureModel.Remediation remediation = GitHubReviewFailureModel.fromResult("gh pr list", result, false);
            State state = remediation.kind() == GitHubReviewFailureModel.Kind.AUTHENTICATION ? State.UNAUTHENTICATED : State.ERROR;
            return new Snapshot(state, repository, List.of(), remediation.format());
        }
        if (result.stdout.endsWith("\n[shed: output truncated]")) {
            return new Snapshot(State.ERROR, repository, List.of(), GitHubReviewFailureModel.malformed("gh pr list output was truncated").format());
        }
        List<String> fields = nulSeparated(result.stdout);
        if (fields.size() % 6 != 0) return new Snapshot(State.ERROR, repository, List.of(), GitHubReviewFailureModel.malformed("gh pr list output").format());
        List<PullRequest> pullRequests = new ArrayList<>();
        for (int index = 0; index < fields.size(); index += 6) {
            pullRequests.add(new PullRequest(fields.get(index), fields.get(index + 1), fields.get(index + 2), fields.get(index + 3),
                Boolean.parseBoolean(fields.get(index + 4)), fields.get(index + 5)));
        }
        return new Snapshot(pullRequests.isEmpty() ? State.EMPTY : State.READY, repository, pullRequests,
            pullRequests.isEmpty() ? "No open pull requests in " + repository + "." : pullRequests.size() + " open pull request" + (pullRequests.size() == 1 ? "." : "s."));
    }

    private static List<String> nulSeparated(String value) {
        List<String> fields = new ArrayList<>();
        String source = value == null ? "" : value;
        if (source.isEmpty()) return fields;
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\u0000') {
                fields.add(source.substring(start, index));
                start = index + 1;
            }
        }
        if (start != source.length()) fields.add(source.substring(start));
        return fields;
    }
}
