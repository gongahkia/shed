# Large-File Storage Architecture

## Current implementation

Shed ships a Java `FileChannel`-backed UTF-8 source store and a bounded Swing projection. It does not ship a piece table, add-store, or editable large-file document. The implementation is local only and creates no telemetry or network path.

| Owner | Responsibility | Memory rule |
| :--- | :--- | :--- |
| `LargeFileStore` | Open source channels on demand, decode UTF-8, and retain sparse line checkpoints | Read fixed-size byte pages on demand; never materialize the source as one array or `String` |
| `LargeFileProjection` | Own the visible Swing `Document` window and cursor translation | Keep only viewport content plus bounded margins in `PlainDocument` |
| `FileBuffer` | Select normal or large-file path and expose save/reload/error state | Must not retain a duplicate full-content snapshot for a large file |

The prior `FileBuffer` preview path read and split all bytes; #62 measured that baseline. It is not the shipped bounded projection. User-facing limits are in [large-file support](LARGE_FILE_SUPPORT.md).

## Current open foundation

The #64 foundation selects the large-file path when a source exceeds the configured byte or line threshold. It validates UTF-8 through a 64 KiB channel page scanner, stores source metadata and a preview capped at 256 Ki UTF-16 code units, and does not retain full source content. Malformed UTF-8 and unsupported source types surface a visible unavailable state without an in-memory fallback.

The #65 projection replaces that initial preview with a read-only source window. It uses sparse checkpoints every 1,024 logical lines, keeps at least 64 projected lines with 16-line caret margins, and replaces only the Swing document window on scroll, caret-edge movement, or viewport resize. Each projected window remains capped at 256 Ki UTF-16 code units; a single overlong line is visibly truncated rather than forcing a full-document allocation.

The #66 save path streams the unchanged large-file source through a sibling temporary file in 64 KiB chunks, forces it, verifies output size and SHA-256 digest, and atomically replaces the target. A stream, move, or verification failure reports its recovery outcome and attempts to restore the forced sibling backup. Editing and save-as remain unavailable.

## Encoding and line index

The shipped large-file path accepts well-formed UTF-8, including an optional initial BOM. It decodes pages with carry-over bytes at page boundaries. An unchanged streamed save copies the original bytes.

`LargeFileStore` keeps sparse byte-offset and logical-line checkpoints every 1,024 lines. A window lookup seeks to the nearest checkpoint and scans bounded pages. Newline detection treats `LF`, `CRLF`, and `CR` as one logical line break.

Malformed UTF-8, unsupported encodings, non-regular files, symlinks, unreadable files, or an unavailable index produce an explicit unavailable state. A file at or above the large-file limit does not silently fall back to full in-memory loading.

## Edit and undo path

No large-file edit or undo path is shipped. The projection is read-only and cannot become the source of truth. Any future piece-table or append-store design remains unimplemented and must not be inferred from this document.

## Save, reload, and recovery

Save streams unchanged source bytes into a sibling temporary file through `FileChannel`, forces the channel, verifies byte count and SHA-256 digest, then uses atomic replacement semantics. The original file remains intact until replacement succeeds. Temporary output is removed where possible and cleanup failures are reported.

Reload and existing external-change detection remain available. Backups and recovery snapshots are unavailable because no large-file edit model is shipped.

## Limits and fallbacks

| Area | Contract |
| :--- | :--- |
| Source bytes | Bounded page reads use `long` byte positions; no universal maximum file size is published |
| Lines | Sparse logical-line checkpoints; no universal maximum line count is published |
| Projection | Read-only 64-line minimum window with 16-line margins, capped at 256 Ki UTF-16 code units |
| Search and syntax | Disabled in large-file mode with a reason |
| Save | Streaming atomic write of unchanged source; no `getFullContent`, `readAllBytes`, `split`, or whole-file encode step |

Initial scope is regular local UTF-8 text files. Binary data, arbitrary legacy encodings, network filesystems with unreliable identity semantics, full-document formatting, and unrestricted multi-file transformations remain unavailable rather than taking an unsafe or unbounded path.

## Test strategy

The implementation issues validate the shipped contract with deterministic generated UTF-8 fixtures at 1 MiB, 100 MiB, and 1 GiB, plus small boundary fixtures.

- Open and scroll tests prove source bytes are not fully materialized and the projection stays bounded.
- UTF-8 tests cover page-boundary decoding, malformed input, line endings, and sparse line-index lookup.
- Save tests verify exact streamed output without whole-file reconstruction.
- Benchmark tests use `LargeFileBenchmark` and record environment, operation states, median/p95, heap deltas, and failures locally.
- CI uses small fixtures only. The 100 MiB and 1 GiB measurements are explicit local benchmark work, not mandatory CI workloads.

## Delivery sequence

1. #64 adds channel ownership, UTF-8 paging, and safe open states.
2. #65 adds the bounded projection and viewport rendering.
3. #66 adds streamed atomic save and failure recovery.
4. #67 exposes availability, disabled operations, and remediation.
5. #68 adds generated fixtures; #69 automates local responsiveness measurements; #70 publishes supported limits.
