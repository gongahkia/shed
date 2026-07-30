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
