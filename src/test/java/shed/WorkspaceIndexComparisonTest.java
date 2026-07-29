package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceIndexComparisonTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsAdHocDefaultAndDoesNotCreateAnIndex() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);

        WorkspaceIndexComparison.Report report = new WorkspaceIndexComparison(service).inspect(false, root);

        assertTrue(report.format().contains("Current search source: ad-hoc project scan\n"));
        assertTrue(report.format().contains("Persistent-index preference: disabled (default)\n"));
        assertTrue(report.format().contains("Index status: absent (no persisted index)\n"));
        assertTrue(report.format().contains("Build cost: not measured; run :workspace index benchmark explicitly\n"));
        assertFalse(Files.exists(tempDir.resolve("index-store")));
    }

    @Test
    void reportsExistingCacheWithoutRebuildingIt() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(root.resolve("one.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("two.txt"), "two", StandardCharsets.UTF_8);
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);
        WorkspaceIndexService.BuildResult built = service.build(true, root, WorkspaceIndexService.Observer.NO_OP);
        long cacheBytes = Files.size(built.persistedPath());

        WorkspaceIndexComparison.Report report = new WorkspaceIndexComparison(service).inspect(true, root);

        assertTrue(report.format().contains("Persistent-index preference: enabled\n"));
        assertTrue(report.format().contains("Index status: ready (persisted index is available)\n"));
        assertTrue(report.format().contains("Index entries: 2\n"));
        assertTrue(report.format().contains("Cache cost: " + cacheBytes + " bytes\n"));
    }

    @Test
    void reportsUnavailableWorkspaceWithoutTouchingStorage() {
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);

        WorkspaceIndexComparison.Report report = new WorkspaceIndexComparison(service).inspect(false, null);

        assertTrue(report.format().contains("Workspace: unavailable\n"));
        assertTrue(report.format().contains("Index status: unavailable (no workspace root)\n"));
        assertFalse(Files.exists(tempDir.resolve("index-store")));
    }

    @Test
    void reportsMalformedCacheAndItsExistingCost() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        WorkspaceIndexService service = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);
        Path cache = service.indexPath(root);
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "{", StandardCharsets.UTF_8);

        WorkspaceIndexComparison.Report report = new WorkspaceIndexComparison(service).inspect(true, root);

        assertTrue(report.format().contains("Index status: invalid ("));
        assertTrue(report.format().contains("Cache cost: 1 bytes\n"));
        assertTrue(report.format().contains("Cache path: " + cache + "\n"));
    }
}
