package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

public class WorkbenchPlacementStateTest {
    @Test
    void serializesDeterministicDetachedAndDockedPlacements() {
        WorkbenchPlacementState state = new WorkbenchPlacementState();
        state.put(detached("workspace", WorkbenchLayout.SurfaceType.GIT, "changes", "window-a", 40, 50, 720, 460));
        state.put(docked("workspace", WorkbenchLayout.SurfaceType.TREE, "project", "window-a", WorkbenchLayout.Region.LEADING));

        WorkbenchPlacementState restored = WorkbenchPlacementState.fromList(state.toList());

        assertEquals(2, restored.entriesForWorkspace("workspace").size());
        assertEquals(WorkbenchPlacementState.Mode.DETACHED,
            restored.get("workspace", WorkbenchLayout.SurfaceType.GIT, "changes").mode());
        assertEquals(new WorkbenchPlacementState.Bounds(40, 50, 720, 460),
            restored.get("workspace", WorkbenchLayout.SurfaceType.GIT, "changes").bounds());
    }

    @Test
    void rejectsInvalidDetachedStateAndIgnoresMalformedPersistedValues() {
        assertThrows(IllegalArgumentException.class, () -> new WorkbenchPlacementState.Entry("workspace",
            WorkbenchLayout.SurfaceType.GIT, "changes", WorkbenchPlacementState.Mode.DETACHED, "window-a",
            WorkbenchLayout.Region.DETACHED, null));
        WorkbenchPlacementState restored = WorkbenchPlacementState.fromList(List.of(java.util.Map.of("workspace", "workspace")));
        assertNull(restored.get("workspace", WorkbenchLayout.SurfaceType.GIT, "changes"));
    }

    @Test
    void replacesAndRemovesSurfacePlacementByWorkspaceBoundIdentity() {
        WorkbenchPlacementState state = new WorkbenchPlacementState();
        state.put(docked("workspace", WorkbenchLayout.SurfaceType.DEBUGGER, "main", "window-a", WorkbenchLayout.Region.BOTTOM));
        state.put(detached("workspace", WorkbenchLayout.SurfaceType.DEBUGGER, "main", "window-a", 1, 2, 640, 480));

        assertEquals(WorkbenchPlacementState.Mode.DETACHED,
            state.get("workspace", WorkbenchLayout.SurfaceType.DEBUGGER, "main").mode());
        assertEquals(WorkbenchPlacementState.Mode.DETACHED,
            state.remove("workspace", WorkbenchLayout.SurfaceType.DEBUGGER, "main").mode());
        assertNull(state.get("workspace", WorkbenchLayout.SurfaceType.DEBUGGER, "main"));
    }

    private WorkbenchPlacementState.Entry docked(String workspace, WorkbenchLayout.SurfaceType surface, String instance,
                                                  String owner, WorkbenchLayout.Region region) {
        return new WorkbenchPlacementState.Entry(workspace, surface, instance, WorkbenchPlacementState.Mode.DOCKED,
            owner, region, null);
    }

    private WorkbenchPlacementState.Entry detached(String workspace, WorkbenchLayout.SurfaceType surface, String instance,
                                                    String owner, int x, int y, int width, int height) {
        return new WorkbenchPlacementState.Entry(workspace, surface, instance, WorkbenchPlacementState.Mode.DETACHED,
            owner, WorkbenchLayout.Region.DETACHED, new WorkbenchPlacementState.Bounds(x, y, width, height));
    }
}
