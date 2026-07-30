# Accessibility QA

Custom editor, palette, inspector, recovery, Git, and GitHub controls expose accessible names and descriptions. Native Swing controls retain their standard keyboard traversal and scalable Look-and-Feel fonts.

Before release, run this manual checklist on macOS, Windows, and Linux at default and increased system text scale:

- Use VoiceOver, Narrator, or Orca to verify the editor, palette filter/results, Settings, Keymap, recovery table, Git changes/conflicts/history, and pull-request controls announce their purpose and state.
- Check Tab/Shift-Tab order, Enter activation, and Escape dismissal/defer using the [keyboard focus checklist](KEYBOARD_FOCUS.md).
- Verify no clipped labels, hidden actions, or unusable split panes at 125%, 150%, and 200% system scale.
- Check editor, command, status, selection, and dialog text against their backgrounds. Normal-size text requires at least 4.5:1 contrast; large text requires at least 3:1. Custom palette colors are user-configurable, so record any theme that fails this check.

`AccessibilitySupportTest` verifies metadata wiring and the WCAG contrast formula. It does not replace assistive-technology or visual QA.
