# Git Gap

## Sources checked

- Git `status` documentation: porcelain v2 is the stable parse target; `--branch` exposes branch head/upstream/ahead/behind headers.
- Git `diff` documentation: staged and worktree diffs are separate command modes.
- VS Code source-control documentation: competitive baseline includes changed-file lists, staging, committing, branches, remotes, diff view, gutter/blame, and conflict workflows.
- Zed Git documentation: competitive baseline includes a Git panel with working tree/staging state, branch info, immediate command-line refresh, project diff, staging, commit, branch, worktree, stash, and conflict flows.

## Current implementation slice

- Added a pure `ItsyEditor` Git core that shells out to `/usr/bin/git`.
- Added porcelain v2 status parsing for branch headers, ordinary entries, renames, unmerged entries, untracked files, staged counts, and unstaged counts.
- Added status, diff, stage, and unstage repository commands.
- Added parser and real temporary-repository tests.

## Not done yet

- No Git panel.
- No file-tree status badges.
- No gutter hunk indicators.
- No commit UI.
- No branch/switch/fetch/pull/push/stash/conflict workflows.

## Next slice

Attach this core to an app-level Git model that refreshes from the open project root and can feed both file-tree badges and a future Git panel.
