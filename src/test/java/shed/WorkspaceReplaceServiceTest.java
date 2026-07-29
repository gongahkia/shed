package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceReplaceServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void previewsWithoutWritingAndAppliesSelectedFilesAndMatches() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Path first = root.resolve("first.txt");
        Path second = root.resolve("second.txt");
        Files.writeString(first, "old old\n", StandardCharsets.UTF_8);
        Files.writeString(second, "old\n", StandardCharsets.UTF_8);
        WorkspaceReplaceService service = new WorkspaceReplaceService(new WorkspaceIndexService(tempDir.resolve("index-store"),
            (workspaceRoot, relativePath) -> false));

        WorkspaceReplaceService.Preview preview = service.preview(false, root, "old", "new", WorkspaceIndexService.Cancellation.NONE);

        assertEquals(WorkspaceReplaceService.State.READY, preview.state());
        assertEquals("old old\n", Files.readString(first));
        assertEquals("old\n", Files.readString(second));
        WorkspaceReplaceService.Plan plan = preview.plan();
        WorkspaceReplaceService.FilePlan firstPlan = plan.files().stream().filter(file -> file.path().equals(first)).findFirst().orElseThrow();
        WorkspaceReplaceService.FilePlan secondPlan = plan.files().stream().filter(file -> file.path().equals(second)).findFirst().orElseThrow();
        assertTrue(plan.selectMatch(firstPlan.matches().get(1).matchId(), WorkspaceReplaceService.Selection.OFF));
        assertTrue(plan.selectFile(secondPlan.fileId(), WorkspaceReplaceService.Selection.OFF));

        WorkspaceReplaceService.ApplyResult applied = service.apply(plan, WorkspaceIndexService.Cancellation.NONE);

        assertEquals(WorkspaceReplaceService.State.COMPLETE, applied.state());
        assertEquals("new old\n", Files.readString(first));
        assertEquals("old\n", Files.readString(second));
        assertEquals(WorkspaceReplaceService.FileState.CHANGED, applied.files().stream()
            .filter(file -> file.path().equals(first)).findFirst().orElseThrow().state());
        assertEquals(WorkspaceReplaceService.FileState.SKIPPED, applied.files().stream()
            .filter(file -> file.path().equals(second)).findFirst().orElseThrow().state());
        assertFalse(Files.exists(tempDir.resolve("index-store")));
    }

    @Test
    void skipsFilesChangedAfterPreviewWithoutOverwritingThem() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "old\n", StandardCharsets.UTF_8);
        WorkspaceReplaceService service = new WorkspaceReplaceService(new WorkspaceIndexService(tempDir.resolve("index-store"),
            (workspaceRoot, relativePath) -> false));
        WorkspaceReplaceService.Preview preview = service.preview(false, root, "old", "new", WorkspaceIndexService.Cancellation.NONE);
        Files.writeString(file, "external\n", StandardCharsets.UTF_8);

        WorkspaceReplaceService.ApplyResult applied = service.apply(preview.plan(), WorkspaceIndexService.Cancellation.NONE);

        assertEquals(WorkspaceReplaceService.FileState.SKIPPED, applied.files().getFirst().state());
        assertEquals("external\n", Files.readString(file));
    }

    @Test
    void cancellationBeforeApplyLeavesPreviewedFilesUnchanged() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "old\n", StandardCharsets.UTF_8);
        WorkspaceReplaceService service = new WorkspaceReplaceService(new WorkspaceIndexService(tempDir.resolve("index-store"),
            (workspaceRoot, relativePath) -> false));
        WorkspaceReplaceService.Preview preview = service.preview(false, root, "old", "new", WorkspaceIndexService.Cancellation.NONE);
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        cancellation.cancel();

        WorkspaceReplaceService.ApplyResult applied = service.apply(preview.plan(), cancellation);

        assertEquals(WorkspaceReplaceService.State.CANCELLED, applied.state());
        assertTrue(applied.files().isEmpty());
        assertEquals("old\n", Files.readString(file));
    }
}
