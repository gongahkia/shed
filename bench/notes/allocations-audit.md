# Allocations audit 2026-06-29

## Command

```sh
swift build -c release
bench/scripts/make_app.sh
codesign --force --sign - --entitlements /tmp/itsy-debug-entitlements.plist Itsy.app
xcrun xctrace record --quiet --no-prompt --template 'Allocations' --time-limit 10s --output bench/traces/itsy-allocations-2026-06-29.trace --launch -- Itsy.app bench/corpus/large.ts
```

During the trace, `System Events` sent 40 Down-arrow key events, inserted `x`, then sent 20 Up-arrow key events. The trace is local and gitignored at `bench/traces/itsy-allocations-2026-06-29.trace`.

## Result

Trace summary:

```text
template: Allocations
duration: 10.848151 s
target: Itsy bench/corpus/large.ts
end: time limit reached
```

Top transient allocation categories from the exported Statistics table:

| Category | Transient bytes | Transient count | Persistent bytes | Persistent count |
|---|---:|---:|---:|---:|
| All Heap Allocations | 484,891,584 | 2,485,556 | 508,492,048 | 3,931,711 |
| _ContiguousArrayStorage<HighlightSpan> | 182,788,096 | 22 | 64,012,288 | 1 |
| _ContiguousArrayStorage<Substring> | 122,097,984 | 1,557,329 | 64 | 1 |
| _ContiguousArrayStorage<TextHighlightSpan> | 71,311,360 | 20 | 74,317,824 | 1 |
| _ContiguousArrayStorage<String> | 52,327,456 | 856,540 | 112 | 2 |

## Fix

Render-path changes in `MetalTextView`:

- Reuse one `MTLRenderPassDescriptor` instead of allocating one per frame.
- Reuse one large `MTLBuffer` for instance uploads instead of allocating a buffer per text frame.
- Reuse scratch arrays for text and solid instances.
- Cache shaped visible-line glyph data by line range, rendering mode, and highlight revision. Cursor blink and repaint over already-shaped visible lines now reuse cached CoreText output.

Remaining allocation hotspot is initial full-file syntax highlight materialization for `large.ts`, not instance upload. The largest app-owned categories are `HighlightSpan`, `TextHighlightSpan`, `Substring`, and `String` storage produced while parsing/highlighting the 100k-line file and handling the edit.
