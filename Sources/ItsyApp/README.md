# ItsyApp layout

`ItsyApp` owns the AppKit shell and feature coordinators. Source files stay at the target root until the Phase 21 extraction tasks move them.

## Directory ownership

- `App/`: application entry coordination and `AppDelegate` lifecycle.
- `Documents/`: `ItsyDocument`, document controller, save/load, file watching, tab coordination.
- `Windows/`: editor windows, panes, pane coordination, gutter composition, LSP bridging.
- `Palette/`: command palette panel, command registry integration, command filtering/execution.
- `ProjectFind/`: project-wide find panel, query state, result navigation.
- `Git/`: Git panel, commit composer, branch/stash/conflict UI, gutter integration.
- `Tasks/`: task discovery/run panel and task output state.
- `Problems/`: problems panel, diagnostics presentation, compiler/task issue state.
- `Outline/`: outline panel, symbol tree nodes, collapse state.
- `References/`: references panel and navigation results.
- `Hover/`: hover tooltip presentation.
- `Signature/`: signature help popover presentation.
- `Completion/`: completion popup presentation and apply flow.
- `FileTree/`: sidebar file tree, child cache, preview/open state.
- `Terminal/`: terminal panel, PTY session, emulator, terminal view.
- `Settings/`: settings window and preferences UI.
- `Menu/`: main menu construction and action routing.
- `Bench/`: app-side benchmark stage helpers.
