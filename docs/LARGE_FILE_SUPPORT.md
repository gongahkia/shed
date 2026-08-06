# Large-File Support

## Current status

Large-file mode is a local, read-only UTF-8 viewport path. Files at or below Shed's editable boundary use the versioned text model described in [editing performance](EDITING_PERFORMANCE.md). Use `:largefile` or `:lf` to show the active buffer's state, limits, and remediation.

## Activation

Shed selects large-file mode when either condition is true:

- The source is larger than `large.file.threshold.mb`. The default is 25 MiB (26,214,400 bytes); a file exactly that size does not select by size alone.
- The source has more logical lines than `large.file.line.threshold`. The default is 500,000 lines.

The effective minimums are 1 MiB, 1,000 lines, and 50 preview lines even if a lower configuration value is supplied. See [configuration](CONFIG.md) for the persisted keys.

## Supported source contract

Large-file mode requires a local regular, non-symlink, well-formed UTF-8 file. UTF-8 with an initial BOM is accepted; an unchanged streamed save preserves original source bytes. LF, CRLF, and CR are recognized as logical line endings.

Malformed UTF-8, non-regular files, symlinks, unreadable files, and other open/index failures enter an explicit unavailable state. Shed does not load those large sources into a normal full-document fallback. A file below the configured thresholds follows the normal in-memory file path, which has different encoding and editing behavior.

## Available operations

- Bounded viewport rendering, scrolling, caret-edge movement, and resize updates.
- Reload and existing external-change detection.
- Atomic streamed save of unchanged source content. Shed writes a sibling temporary file, verifies byte count and SHA-256 digest, then atomically replaces the target; failures report their recovery outcome and attempt to restore the original.
- Local fixture generation and benchmark reporting. These commands do not send telemetry or make network requests.

The projection keeps a 64-line minimum window with 16-line margins. It indexes every 1,024 logical lines and caps a projected window at 256 Ki UTF-16 code units. A single long line can therefore be visibly truncated.

## Unavailable operations

- Editing, undo/redo, save-as, backups, and recovery snapshots.
- LSP synchronization, Markdown preview, full-document search, and syntax analysis.
- Binary or legacy encodings, network-filesystem guarantees, whole-document formatting, and unrestricted workspace transformations.

Use a smaller file below the configured limits when those operations are required. Raising a threshold moves a file back to the normal in-memory path only when the available heap is sufficient; it is not a large-file edit workaround.

## Limits and benchmark evidence

The shipped bounded path uses `long` byte and line counters where implemented, but Shed does not claim a universal maximum file size, line count, latency, or memory ceiling. Host JVM heap, storage, file content, and platform influence results.

The deterministic 1 MiB, 100 MiB, and 1 GiB UTF-8 fixtures are local and Git-ignored. They are reproducible benchmark inputs, not a certification that every operation is supported at each size. Run the checksum verifier before measurement and read the machine-readable operation state:

- `PASS` means every available operation completed.
- `UNSUPPORTED` identifies an intentionally unavailable operation, such as large-file editing.
- `FAIL` means a user-supplied p95 limit was exceeded.
- `ERROR` means setup or an operation failed.

See [benchmark procedure](LARGE_FILE_BENCHMARK.md) for commands and result fields, and [implementation details](LARGE_FILE_ARCHITECTURE.md) for the storage and projection design.
