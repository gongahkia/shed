package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class DebugInspectionTest {
    @Test
    void discardsStalePausedFrameResultsAfterExecutionContinues() {
        DebugInspection inspection = new DebugInspection();
        inspection.stopped(7, "breakpoint", "Paused in main");
        assertTrue(inspection.addWatch("count").succeeded());
        DebugInspection.Load load = inspection.beginLoad();
        inspection.invalidated("Debug execution continued.");

        assertFalse(inspection.complete(load.generation(), List.of(new DebugInspection.ThreadInfo(7, "main")),
            List.of(new DebugInspection.Frame(44, "main", "Main.java", 8, 1)), 44, List.of(), List.of(), "stale", DebugInspection.State.READY));
        assertFalse(inspection.snapshot().paused());
        assertEquals(DebugInspection.WatchState.PENDING, inspection.snapshot().watches().getFirst().state());
    }

    @Test
    void acceptsOnlyReturnedFrameSelectionAndResetsDependentViews() {
        DebugInspection inspection = new DebugInspection();
        inspection.stopped(7, "breakpoint", "");
        DebugInspection.Load load = inspection.beginLoad();
        assertTrue(inspection.complete(load.generation(), List.of(), List.of(
            new DebugInspection.Frame(44, "main", "Main.java", 8, 1), new DebugInspection.Frame(45, "caller", "Main.java", 2, 1)),
            44, List.of(new DebugInspection.Scope("Locals", 2, false, List.of())), List.of(), "ready", DebugInspection.State.READY));

        assertFalse(inspection.selectFrame(99).succeeded());
        assertTrue(inspection.selectFrame(45).succeeded());
        assertEquals(45, inspection.snapshot().frameId());
        assertTrue(inspection.snapshot().scopes().isEmpty());
    }

    @Test
    void discardsNestedVariablesWhenThePausedStateChanges() {
        DebugInspection inspection = new DebugInspection();
        inspection.stopped(7, "breakpoint", "");
        DebugInspection.Load load = inspection.beginLoad();
        assertTrue(inspection.complete(load.generation(), List.of(), List.of(new DebugInspection.Frame(44, "main", "Main.java", 8, 1)), 44,
            List.of(new DebugInspection.Scope("Locals", 55, false, List.of(new DebugInspection.Variable("object", "{}", "Map", 56)))), List.of(), "ready",
            DebugInspection.State.READY));
        DebugInspection.VariableLoad variableLoad = inspection.beginVariableLoad(56);
        assertTrue(variableLoad != null);
        inspection.invalidated("Debug execution continued.");

        assertFalse(inspection.completeVariableLoad(variableLoad.generation(), variableLoad.variablesReference(),
            List.of(new DebugInspection.Variable("field", "1", "int", 0))));
        assertTrue(inspection.snapshot().expandedVariables().isEmpty());
    }
}
