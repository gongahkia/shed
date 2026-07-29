# Recovery Journal

Shed stores unsaved-buffer recovery state only in `~/.shed/recovery/journal-v1.json`. The file is local; it has no network transport or synchronization.

## Format

The outer envelope has `payload` and `integrity`. `integrity` is `sha256:` followed by the SHA-256 digest of the canonical JSON serialization of `payload`.

```json
{
  "payload": {
    "version": 1,
    "writtenAt": "2026-07-29T00:00:00Z",
    "retention": {
      "maxEntries": 32,
      "maxContentBytes": 8388608,
      "retainedEntries": 1,
      "retainedContentBytes": 12,
      "droppedEntries": 0
    },
    "workspace": {
      "workingDirectory": "/work/project",
      "activePath": "/work/project/notes.txt",
      "activeCaretPosition": 8
    },
    "entries": [
      {"id": "file-ab12", "name": "notes.txt", "path": "/work/project/notes.txt", "content": "unsaved text"}
    ]
  },
  "integrity": "sha256:<payload digest>"
}
```

`version=1` is required. Each entry is a complete buffer payload; Shed never writes a partial document to satisfy retention. Entries are retained in editor-buffer order. Defaults retain up to 32 entries and 8 MiB of UTF-8 content; `recovery.retention.max.entries` and `recovery.retention.max.content.bytes` can lower either bound, never raise the hard maximum. `droppedEntries` makes bounded retention explicit.

## Read and write semantics

Shed writes a fully forced temporary file in the recovery directory, then atomically replaces `journal-v1.json`. If the filesystem cannot provide an atomic move, the write fails and the prior journal remains authoritative. Reads validate the envelope, version, retention metadata, and SHA-256 digest before exposing any entry. An invalid journal is not restored.

Workspace context records the working directory, active file path, and caret position. Restored buffers are dirty and require an explicit save; recovery never writes restored content to a source file automatically.

At startup, the Crash Recovery Workspace presents each snapshot beside its current on-disk content when available. Restore applies only checked snapshots. Defer leaves the journal unchanged, and discarding all snapshots requires confirmation. A partial restore keeps unselected snapshots for a later startup; a full restore rewrites the journal from its restored dirty buffers.

## Write scheduling

Shed captures recovery input on the EDT, then replaces one pending write after a 750 ms debounce. Disk serialization runs on a daemon worker, so editing does not wait for journal I/O and queued recovery state is bounded to one snapshot. Successful writes are recorded by `:perf` as `recovery.journal.write`; failures are recorded in the local diagnostic log.

## Cleanup policy

With `recovery.cleanup.on.clean.exit=true`, Shed removes the journal only after a clean exit with no dirty recoverable buffer and no deferred recovery decision. Unsaved buffers are flushed before shutdown; deferred recovery preserves the prior journal and pauses replacement writes. An explicit discard requests deletion, but a deletion failure is recorded locally and leaves recoverable data in place.
