package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GitHubPullRequestDetailModelTest {
    @Test
    void parsesReadOnlyMetadataFileListAndPatch() {
        String metadata = "OPEN\0octo\0" + "2026-07-30T00:00:00Z\0" + "10\0" + "5\0" + "2\0summary";
        GitHubPullRequestDetailModel.Detail detail = GitHubPullRequestDetailModel.fromResults(new CommandResult(0, metadata, ""),
            new CommandResult(0, "src/App.java\ndocs/README.md\n", ""), new CommandResult(0, "diff --git a/src/App.java\n", ""));

        assertTrue(detail.available());
        assertEquals("OPEN", detail.state());
        assertEquals("10", detail.additions());
        assertEquals(2, detail.files().size());
        assertTrue(detail.patch().startsWith("diff --git"));
    }

    @Test
    void rejectsMalformedAndTruncatedDetails() {
        GitHubPullRequestDetailModel.Detail malformed = GitHubPullRequestDetailModel.fromResults(new CommandResult(0, "OPEN\0partial", ""),
            new CommandResult(0, "", ""), new CommandResult(0, "", ""));
        GitHubPullRequestDetailModel.Detail truncated = GitHubPullRequestDetailModel.fromResults(new CommandResult(0,
            "OPEN\0octo\0updated\0" + "1\0" + "1\0" + "1\0body", ""), new CommandResult(0, "file\n", ""),
            new CommandResult(0, "partial\n[shed: output truncated]", ""));

        assertFalse(malformed.available());
        assertTrue(malformed.error().contains("malformed"));
        assertFalse(truncated.available());
        assertTrue(truncated.error().contains("truncated"));
    }
}
