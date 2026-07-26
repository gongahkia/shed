# Workbench layout manual matrix

Run a fresh app with a workspace open. Keep the same window open for each row unless the row says restart.

| Scenario | Steps | Expected result |
|---|---|---|
| Resize | Open Git and Debugger, drag the secondary sidebar wider/narrower, then resize the editor from wide to narrow and back. | Git and Debugger share one trailing sidebar and switch through its tabs; the editor never receives two fixed trailing panes. At constrained widths the secondary sidebar collapses, then returns after widening. |
| Fullscreen | Open terminal, Git, and Debugger. Enter/leave fullscreen twice. | All visible workbench surfaces relayout without stale frames, clipping, blank areas, or duplicate panes. |
| Sidebar position | Set `layout.sidebar_position` to `leading`, then `trailing`; open Git and Debugger together. | File tree moves as configured. The shared Git/Debugger sidebar remains a single trailing secondary surface. |
| Detached tools | Set each of `[terminal]`, `[git]`, and `[debugger]` `presentation = "window"`, open it, then change back to its embedded mode. | Each active tool moves immediately between its panel and embedded surface without reopening or losing its content. |
| Debugger tabs | Open Debug → Call Stack, Variables, Watches, and Debug Console. | The same Debugger presentation hosts all four tabs. No independent Variables/Watches/Console panels remain open. |
| Width persistence | Resize Git and Debugger to different widths, switch tabs, quit, and reopen the workspace. | Git and Debugger retain their own widths. |
| JSON reload | With an active embedded tool, edit its `presentation` in `settings.json`. Then introduce an invalid value and fix it. | The active tool relocates immediately. A settings-applied toast names active presentations. An error toast identifies the validation issue and states that a fallback value remains active. |
| Two editor windows | Open two editor windows. Open Terminal/Git/Debugger in the first, make the second key, then change the active tool presentation in JSON. | The tool remains owned by and relocates inside the first window; it does not jump to the key window. Close it, then open it from the second window to transfer ownership intentionally. |
| Restart | Repeat the width and presentation checks after quitting and reopening. | Persisted layout state restores; no stale detached panels or duplicate embedded surfaces appear. |
