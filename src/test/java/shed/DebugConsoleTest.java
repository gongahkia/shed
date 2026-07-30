package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class DebugConsoleTest {
    @Test
    void preservesOutputEventOrderAndConnectionState() {
        DebugConsole console = new DebugConsole();
        console.start();
        console.append("stdout", "one\n");
        console.append("stderr", "two\n");
        console.append("console", "three\n");
        console.disconnected("Debug adapter terminated.");

        DebugConsole.Snapshot snapshot = console.snapshot();
        assertEquals(DebugConsole.State.DISCONNECTED, snapshot.state());
        assertEquals("[stdout] one\n[stderr] two\n[console] three\n", snapshot.output());
        assertEquals(3, snapshot.events());
    }

    @Test
    void retainsOnlyTheBoundedOutputTail() {
        DebugConsole console = new DebugConsole();
        console.start();
        console.append("stdout", "x".repeat(DebugConsole.MAX_CHARACTERS));

        DebugConsole.Snapshot snapshot = console.snapshot();
        assertTrue(snapshot.truncated());
        assertEquals(DebugConsole.MAX_CHARACTERS, snapshot.output().length());
    }
}
