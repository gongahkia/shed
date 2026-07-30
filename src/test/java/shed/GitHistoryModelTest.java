package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

public class GitHistoryModelTest {
    @Test
    void parsesNulDelimitedHistoryWithoutSplittingSubjectsOrDecorations() {
        String history = "0123456789abcdef\0HEAD -> main, origin/main\0Subject with spaces\0Ada Lovelace\0" + "2026-07-30T00:00:00Z\0"
            + "fedcba9876543210\0\0Second subject\0Grace Hopper\0" + "2026-07-29T00:00:00Z\0";

        GitHistoryModel.Snapshot snapshot = GitHistoryModel.fromCommands(new File("/repo"), new CommandResult(0, history, ""),
            new CommandResult(0, "origin\nbackup\n", ""));

        assertTrue(snapshot.available());
        assertEquals(List.of("origin", "backup"), snapshot.remotes());
        assertEquals(2, snapshot.commits().size());
        assertEquals("0123456789ab Subject with spaces", snapshot.commits().getFirst().display());
        assertEquals("HEAD -> main, origin/main", snapshot.commits().getFirst().decorations());
        assertEquals("Grace Hopper", snapshot.commits().get(1).author());
    }

    @Test
    void rejectsTruncatedHistoryInsteadOfPresentingPartialCommits() {
        GitHistoryModel.Snapshot snapshot = GitHistoryModel.fromCommands(new File("/repo"),
            new CommandResult(0, "0123456789abcdef\0\n[shed: output truncated]", ""), new CommandResult(0, "origin\n", ""));

        assertFalse(snapshot.available());
        assertEquals(GitHistoryModel.State.ERROR, snapshot.state());
        assertTrue(snapshot.detail().contains("truncated"));
    }

    @Test
    void remoteActionsUseFixedCommandsAndConfirmOnlyMutatingOperations() {
        assertEquals(List.of("git", "fetch", "--prune"), GitHistoryModel.RemoteAction.FETCH.command());
        assertEquals(List.of("git", "pull", "--ff-only"), GitHistoryModel.RemoteAction.PULL.command());
        assertEquals(List.of("git", "push"), GitHistoryModel.RemoteAction.PUSH.command());
        assertFalse(GitHistoryModel.RemoteAction.FETCH.requiresConfirmation());
        assertTrue(GitHistoryModel.RemoteAction.PULL.requiresConfirmation());
        assertTrue(GitHistoryModel.RemoteAction.PUSH.requiresConfirmation());
    }
}
