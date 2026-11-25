# Editor-first agent visualization and smooth scrolling

The normal external-agent workflow uses the existing editor, viewport, source-change cursor, Explorer badges and Git diff surfaces. Starting, stopping, following or querying a watch does not open or switch the tool dock. Receiving a checked proposal does not open review automatically or bypass human acceptance.

## Optional management controls

- `:agent changes` explicitly opens the retained Live changes controls.
- `:agent proposals` explicitly opens review; queued workspace proposals retain workspace ownership instead of being retargeted to a conversation when opened later.
- `:agent` or Tools → Agents opens the optional built-in assistant.
- The Agents dock tab is added on explicit access. A saved Agent dock/detached window is not restored visibly on a fresh launch without that request. Focus-mode restoration within a session still preserves tools the user explicitly opened.

The assistant dropdown is an ACP-program selector, not installed-agent discovery or an external CLI/model catalog. OpenCode remains the built-in default preset. Its previous hardcoded UI list now uses `ConfigManager.getAgentProviderNames()` so all user-configured ACP-compatible programs appear. Connect remains explicit; neither dropdown population nor ordinary file visualization launches them. User-defined commands must actually speak ACP. Model preference updates stay scoped to the selected configured provider.

A user can run `shed visualize .` and let any CLI agent edit normally. No provider selection is required. The existing permission, source/revision, Undo, save and proposal-approval boundaries remain unchanged. Successful visualization startup shows a short watching message rather than leaving connection plumbing in the editor footer.

## Smooth scrolling contract

`SmoothScrollPaneUI` is installed through Shed's existing `WorkbenchStyle` UI defaults. Standard Swing scroll panes therefore share it across source tabs/splits, Explorer, lists/pickers, Git/diffs, tool panels, settings/dialogs and supported previews. No replacement layout or rendering engine is introduced.

Wheel/trackpad input interpolates bounded scrollbar positions over 140 ms with cubic deceleration. It retains precise rotations, Shift-wheel horizontal input, configured unit/block increments, accumulation and prompt reversal. At a nested boundary, input is handed to the enclosing scroll pane. Frame updates move the existing viewport; they do not edit source, move the human caret or rebuild text views.

Manual keyboard/caret navigation, direct scrollbar changes/drags, document/view replacement, hiding, disabling and UI uninstall cancel pending frames. Timers coalesce, run only during a gesture and remove their borrowed view/model listeners on uninstall. Existing reduced-motion overrides (including `shed.caretAnimation=false` and `prefers.reduced.motion=true`) disable interpolation; individual views can also disable it through the internal `shed.smoothScrolling` client property.

Keyboard reveals, selection and scrollbar dragging remain immediate. The agent's existing block tour keeps its own presentation timing and pauses on human wheel input. JediTerm grids keep their own row/protocol scrolling; no terminal escape/mouse input or unqualified native-shell fixture is intercepted. Terminal-grid pixel smoothing remains outside the verified standard Swing scroll-pane coverage.

The native capture also exposed an existing agent-name overlap with source on the first line. Names now use available end-of-line space, and are omitted when a bounded safe label position is unavailable. The colored source marker remains. Label lookup is capped at a 256-unit logical line and includes the label's visible row in repainting, avoiding a long-line scan or a stale label outside the changed word.

## Verification and limits

See [the evidence index](performance/editor-first-agent/INDEX.md) for exact commands/results, captures, failed attempts and artifact identity. Headless contract tests are distinct from actual desktop tests. Native-shell, Windows/Linux desktop, physical input/scanout and full native-memory qualifications remain open. Existing Unicode glyph/layout and HTML-preview latency limitations are unchanged.

No new dependency, font, theme, setting, source model, background service or agent connector was introduced. README is protected; its before/after SHA-256 is recorded in the evidence index. Local atomic commits only; no push by this agent.

The final reviewed safe suite ran 430 tests: 423 passes, seven skips and zero failures/errors. Python tooling passed 95/95. Actual model execution, explicit review/Apply/Undo/Redo/save, direct launcher and packaged-media acceptance all passed against the same final JAR. Warm typing p95 in the active 5,000-file watch sample was 0.82 ms. Only standard Swing scroll panes are included in the new smooth-scrolling coverage; terminal-grid and unsupported native/platform cases remain qualified above.

Changed production files: `SmoothScrollPaneUI.java`, `WorkbenchStyle.java`, `AgentEditPresentation.java`, `AgentSessionPanel.java`, `AgentWorkbenchPanel.java`, `AgentWorkspaceController.java`, `ToolWindowHost.java`, `ShedVisualize.java`. Tests/tooling: `SmoothScrollPaneUITest.java`, `AgentEditPresentationTest.java`, `AgentReviewPanelTest.java`, the reviewed test hash manifest, `scripts/test_native_fixture.py`, and `AgentWorkflowDesktopProbe.java`. Documentation updates the existing usage guides and adds this report/evidence directory. No README, dependency or source-document model change.
