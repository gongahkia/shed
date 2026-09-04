package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ExceptionBreakpointStoreTest {
    @Test
    void persistsWorkspaceScopedExplicitFilterOverrides(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        ExceptionBreakpointStore store = new ExceptionBreakpointStore(tempDir.resolve("state"));

        store.configure(workspace, "uncaught", false);
        store.configure(workspace, "raised", true);

        ExceptionBreakpointStore reloaded = new ExceptionBreakpointStore(tempDir.resolve("state"));
        assertEquals(Map.of("uncaught", new ExceptionBreakpointStore.Setting("uncaught", false),
            "raised", new ExceptionBreakpointStore.Setting("raised", true)), reloaded.settings(workspace));
    }

    @Test
    void rejectsUnsafeFilterIdentifiers(@TempDir Path tempDir) {
        ExceptionBreakpointStore store = new ExceptionBreakpointStore(tempDir.resolve("state"));
        assertThrows(IllegalArgumentException.class, () -> store.configure(tempDir, "raised\nall", true));
    }
}
