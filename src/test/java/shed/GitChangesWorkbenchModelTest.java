package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

public class GitChangesWorkbenchModelTest {
    @Test
    void parsesBranchAndAllPorcelainChangeKindsWithoutShellSplitting() {
        String porcelain = "## main...origin/main [ahead 1]\0M  staged.txt\0 M edited.txt\0?? new file.txt\0R  after.txt\0before.txt\0";

        GitChangesWorkbenchModel.Snapshot snapshot = GitChangesWorkbenchModel.parse("/repo", porcelain);

        assertTrue(snapshot.available());
        assertEquals("main...origin/main [ahead 1]", snapshot.branch());
        assertEquals(4, snapshot.changes().size());
        assertEquals("Modified", snapshot.changes().get(0).indexStatus());
        assertEquals("Modified", snapshot.changes().get(1).worktreeStatus());
        assertEquals("Untracked", snapshot.changes().get(2).indexStatus());
        assertEquals("before.txt → after.txt", snapshot.changes().get(3).displayPath());
        assertEquals("4 changed files.", snapshot.detail());
    }

    @Test
    void keepsCleanAndUnavailableStatesExplicit() {
        GitChangesWorkbenchModel.Snapshot clean = GitChangesWorkbenchModel.parse("/repo", "## trunk\0");
        GitChangesWorkbenchModel.Snapshot unavailable = GitChangesWorkbenchModel.unavailable("Not inside a Git repository.");

        assertTrue(clean.available());
        assertTrue(clean.changes().isEmpty());
        assertEquals("Working tree is clean.", clean.detail());
        assertFalse(unavailable.available());
        assertEquals(GitChangesWorkbenchModel.State.UNAVAILABLE, unavailable.state());
        assertEquals("Not inside a Git repository.", unavailable.detail());
    }

    @Test
    void reportsGitFailureWithoutTreatingItAsARepositoryState() {
        GitChangesWorkbenchModel.Snapshot error = GitChangesWorkbenchModel.fromStatus(new File("/repo"),
            new CommandResult(128, "fatal: not a git repository", ""));

        assertEquals(GitChangesWorkbenchModel.State.ERROR, error.state());
        assertEquals("fatal: not a git repository", error.detail());
    }

    @Test
    void rejectsTruncatedStatusRatherThanPresentingIncompleteChanges() {
        GitChangesWorkbenchModel.Snapshot error = GitChangesWorkbenchModel.fromStatus(new File("/repo"),
            new CommandResult(0, "## main\0 M partial.txt\n[shed: output truncated]", ""));

        assertEquals(GitChangesWorkbenchModel.State.ERROR, error.state());
        assertTrue(error.detail().contains("truncated"));
    }
}
