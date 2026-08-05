package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

public class GitRepositoryModelTest {
    @Test
    void parsesStableNulDelimitedWorktreeAndStashOutput() {
        String worktrees = "worktree /repo\0HEAD abc\0branch refs/heads/main\0\0worktree /repo/feature\0HEAD def\0detached\0locked usb\0\0";
        String stashes = "stash@{0}\u001fabc\u001fWIP on main\u001f2026-08-05T12:00:00Z\0";

        GitRepositoryModel.Snapshot snapshot = GitRepositoryModel.fromCommands(new File("/repo"), new CommandResult(0, worktrees, ""),
            new CommandResult(0, stashes, ""));

        assertTrue(snapshot.available());
        assertEquals(2, snapshot.worktrees().size());
        assertTrue(snapshot.worktrees().getFirst().main());
        assertEquals("refs/heads/main", snapshot.worktrees().getFirst().branch());
        assertEquals("usb", snapshot.worktrees().get(1).locked());
        assertEquals(1, snapshot.stashes().size());
        assertEquals("stash@{0}", snapshot.stashes().getFirst().reference());
    }
}
