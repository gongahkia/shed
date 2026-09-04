package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class DebugStoppedFrameSelectionTest {
    @Test
    void selectsTheAdapterSelectedPausedFrame() {
        DebugInspection.Frame first = new DebugInspection.Frame(7, "outer", "/tmp/outer.py", 2, 1);
        DebugInspection.Frame selected = new DebugInspection.Frame(11, "inner", "/tmp/inner.py", 5, 3);
        DebugInspection.Snapshot snapshot = new DebugInspection.Snapshot(DebugInspection.State.READY, "Paused", true, 4, 11,
            List.of(), List.of(first, selected), List.of(), List.of());

        assertEquals(selected, DebugSessionController.selectedStoppedFrame(snapshot));
    }

    @Test
    void ignoresFramesWhenExecutionIsNotPaused() {
        DebugInspection.Snapshot snapshot = new DebugInspection.Snapshot(DebugInspection.State.IDLE, "Running", false, 0, 0,
            List.of(), List.of(new DebugInspection.Frame(7, "outer", "/tmp/outer.py", 2, 1)), List.of(), List.of());

        assertNull(DebugSessionController.selectedStoppedFrame(snapshot));
    }
}
