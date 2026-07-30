package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GitHubReviewFailureModelTest {
    @Test
    void classifiesEverySupportedFailureWithRetryAndLocalFallback() {
        assertFailure("run gh auth login", GitHubReviewFailureModel.Kind.AUTHENTICATION, true);
        assertFailure("API rate limit exceeded", GitHubReviewFailureModel.Kind.RATE_LIMIT, true);
        assertFailure("Resource not accessible by integration", GitHubReviewFailureModel.Kind.PERMISSION, true);
        assertFailure("dial tcp: connection timed out", GitHubReviewFailureModel.Kind.NETWORK, true);
        assertFailure("unknown flag: --json", GitHubReviewFailureModel.Kind.CLI_VERSION, false);
        GitHubReviewFailureModel.Remediation malformed = GitHubReviewFailureModel.malformed("gh pr list");
        assertEquals(GitHubReviewFailureModel.Kind.MALFORMED_OUTPUT, malformed.kind());
        assertTrue(malformed.retrySafe());
        assertTrue(malformed.format().contains("Non-GitHub fallback"));
    }

    @Test
    void blocksAutomaticRetryForUncertainWriteFailures() {
        GitHubReviewFailureModel.Remediation uncertain = GitHubReviewFailureModel.fromResult("gh pr review",
            new CommandResult(-1, "", "Process timed out"), true);

        assertEquals(GitHubReviewFailureModel.Kind.NETWORK, uncertain.kind());
        assertFalse(uncertain.retrySafe());
        assertTrue(uncertain.format().contains("do not retry automatically"));
    }

    private static void assertFailure(String output, GitHubReviewFailureModel.Kind kind, boolean retrySafe) {
        GitHubReviewFailureModel.Remediation remediation = GitHubReviewFailureModel.fromResult("gh pr list", new CommandResult(1, output, ""), false);
        assertEquals(kind, remediation.kind());
        assertEquals(retrySafe, remediation.retrySafe());
        assertTrue(remediation.format().contains("Retry safe:"));
        assertTrue(remediation.format().contains("Non-GitHub fallback"));
    }
}
