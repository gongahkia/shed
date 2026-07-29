# Workspace State Format

Workspace state uses canonical JSON with `version: 2` and exactly these top-level fields: `roots`, `buffers`, `panes`, `activeSelection`, and `tools`. Version 1 is accepted only for recoverable migration.

`roots` contains zero or more unique absolute paths. A buffer is either a file buffer with an absolute path and a verified file snapshot, or a scratch buffer with a name and content. A file snapshot records file identity, creation/modification times, size, and a SHA-256 digest. Dirty file buffers include content; clean file buffers do not. Panes name their buffer and caret. `activeSelection` names the active pane, its buffer, and caret; it is absent only when the workspace has no buffers. Tool states use a unique identifier and string key/value metadata.

The decoder rejects unsupported versions, unknown or missing fields, wrong types, duplicate IDs, invalid paths, dangling pane references, and inconsistent active selection. It retains its last successfully decoded workspace when a later decode fails; persistence and restore owners consume that result in later workspace stages.

`WorkspaceStateRestoreService` restores only verified file paths. Missing, changed, unreadable, and version-1 unverified file paths are reported as recoverable failures and are never opened. Dirty snapshots from those paths are restored only as detached recovery scratch buffers, preserving content without attaching it to a potentially different file. Pane assignments and active selection are restored only for successfully restored buffers.

## Persistence

`WorkspaceStatePersistenceService` is the single asynchronous writer for accepted `WorkspaceState` snapshots. Its save request accepts only an already validated immutable state, coalesces queued requests to the latest snapshot, creates the target parent directory, and writes canonical JSON through `AtomicFileWriter`. Normal saves and observer callbacks run on its daemon worker, never on the Swing event-dispatch thread. Closing the service stops that worker and flushes the last accepted snapshot before shutdown completes.
