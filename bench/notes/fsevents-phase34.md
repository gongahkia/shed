# Phase 34 FSEvents Checkpoint

Date: 2026-07-02; refreshed 2026-07-07

Implemented slice:

- added `Sources/ItsyEditor/Workspace/FSEventStream.swift`.
- moved workspace watching behind `WorkspaceFSEventStream` with a dedicated dispatch queue.
- added debounced event batching and path coalescing.
- persisted last-seen FSEvent IDs per workspace under `~/.config/itsy/fsevents`.
- wired app workspace events through the editor-core wrapper.
- trigger a full workspace reindex when FSEvents reports dropped events, wrapped IDs, root changes, or must-scan-subdirs.
- added `ItsyBench workspace-fsevents` for create/modify/rename/remove/resume delivery.
- added `ItsyBench workspace-index` for 10k-file persisted-index validation.
- added exact/substring workspace-symbol search fast paths before fuzzy fallback.

Verification:

```sh
swift test --filter WorkspaceFSEvent
swift test --filter WorkspaceIndex
swift run -c release ItsyBench workspace-fsevents --timeout-ms 8000
swift run -c release ItsyBench workspace-index --files 10000 --ignored-files 2000
```

Result:

- `WorkspaceFSEvent`: 2 tests passed.
- `WorkspaceIndex`: 7 tests passed.
- FSEvents smoke: `{"batches":6,"create_seen":true,"modify_seen":true,"remove_seen":true,"rename_seen":true,"resume_seen":true,"stored_event_id":1245888942}`.
- 10k persisted-index bench: build `1072.847542 ms`, load `60.06225 ms`, first symbol query `8.173625 ms`, ignored indexed files `0`, indexed files `10001` including `.gitignore`.

Conclusion:

- #6 acceptance is met on the refreshed current tree.
