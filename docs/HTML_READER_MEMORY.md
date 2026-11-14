# HTML reader memory amplification — Tranche 1C.2

## Executive finding

**GO for one local memory correction; NO-GO for completing general preview responsiveness with it.** The original failure is reproducible in both an independently owned HTML document and the actual packaged `MarkdownPreviewPane` constructor, without requesting layout. Both fail at the same JDK `ElementSpec` array copy with a fixed 512 MiB heap. This is production-relevant Swing/JDK behavior, not an artifact of staged construction, CommonMark or the sanitizer. Confidence is high for the recorded Temurin 21.0.12.1 build; other JDK builds and operating systems were not independently qualified.

The correction passes only the requested character range to Swing's existing reader. It prevents the demonstrated repeated whole-code-block copies without changing parsing, Markdown output, source content, element structure or presentation. The original 1 MiB composite now constructs successfully in three fresh packaged-runtime processes. It is **not** a general memory bound or responsiveness guarantee.

A second allocation problem remains: Swing's HTML parser grows some accumulated text arrays in fixed 128-character increments. A 256 KiB single-line code block still allocates about 517 MiB during reading, even with the correction. Large tables, initial layout and global reflow retain the 1C.1 architectural limitations. Park broader stock-HTML optimization pending the shared presentation design. Bounded source highlighting can proceed independently.

Raw measurements, failures, commands, environment and profiles are indexed in [the evidence directory](performance/markdown-tranche1c2/INDEX.md). Previous reports and evidence remain unchanged.

## Reproduction and safety

Each diagnostic child uses Java 21, `-Xms64m -Xmx512m`, an isolated home/temp directory, at most 1 MiB of fixture input and a 45-second watchdog in these runs. Optional JFR recordings are capped at 64 MiB. No heap dumps, heap increases, system-memory stress, dependency installation or user-document edits were used. Timed children ran sequentially, separately from builds/tests. Deliberate OOM reproduction stopped after establishing detached and actual-production relevance; larger stock failures were not pursued.

The exact 1C.1 failure fixture is regenerated from existing table (65,536 bytes), fence (262,144 bytes) and prose (720,896 bytes) generators, followed by the existing same-length p-space padding normalization. Result: 1,048,576 bytes, SHA-256 `2bd8379d31ec16a14bd860a6550dd76fd774045354839709dd2bac5179c46825`; its fenced code literal contains 242,000 characters over 2,000 lines. Generated full preview HTML contains 1,100,701 Java characters. No giant fixture is committed.

The size matrix includes 4 KiB/32-line, 64 KiB/512-line, 128 KiB/1,024-line and 256 KiB/2,048-line code bodies, a 256 KiB single line, escaping/Unicode/tabs/indentation, direct raw `<pre><code>` HTML, 1 MiB prose and a 64 KiB table. Generation parameters and hashes accompany every case. The original input fails before layout; reader-only direct HTML shows the same amplification, independently bypassing Markdown generation and sanitization.

Use [the reproduction commands](performance/markdown-tranche1c2/INDEX.md). Failure is recorded as ERROR with `oom: true`, not successful rendering. Watchdog expiration is TIMEOUT. The script never rescues a failed run with a larger heap. The production route always instantiates the supplied JAR's actual preview, rather than substituting a diagnostic reader.

## Allocation attribution

Inspection used the installed JDK's `lib/src.zip`, with source hashes recorded in `attribution.json`:

| Stage | Evidence |
| --- | --- |
| Source capture / CommonMark / HTML / sanitizer | Complete before failure. Separately labelled replays record allocations and time; they are not additive partitions of the real render. |
| HTML text parsing | `Parser.parseContent` accumulates a text run until markup. Its ordinary-character branch at approximately 2318 allocates `new char[text.length + 128]` and copies the previous buffer. Reader I/O boundaries do not flush this text. |
| PRE handling | `HTMLDocument.HTMLReader.handleText → preContent` (approximately 3855–3861) divides the incoming block at newlines, passing the **entire** character array plus a per-line offset/length to `addContent`. |
| Document construction | `addContent → DefaultStyledDocument.ElementSpec.<init>` (approximately 1281) uses `Arrays.copyOf(txt, txt.length)`. Every small line can therefore retain another whole-block copy in the parse buffer until a flush. |
| Flush | `DefaultStyledDocument.create/insert → ElementSpec.getArray` (approximately 1340) defensively copies the stored array again. This is additional transient allocation. |
| View/layout | Not needed for detached failure. Actual production fails at `JEditorPane.setText → MarkdownPreviewPane.renderNow:154 → refreshAppearance → constructor`, before explicit preferred-size/layout work. The source-view optimization is not involved. |

The stock/counting reader at 64 KiB receives 33,553,935 incoming array characters for only 65,550 requested range characters; at 128 KiB those numbers become 134,216,719 and 131,086. These counters describe callback arguments, not exact allocated or retained bytes. Doubling characters and line count at fixed line width nearly quadruples the full-array traffic. The inspected algorithm is proportional to block characters times line count in this path; pending peak also depends on parse-buffer flushing. It is not a universal formula for every HTML document.

JFR allocation samples independently show `char[]` creation at `ElementSpec.<init>`, `ElementSpec.getArray` and `Parser.parseContent`. The original OOM stack ends at the constructor's `Arrays.copyOf` in both routes. Samples after slicing are dominated by the parser growth path instead. Profile weights span the entire child, including replays/validation, and are **not exact per-stage totals or retained heap**. ThreadMXBean allocation deltas around the reader provide the exact measured-thread traffic reported below.

The remaining fixed-increment parser growth is quadratic for a sufficiently long ordinary text run through that branch: summing successively larger arrays is disproportionate to the final text size. This is supported by source and the 35 / 134 / 522 MiB sliced multiline progression, and the 517 MiB single-line control. Entity handling has a separate geometric-growth branch; escaping can change this behavior. Do not generalize these results to every JDK vendor/release or identify an upstream defect ticket without independent evidence.

## Candidates and production decision

1. **Exact-range reader input — implemented.** A preview-specific `HTMLEditorKit` creates the same stylesheet chain, parser, token threshold (100) and asynchronous-load priority (4). Its full-read `HTMLReader.addContent` override slices only when the incoming array includes unrelated characters. Swing still handles attributes, implied paragraphs, whitespace and element insertion. This fixes both wasteful ElementSpec construction and the later full-array getter copies. It uses protected/public extension points, without reflection or a JDK patch in production. There is no cache, worker, source-document change or new runtime dependency. The full-read `getReader(int)` route is intentional: this is not a general replacement for every incremental `HTMLDocument` insertion API.
2. **Smaller Reader chunks — rejected by source inspection, not shipped.** Swing already consumes an input buffer of up to 256 characters after BODY; `readCh` (approximately 2436) refills that buffer without emitting the accumulated text. Smaller I/O reads cannot bound the offending text array or PRE callback. Injecting tags/comments, changing parser internals, replacing ElementSpec or altering PRE paragraphs would require additional semantic/maintenance risk. No such transformation was introduced.

The first candidate meets the narrow authorization gates: a production failure is established; the change is 40 lines plus one registration; exact content/structure and sampled appearance are tested; memory improvement repeats; read latency improves for the affected case; allocation remains essentially unchanged in prose/single-line controls; no retained metadata is introduced. Its maintenance cost is justified while this preview remains in use. It remains local and removable if the shared presentation replaces HTML. **It does not satisfy a general bounded-memory preview contract.**

## Paired measurements

Same host: MacBookPro15,2, Intel i5-8279U 2.40 GHz, 8 logical CPUs, 8 GiB RAM, macOS 15.7.7, Temurin 21.0.12.1+1-LTS, Retina display. Existing swap usage and variable host load are recorded per child; these are exploratory timings, not clean-machine product qualification. Before JAR SHA-256: `8ff401830509ef3f1f13924a7dde18ba58996f83ccd0f10c8eb6d292cbe401da`; after: `8a07b7575367f5ff607e48049addfda7dc9e573dc364ca22946a269fff74cd7f`.

Three reads per process, zero discarded warmups, requested GC between samples. First-use effects remain in raw data. Show medians, not p95/p99 or cross-process confidence claims. Times include reader/model construction only, unless labelled EDT/constructor; they exclude full layout and paint.

| Workload | Reader ms before → after, median | Allocated MiB before → after, median |
| --- | ---: | ---: |
| 64 KiB multiline code | 100.23 → 26.76 | 162.60 → 34.98 |
| 128 KiB multiline code | 277.99 → 51.48 | 644.88 → 133.64 |
| 1 MiB original composite | OOM → 233.07 | incomplete → 483.61 |
| 1 MiB ordinary prose | 49.21 → 52.80 | 20.47 → 20.47 |
| 64 KiB table | 140.79 → 83.23 | 25.76 → 25.76 |
| 256 KiB single-line code | 168.43 → 171.10 | 517.14 → 517.14 |
| 64 KiB multiline, actual EDT setText | 220.33 → 86.50 | 164.23 → 36.61 |

The table timing movement is not attributed to this change: allocation is unchanged and host/JIT variation is material. No significant prose/single-line latency conclusion is supported by three samples. The matched source/text/tree hashes do establish equal inputs and constructed output.

For 128 KiB code, the maximum sum of heap-pool peaks across construction **and validation** falls from 409.20 to 47.24 MiB; sampled process peak RSS falls from 725.25 to 467.21 MiB. For 64 KiB those bounds are 143.93 → 44.80 MiB. Pool maxima may occur at different times: this is an **upper bound**, not the simultaneous peak heap. RSS includes native/font/JVM memory and prior render initialization, sampled at approximately 100 ms, not a kernel high-water mark. Requested-GC occupancy after releasing the reader model stays around 9.08–9.09 MiB for 128 KiB, and 12.93 MiB for the repaired original composite. This is not a dominator/leak census; local-reference lifetimes and runtime state limit retention conclusions.

The actual packaged preview constructor completes the original composite in **764.12 / 771.04 / 769.55 ms**, with approximately 576 MiB of current-thread allocation and pool-peak sums around 157–161 MiB across those fresh processes. All three use `shed.MarkdownPreviewHtmlKit`, preserve source revision/snapshot/dirty/undo state and match the independently constructed complete code text. No explicit full layout is requested. Production-probe queued callbacks include diagnostic validation/disposal, so their 850–865 ms values are not clean constructor or typing measurements.

The simpler setText experiment queues a callback immediately before the EDT call: median queue wait falls from **235.02 to 86.80 ms**, maxima 371.27 → 104.23 ms. It is an event-queue probe, not physical input-to-paint latency, and remains well above a frame budget. All live Swing mutation stays on EDT. No responsiveness success is claimed from avoiding OOM.

Stage replays, original raw PRE controls, escaping controls, failures and every raw sample are retained. Small/error pilot results are explicitly separated in the evidence index.

## Content, geometry and integration

The new deterministic JUnit coverage compares stock and corrected readers for multiline and single-line code, empty content, EOF and CR/LF behavior, whitespace/indentation, escaping, Unicode/surrogates/combining characters and reader base URLs/settings. Code is independently checked against CommonMark's fenced-block literal. Swing's final implicit document newline is included in PRE extraction rather than trimmed from the expected result.

For representative rendered Markdown, exact document text and element ranges/attributes match stock. Task checkbox models are compared by class/selected/enabled state instead of object identity. At widths 400 and 640, tests require identical preferred size, sampled model/view/hit-test coordinates, complete selection text and a 480-pixel-high RGB crop. Links/anchors, headings, emphasis, lists, tasks, tables, local images, math and sanitized HTML are included. Image data is preloaded identically for differential tests so asynchronous image completion is not mistaken for a layout difference. Repeated setText, relative resource metadata and actual production factory registration are covered. These are sampled visual checks, not full-document pixel or accessibility certification.

The packaged smoke now uses this same preview kit for rich HTML installation/layout, retaining all existing provider-origin checks, valid/repeated/invalid Mermaid cases, math/local-image checks and error fallback. Actual application scroll/caret synchronization, disabled sync, notification geometry, splits, persistence, recovery, source-view and 0A/0B correctness remain covered by the existing unchanged integration suites. There is no new async publication/generation behavior to qualify; the unresolved synchronous architecture remains explicit.

## Changes and verification

Production: `MarkdownPreviewHtmlKit.java` (new range-copy boundary); `MarkdownPreviewPane.java:67` (register it). Diagnostic/test: `MarkdownPreviewHtmlKitTest.java`, `HtmlReaderMemoryProbe.java`, `PackagedMarkdownSmoke.java`, `benchmark-html-reader.py`, `test_benchmark_html_reader.py`. Documentation/evidence: this report and `docs/performance/markdown-tranche1c2/`.

The first focused run exposed a test helper comparing independent checkbox models by identity; the test now checks their semantic state. A prior probe pilot incorrectly excluded Swing's terminal implicit newline; extraction was corrected, without weakening the exact CommonMark-literal comparison. The first full suite also found that mistake in the subsequently added production-registration test; its assertion now checks the complete PRE span exactly. These failures are retained rather than hidden. No existing assertion or production behavior was weakened.

Final verification: **1,324 Maven cases, 1,319 passed, five existing skips, zero failures/errors**. The focused reader/preview run passes all eight cases; Python tooling passes all nine tests; the independent packaged smoke passes, using only the shaded distribution and probe classes. Existing skips cover platform/tool availability and opt-in AI benchmarks (details in `verification.json`). The first full run had 1,318 passes, one new-test helper error and the same five skips; the corrected full repeat is green. Exact commands, suite counts, skip reasons and logs are preserved in `verification.json`, `verification-logs.json.gz` and the evidence index. `git diff --check` passes.

No UI components, fonts, themes, geometry policies, navigation/keymaps, source ownership, persistence, caches, background workers or dependencies were changed. Only the existing preview reader's array input changes. The packaged artifact adds three small class files, changes `MarkdownPreviewPane.class`, removes nothing and preserves every other archive entry's bytes, including service metadata; size increases by **2,531 bytes**. No eager renderer initialization is added. Startup latency was not requalified; unchanged initialization policy is an inspection result, not a measured startup claim. Notebook and other generic HTML surfaces are intentionally unchanged and can retain the same upstream risk.

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Previous 1C/1C.1 evidence and 1B source optimization are byte-preserved. Git HEAD advanced through externally created commits during this work; no commit/push/reset/clean/revert command was issued by this session. Verification compares task-start file hashes independently of that movement.

## Architectural disposition and next handoff

Retain the existing HTML preview as a **legacy presentation with documented practical limits**, with this one local correction. It has no newly enforced content cutoff and still cannot guarantee arbitrary-size memory or responsive layout. Do not silently truncate code or disable ordinary previews. Keep 1C.1's NO-GO for general stock-HTML responsiveness and CONDITIONAL GO for staging as a building block. The 1C duplicate-initial-render correction remains complete; asynchronous responsive presentation, bounded rich assets and safe large-table/global reflow remain unresolved. **Tranche 1C is incomplete and should be parked, not declared successful.**

Do not continue polishing JDK parser internals here. Coordinate any replacement/virtualized presentation with the shared Markdown semantic model and planned source-preserving live editor, avoiding an independent preview-only renderer. Reuse existing image path/security handling and math/Mermaid rasterization as asset providers behind bounded lifecycle ownership; this correction neither implements inline media nor solves GraalJS initialization/EDT execution.

**Smallest next implementation: Tranche 1D, bounded Markdown highlighting, independently.** Inspect `GrammarHighlightService.highlightRange` (around 93), its Markdown fallback/lexer, and `SyntaxUiController.highlightSnapshot` (around 557). Reproduce beginning/middle/end production viewport requests using the existing fixture/performance harness before editing. Limit scope to range/state reuse, cancellation and stale-highlight rejection; keep source snapshots, colors, Markdown semantics and preview untouched. Define an explicit state/cache budget and correct invalidation after edits, file/font switches and pane disposal. Risks are fences/inline state across viewport boundaries, stale offsets and retained snapshots. Acceptance: viewport output matches the existing lexer for supported syntax; work and emitted tokens stop scaling with the entire preceding prefix on warm viewport requests; p95 convergence and allocation improve in paired 100 KiB/1/10 MiB runs; all 0A/0B/preview/source-view tests remain green. Do not begin this work automatically.

Any stronger preview responsiveness claim requires a separately reviewed shared-presentation tranche, including tables, resize, complete geometry/navigation, accessibility, rich assets and lifecycle bounds. The memory mitigation is useful containment, not evidence that those decision gates have changed.
