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

class DataBreakpointStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsWorkspaceScopedDataBreakpointsAndAppliesAdapterResults() throws Exception {
        Path storage = temporaryDirectory.resolve("state");
        Path workspace = temporaryDirectory.resolve("workspace");
        DataBreakpointStore store = new DataBreakpointStore(storage);
        DataBreakpointStore.Breakpoint breakpoint = store.add(workspace, "variable:42", "counter", DataBreakpointStore.AccessType.WRITE);

        DataBreakpointStore.Breakpoint configured = store.configure(workspace, breakpoint.dataId(), true,
            DataBreakpointStore.AccessType.READ_WRITE, "counter > 10", "3");
        DataBreakpointStore.SyncResult result = store.apply(workspace, List.of(configured), Map.of("breakpoints", List.of(Map.of("verified", false,
            "message", "not writable"))));

        assertEquals(DataBreakpointStore.State.REJECTED, result.breakpoints().getFirst().state());
        assertEquals("Data breakpoint 'counter' was rejected: not writable", result.diagnostics().getFirst());
        DataBreakpointStore reloaded = new DataBreakpointStore(storage);
        DataBreakpointStore.Breakpoint persisted = reloaded.breakpoints(workspace).getFirst();
        assertEquals(DataBreakpointStore.AccessType.READ_WRITE, persisted.accessType());
        assertEquals("counter > 10", persisted.condition());
        assertEquals(DataBreakpointStore.State.REJECTED, persisted.state());
        assertTrue(Files.exists(storage.resolve("data-breakpoints-" + hash(workspace.toAbsolutePath().normalize().toString()).substring(0, 16) + ".json")));
    }

    @Test
    void rejectsInvalidOrDuplicateDataBreakpointValues() throws Exception {
        DataBreakpointStore store = new DataBreakpointStore(temporaryDirectory.resolve("state"));
        Path workspace = temporaryDirectory.resolve("workspace");

        store.add(workspace, "data-id", "value", DataBreakpointStore.AccessType.WRITE);
        assertThrows(IllegalArgumentException.class, () -> store.add(workspace, "data-id", "value", DataBreakpointStore.AccessType.WRITE));
        assertThrows(IllegalArgumentException.class, () -> store.add(workspace, "", "value", DataBreakpointStore.AccessType.WRITE));
        assertThrows(IllegalArgumentException.class, () -> DataBreakpointStore.AccessType.parse("execute"));
        assertFalse(store.remove(workspace, "missing"));
        assertTrue(store.remove(workspace, "data-id"));
    }

    private static String hash(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }
}
