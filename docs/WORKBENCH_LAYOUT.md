# Workbench layout and ownership

Production Swing ownership is split between `WindowLayoutNode` for editor-pane topology and `ToolWindowHost` for docked/floating tool components. `Texteditor` composes and disposes them. Neither owns a second text store: editor panes retain the existing document and editor-surface adapters.

`WorkbenchLayout` remains a separate, UI-independent placement model. Its workspace-scoped SurfaceId/HostId values enforce one surface per host, reject cross-workspace placement and replace a surface's old host assignment. Those invariants are unit-tested; the class is **not** a live registry through which every production Swing reparenting passes. `WorkbenchPlacementState` reuses its surface/region vocabulary for session data. Do not infer complete frontend detachment or runtime registry enforcement from that model.

| Surface | Production owner and presentation |
| --- | --- |
| Workspace/navigation | WorkbenchHeader above documents; roots and document operations use the application contracts |
| Open documents | OpenEditorsBar in the header; identities and metadata come from DocumentWorkspace |
| Editor/tree/terminal/preview/notebook | Existing EditorPane and WindowLayoutNode leaves; specialized components retain their lifecycle |
| Search/Git/Debug/Tasks/Tests/Problems/Replace/Agents | One ToolWindowHost, initially docked at the bottom, with optional modeless floating dialogs |
| Status | Existing footer, owned by EditorUiController |
| Git conflicts/history and other standalone tools | Existing owner-aware dialogs; retained placement helpers and feature lifecycles |

The active split has an accent viewport edge; other visible splits reserve the same edge thickness. Single-pane layout adds no viewport border. The workbench path/focus cue and each pane's accessible name expose the current location without changing document or undo ownership. Document labels distinguish duplicate names using the shortest useful parent suffix; full paths remain available as tooltips/accessibility text.

## Tool docking and recovery

Tools retain a canonical dock order: Search, Git, Debug, Tasks, Tests, Problems, Replace, Agents. The strip scrolls in narrow windows; the workbench Tools menu exposes every panel and explicit focus entry points. Data refresh does not request focus. Escape from a dock returns to the source, and the explicit Hide action returns focus to it.

Detach reparents the same tool presentation into an editor-owned JDialog. Closing that dialog returns the component to its canonical dock position. Tools → Return detached tools to dock is a recovery action for all floating panels. Closing the application disposes the floating windows and closes every surface; Git stops its refresh timer, Search stops its debounce, and Agent review rejects late UI delivery. Approved workspace operations retain their existing mutation/journal/cancellation rules.

Every tool surface has one ToolViewport presentation, retained when the surface is detached or reattached. When its dock is short, the content keeps a usable scaled minimum height and scrolls vertically. The viewport tracks width so existing field/action wrapping still responds to narrow windows. This preserves the chosen dock height while avoiding overlapping forms, empty job tables and clipped decision rows. Larger docks fill the available height. The original tool/controller and its data remain canonical. Tasks additionally compute job/output minimums from the current row/header/font metrics and clamp the inner vertical divider to those readable minimums after layout; valid user divider positions remain unchanged.

Floating tools retain their user/session outer bounds during appearance updates; the generic dialog font-sizing path does not repack placement-managed windows. A saved rectangle is applied again on the EDT after the native window-open event, to account for decoration/initial-pack events. That one queued operation checks that the original dialog is still showing and has not returned to the dock.

A per-host focus listener reveals the focused control through every nested viewport (including existing debugger forms). It uses the selected cell/row or text caret for large data widgets and the whole rectangle for fields/buttons. The listener is removed when ToolWindowHost closes. The gallery does not exempt ordinary controls merely because they are inside ToolViewport: it scrolls them into view, checks full visible bounds, and restores the original viewport positions after checking.

Dock height grows automatically for denser tools only while the divider still has its automatic position. A manual resize is preserved when switching tools and across hide/reopen. The source retains a minimum visible area when restoring a saved height. Focus mode temporarily hides the existing surfaces; it does not construct replacements.

## Session compatibility

Session version remains **2**. Existing sessions without the optional new data remain valid. Existing `layout` still stores editor split topology. No configuration format or launch path changed.

- `toolPlacements` stores validated workspace/surface/instance entries. Tool panels use instance `panel` and owner `primary`. Detached entries require bounded geometry and DETACHED region; docked entries use BOTTOM and may retain the last floating rectangle for the next detach.
- `toolDock` optionally stores `visible`, the selected tool enum name, and a finite divider `ratio` between 0.1 and 0.9. Unknown selection or invalid ratios are ignored.
- A floating dialog's capture/dock callbacks bind to the original workspace and placement model. Loading another session cannot make a late close event overwrite its replacement state. Restore snapshots desired placements before reattaching current windows, so restoring the same model also preserves intended detached entries.
- Automatic floating restore uses only the active workspace and `primary` owner, and clamps geometry to Java's maximum available window bounds. It does not claim testing of every multi-monitor arrangement. Unsupported or malformed entries remain ignored.
- If Swing tools have not yet been constructed during staged startup, SessionConfigController holds the restoration request until EditorUiController finishes composing them. Restored floating windows do not explicitly request focus.

The production layout/session test verifies a manually sized dock survives hide/reopen and tool selection, same-model restore retains detachment, reattach records DOCKED, and a saved session restores the exact supported floating rectangle. The journey probe verifies appearance changes, resize, detach/reattach and session restore together. Exact final artifacts and platform limits are recorded in [UI modernization](UI_MODERNIZATION.md).

## Constrained editor panes

Each editor-pane leaf requires at least 280×180 logical pixels. If a split cannot satisfy its child minimums, Shed renders the branch containing the active pane. The full WindowLayoutNode remains unchanged; growing the host restores the topology and ratios. Tool fields/actions wrap or scroll through the shared styling path rather than requiring a replacement editor engine.
