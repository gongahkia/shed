# Large-File Storage Architecture

## Decision

Future large-file support uses a Java `FileChannel`-backed UTF-8 piece table, not a `String`, `PlainDocument`, mapped file, native store, or a port of another platform's editor engine. The implementation is local only and creates no telemetry or network path. It is split into a storage model and a bounded Swing projection:

| Owner | Responsibility | Memory rule |
| :--- | :--- | :--- |
| `LargeFileStore` | Own the source `FileChannel`, UTF-8 decoder, sparse line index, and append-only edit store | Read fixed-size byte pages on demand; never materialize the source as one array or `String` |
| `LargeFileDocument` | Own ordered original/add-store pieces and transactional edits | Store piece metadata, not source text; offsets and line counts are `long` |
| `LargeFileProjection` | Own the visible Swing `Document` window and cursor translation | Keep only viewport content plus bounded margins in `PlainDocument` |
| `FileBuffer` | Select normal or large-file path and expose save/reload/error state | Must not retain a duplicate full-content snapshot for a large file |

The prior `FileBuffer` preview path read and split all bytes; #62 measured that baseline. It is not the bounded-memory implementation described here.

## Current open foundation

The #64 foundation now selects the large-file path when a regular file exceeds the configured byte or line threshold. It validates UTF-8 through a 64 KiB channel page scanner, stores source metadata and a preview capped at 256 Ki UTF-16 code units, and does not retain full source content. Large files are read-only until the bounded projection, edit, and streamed-save milestones land. Save, backup, LSP synchronization, Markdown preview, and recovery snapshots are deliberately unavailable for this state; malformed UTF-8 and unsupported file types surface a visible unavailable state without an in-memory fallback.

The #65 projection replaces that initial preview with a read-only source window. It uses sparse checkpoints every 1,024 logical lines, keeps at least 64 projected lines with 16-line caret margins, and replaces only the Swing document window on scroll, caret-edge movement, or viewport resize. Each projected window remains capped at 256 Ki UTF-16 code units; a single overlong line is visibly truncated rather than forcing a full-document allocation.

## Encoding and line index

The target large-file path will support well-formed UTF-8, including an optional UTF-8 BOM retained as file metadata. It will decode pages with carry-over bytes at page boundaries and record only safe UTF-8 decode boundaries.

`LargeFileStore` keeps a sparse index of byte offset, UTF-16 offset, and line number checkpoints. A lookup seeks to the nearest checkpoint, scans bounded pages, and caches recent checkpoints. Newline detection treats `LF`, `CRLF`, and `CR` as one logical line break while preserving the source's dominant line ending for save.

Malformed UTF-8, unsupported encodings, non-regular files, symlinks, unreadable files, or an unavailable index produce an explicit unavailable state. A file below the normal in-memory limit may fall back to the existing decoder. A file at or above the large-file limit must not silently fall back to full in-memory loading.

## Edit and undo path

Edits replace ranges in the piece table. Inserted UTF-8 bytes go to a private append-only temporary edit store; original spans continue to reference the source channel. The model validates byte, UTF-16, and line boundaries before every edit. Invalid positions, stale source identity, or an unavailable edit store fail without mutating the document.

Undo and redo retain bounded piece-table operations and append-store spans under the existing configured undo limits. They never reconstruct a whole-document `String`. Selection, search, and navigation operate through model offsets; the Swing projection is a cache and cannot become the source of truth.

## Save, reload, and recovery

Save streams pieces into a sibling temporary file through `FileChannel`, forces the channel, verifies byte count and source identity, then uses the existing atomic replacement semantics. The original file remains intact until atomic replacement succeeds. Save failure preserves the source and the in-memory piece table; temporary output is removed where possible and any cleanup failure is reported.

Reload discards the projection and edit store only after opening and validating a new source channel. External replacement, deletion, or type changes leave the existing model readable and surface the same conflict/remediation state used for normal buffers. Recovery records bounded edit operations or a recoverable edit-store reference, never a full large-file snapshot.

## Limits and fallbacks

| Area | Contract |
| :--- | :--- |
| Source bytes | `long` byte offsets; reject negative, overflowing, or unsupported file sizes before allocation |
| Projection | Bounded viewport plus margins; no full-document `PlainDocument` |
| Lines | `long` logical line numbers; UI controls show a clear limit/remediation when an `int`-only Swing API cannot represent a location |
| UTF-16 positions | Translate only requested visible/search ranges; reject invalid surrogate boundaries |
| Search and syntax | Incremental, cancellable, and page-based; disabled with a reason when unsupported by the current large-file milestone |
| Save | Streaming atomic write; no `getFullContent`, `readAllBytes`, `split`, or whole-file encode step |

Initial scope is regular local UTF-8 text files. Binary data, arbitrary legacy encodings, network filesystems with unreliable identity semantics, full-document formatting, and unrestricted multi-file transformations remain unavailable rather than taking an unsafe or unbounded path.

## Test strategy

The implementation issues validate this contract with deterministic generated UTF-8 fixtures at 1 MiB, 100 MiB, and 1 GiB, plus small boundary fixtures.

- Open and scroll tests prove source bytes are not fully materialized and the projection stays bounded.
- UTF-8 fixtures cover BOM, multibyte page boundaries, combining sequences, line-ending variants, malformed input, and sparse line-index lookup.
- Edit/undo tests cover adjacent, overlapping, and cross-page ranges; they verify exact streamed output without whole-file reconstruction.
- Save tests inject write, force, verify, atomic-move, and cleanup failures; source content and recovery state remain valid.
- Benchmark tests use `LargeFileBenchmark` and record environment, workload, median/p95, heap deltas, and failures locally.
- CI uses small fixtures only. The 100 MiB and 1 GiB measurements are explicit local benchmark work, not mandatory CI workloads.

## Delivery sequence

1. #64 adds channel ownership, UTF-8 paging, and safe open states.
2. #65 adds the bounded projection and viewport rendering.
3. #66 adds streamed atomic save and failure recovery.
4. #67 exposes availability, disabled operations, and remediation.
5. #68 adds generated fixtures; #69 automates local responsiveness measurements; #70 publishes supported limits.
