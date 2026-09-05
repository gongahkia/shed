package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstructionBreakpointStoreTest {
    @Test
    void persistsInstructionBreakpointsAndAdapterRejection(@TempDir Path temporaryDirectory) throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path storage = temporaryDirectory.resolve("state");
        InstructionBreakpointStore store = new InstructionBreakpointStore(storage);

        store.add(workspace, "module!0x100", 4);
        InstructionBreakpointStore.Breakpoint configured = store.configure(workspace, "module!0x100", 4, true, "counter > 2", "5");
        InstructionBreakpointStore.SyncResult result = store.apply(workspace, List.of(configured), Map.of("breakpoints", List.of(
            Map.of("verified", false, "message", "not executable"))));

        assertEquals(InstructionBreakpointStore.State.REJECTED, result.breakpoints().getFirst().state());
        InstructionBreakpointStore.Breakpoint restored = new InstructionBreakpointStore(storage).breakpoints(workspace).getFirst();
        assertEquals("module!0x100", restored.instructionReference());
        assertEquals(4, restored.offset());
        assertEquals("counter > 2", restored.condition());
        assertEquals("5", restored.hitCondition());
        assertEquals(InstructionBreakpointStore.State.REJECTED, restored.state());
    }

    @Test
    void rejectsDuplicateOrUnboundedInstructionLocations(@TempDir Path temporaryDirectory) throws Exception {
        InstructionBreakpointStore store = new InstructionBreakpointStore(temporaryDirectory.resolve("state"));
        Path workspace = temporaryDirectory.resolve("workspace");

        store.add(workspace, "0x100", 0);

        assertThrows(IllegalArgumentException.class, () -> store.add(workspace, "0x100", 0));
        assertThrows(IllegalArgumentException.class, () -> store.add(workspace, "", 0));
        assertThrows(IllegalArgumentException.class, () -> store.add(workspace, "0x100", 1_048_577));
    }
}
