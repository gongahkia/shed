package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class WorkbenchLayoutTest {
    @Test
    void assignsEachSurfaceToExactlyOneWorkspaceHost() {
        WorkbenchLayout layout = new WorkbenchLayout();
        WorkbenchLayout.SurfaceId editor = surface("workspace-a", WorkbenchLayout.SurfaceType.EDITOR, "main");
        WorkbenchLayout.HostId host = host("workspace-a", "pane-1");

        WorkbenchLayout.Placement placement = layout.place(editor, host, WorkbenchLayout.Region.CENTER);

        assertEquals(placement, layout.placement(editor));
        assertEquals(editor, layout.owner(host));
        assertEquals(1, layout.placements("workspace-a").size());
    }

    @Test
    void rejectsDuplicateHostOwnershipAndAllowsExplicitSurfaceMoves() {
        WorkbenchLayout layout = new WorkbenchLayout();
        WorkbenchLayout.SurfaceId tree = surface("workspace-a", WorkbenchLayout.SurfaceType.TREE, "project");
        WorkbenchLayout.SurfaceId terminal = surface("workspace-a", WorkbenchLayout.SurfaceType.TERMINAL, "shell-1");
        WorkbenchLayout.HostId first = host("workspace-a", "pane-1");
        WorkbenchLayout.HostId second = host("workspace-a", "pane-2");
        layout.place(tree, first, WorkbenchLayout.Region.LEADING);

        assertThrows(IllegalStateException.class, () -> layout.place(terminal, first, WorkbenchLayout.Region.BOTTOM));
        layout.place(tree, second, WorkbenchLayout.Region.TRAILING);
        assertNull(layout.owner(first));
        assertEquals(tree, layout.owner(second));
    }

    @Test
    void rejectsCrossWorkspacePlacementAndRemovesBothIndexes() {
        WorkbenchLayout layout = new WorkbenchLayout();
        WorkbenchLayout.SurfaceId status = surface("workspace-a", WorkbenchLayout.SurfaceType.STATUS, "main");
        WorkbenchLayout.HostId foreignHost = host("workspace-b", "footer");
        WorkbenchLayout.HostId host = host("workspace-a", "footer");

        assertThrows(IllegalArgumentException.class, () -> layout.place(status, foreignHost, WorkbenchLayout.Region.FOOTER));
        layout.place(status, host, WorkbenchLayout.Region.FOOTER);
        assertEquals(WorkbenchLayout.Region.FOOTER, layout.remove(status).region());
        assertNull(layout.placement(status));
        assertNull(layout.owner(host));
    }

    private WorkbenchLayout.SurfaceId surface(String workspace, WorkbenchLayout.SurfaceType type, String instance) {
        return new WorkbenchLayout.SurfaceId(workspace, type, instance);
    }

    private WorkbenchLayout.HostId host(String workspace, String id) {
        return new WorkbenchLayout.HostId(workspace, id);
    }
}
