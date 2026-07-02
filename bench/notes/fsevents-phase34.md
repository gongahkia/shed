# Phase 34 FSEvents Checkpoint

Date: 2026-07-02

Implemented slice:

- added `Sources/ItsyEditor/Workspace/FSEventStream.swift`.
- moved workspace watching behind `WorkspaceFSEventStream` with a dedicated dispatch queue.
- added debounced event batching and path coalescing.
- persisted last-seen FSEvent IDs per workspace under `~/.config/itsy/fsevents`.
- wired app workspace events through the editor-core wrapper.
- trigger a full workspace reindex when FSEvents reports dropped events, wrapped IDs, root changes, or must-scan-subdirs.

Verification:

```sh
swift test --filter WorkspaceFSEvent
swift test --filter WorkspaceIndex
```

Result:

- `WorkspaceFSEvent`: 2 tests passed.
- `WorkspaceIndex`: 4 tests passed.

Remaining for #6:

- validate real create/remove/rename/modify events against an app run.
- bench 10k-file cold-open with persisted index and first symbol query under 100 ms.
- confirm ignored paths are dropped before expensive reindex work in a large tree.
