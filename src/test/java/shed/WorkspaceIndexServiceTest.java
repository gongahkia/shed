package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceIndexServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void staysDisabledWithoutCreatingPersistentIndex() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"));

        WorkspaceIndexService.BuildResult result = service.build(false, root, WorkspaceIndexService.Observer.NO_OP);

        assertEquals(WorkspaceIndexService.State.DISABLED, result.status().state());
        assertNull(result.index());
        assertNull(result.persistedPath());
        assertFalse(Files.exists(tempDir.resolve("index-store")));
    }

    @Test
    void persistsOnlyGitIgnoreVisibleFilesAndPublishesExactProgress() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        initializeGit(root);
        Files.writeString(root.resolve(".gitignore"), "ignored/\n*.tmp\n!keep.tmp\n", StandardCharsets.UTF_8);
        write(root.resolve("visible.txt"), "visible");
        write(root.resolve("ignored/secret.txt"), "secret");
        write(root.resolve("drop.tmp"), "drop");
        write(root.resolve("keep.tmp"), "keep");
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"));
        List<WorkspaceIndexService.Status> statuses = new ArrayList<>();

        WorkspaceIndexService.BuildResult result = service.build(true, root, statuses::add);

        assertEquals(WorkspaceIndexService.State.READY, result.status().state());
        assertTrue(statuses.stream().anyMatch(status -> status.state() == WorkspaceIndexService.State.BUILDING));
        assertEquals(result.status(), service.status());
        assertEquals(result.status().visited(), result.status().indexed() + result.status().ignored());
        assertEquals(List.of(".gitignore", "keep.tmp", "visible.txt"), result.index().entries().stream()
            .map(WorkspaceIndexService.Entry::relativePath).sorted().toList());
        assertTrue(Files.isRegularFile(result.persistedPath()));
        assertEquals(result.index(), service.load(root));
    }

    @Test
    void combinesAdditionalSearchExclusionsWithGitIgnoreResults() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace-search-exclusions"));
        initializeGit(root);
        Files.writeString(root.resolve(".gitignore"), "ignored.txt\n", StandardCharsets.UTF_8);
        write(root.resolve("visible.txt"), "visible");
        write(root.resolve("ignored.txt"), "ignored");
        write(root.resolve("generated.txt"), "generated");
        WorkspaceIndexService service = WorkspaceIndexService.withAdditionalIgnore(tempDir.resolve("index-store"),
            (workspaceRoot, relativePath) -> relativePath.toString().equals("generated.txt"));

        WorkspaceIndexService.BuildResult result = service.scan(root, WorkspaceIndexService.Cancellation.NONE,
            WorkspaceIndexService.Observer.NO_OP);

        assertEquals(List.of(".gitignore", "visible.txt"), result.index().entries().stream().map(WorkspaceIndexService.Entry::relativePath).sorted().toList());
        assertEquals(2, result.status().ignored());
    }

    @Test
    void neverIndexesPathsOutsideTheNormalizedWorkspaceRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        write(root.resolve("inside.txt"), "inside");
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);

        WorkspaceIndexService.BuildResult result = service.build(true, root, WorkspaceIndexService.Observer.NO_OP);

        assertEquals(List.of("inside.txt"), result.index().entries().stream().map(WorkspaceIndexService.Entry::relativePath).toList());
        assertEquals(0, result.status().outsideBoundary());
        assertFalse(result.index().entries().stream().anyMatch(entry -> entry.relativePath().contains("outside")));
    }

    @Test
    void cancellationStopsIndexingWithoutWritingPartialIndex() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        write(root.resolve("one.txt"), "one");
        write(root.resolve("two.txt"), "two");
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> {
            cancellation.cancel();
            return false;
        });

        WorkspaceIndexService.BuildResult result = service.build(true, root, cancellation, WorkspaceIndexService.Observer.NO_OP);

        assertEquals(WorkspaceIndexService.State.CANCELLED, result.status().state());
        assertNull(result.index());
        assertNull(result.persistedPath());
        assertFalse(Files.exists(tempDir.resolve("index-store")));
    }

    @Test
    void rebuildsStaleAndIncompleteIndexesWithoutReturningOldEntries() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Path file = root.resolve("document.txt");
        write(file, "old");
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);
        WorkspaceIndexService.BuildResult initial = service.build(true, root, WorkspaceIndexService.Observer.NO_OP);
        Files.writeString(file, "new content", StandardCharsets.UTF_8);
        WorkspaceIndexService restarted = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);

        WorkspaceIndexService.BuildResult stale = restarted.recover(true, root, WorkspaceIndexService.Cancellation.NONE,
            WorkspaceIndexService.Observer.NO_OP);

        assertEquals("rebuilt stale index", stale.status().message());
        assertEquals(Files.size(file), stale.index().entries().getFirst().size());

        Files.writeString(initial.persistedPath(), "{broken", StandardCharsets.UTF_8);
        WorkspaceIndexService.BuildResult incomplete = restarted.recover(true, root, WorkspaceIndexService.Cancellation.NONE,
            WorkspaceIndexService.Observer.NO_OP);

        assertEquals("rebuilt incomplete index", incomplete.status().message());
        assertEquals(incomplete.index(), restarted.load(root));
    }

    @Test
    void recentIndexIsReusedForRepeatedProjectQueries() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("reused-workspace"));
        write(root.resolve("one.txt"), "first");
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("reused-index"), (workspace, path) -> false);
        WorkspaceIndexService.BuildResult built = service.build(true, root, WorkspaceIndexService.Observer.NO_OP);

        WorkspaceIndexService.BuildResult reused = service.recover(true, root, WorkspaceIndexService.Cancellation.NONE,
            WorkspaceIndexService.Observer.NO_OP);

        assertEquals("using in-memory index", reused.status().message());
        assertTrue(built.index() == reused.index());
    }

    @Test
    void fileChangesUpdateTheInMemoryIndexWithoutRescanningTheWorkspace() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("incremental-workspace"));
        Path file = root.resolve("one.txt");
        write(file, "first");
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("incremental-index"), (workspace, path) -> false);
        try {
            service.build(true, root, WorkspaceIndexService.Observer.NO_OP);
            Files.writeString(file, "second version");
            WorkspaceIndexService.BuildResult updated = null;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            do {
                updated = service.recover(true, root, WorkspaceIndexService.Cancellation.NONE, WorkspaceIndexService.Observer.NO_OP);
                if (updated.index().entries().getFirst().size() == Files.size(file)) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertEquals("incrementally updated index", updated.status().message());
            assertEquals(Files.size(file), updated.index().entries().getFirst().size());

            Files.writeString(root.resolve("two.txt"), "new file");
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            do {
                updated = service.recover(true, root, WorkspaceIndexService.Cancellation.NONE, WorkspaceIndexService.Observer.NO_OP);
                if (updated.index().entries().size() == 2) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertEquals(2, updated.index().entries().size());
        } finally {
            service.close();
        }
    }

    @Test
    void nonGitWorkspaceDoesNotFailWhenGitExitsBeforeReceivingLargeInput() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("non-git-workspace"));
        for (int index = 0; index < 3_000; index++) {
            write(root.resolve("document-with-long-name-" + index + ".txt"), "content");
        }
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("non-git-index"));
        try {
            WorkspaceIndexService.BuildResult result = service.build(true, root, WorkspaceIndexService.Observer.NO_OP);
            assertEquals(WorkspaceIndexService.State.READY, result.status().state());
            assertEquals(3_000, result.index().entries().size());
        } finally {
            service.close();
        }
    }

    @Test
    void indexStorageInsideWorkspaceIsExcludedFromItsOwnIndex() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace-with-cache"));
        write(root.resolve("source.txt"), "source");
        WorkspaceIndexService service = new WorkspaceIndexService(root.resolve(".shed/index"), (workspace, path) -> false);
        try {
            WorkspaceIndexService.BuildResult built = service.build(true, root, WorkspaceIndexService.Observer.NO_OP);
            assertEquals(List.of("source.txt"), built.index().entries().stream().map(WorkspaceIndexService.Entry::relativePath).toList());
            WorkspaceIndexService.BuildResult reused = service.recover(true, root, WorkspaceIndexService.Cancellation.NONE,
                WorkspaceIndexService.Observer.NO_OP);
            assertEquals("using in-memory index", reused.status().message());
        } finally {
            service.close();
        }
    }

    private void initializeGit(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "init", "--quiet", root.toString()).redirectErrorStream(true).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git init failed: " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
