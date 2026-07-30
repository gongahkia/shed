# Workbench Layout Model

`WorkbenchLayout` is the ownership registry for workspace surfaces. A `SurfaceId` and `HostId` both carry a workspace identity; a placement is rejected across workspaces. One surface has one host, and one host has one surface. Moving a surface replaces its prior host assignment; placing a different surface into an occupied host fails before Swing components are reparented.

| Surface | Current presentation | Canonical region |
| :--- | :--- | :--- |
| Tree | Split editor pane | `LEADING` or `TRAILING` |
| Tabs | Buffer navigation surface | `BOTTOM` when introduced |
| Editor | `WindowLayoutNode` leaf | `CENTER` |
| Terminal | Split editor pane | `BOTTOM` |
| Git | Modeless workbench dialog | `DETACHED` |
| Debugger | Scratch/inspection surface | `BOTTOM` or `DETACHED` |
| Status | Footer labels | `FOOTER` |

`WindowLayoutNode` remains the renderer for split editor-pane leaves. Future docked or detached panels first receive a surface and host placement through this registry, then render their component; controllers do not decide peer ownership.

## Persisted Tool Placement

Session V2 stores `toolPlacements` as validated workspace-scoped entries. A docked entry has a host and non-detached region. A detached entry additionally has bounded window geometry and the logical owner-window id `primary`. Restoring a detached Git tool only uses an entry from the active workspace and owner; its rectangle is clamped to the available screen so an old monitor arrangement cannot place the window off-screen.

Tree and terminal remain docked `WindowLayoutNode` leaves, whose split topology is already stored in `layout`. Git Changes, Conflicts, and History are detached modeless tools; their bounds are captured on move, resize, and close. Unsupported, malformed, cross-workspace, or out-of-range placement data is ignored.
