# Phase 31 Git Checkpoint

Date: 2026-07-07

Implemented slice:

- added libgit2 diff pathspec support.
- added libgit2 diff patch text rendering through `git_diff_to_buf`.
- routed default `GitRepository.diff(path:staged:)` through libgit2 patch rendering.
- added default libgit2 commit creation for non-amend/non-signoff composer commits, with shell fallback preserved for amend/signoff.
- added libgit2 blame core, shell blame fallback parser, and an invalidatable blame cache.
- added file history and line history repository APIs.
- exposed blame, file history, line history, and remote cancellation through Git menu/command palette actions.
- added a cancel button for running shell-backed remote operations.

Verification:

```sh
swift test --filter GitRepository
swift test --filter Libgit2
swift build --target ItsyApp
```

Result:

- `GitRepository`: 27 tests passed.
- `Libgit2`: 4 tests passed.
- `swift test`: 459 tests passed.
- `swift build --target ItsyApp`: passed.
- `swift build -c release`: passed.

Remaining for #3:

- libgit2 hunk stage/unstage without temp patch stdin round-trips.
- signoff/amend commit support in libgit2 before shell fallback retirement.
- blame gutter UI.
- native libgit2 remote operations and cancellation.
- credential and signing flows.
