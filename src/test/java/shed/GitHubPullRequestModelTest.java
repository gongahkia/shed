package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GitHubPullRequestModelTest {
    @Test
    void parsesScopedReadOnlyPullRequestFields() {
        String output = "42\0Improve discovery\0octo\0" + "2026-07-30T00:00:00Z\0false\0https://github.com/owner/repo/pull/42\0";
        GitHubPullRequestModel.Snapshot snapshot = GitHubPullRequestModel.fromResult("owner/repo", new CommandResult(0, output, ""));

        assertEquals(GitHubPullRequestModel.State.READY, snapshot.state());
        assertEquals("owner/repo", snapshot.repository());
        assertEquals("#42 Improve discovery", snapshot.pullRequests().getFirst().toString());
    }

    @Test
    void reportsEmptyAuthenticationAndMalformedStates() {
        GitHubPullRequestModel.Snapshot empty = GitHubPullRequestModel.fromResult("owner/repo", new CommandResult(0, "", ""));
        GitHubPullRequestModel.Snapshot auth = GitHubPullRequestModel.fromResult("owner/repo", new CommandResult(1, "", "run gh auth login"));
        GitHubPullRequestModel.Snapshot malformed = GitHubPullRequestModel.fromResult("owner/repo", new CommandResult(0, "42\0partial", ""));

        assertEquals(GitHubPullRequestModel.State.EMPTY, empty.state());
        assertEquals(GitHubPullRequestModel.State.UNAUTHENTICATED, auth.state());
        assertTrue(auth.detail().contains("auth login"));
        assertTrue(auth.detail().contains("Retry safe: yes"));
        assertEquals(GitHubPullRequestModel.State.ERROR, malformed.state());
        assertTrue(malformed.detail().contains("Non-GitHub fallback"));
    }
}
