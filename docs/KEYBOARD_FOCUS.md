# Keyboard Focus QA

Shed uses native Swing focus traversal. Tab and Shift-Tab must advance and reverse through visible, enabled controls only; Escape must dismiss modeless dialogs or defer recovery without discarding data.

Run this checklist manually on each supported OS at default and increased system text scale:

- Editor: open a file, switch panes, create/close a split, return focus to the active editor, and exit modal input with Escape.
- Palettes: open command, file, buffer, symbol, and completion palettes; type a filter, use Up/Down and Enter, then Escape.
- Panels: open tree, quickfix, terminal, and Git workbench surfaces; tab through visible controls, confirm terminal input owns focus, and return to the editor.
- Dialogs: Settings, Keymap, Git changes/conflicts/history/remotes, pull requests, and recovery must expose every enabled action by Tab order; Escape closes modeless dialogs and defers recovery.
- LSP and debugger: open completion/signature help, diagnostics, debug configuration, threads, stack, scopes, variables, and console; confirm Escape cancels transient UI and focus remains recoverable.

Record the OS, scale, surface, order observed, Escape result, and any inaccessible control. The automated `KeyboardFocusSupportTest` verifies the shared root-pane Escape binding; it does not replace this assistive-technology QA.
