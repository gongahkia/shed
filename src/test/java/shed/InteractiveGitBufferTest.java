package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class InteractiveGitBufferTest {
    @Test
    void parsesLocalBranchesButRejectsRemoteRows() {
        assertEquals("feature/validation", TreeGitController.localBranchName("* feature/validation fc51a4d Add notes"));
        assertEquals("main", TreeGitController.localBranchName("  main 942047e Initial fixture"));
        assertNull(TreeGitController.localBranchName("  remotes/origin/main 942047e Initial fixture"));
    }

    @Test
    void extractsCommitHashFromAsciiGraphRows() {
        assertEquals("fc51a4d", TreeGitController.commitHash("| * fc51a4d (HEAD -> feature/validation) Add notes"));
        assertNull(TreeGitController.commitHash("repo: /tmp/fixture"));
    }
}
