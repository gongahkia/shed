package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSymbolServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void searchesMultipleRootsWithLocalLexicalSymbolsOnly() throws Exception {
        Path first = Files.createDirectories(temporaryDirectory.resolve("first"));
        Path second = Files.createDirectories(temporaryDirectory.resolve("second"));
        Files.writeString(first.resolve("App.java"), "class App {\n  void run() {}\n}\n");
        Files.writeString(second.resolve("worker.kt"), "class Worker {\n  fun runTask() {}\n}\n");
        Files.writeString(second.resolve("notes.txt"), "runTask should not become a symbol\n");

        WorkspaceSymbolService service = new WorkspaceSymbolService(
            new WorkspaceIndexService(temporaryDirectory.resolve("index"), (root, path) -> false), new SymbolService());
        WorkspaceSymbolService.SearchResult result = service.search(false, List.of(first, second), "run",
            WorkspaceIndexService.Cancellation.NONE);

        assertEquals(WorkspaceSymbolService.State.COMPLETE, result.state());
        assertFalse(result.truncated());
        assertEquals(List.of("run", "runTask"), result.matches().stream().map(WorkspaceSymbolService.Match::name).toList());
        assertEquals(List.of("App.java", "worker.kt"), result.matches().stream().map(WorkspaceSymbolService.Match::relativePath).toList());
        assertEquals(3, result.indexedFiles());
        assertEquals(2, result.filesRead());
    }

    @Test
    void boundsInputFilesAndHonorsCancellation() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(root.resolve("Large.java"), "class Large {}\n");
        WorkspaceSymbolService bounded = new WorkspaceSymbolService(
            new WorkspaceIndexService(temporaryDirectory.resolve("index"), (workspaceRoot, path) -> false), new SymbolService(), 20, 8);

        WorkspaceSymbolService.SearchResult skipped = bounded.search(false, List.of(root), "large", WorkspaceIndexService.Cancellation.NONE);
        assertEquals(WorkspaceSymbolService.State.COMPLETE, skipped.state());
        assertTrue(skipped.matches().isEmpty());
        assertEquals(1, skipped.skippedFiles());

        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        cancellation.cancel();
        WorkspaceSymbolService.SearchResult cancelled = bounded.search(false, List.of(root), "large", cancellation);
        assertEquals(WorkspaceSymbolService.State.CANCELLED, cancelled.state());
    }
}
