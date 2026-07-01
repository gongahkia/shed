# Text Stack Design

Status: proposed for Phase 22 design lock.

## Goal

Open and edit 1 GB UTF-8 text files without materializing the full file as `String` or `Data`, while preserving current editor semantics: byte-offset selections, undo/redo, tree-sitter incremental parsing, syntax spans, and grapheme-aware cursor motion.

## Current Failure Mode

`ItsyDocument.read(from:ofType:)` receives `Data`, decodes the whole file into `String`, and builds `Editor(text:)`. `data(ofType:)` returns `Data(editor.text.utf8)`. `Editor.text` flattens the whole rope. This path cannot meet the 1 GB open/save KPI.

`SyntaxPipeline` already uses a chunk callback through `RopeInput`, but `RopeInput` copies into a fixed 16 KB scratch buffer. The piece tree keeps that interface shape and removes the full-document copy.

## Storage Model

`Editor` moves from `rope: Rope` to:

```swift
enum TextStorage {
	case rope(Rope)
	case pieceTree(PieceTree)
}
```

The initial integration keeps `.rope` as default. `ITSY_EDITOR_STORAGE=piecetree` and later `settings.toml [editor.experimental] storage = "piecetree"` enable `.pieceTree`.

All editor-facing offsets remain UTF-8 byte offsets. Rendering, selections, LSP position conversion, and tree-sitter edits already use byte offsets or can derive line/UTF-16 positions from byte offsets.

## Piece Tree

`PieceTree` is a red-black tree of immutable pieces.

```text
Piece {
	buffer: .original(Int) | .add(Int)
	start: Int
	length: Int
	lineFeeds: Int
	graphemes: Int
}

Node summary {
	bytes: Int
	lineFeeds: Int
	graphemes: Int
}
```

Buffers:

- `original`: one or more read-only mapped file regions.
- `add`: append-only allocated byte chunks for inserted text.

Operations:

- `insert(_:at:)`: split the containing piece at the byte offset, append inserted UTF-8 to the add buffer, insert a new piece, then rebalance and update summaries.
- `remove(_:)`: split at range bounds, drop covered pieces, coalesce adjacent pieces when they reference contiguous ranges in the same buffer.
- `substring(_:)`: iterate pieces and decode only the requested range.
- `iterateBytes(from:_:)`: visit contiguous byte spans without copying.
- `copyUTF8(at:into:)`: compatibility path for APIs that still require a caller-owned scratch buffer.
- `line(forOffset:)` and `offset(forLine:)`: descend by `lineFeeds`.

Piece splitting must never create invalid UTF-8 fragments for inserted text. Original file bytes are accepted only after UTF-8 validation in the load path.

## Mmap Policy

`MMapBuffer` owns a file descriptor, file size, and mapped pointer.

Policy:

- Regular files larger than 1 MB use `open(2)`, `fstat(2)`, `mmap(PROT_READ, MAP_PRIVATE)`.
- Small files can keep the current in-memory rope path until the feature flag flips.
- Empty files create an empty piece tree with no map.
- Non-regular files, failed `mmap`, or invalid UTF-8 fail fast in the piece-tree path and fall back only when explicitly running the rope path.
- The fd may close after a successful map; the mapping lifetime is owned by `MMapBuffer`.
- `munmap` runs in `deinit`.

The original mapping is never mutated. All edits go to add-buffer chunks.

## Save Flow

`ItsyDocument.write(to:ofType:for:)` bypasses `data(ofType:)` when storage is `.pieceTree`.

Flow:

1. Open the destination URL supplied by `NSDocument` with truncation.
2. Iterate pieces in order.
3. Batch contiguous spans into `iovec` entries.
4. Write via `writev(2)` up to `IOV_MAX`, retrying partial writes.
5. Close and surface errors as `CocoaError`.

Original pieces write directly from mmap spans. Add pieces write from append-buffer spans. The whole document is never flattened. The rope path keeps `Data(editor.text.utf8)` until removed.

## Undo Hooks

`PieceTree.replace(_:with:)` returns the edit needed for undo:

```text
Edit {
	range: Range<Int>
	removed: Data
	inserted: Data
}
```

The mutation path copies only removed bytes into the undo payload. Inserts reuse the caller's inserted bytes. Phase 23 replaces `UndoStack` snapshots with byte edits and enforces caps:

- max edit entries
- max retained removed bytes
- grouped edits keep group ids, not full text snapshots

No undo path may call `Editor.text`.

## Tree-Sitter Input

Tree-sitter's C API accepts `TSInput`: a `read` callback, payload, encoding, and optional decoder. The callback returns a borrowed pointer plus byte count and reports EOF with `bytes_read = 0`.

`PieceTreeInput` follows the existing `RopeInput` shape but returns direct spans:

- Map the requested byte offset to the containing piece.
- Return a pointer into the mmap region or add-buffer chunk.
- Set `bytes_read` to the remaining contiguous bytes in that piece, capped if needed.
- Return EOF when the offset equals tree length.

Pointer lifetime:

- The parser receives an immutable `PieceTree` snapshot.
- Original mmap buffers are retained by the snapshot.
- Add buffers are fixed allocated chunks, never Swift `Data` pointers escaping a closure.
- No mutation occurs while `ts_parser_parse` is using the snapshot.

Incremental parsing keeps the existing `Tree.edit(_:)` flow. Each editor edit produces `InputEdit` from old/new byte ranges and line/column points; parser reuse remains legal only after the tree edit exactly matches the storage mutation.

## Grapheme Strategy

Cursor motion and deletion must use Unicode extended grapheme clusters, not scalar or UTF-16 counts. The implementation follows UAX #29.

Plan:

- Add `UAX29GraphemeIterator` over UTF-8 bytes.
- Generate compact grapheme break property tables from the Unicode Character Database.
- Each piece caches grapheme count and boundary metadata.
- The tree summary stores total graphemes.
- `graphemeIndex(forOffset:)` descends by summary, then scans only the target piece.
- `previousCharacterRange(before:)` and `nextCharacterRange(after:)` ask storage for adjacent grapheme boundaries.

Seams matter. A boundary at a piece edge can depend on neighboring bytes, especially for combining marks, regional indicators, and ZWJ emoji sequences. The piece tree must maintain seam state:

- Store periodic grapheme checkpoints per piece or every 4 KB.
- Each checkpoint records enough UAX #29 DFA state to resume scanning.
- On edit, recompute touched pieces and adjacent seams until boundary state converges.
- Selection validation in debug builds rejects offsets that are not grapheme boundaries.

## Integration Order

1. Add `PieceTree` and `MMapBuffer` in `ItsyEditor`.
2. Add tests for load, insert, remove, line lookup, substring, save, and random edit parity.
3. Add `TextStorage` enum behind the feature flag.
4. Route editor mutations through storage.
5. Add `PieceTreeInput` to `ItsySyntax`.
6. Override document read/write for mapped files.
7. Replace public `Editor.text` call sites.
8. Add grapheme conformance and multi-cursor regression suites.
9. Flip piece tree to default only after the bench gates pass.

## Non-Goals

- No CRDT or collaborative editing data model.
- No binary editing.
- No in-place modification of mapped files.
- No default flip until save, undo, syntax, and grapheme suites pass.

## References

- Tree-sitter C API `TSInput`: https://github.com/tree-sitter/tree-sitter/blob/master/lib/include/tree_sitter/api.h
- Tree-sitter parser guide: https://tree-sitter.github.io/tree-sitter/using-parsers/
- Unicode UAX #29: https://www.unicode.org/reports/tr29/
- Crowley, "Data Structures for Text Sequences": https://www.cs.unm.edu/~crowley/papers/sds.pdf
