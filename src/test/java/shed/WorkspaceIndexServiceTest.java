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
