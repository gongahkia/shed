package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FunctionBreakpointStoreTest {
    @TempDir Path tempDir;

    @Test
    void persistsConfiguredFunctionBreakpointsAndAppliesAdapterResults() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path storage = tempDir.resolve("state");
        FunctionBreakpointStore store = new FunctionBreakpointStore(storage);
        store.add(workspace, "main");
        FunctionBreakpointStore.Breakpoint requested = store.configure(workspace, "main", true, "count > 2", "5");

        FunctionBreakpointStore.SyncResult result = store.apply(workspace, List.of(requested), Map.of("breakpoints", List.of(
            Map.of("verified", false, "message", "function not loaded")
        )));

        assertEquals(FunctionBreakpointStore.State.REJECTED, result.breakpoints().getFirst().state());
        assertTrue(result.diagnostics().getFirst().contains("function not loaded"));
        FunctionBreakpointStore.Breakpoint restored = new FunctionBreakpointStore(storage).breakpoints(workspace).getFirst();
        assertEquals("count > 2", restored.condition());
        assertEquals("5", restored.hitCondition());
        assertEquals(FunctionBreakpointStore.State.REJECTED, restored.state());
        assertTrue(Files.list(storage).anyMatch(path -> path.getFileName().toString().startsWith("function-breakpoints-")));
    }

    @Test
    void validatesNamesAndOptionsAndDoesNotOverwriteLaterEdits() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        FunctionBreakpointStore store = new FunctionBreakpointStore(tempDir.resolve("state"));
        assertThrows(IllegalArgumentException.class, () -> store.add(workspace, "\n"));
        store.add(workspace, "main");
        FunctionBreakpointStore.Breakpoint stale = store.breakpoints(workspace).getFirst();
        store.configure(workspace, "main", false, "", "");

        store.apply(workspace, List.of(stale), Map.of("breakpoints", List.of(Map.of("verified", true))));

        FunctionBreakpointStore.Breakpoint current = store.breakpoints(workspace).getFirst();
        assertFalse(current.enabled());
        assertEquals(FunctionBreakpointStore.State.REQUESTED, current.state());
        assertThrows(IllegalArgumentException.class, () -> store.configure(workspace, "main", true, "bad\nvalue", ""));
    }
}
