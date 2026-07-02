# Phase 31 Git Checkpoint

Date: 2026-07-02

Implemented slice:

- added libgit2 diff pathspec support.
- added libgit2 diff patch text rendering through `git_diff_to_buf`.
- kept shell diff/stage/unstage paths unchanged.
- extended the libgit2 fixture test to verify patch text and pathspec filtering.

Verification:

```sh
swift test --filter Libgit2
```

Result:

- `Libgit2`: 3 tests passed.

Remaining for #3:

- libgit2 hunk stage/unstage without temp patch stdin round-trips.
- libgit2 commit composer and shell fallback retirement.
- blame core/cache and gutter UI.
- file/line history panels.
- cancelable libgit2 remote operations.
- credential and signing flows.
