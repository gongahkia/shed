package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceTextSearchServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void searchesAdHocGitIgnoreVisibleFilesIncrementallyWithExactLocations() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(root.resolve("visible.txt"), "alpha\nprefix needle needle\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("ignored.txt"), "needle\n", StandardCharsets.UTF_8);
        WorkspaceIndexService index = new WorkspaceIndexService(tempDir.resolve("index-store"),
            (workspaceRoot, relativePath) -> relativePath.toString().equals("ignored.txt"));
        List<WorkspaceTextSearchService.Match> batches = new ArrayList<>();

        WorkspaceTextSearchService.SearchResult result = new WorkspaceTextSearchService(index, 20, 1)
            .search(false, root, "needle", WorkspaceIndexService.Cancellation.NONE, batches::addAll);

        assertEquals(WorkspaceTextSearchService.Source.AD_HOC, result.source());
        assertEquals(WorkspaceTextSearchService.State.COMPLETE, result.state());
        assertEquals(2, result.matches().size());
        assertEquals(root.resolve("visible.txt").toString(), result.matches().get(0).filePath());
        assertEquals(2, result.matches().get(0).line());
        assertEquals(8, result.matches().get(0).column());
        assertEquals(15, result.matches().get(1).column());
        assertEquals(result.matches(), batches);
        assertFalse(Files.exists(tempDir.resolve("index-store")));
    }

    @Test
    void searchesPersistentIndexAndReportsNoMatches() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(root.resolve("visible.txt"), "alpha\n", StandardCharsets.UTF_8);
        WorkspaceIndexService index = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);

        WorkspaceTextSearchService.SearchResult result = new WorkspaceTextSearchService(index)
            .search(true, root, "needle", WorkspaceIndexService.Cancellation.NONE, WorkspaceTextSearchService.Observer.NO_OP);

        assertEquals(WorkspaceTextSearchService.Source.PERSISTENT_INDEX, result.source());
        assertEquals(WorkspaceTextSearchService.State.COMPLETE, result.state());
        assertTrue(result.matches().isEmpty());
        assertEquals("no matches", result.message());
        assertTrue(Files.isRegularFile(index.indexPath(root)));
    }

    @Test
    void stopsAfterIncrementalObserverCancelsTheSearch() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(root.resolve("one.txt"), "needle\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("two.txt"), "needle\n", StandardCharsets.UTF_8);
        WorkspaceIndexService index = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();

        WorkspaceTextSearchService.SearchResult result = new WorkspaceTextSearchService(index, 20, 1)
            .search(false, root, "needle", cancellation, matches -> cancellation.cancel());

        assertEquals(WorkspaceTextSearchService.State.CANCELLED, result.state());
        assertEquals(1, result.matches().size());
        assertFalse(Files.exists(tempDir.resolve("index-store")));
    }

    @Test
    void searchesMultipleWorkspaceFoldersWithinOneResultBudget() throws Exception {
        Path first = Files.createDirectory(tempDir.resolve("first"));
        Path second = Files.createDirectory(tempDir.resolve("second"));
        Files.writeString(first.resolve("one.txt"), "needle\n", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("two.txt"), "needle\n", StandardCharsets.UTF_8);
        WorkspaceIndexService index = new WorkspaceIndexService(tempDir.resolve("index-store"), (workspaceRoot, relativePath) -> false);

        WorkspaceTextSearchService.SearchResult result = new WorkspaceTextSearchService(index, 2, 1)
            .search(false, List.of(first, second), "needle", WorkspaceIndexService.Cancellation.NONE, WorkspaceTextSearchService.Observer.NO_OP);

        assertEquals(WorkspaceTextSearchService.State.COMPLETE, result.state());
        assertEquals(2, result.matches().size());
        assertFalse(result.truncated());
        assertTrue(result.matches().stream().anyMatch(match -> match.filePath().equals(first.resolve("one.txt").toString())));
        assertTrue(result.matches().stream().anyMatch(match -> match.filePath().equals(second.resolve("two.txt").toString())));
    }
}
