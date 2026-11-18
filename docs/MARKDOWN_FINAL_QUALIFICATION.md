# Markdown Tranche 7A — performance, memory and reliability qualification

## A. Executive verdict

**Not yet qualified for the intended fast, low-memory experience.** Ordinary ASCII Markdown source editing, bounded navigation/indexing, checked edits and asynchronous media work performed well in the measured fixtures. Three concrete issues need separate remediation: complex-script/Unicode source layout can stall the EDT for seconds, the stock HTML preview still blocks source input, and closed buffers remain reachable through completion-index entries and completed-job callbacks.

No data-loss defect was observed. Passing regression tests is evidence for the tested contracts, not full product qualification. System-wide low-memory usability and Windows/Linux desktop behavior remain unqualified. Tranche 4A remains a production **NO-GO**; optional 5C.2 remains skipped; 1C is still incomplete. This tranche implements no remediation.

Two explicit user requests during this audit authorize a narrow exception to its production-change prohibition: the default editor font is now **JetBrains Mono**, and the default theme is **Shed Light**, including fresh headful test homes. Existing explicit configuration choices remain honored. The regular font and its SIL Open Font License are bundled; no system-font installation is required. Terminal and interface font defaults are unchanged. Original audit results and post-default-change checks are separate artifact epochs throughout this report.

## B. Environment and methodology

November 18, 2026, macOS 26.5.2, Mac15,12 / Apple M3, eight CPUs, 16 GiB RAM, 10-core GPU; built-in 2560×1664 Retina display plus external 1920×1080 display. OpenJDK Temurin 21.0.13+9 and Maven 3.9.11 were resolved using the existing repository tooling. The desktop probe reports a 2× graphics transform. Host load, swap, pressure, display and VM counters accompany every case. One initial observation had load 2.59/3.46/4.01, 61% system memory free and 916 MiB of pre-existing swap use. This was an occupied developer machine; unrelated applications were not closed.

The original packaged JAR was frozen outside the checkout before the defaults changed. Its SHA-256, subsequent JAR identity, start/end HEAD and baseline file hashes are in [verification.json](performance/markdown-tranche7a/verification.json). All original benchmark reports identify the actual JAR hash. The initial checkout included previous 5B/5C.1 work; externally introduced commits subsequently incorporated that work and the defaults. They were preserved. This agent made no commits or pushes.

The original `benchmark-editor.py`, Markdown runner, fixture generators and probes were reused without changing their measurement methodology. Additional opt-in probes use actual Shed components and existing operations, isolated homes, disposable fixtures, bounded child lifetimes and no personal documents. Most controlled children use `-Xms64m -Xmx512m`; low-heap children use 128 MiB. Actual `-jar` launcher controls also run with **no heap flags**, matching `scripts/shed`. Plain launcher observations last seven seconds and then terminate only their own child; diagnostic children have a 120-second watchdog. No system-wide pressure experiment was run.

Definitions matter:

- Startup markers measure fresh-process launch to visibility, explicitly forced source paint, accepted programmed input, and full-workbench forced paint. These are warm filesystem-cache launches, not cold-disk measurements or display scanout measurements.
- Editing `input_to_real_paint_ms` includes the queued EDT operation through a subsequently attributed real Swing paint. Mutation/dispatch, worker time, queue delay, paint and convergence are separately recorded. No claim is made about physical keyboard-to-photon latency.
- Startup has three fresh processes per scenario. Source-edit distributions generally have 100 samples/10 warmups; adverse fixtures 30/3; insertion/keyboard 50/5; subtree/navigation 30/3. Preview diagnostic refresh/resize counts are only five/three: their reported nearest-rank p95 is effectively the observed maximum, not a population estimate.
- Sampled peak RSS is a 250 ms process observation, which may miss short peaks. A last-second observation is explicitly different from the peak. Used heap, committed heap, allocation samples, requested-GC observations and reachable objects are distinct quantities.
- NMT, JFR, `jcmd` histograms, requested GC and `vmmap` are intrusive diagnostics, with uninstrumented controls kept separate. NMT is not complete RSS accounting and does not cover all JDK/native-library allocations. See the [JDK 21 NMT documentation](https://docs.oracle.com/en/java/javase/21/vm/native-memory-tracking.html).

The new CLI rejects an existing output directory and repository-local outputs. Its generated fixtures total under 12 MiB per admission test. Heavy workloads are opt-in, outside normal Maven execution. Raw reports, commands, failures and diagnostics are indexed under [performance evidence](performance/markdown-tranche7a/INDEX.md).

## C. Startup results

The existing desktop budgets remain **visible ≤2 s, painted source ≤3 s, full UI ≤10 s**. No threshold was changed. The original three-run desktop harness passed its diagnostic budgets. The original extended startup matrix gave these ranges, in milliseconds from child launch (three processes per row):

| Original artifact / configuration | Visible | Source paint | Input accepted | Full UI |
|---|---:|---:|---:|---:|
| Empty | 464–508 | 495–559 | 514–589 | 1790–2005 |
| Small Java source | 468–533 | 539–629 | 553–656 | 1808–2058 |
| Small Markdown | 473–521 | 559–618 | 579–666 | 1810–2042 |
| 1 MiB plain Markdown | 467–517 | 613–761 | 642–818 | 1843–1988 |
| Explicit Dialog font, small Markdown | 1716–1827 | 1804–1943 | 1832–1994 | 1850–2028 |

The new **JetBrains Mono / Shed Light** packaged artifact was measured separately:

| New defaults / configuration | Visible | Source paint | Input accepted | Full UI |
|---|---:|---:|---:|---:|
| Empty | 1648–1679 | 1842–1968 | 1852–1986 | 1856–1991 |
| Small Java source | 1653–1707 | 1887–2062 | 1910–2080 | 1916–2087 |
| Small Markdown | 1627–1769 | 1940–2075 | 1966–2104 | 1972–2110 |
| 1 MiB plain Markdown | 1652–1853 | 1963–2242 | 1990–2285 | 1995–2296 |
| Explicit Dialog font, small Markdown | 1755–1931 | 1822–2041 | 1834–2079 | 1844–2107 |

All those observed runs meet the startup gates, but the new defaults consume substantially more of the visibility budget. This is a measured tradeoff, not startup equivalence. [Inference] Early physical-font initialization contributes; the font and theme changed together and these runs do not isolate their individual causal contributions. First preview and Mermaid initialization are separate costs below. The Unicode workload fails source/input readiness despite an initially visible window, so startup is not qualified for all supported content.

## D. Memory attribution

### Comparable original workbench controls

| Original artifact, one fresh process per cell | Plain `-jar` sampled peak RSS MiB, 512 MiB heap | Probe used / committed heap MiB after requested GC |
|---|---:|---:|
| Empty | 641.8 | 11.8 / 64 |
| Small Java source | 649.5 | 13.3 / 64 |
| 4 KiB Markdown | 654.0 | 12.8 / 64 |
| 100 KiB Markdown | 656.9 | 14.5 / 64 |
| 1 MiB Markdown | 675.8 | 23.0 / 66 |
| 10 MiB Markdown | 850.1 | 70.7 / 218 |

These columns are separate processes and quantities; they cannot be subtracted to compute native memory. Before requested GC the 10 MiB probe used about 158.8 MiB heap. The no-heap-flags original launcher controls observed about 603.4 MiB empty and 676.0 MiB with 1 MiB Markdown; its NMT empty control had 12.8 MiB used / 48 MiB committed heap. Startup or active peaks must not be presented as a stable leak slope.

The separate new-default, no-heap-flags controls observed 615.4 MiB empty / 694.6 MiB with 1 MiB Markdown sampled peak RSS; its NMT empty control used 13.37 MiB heap with 48 MiB committed. These single-process observations do not establish memory equivalence or a statistically significant increase.

### Fixed font, graphics and JVM costs

The JDK-only controls do not construct Shed, parse Markdown, start indexes or initialize Mermaid. They deliberately reverse font-catalogue and drawing order:

- Catalogue-first process: roughly 53 MiB RSS before fonts, 530 MiB after enumeration, 533 MiB after a small text raster. Used heap increased only from about 1.1 to 6.6 MiB.
- Drawing-first process: roughly 52 MiB before fonts, 535 MiB after a tiny text draw; later catalogue enumeration changed little. Therefore simply removing Shed's explicit catalogue warming has **not** been established as a solution.
- This host exposes approximately 535 font families and 3,430 native font-face objects. In catalogue control `vmmap` private physical footprint increased about 30.8→153.4 MiB, `MALLOC_SMALL` about 6.7→101.1 MiB, and resident read-only library mappings about 256.7→356.1 MiB. NMT committed increased only about 12 MiB.

These observations attribute much of the fixed RSS jump to JDK/macOS font initialization and shared/native mappings rather than Markdown caches. RSS, `vmmap` physical footprint and resident category totals have different definitions and timestamps; those numbers are not additive. Some process memory remains unattributed. The evidence does not establish a general JVM leak or justify a runtime rewrite.

An instrumented empty workbench showed approximately 264.8 MiB NMT committed: 64 MiB Java heap, 3.5 MiB Class, 21 MiB Metaspace, 80.7 MiB Thread, 11.8 MiB Code and 49.9 MiB GC among the categories. Committed thread stacks are not measured resident stack pages. `vmmap` additionally exposed significant font/native malloc and graphics mappings, including approximately 31.7 MiB IOSurface and 8.6 MiB CoreGraphics image residency in that capture. Shared library residency remains material. Direct-buffer pools were small in idle cases; no large mapped-file pool was observed there.

### Workload-dependent retention and disposal

The original 1,001-file index fixture retained a bounded project model and disposed its service successfully. A separate diagnostic **1,002-file, two-links-per-note** fixture increased requested-GC used heap 22.87→24.66 MiB, then 23.20 MiB after disposal, with the index weak reference collected. This smaller-link-density diagnostic is not a replacement for the established 1,001-file benchmark.

Matched NMT+JFR rich-media runs peaked at **1269.3 MiB service / 1279.2 MiB legacy renderer control**. Used/committed heap was approximately 23.8/73 MiB initially, 68.3/237 MiB retained, and 50.0/170 MiB after service disposal. The renderer control ended around 51.3/177 MiB. The service reported zero resident assets, queued work, running work and sessions after close, and the existing weak-reference/worker checks passed. GraalJS/Batik classes, font caches, JIT code and JVM capacity survive renderer use; JFR also contributes tracing memory. These results do not show a service-specific native leak, nor prove repeated arbitrary media workloads are safe. Residual RSS remains a qualification concern.

### Confirmed closed-buffer retention

Ten picker and preview cycles released their observed picker objects. Closing source buffers did not release them: read-only root inspection found `InputController.openBufferCompletionIndex.snapshots` holding closed `FileBuffer` keys and snapshot text. The existing removal helper has no close-path caller.

A causal control opened, indexed and closed eight separate 1 MiB copies while leaving one buffer open. Requested-GC used heap rose **23.03→97.08 MiB**, and all eight closed buffers remained reachable. A normal existing completion query pruned the index from ten entries to one, but all eight buffers still remained reachable at 98.29 MiB. The second root was:

```text
editor.asyncJobService.jobs.value[…].cancelledBeforeStart.arg$2.arg$4
```

After 201 bounded no-op jobs exercised the existing 200-entry history eviction, all eight weak references collected; used heap fell to 57.87 MiB. This establishes two distinct owners: completion snapshots persist until a later query, and completed-job cancellation closures persist until history eviction. The remaining heap is not claimed to equal eight-buffer retention exactly. The diagnostic never cleared application fields or implemented a cleanup fix. Closure retention is history-bounded; the snapshot map has no corresponding close-time bound demonstrated by this audit.

## E. Editing responsiveness

Original artifact, source-only mixed ASCII Markdown, 100 measured operations each, completed Swing paint p95:

| Operation | 100 KiB | 1 MiB | 10 MiB |
|---|---:|---:|---:|
| Insert | 1.50 ms | 1.55 ms | 2.47 ms |
| Delete | 1.19 ms | 1.08 ms | 0.98 ms |
| Newline | 1.16 ms | 1.32 ms | 1.27 ms |

The new default-font/light artifact measured insert p95 **1.62 ms / 2.07 ms** for 100 KiB / 1 MiB (100 samples each). It remains below 16 ms in those workloads. This is not a claim about Unicode first use or preview-active editing.

At 1 MiB, numerous headings, tables and fenced-code source had insert p95 2.24, 2.62 and 4.48 ms respectively (30 samples). The long-line fixture failed the 16 ms objective: insert/Delete/newline p95 about **33.55/33.62/32.70 ms**. It contains exceptionally long lines, unlike the normal mixed fixture. The 10 MiB normal fixture remained editable under the configured policy; this does not qualify 50–100 MiB editing. Semantic caps remain 1 Mi UTF-16 units, 64 Ki line length, 50,000 lines and other existing node/span limits.

Caret movement, selections, scrolling and shared-split operations were exercised by the existing UI probe. For example, the 10 MiB fixture's EDT distant-caret/selection/scroll p95 was 1.04/0.76/0.77 ms; these are dispatch timings, not end-to-end display scanout. The 1 MiB search control recorded literal/regex **convergence** maxima 110/122 ms (three samples), not 110 ms EDT keystrokes; searches already use existing workers.

Keyboard, selection insertion and subtree controls preserved their exact-source/undo checks. At 1 MiB, quote/list Enter completed-paint p95 was 2.19/2.19 ms; opening-fence completion 1.57 ms; selected link/image insertion 1.75/2.44 ms. Small/wide subtree edits had p95 3.57/14.03 ms, with separately measured worker semantic preparation 92.7/283.3 ms and pure planning 1.05/1.91 ms. These explicit commands are not ordinary typing. Existing edit limits, one-step undo and rejection contracts remain unchanged.

### Unicode is a release-relevant counterexample

The unchanged 1 MiB Unicode benchmark showed roughly **45 seconds before source forced paint**, followed by an EDT first edit of **12.29 seconds**, about 65.95 GB of cumulative thread allocation, and no attributed completed paint within its five-second observation window. Its eventual benchmark failure was an unrelated harness assertion equating UTF-8 file bytes with UTF-16 document length; that assertion is invalid for Unicode and is not evidence of corruption. The failure is preserved, not counted as a passing performance run.

A new read-only measurement adapter reused the identical startup path and validated actual UTF-16 content plus exact Undo. It reproduced a roughly **39-second source installation and 13.58-second first edit**, with 65.86 GB cumulative allocation, in the 1 MiB case. Later 30-sample insert p95 was 14.57 ms, which must not hide that first-use stall. The 100 KiB Unicode control also had a 407 ms initial edit and 930 ms subsequent edit before the latency guard stopped repetition. The profiling variant was slower. Source and undo content were exact.

The new default-font/light artifact also reproduced the issue: 1 MiB source paint arrived 16.46 seconds after launch and its first edit took **10.55 seconds** through completed paint, allocating 64.72 GB cumulatively on the EDT. Its later 30-sample typing p95 was 5.60 ms. At 100 KiB the first edit was 838 ms and warmed typing p95 15.39 ms. Content and Undo were exact in both cases. The changed defaults therefore do not remove this blocker.

The active view was stock `javax.swing.text.BoxView`, whereas normal ASCII used `shed.SourcePlainView`. The optimized adapter explicitly only wraps stock `PlainView`; Swing's complex-text/i18n path takes another hierarchy. The 100 KiB JFR control contains 103 EDT samples with `FlowView`, 110 with `ParagraphView`, and 65 with `SunLayoutEngine` (overlapping stack-presence counts, not CPU percentages). Stacks include native glyph bounds, `LineBreakMeasurer`, `TextLayoutStrategy.sync` and font-triggered view rebuilding during `finishStagedStartup`. Weighted allocation samples prominently include character, integer and float arrays; they measure allocation, not retention. [Inference] Complex-text view/layout rebuilding explains much of the divergence; the exact first-edit and font/shape boundary still needs targeted attribution before choosing a correction. Disabling i18n, bidi, shaping or Unicode handling is not an acceptable workaround. Neither is resurrecting the rejected live-presentation prototype.

## F. Background-work impact

The existing index fixture built 1,001 files, explicitly exposed COMPLETE status, rejected a stale generation and collected the service after disposal. Repeated outgoing/backlink query p95 was **0.007/0.006 ms**. Cold builder p95 was 572 ms (three builds), unchanged refresh 54.1 ms and single-file refresh 54.5 ms (20 each). These measurements retain the original fixture and methodology; they do not imply arbitrary project sizes are inexpensive.

Index-active typing p95 was 1.04 ms, but only **one** same-project sample truly overlapped the worker because editing invalidated that generation. An independent-project control had **50/50** actual overlap samples and typing p95 2.24 ms. Navigation indexing similarly had one observed overlap sample. Do not claim its entire nominal active sample set measures simultaneous indexing.

Semantic-consumer outstanding-job typing p95 was 1.59 ms at 1 MiB. Coordinator metadata reported reuse, stale rejection and exact source restoration. It does not establish that every sample overlapped CPU-intensive parsing. Lexical controls retained viewport work and bounded checkpoint invalidation; adverse unmatched-fence convergence can take longer in a worker (10 MiB fixture approximately 39 ms p95) without being equated with EDT mutation time. No semantic cap, queue budget or highlighting architecture was changed.

The asynchronous media control had **50/50** samples overlapping real queued/running work: source typing p95 2.65 ms versus 2.08 ms inactive. Peak queue depth was eight; eight excess requests were explicitly rejected, duplicate work coalesced, and one stale result discarded. Cold Mermaid result latency was 3.62 seconds (one first-use observation); changed warm diagrams p95 1.08 seconds (ten), cached result p95 0.208 ms (50). Image first result was 7.20 ms, queued math result p95 94.8 ms (eight). Request-to-result includes scheduling; these are not isolated renderer execution times or a cached-versus-cold speedup comparison. No new service or concurrency expansion was introduced.

Follow-link EDT dispatch p95 was 0.218 ms, validation worker 1.28 ms. Outgoing/backlink picker EDT population p95 was 1.54/1.20 ms; 100 opened picker models had zero surviving weak references. Navigation source revision delta was zero. Focus, keymaps, stale requests, unsaved overlays and split behavior have regression coverage; these diagnostic runs do not substitute for manual cross-platform interaction qualification.

## G. Preview qualification

**Fails responsive-source-input qualification while preview work executes.** These measurements call the existing production preview, then enqueue a real source insertion plus Undo behind it. The source was restored exactly. No replacement renderer or preview pipeline was introduced.

| Workload | Open EDT / queued source delay | Refresh EDT p95 / queued source delay p95 | Resize queued source delay p95 |
|---|---:|---:|---:|
| Plain 100 KiB | 252 / 253 ms | 67.7 / 72.4 ms | 48.8 ms |
| Plain 1 MiB | 826 / 826 ms | 453.6 / 542.5 ms | 379.9 ms |
| Table 100 KiB | 619 / 619 ms | 342.4 / 353.1 ms | 7.3 ms |
| Fence 100 KiB | 192 / 193 ms | 49.5 / 61.0 ms | 17.4 ms |
| Mixed rich media 4 KiB | 2077 / 2077 ms | 26.6 / 31.8 ms | 12.8 ms |

Resize method dispatch alone was sometimes only 3–8 ms while queued input waited hundreds of milliseconds for subsequent layout. Reporting only the short method would conceal the bottleneck. First-use Mermaid is especially costly even for small source; warm refresh differs materially. These are observed sequences, not a measurement of natural user-session stall frequency.

This is a **P1 limitation** for an unrestricted responsiveness claim. A source-only release claim can explicitly exclude synchronous preview work; restricting existing preview availability or changing its supported limit requires a separate product decision. No such restriction was implemented here. The 3B service being asynchronous does not make current stock HTML layout asynchronous. Tranche 1C remains incomplete.

## H. Reliability and verification

The original full suite ran **1,540 tests: 1,535 passed, five skipped, zero failures/errors**. After the requested defaults and two new font tests, the final complete suite ran **1,542 tests: 1,537 passed, five skipped, zero failures/errors**. Individual suite/skip details are in [regression-suites.json](performance/markdown-tranche7a/regression-suites.json); original and intermediate logs are retained.

Verification commands (resolved toolchain paths are in the raw command evidence):

```sh
source /tmp/shed-tranche2c-run/toolchain.env
"$MVN" test
"$MVN" -DskipTests package
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --output /tmp/shed-tranche7a-run/packaged-smoke.json
git diff --check
shasum -a 256 README.md
```

The focused defaults verification passed 65 tests. The final tooling discovery passed **19 tests**; the packaged-only Mermaid/math/image smoke **passed**. Outcomes and exact invocations are preserved in the evidence index. Packaged smoke uses only the JAR plus an independently compiled probe: Mermaid runtime, math and local image behavior cannot be rescued by an ambient development classpath.

Intermediate verification was not uniformly clean. One font-transition full run had an obsolete Monospaced assertion and a buffer-picker timeout; its focused retry passed. The first light-default full run exposed three assertions that assumed dialog floating surfaces equal command-bar surfaces, which happened to hold in the old default dark palette. Assertions now compare the existing floating-surface contract; production dialog styling was not changed. The final full run passed. Those failures and the transient timeout remain visible in the archived logs.

The suite covers checked edits, semantic subtrees, fences/quotes/lists, source ownership, undo, BOM/encoding/line endings, recovery, persistence, split views, Plain/Vim/Emacs routing, navigation/overlays and media lifecycle. No new language-server installation or live external LSP qualification was attempted; fixture metadata reported zero attached clients. Native IME, accessibility, all Unicode shaping combinations, real hardware input and Windows/Linux headful behavior remain unqualified.

The bounded 128 MiB heap control completed 1 MiB edit/Undo, recovery, save and reopen. The 10 MiB control exhausted heap while the **probe retained several full snapshots/strings and constructed a second `FileBuffer` for verification**; its AWT process subsequently hit the watchdog. The saved file was independently verified byte-exact against the intended edit. This is not proof that ordinary single-buffer 10 MiB editing necessarily fails, nor a passing low-memory qualification. A cleaner ownership-controlled follow-up is necessary. No intentional system-RAM or swap exhaustion was attempted.

## I. Product capability matrix

| Capability | Status | Scope / qualification |
|---|---|---|
| Fast ordinary Markdown source editing | **Fails overall; meets tested ordinary ASCII fixtures** | Unicode first use and very long lines fail the budget |
| Low idle memory | **Inconclusive** | Small-file RSS fits the historical 768 MiB diagnostic ceiling; a low-memory product target needs explicit settled/private-memory gates |
| Low-memory-pressure usability | **Inconclusive** | 128 MiB Java-heap 1 MiB persistence passes; this is not genuinely low system RAM |
| Responsive Markdown preview | **Fails** | Measured hundreds of milliseconds of source-input delay, seconds on rich first use |
| Markdown editing correctness | **Meets tested regression contracts** | Not a universal Unicode/IME/platform certification |
| Wiki-links and backlinks | **Meets bounded tested scope** | Revision-safe overlays, explicit completeness, bounded project fixture |
| Mermaid, images and math in preview | **Meets functional tests** | Preview execution latency still fails the responsiveness claim |
| Inline rich-media editing | **Deferred / unsupported** | Infrastructure is not editor presentation |
| Source-preserving live Markdown typography | **Deferred / unsupported** | 4A production migration remains NO-GO |
| 50–100 MiB editable documents | **Deferred / unsupported** | Existing large-file projection is read-only; 10 MiB measured editing is not evidence for this range |
| Cross-platform qualification | **Inconclusive** | macOS/JDK 21 desktop and packaged JAR measured here; Windows/Linux desktop gates outstanding |

## J. Ranked remediation register

No observed P0 data-loss/corruption issue. All proposed work below is **unimplemented**.

| Priority / finding | Evidence, impact and uncertainty | Smallest next action / files | Gates, risk, complexity and release decision |
|---|---|---|---|
| **P1 — closed-buffer source retention** | Eight closed copies, ~74 MiB heap growth, two demonstrated root paths; not unexplained RSS | Invoke existing word-index removal at every real close path in `PaneBufferController` / `InputController`; retire finished `AsyncJobService.JobRecord.cancelledBeforeStart` callbacks with race-safe lifecycle handling; test `OpenBufferCompletionIndex` | Small independent cleanup within existing architecture. Zero closed-buffer/index reachability after idle GC without a later completion query or 201 unrelated jobs; cancellation exactly once; preserve undo/recovery/open splits. Block low-memory qualification and appearance work until verified. |
| **P1 — complex-text source layout stalls** | 100 KiB and 1 MiB Unicode counterexamples, BoxView fallback, enormous transient allocation; JDK layout attribution needs narrowing | Profile the existing source view factory, `SourcePlainView`, `EditorUiController` and Swing complex-text path on smaller reproductions; time-box a source-view-specific correction only if shaping/caret correctness can be retained | Medium/high uncertainty. Unicode first edit and completed paint must meet explicit supported-size gates, no i18n/bidi/IME disablement, no editor replacement. Stop if a broad architecture rewrite is required. Blocks an unrestricted responsiveness claim. |
| **P1 — synchronous preview input stalls** | Measured open/refresh/reflow/input delay on plain/table/rich fixtures; known 1C limitation | Separate product decision on supported operating limits; only then evaluate narrowly scoped work in `MarkdownPreviewPane`, `MarkdownPreviewRenderer`, `MarkdownPreviewAssets` against exact phases | High uncertainty for layout; 3B alone cannot solve it. No promised speedup, renderer replacement or `setText` off the EDT. Blocks a claim of responsive source editing with unrestricted preview active. |
| **P2 — long-line editing** | ~33 ms insert/Delete/newline p95 on the 1 MiB long-line fixture | Isolate visible-line painting/shaping vs width-summary invalidation in existing source view/probe, with exact long-line limits | Bounded diagnostic/fix only; preserve tabs, selection and source geometry. 16 ms remains the target; document any product-approved exception. Does not justify large-file engine work. |
| **P2 — constrained-memory coverage** | 128 MiB/10 MiB diagnostic OOM with simultaneous verification copies; no observed bad save | Repeat single-buffer operation, release old references before reopen, independently verify disk/recovery in a child with a watchdog | Small tooling follow-up first; do not change production or JVM defaults on this evidence alone. System-pressure usability remains unqualified; no disruptive stress. |
| **P2 — fixed font/native footprint and tighter startup margin** | JDK-only font controls explain large fixed RSS; new bundled-font defaults increase measured visibility time while meeting observed gates | Reproduce on a cleaner font catalogue and second supported JDK/host; separate font initialization, graphics footprint and new-default startup attribution | Diagnostic work first; no JVM migration or removal of Unicode fallback on RSS alone. Do not label residual memory a leak. Maintain 2/3/10 s startup gates. |
| **P3 — deferred rich editing and appearance** | Not delivered, deliberately gated | Keep 4A/4B, inline media, huge-file editing, optional table navigation and appearance features deferred | No remediation disguised as new features. Reconsider only with new measured evidence and authorization. |

## K. Tranche 7B proposal

Authorize independent fixes rather than an optimization campaign:

1. **7B.1 — closed-buffer and completed-callback cleanup.** Touch only close-path cleanup and existing job lifecycle ownership, plus deterministic tests and the opt-in retention probe. Exercise all close paths, shared splits, cancellation before start/during execution, failed/rejected jobs, document switching and shutdown. No new cache, service or document model. Confirm the two roots independently disappear without relying on later user activity.
2. **7B.2 — complex-text source-layout diagnosis and bounded correction gate.** First make a minimal deterministic mixed-script reproducer with UTF-16 assertions, stack/allocation evidence and first-use vs warmed controls. Only implement a fix if it preserves Swing shaping, selection, bidi and IME behavior in the existing editor. Stop and report if that cannot be achieved narrowly; the failed live-editor prototype is not an alternative.
3. **7B.3 — preview disposition decision.** Present exact operating-limit and compatibility tradeoffs for product approval before restricting behavior. A separate measured adapter experiment may reuse 3B for media, but must not claim HTML layout is fixed. No unapproved renderer migration.

Long-line, low-heap and font-cost follow-ups should begin as small diagnostics, not presumed production optimizations. Do not combine them into the buffer-retention patch. No 7B work is started by this report.

## L. Tranche 7C release criteria

- Freeze the artifact, runtime, font/theme, host/display, heap policy and fixture hashes. At least five serial fresh-process launches per normal startup scenario; keep existing **2 s visible / 3 s source / 10 s full UI** gates. Label fresh vs cold-cache and custom-font cases separately. No eager Mermaid, project scan or new idle worker.
- Use at least 100 completed-paint samples after separately reporting first-edit latency. **p95 ≤16 ms** for ordinary supported source workloads, including representative Unicode, 100 KiB and 1 MiB; explicitly decide the supported long-line and 10 MiB envelope. Report queue, mutation, paint and convergence separately. Include native keyboard/IME and shaping/caret checks.
- Retain the historical **384 MiB used-heap / 768 MiB RSS diagnostic budgets**, without converting them to settled-memory guarantees. Establish a product-approved settled RSS/private-footprint budget on each host before claiming “low memory.” Pair instrumented/uninstrumented runs. Closed buffers, picker state, finished callbacks and disposed index/media ownership must release without unrelated user commands; repeated close cycles must show a bounded plateau. Investigate any sustained residual increase with owner/stack evidence.
- Verify real overlapping semantic/index/media work and queue admission; reject stale results, release workers/assets, preserve source latency. Count actual overlaps instead of nominal labels. No budget expansion to improve results.
- Qualify preview input delay end-to-end, including open, debounced refresh and reflow, with explicit product-approved supported-size/latency gates. The ordinary source target remains 16 ms; choosing a different preview operating envelope requires a documented decision, not a benchmark redefinition. Keep 1C incomplete until those gates pass.
- Complete bounded 128/256 MiB heap save/recovery/open-close scenarios without retaining artificial duplicate verification copies. Treat system-level low RAM as separate: provide an opt-in externally supervised plan on a spare machine with hard child timeouts and stop conditions, not an exhaustion workload on a user's active machine.
- Rerun full Maven, tooling and packaged smoke; require zero unexplained failures and enumerate skips. Include exact encoding/BOM/line-ending persistence, undo/recovery, read-only projection, split/keymap/navigation/overlay lifecycles and an available LSP server smoke.
- Run Windows, Linux and macOS desktop checks with the same source and packaged assets. Cover native IME, accessibility, Unicode, font fallback, clipboard, split focus and shutdown. Unit tests alone do not satisfy this gate.

## M. Appearance initiative readiness

**Not ready for 8A baseline qualification.** First resolve or explicitly scope the P1 blockers and complete 7C. Transparency/blur must later have opaque disabled controls, paired completed-paint/input measurements, graphics/IOSurface/private-footprint observations, supported-OS fallbacks and repeated window/disposal checks. Existing 16 ms editing and startup budgets cannot be spent implicitly on compositing. This report neither implements appearance features nor authorizes a UI redesign.

## N. Repository and UI/UX compliance

Audit additions are limited to opt-in diagnostics and evidence:

- `scripts/qualify-markdown.py`, `scripts/test_benchmark_qualification.py`.
- `src/benchmark/java/shed/MarkdownQualificationProbe.java`, `FontMemoryQualificationProbe.java`.
- This report and all files listed in `docs/performance/markdown-tranche7a/manifest.json` (raw reports/logs, measurements, verification, JFR summaries/identities and index).

A complete path inventory, including the generated diagnostic Python bytecode incorporated by an external commit, is in [implementation-inventory.json](performance/markdown-tranche7a/implementation-inventory.json). Existing ignored instructions and pre-existing Python caches were preserved.

The two explicit defaults requests additionally changed:

- Production: `BundledEditorFont.java` (new lazy physical-font loader), `ConfigManager.java`, `FontFamilyCatalog.java`, `EditorUiController.java`, `StagedStartupFont.java`.
- Packaging: `pom.xml` resource includes; new `assets/fonts/JetBrainsMono-Regular.ttf` and `JetBrainsMono-OFL.txt`, upstream JetBrainsMono **v2.304**. No new dependency.
- Tests: new `BundledEditorFontTest.java`; default/fallback expectations in `ConfigManagerTest.java`, `ThemePreviewTest.java`, `TexteditorSwingIntegrationTest.java`; the latter also asserts the existing floating-dialog surface contract in light mode.
- Documentation: `docs/CONFIG.md` default font/theme descriptions.

Existing explicit font/theme choices remain honored; no personal configuration or OS appearance settings were overwritten. Fresh headful fixtures use light mode; explicit dark-theme regression tests still exercise dark themes. No layout, component, keybinding, navigation, syntax color pipeline, canonical source owner, media/index architecture or preview execution was redesigned. Font and default color appearance deliberately change only as requested. The audit adds no startup services, permanent cache, worker or production diagnostic instrumentation.

`README.md` before and after SHA-256:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

All baseline files remain present; only the enumerated defaults-related baseline files changed beyond the initial user work. Earlier performance evidence remains byte-for-byte intact. `git diff --check` is recorded in the verification artifact. External commits are preserved; this agent created no commit or push. **Stop at 7A: no 7B, 7C, 5C.2, live-editor or appearance implementation.**
