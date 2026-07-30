package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class GitHubReviewSubmissionModelTest {
    private static final GitHubReviewDraftStore.Target TARGET = new GitHubReviewDraftStore.Target("owner/repo", "42");

    @Test
    void createsScopedCommandsForEachApprovedAction() {
        GitHubReviewSubmissionModel.Request comment = new GitHubReviewSubmissionModel.Request(TARGET,
            GitHubReviewSubmissionModel.Action.COMMENT, "review body");
        GitHubReviewSubmissionModel.Request approve = new GitHubReviewSubmissionModel.Request(TARGET,
            GitHubReviewSubmissionModel.Action.APPROVE, "");

        assertEquals(List.of("gh", "pr", "review", "42", "--repo", "owner/repo", "--comment", "--body", "review body"),
            GitHubReviewSubmissionModel.command(comment));
        assertEquals(List.of("gh", "pr", "review", "42", "--repo", "owner/repo", "--approve"), GitHubReviewSubmissionModel.command(approve));
        assertThrows(IllegalArgumentException.class, () -> new GitHubReviewSubmissionModel.Request(TARGET,
            GitHubReviewSubmissionModel.Action.REQUEST_CHANGES, ""));
    }

    @Test
    void preservesAcknowledgedFailedAndUnknownServerOutcomes() {
        GitHubReviewSubmissionModel.Result acknowledged = GitHubReviewSubmissionModel.fromResult(new CommandResult(0, "review submitted", ""));
        GitHubReviewSubmissionModel.Result failed = GitHubReviewSubmissionModel.fromResult(new CommandResult(1, "validation failed", ""));
        GitHubReviewSubmissionModel.Result unknown = GitHubReviewSubmissionModel.fromResult(new CommandResult(-1, "", "Process timed out"));

        assertTrue(acknowledged.acknowledged());
        assertEquals("review submitted", acknowledged.serverResult());
        assertFalse(failed.acknowledged());
        assertEquals(GitHubReviewSubmissionModel.State.FAILED, failed.state());
        assertEquals("validation failed", failed.serverResult());
        assertTrue(failed.detail().contains("Retry safe:"));
        assertTrue(failed.detail().contains("Non-GitHub fallback"));
        assertEquals(GitHubReviewSubmissionModel.State.UNKNOWN, unknown.state());
        assertTrue(unknown.detail().contains("do not retry automatically"));
        assertEquals("Process timed out", unknown.serverResult());
    }
}
