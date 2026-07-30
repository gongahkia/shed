package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitHubReviewDraftStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsEditsByRepositoryAndPullRequestThenDiscardsOnlyTheTarget() throws Exception {
        Path file = temporaryDirectory.resolve(GitHubReviewDraftStore.FILE_NAME);
        GitHubReviewDraftStore store = new GitHubReviewDraftStore(file);
        GitHubReviewDraftStore.Target first = new GitHubReviewDraftStore.Target("owner/repo", "42");
        GitHubReviewDraftStore.Target second = new GitHubReviewDraftStore.Target("other/repo", "42");

        assertNull(store.load(first));
        store.save(first, "initial local comment");
        store.save(first, "edited local comment");
        store.save(second, "other pull request");

        assertEquals("edited local comment", store.load(first).body());
        assertEquals("owner/repo", store.load(first).target().repository());
        assertEquals("other/repo", store.load(second).target().repository());
        assertTrue(store.discard(first));
        assertNull(store.load(first));
        assertEquals("other pull request", store.load(second).body());
        assertFalse(store.discard(first));
        assertTrue(store.discard(second));
        assertFalse(Files.exists(file));
    }

    @Test
    void rejectsMalformedLocalDraftDataWithoutDiscardingIt() throws Exception {
        Path file = temporaryDirectory.resolve(GitHubReviewDraftStore.FILE_NAME);
        Files.writeString(file, "{\"version\":2,\"drafts\":[]}");
        GitHubReviewDraftStore store = new GitHubReviewDraftStore(file);

        IOException error = assertThrows(IOException.class, () -> store.load(new GitHubReviewDraftStore.Target("owner/repo", "42")));

        assertTrue(error.getMessage().contains("unsupported"));
        assertTrue(Files.exists(file));
    }

    @Test
    void recordsAcknowledgementsAndRemovesTheAcknowledgedDraft() throws Exception {
        GitHubReviewDraftStore store = new GitHubReviewDraftStore(temporaryDirectory.resolve(GitHubReviewDraftStore.FILE_NAME));
        GitHubReviewDraftStore.Target target = new GitHubReviewDraftStore.Target("owner/repo", "42");

        store.save(target, "review body");
        store.acknowledge(target, "COMMENT", "review body");

        assertNull(store.load(target));
        assertTrue(store.acknowledged(target, "COMMENT", "review body"));
        GitHubReviewDraftStore reloaded = new GitHubReviewDraftStore(temporaryDirectory.resolve(GitHubReviewDraftStore.FILE_NAME));
        assertTrue(reloaded.acknowledged(target, "COMMENT", "review body"));
        assertFalse(store.acknowledged(target, "APPROVE", "review body"));
    }
}
