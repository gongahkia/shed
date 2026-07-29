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

## 2026-06-29 idle memory update

- Default glyph atlas size is now `1024x1024` instead of `2048x2048`.
- [Inference] On Retina/grayscale this reduces the initial glyph atlas texture from about 4 MB to 1 MB; on non-Retina/subpixel it reduces the page from about 16 MB to 4 MB.
- Shaped-line cache cap is now 512 visible-line entries instead of 2048 to reduce retained CoreText-derived glyph arrays after large-file scrolling.

## 2026-06-29 idle VM breakdown

Command shape:

```sh
.build/release/ItsyApp &
.build/release/ItsyBench rss --pid <pid>
vmmap -summary <pid>
```

Observed RSS from `ItsyBench`: `92176 KB`. `vmmap -summary` reported physical footprint `96.5M`.

Largest resident contributors:

| Region | Resident |
|---|---:|
| owned unmapped graphics | 48.8M |
| `__OBJC_RO` | 48.1M |
| `__AUTH_CONST` | 39.0M |
| IOSurface | 19.2M |
| `__DATA_CONST` | 17.2M |
| MALLOC zones total | 14.8M |
| `__LINKEDIT` | 10.7M |
| `__DATA` | 8790K |

[Inference] The idle-RSS miss is dominated by system/AppKit/Metal mapped regions and graphics surfaces, not app-owned heap alone. Reducing app heap allocations will not by itself close the `<30 MB` RSS target.

## 2026-06-29 reproducible idle memory audit

Command:

```sh
ITSY_MEMORY_DATE=2026-06-29-current bench/scripts/memory_audit.sh
```

Committed reports: `bench/results/memory-2026-06-29-current.json` and `bench/results/memory-2026-06-29-current.md`.

Latest local output:

- RSS: `92016 KB`
- Physical footprint: `98611 KB`

Largest resident rows:

| Region | Resident KB |
|---|---:|
| `__TEXT` | 388403 |
| `__OBJC_RO` | 57754 |
| owned unmapped graphics | 49971 |
| `__AUTH_CONST` | 44134 |
| `__LINKEDIT` | 19866 |
| IOSurface | 19661 |
| `__DATA_CONST` | 18739 |
| mapped file | 15770 |
| MALLOC_SMALL | 13722 |
| `__DATA` | 9654 |

[Inference] The automated run confirms the `<30 MB` RSS target is still missed by about 62 MB on this machine. The top rows point first to system/library mappings and graphics surfaces; follow-up work should measure feature-stripped launch variants before removing editor-owned data structures.
