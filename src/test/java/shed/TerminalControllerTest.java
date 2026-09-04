package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TerminalControllerTest {
    @Test
    void parsesExplicitTerminalSplitPlacements() {
        assertEquals(WindowLayoutNode.Orientation.HORIZONTAL, TerminalController.splitOrientation("side"));
        assertEquals(WindowLayoutNode.Orientation.VERTICAL, TerminalController.splitOrientation(" bottom "));
        assertNull(TerminalController.splitOrientation("center"));
        assertNull(TerminalController.splitOrientation(null));
    }
}
