# Markdown preview responsiveness — Tranche 1C partial result

**Tranche 1C is incomplete.** A safe duplicate-initial-render correction is implemented and measured, but no asynchronous production pipeline is shipped. Detached HTML preparation does not resolve Swing's materialized layout cost: the 1 MiB experiment still blocks queued source input for about a second. The requested no-stall guarantee cannot be claimed. Investigation stops at the user's HTML-layout/architecture stopping condition; no 1D or semantic-model work begins.

[Raw evidence, fixture identities and commands](performance/markdown-tranche1c/INDEX.md) distinguish exploratory measurements from fresh paired results. Original [Tranche 1](MARKDOWN_PERFORMANCE_BASELINE.md), [1A](PACKAGED_MARKDOWN_TEST.md) and [1B](SOURCE_VIEW_PERFORMANCE.md) evidence remains unchanged.

## A. Root cause and current pipeline

`MarkdownPreviewPane` installs a 120 ms non-repeating Swing Timer. Source insert/remove/change listeners restart it; timer, explicit refresh and appearance refresh all call `renderNow` on EDT. That method reads full canonical source, runs MarkdownPreviewRenderer, then calls JEditorPane.setText. The constructor called `refreshAppearance` (which already renders), then rendered again. This duplicate is the only production path changed here.

MarkdownPreviewRenderer normalizes and preprocesses Mermaid/math, calls the existing CommonMark parser/extensions, generates HTML, sanitizes it through the existing OWASP policy, resolves local/remote image URLs and emits the existing CSS. All are currently synchronous. Its static parser/renderer/policy and per-preview mutable asset cache were not assumed safe for concurrent mutation; no concurrency was introduced.

MarkdownPreviewAssets initializes its temporary directory, HTTP client and bounded-by-request-count remote-fetch path per preview. Generated math and Mermaid PNGs are cached by content without an entry/byte budget and retained until close. Mermaid execution is guarded by the existing global lock; a cache miss initializes/evaluates GraalJS, while an identical current diagram uses its existing cache. Math painting uses independently created images. Remote fetching is already background work, with two session threads, 32 session requests and existing size/time/security restrictions. Its completion schedules an EDT rerender. Generated-asset cleanup remains synchronous on preview close. Cache/remote-writer disposal races and whole-session retention would require explicit ownership before introducing workers.

Source capture currently materializes whole source through FileBuffer.getFullContent. FileBuffer.textSnapshot supplies immutable piece-tree revisions suitable for future worker reads, while documentRevision and exact document/buffer/pane identities can validate completion. Those abstractions were inspected and left unchanged. Future capture must ensure the snapshot actually represents the captured source revision; it must not silently display a stale snapshot after inactive/programmatic document changes.

HTML installation and source synchronization are separate costs. setText parses a fresh HTMLDocument synchronously; subsequent HTMLEditorKit View construction and ParagraphView/FlowView line breaking calculate layout/preferred size on EDT. `establishPreviewScrollRange` can force layout and also materializes/counts source lines for an estimated fallback range. Painting is not the same event as HTML availability or full layout.

The exploratory fresh 1 MiB baseline measured about 650 ms for opening, 119 ms median explicit refresh, 45 ms HTML setText replay and 926 ms preferred-size/layout replay. The 10 MiB child reached a 7.37 s opening call and timed out at its 45 s watchdog. Its EDT stack is `GlyphPainter1.getBoundedPosition → GlyphView/InlineView.getBreakWeight → ParagraphView.calculateMinorAxisRequirements → BoxView/FlowView.layout`. These are layout/glyph costs, not evidence that CommonMark alone dominates.

## B. Retained change and architecture decision

Remove the second constructor `renderNow()` call. `refreshAppearance()` still applies exactly the existing style and performs the initial render once. Explicit refresh, source debounce, remote-image refresh, scrolling, focus, document switching and disposal keep their prior behavior. No manual-refresh same-HTML suppression was added: local image contents can change without changing their URL, so skipping a refresh solely on equal HTML risks changing supported behavior.

There is **no new production scheduling, worker, queue, cancellation token, revision-generation state, asset cache or installation policy**. Parsing, rich media and HTML installation/layout still run synchronously. This avoids shipping an incomplete concurrency/resource design while retaining a claimed responsiveness guarantee.

The detached experiment follows the installed OpenJDK 21 implementation: JEditorPane.PageLoader.doInBackground invokes EditorKit.read off EDT, while component/document attachment and geometry/scroll work require EDT ownership. Each diagnostic HTMLEditorKit/HTMLDocument is independently owned during worker parsing. No live Swing component or live document is mutated off EDT. Preparing that detached model removes its parser work from EDT, but does not prepare the new JEditorPane View tree or its line breaks. Calling live setText/getPreferredSize/layout off EDT is not a safe workaround.

No small supported API examined provides a per-frame budget for the full materialized HTML View tree. Splitting parsing alone still leaves the measured installation/layout stall; merely moving it to a later EDT callback moves the stall in time. Ignoring old requests or waiting for source idle does not protect a user who resumes typing during the eventual synchronous layout. A size cutoff tight enough to hide the measured ordinary 1 MiB case would regress a required normal-sized preview. No truncation, content removal, ordinary-file preview disablement or new UI is introduced to manufacture a passing result.

The stopping condition applies because a bounded, appearance/geometry-preserving installation path has not been established without a broader HTML view/layout adaptation. This is a limit of the demonstrated stock path, **not proof that another framework or language is necessary**, or that a contained custom Swing view is impossible. That needs a separately bounded feasibility prototype before shipping the complete worker pipeline.

## C. Files changed

| File | Responsibility |
| --- | --- |
| `src/main/java/shed/MarkdownPreviewPane.java` | Remove one redundant constructor render call. |
| `src/test/java/shed/MarkdownPreviewPaneTest.java` | Verify exactly one initial source capture/render, explicit refresh, source/snapshot/revision/saved baseline/dirty/undo preservation and listener disposal. Uses production-safe subclassing, no Unsafe/reflection probe. |
| `src/benchmark/java/shed/MarkdownPerformanceProbe.java` | Add opt-in detached HTML read/install/layout/paint experiment and queued source-action delay; exact source restoration checked after input/undo. |
| `scripts/benchmark-markdown.py` | Register `preview-install` in the existing bounded runner. No new framework or dependencies. |
| `docs/MARKDOWN_PREVIEW_RESPONSIVENESS.md` | Partial implementation report and next bounded decision. |
| `docs/performance/markdown-tranche1c/` | Reproduction index, raw samples, metadata, diagnostics and verification. |

Four pre-existing modified Tranche 1B evidence files were present at task start. Their exact bytes are preserved; they are not 1C changes. No other initially tracked files are intentionally modified.

## Environment and statistical limits

The actual host is MacBookPro15,2, Intel i5-8279U 2.40 GHz, 8 logical CPUs, 8 GiB RAM, macOS 15.7.7 x86_64, Temurin 21.0.12.1+1-LTS with 2× display scaling. JVMs are bounded at 64–512 MiB, use isolated homes and actual shaded distributions. Each case records load, existing swap/pressure, VM statistics, fonts, viewport and artifact identity. Runs are fresh processes with uncontrolled warm OS/font caches, not cold-cache qualification. No destructive pressure experiment or system settings change was performed.

Baseline artifact SHA-256 is `06661ebc00b3bb001c0ae24050d76afc759a88c036bdd0a203d29ed72badf0e0`; partial-fix artifact is `8ff401830509ef3f1f13924a7dde18ba58996f83ccd0f10c8eb6d292cbe401da`. Builds/tests do not run concurrently with the paired timing runs. One process per scenario/artifact and three installation repetitions do not establish a useful cross-process confidence interval. Large stalls are supported by direct queued-event delay, repeated layout samples and the timeout stack; small before/after differences are not attributed to the one-line fix unless its affected work is actually measured.

## D. Paired measurements

Times below are milliseconds. Small-sample rows show median / maximum, not meaningful p99. Every pair uses the same runner, probe and fixture hashes. Initial exploratory `before-*` runs remain separately labeled; only `paired-*` is the direct production-change comparison.

| Scenario | Size | Before median / max | After median / max |
| --- | --- | ---: | ---: |
| Open (n=3, includes first open) | 100 KiB | 45.26 / 450.61 | 29.68 / 368.20 |
| Explicit refresh (n=9) | 100 KiB | 16.27 / 31.12 | 17.24 / 52.62 |
| Latest HTML after ten rapid edits (n=3) | 100 KiB | 165.22 / 167.88 | 171.66 / 262.32 |
| Open (n=3, includes first open) | 1 MiB | 240.09 / 949.32 | 128.83 / 676.57 |
| Explicit refresh (n=9) | 1 MiB | 91.95 / 240.27 | 86.75 / 197.84 |
| Latest HTML after ten rapid edits (n=3) | 1 MiB | 239.62 / 271.78 | 236.02 / 249.40 |

Opening now performs one render instead of two. Explicit refresh, parsing/rendering placement and layout algorithms are unchanged; movements in those controls are not optimization wins. Latest-HTML convergence is not a generation/paint-completion guarantee.

## E. Detached HTML installation experiment

No live component is touched by the worker. Markdown rendering and independent HTMLEditorKit/HTMLDocument parsing occur on the probe main thread, separate from EDT. A fresh JEditorPane is created and mutated only on EDT, with the existing full-preview CSS, font, colors and fixed 600×600 allocation. Source input is queued immediately before installation/layout; the callback types and undoes one character and verifies exact source bytes. These are diagnostic components, never a production preview replacement.

| Independent-document experiment, n=3 | Size | Before median / max | After median / max |
| --- | --- | ---: | ---: |
| Worker Markdown rendering | 100 KiB | 34.00 / 110.61 | 31.62 / 106.26 |
| Worker HTML parsing | 100 KiB | 16.41 / 30.76 | 11.86 / 28.44 |
| EDT setDocument | 100 KiB | 5.90 / 14.34 | 6.14 / 15.81 |
| EDT preferred-size/layout | 100 KiB | 91.80 / 144.90 | 99.39 / 229.81 |
| EDT offscreen paint | 100 KiB | 3.10 / 13.49 | 3.35 / 9.77 |
| Queued source callback delay | 100 KiB | 101.65 / 175.05 | 108.90 / 257.80 |
| Worker Markdown rendering | 1 MiB | 122.26 / 318.47 | 321.49 / 342.22 |
| Worker HTML parsing | 1 MiB | 82.39 / 109.46 | 107.14 / 115.10 |
| EDT setDocument | 1 MiB | 41.48 / 71.31 | 40.62 / 63.29 |
| EDT preferred-size/layout | 1 MiB | 966.06 / 1029.24 | 864.44 / 1032.00 |
| EDT offscreen paint | 1 MiB | 3.33 / 12.55 | 9.13 / 12.42 |
| Queued source callback delay | 1 MiB | 1011.43 / 1115.49 | 913.76 / 1110.49 |

The diagnostic does not use MarkdownPreviewPane, so before/after differences are controls, not effects of the constructor fix. Warm layout still takes hundreds of milliseconds; a ready detached model does not carry a ready component View tree. The queued source callback exposes the remaining input-blocking cost. Native keyboard/compositor latency is excluded.

### Production stage replays

Warm, single-sample replays, **not additive** with the live refresh. They retain explicit thread names in raw samples. Source capture, preprocessing, parse, HTML generation, sanitization, asset resolution, setText and layout execute on EDT in the unchanged production path/replay.

| Replayed stage, 1 MiB | Before ms | After ms |
| --- | ---: | ---: |
| source_capture | 1.03 | 0.95 |
| preprocessing | 17.10 | 14.63 |
| parse | 9.01 | 11.28 |
| html | 3.46 | 3.07 |
| sanitize | 6.75 | 8.33 |
| asset_resolution | 2.19 | 2.83 |
| setText_offscreen | 33.05 | 30.63 |
| preferred_size_layout_offscreen | 614.61 | 810.35 |

### Source editing controls

100 measured insertions after 10 warmup cycles, pooled over beginning/middle/end, values p50 / p95 / p99. These standard loops do not guarantee overlap with a refresh: the 120 ms debounce can postpone rendering throughout continuous edits. Input-to-paint starts after EDT dispatch; queued delay is reported separately. They cannot establish the requested responsiveness-while-rendering target. The detached-installation callback above is the direct blocking counterexample.

| 1 MiB source | Before ms | After ms |
| --- | ---: | ---: |
| Preview open, insertion to completed paint | 6.94 / 12.03 / 15.25 | 6.59 / 11.33 / 12.07 |
| Preview closed, insertion to completed paint | 6.03 / 14.13 / 15.85 | 4.87 / 6.64 / 6.94 |
| Preview open, insertion EDT queue delay | 1.61 / 2.48 / 8.21 | 1.75 / 2.55 / 2.76 |

### Rich media and memory

Asset controls use the unchanged font-ready harness. n=1 first/invalid render, n=3 identical cached renders and n=2 changed renders; no p99 or optimization attribution is justified.

| Asset operation | Before median / max | After median / max |
| --- | ---: | ---: |
| math_changed | 1.85 / 2.09 | 1.90 / 2.27 |
| math_first_use | 190.38 / 190.38 | 194.60 / 194.60 |
| math_identical_cached | 0.02 / 0.04 | 0.02 / 0.03 |
| math_invalid | 0.22 / 0.22 | 0.22 / 0.22 |
| mermaid_changed | 1335.47 / 1543.08 | 1589.95 / 2093.14 |
| mermaid_first_use | 5077.36 / 5077.36 | 5345.68 / 5345.68 |
| mermaid_identical_cached | 0.01 / 0.01 | 0.01 / 0.09 |
| mermaid_invalid | 867.29 / 867.29 | 990.98 / 990.98 |

Rich 4 KiB production preview retains local images, math and valid Mermaid without an error fallback. Invalid rendering is checked by the existing renderer tests and packaged smoke; normal rich-preview errors must not be counted as successful diagrams.

| 1 MiB, preview active after open | Before | After |
| --- | ---: | ---: |
| Heap used, MiB | 165.7 | 89.1 |
| Heap committed, MiB | 230.0 | 114.0 |
| Sampled process peak RSS, MiB | 674.9 | 809.0 |

No forced GC or retained-heap census was performed. RSS is sampled about every 250 ms, not a kernel high-water mark. The production change adds no retained objects or workers; it does not repair existing unbounded generated-asset retention. Asset controls and preview-active memory are separate processes/stages.

## F. Concurrency, lifecycle and unimplemented requirements

Existing synchronous execution remains serialized by EDT. The original 120 ms debounce still collapses rapid edits; disposal stops it and removes source, caret and scrollbar listeners. Existing buffer-switch behavior closes/releases the old preview. Four existing integration scenarios verify initial/explicit refresh, source/caret/disabled scroll synchronization, notification-expiry geometry and split/debounce/buffer-switch/listener isolation. The new test verifies one initial capture and exact source state plus explicit refresh and listener cleanup.

These are **not asynchronous-concurrency tests**. No generation-aware worker pipeline, out-of-order completion handling, saturation policy or newly bounded cache exists in this patch; claiming such coverage would be incorrect. There is no new running task that could retain an obsolete document, but the existing renderer/cache/remote lifecycle limitations remain. The detached diagnostic waits for explicit callback state, not an arbitrary synchronization sleep; it does not add production lifecycle guarantees.

The eventual implementation still needs immutable snapshot/revision/document/pane identity capture, stale rejection on EDT, latest-only pending work, isolated renderer execution and ownership-safe asset disposal. Cancelling a Future must not be equated with stopping GraalJS; one bounded running render may need to finish while its stale output is discarded. None of these mechanisms should hold live Swing references in worker inputs or locks across expensive rendering/EDT callbacks. They are a proposed requirement set, not implemented behavior.

## G. Regression and reproducibility results

The focused quoted Maven selector in the [index](performance/markdown-tranche1c/INDEX.md) passed **20 tests**, including the new constructor regression, four existing Markdown preview integration tests, renderer/folding/editing tests and six SourcePlainView tests. The complete Maven suite then passed **1,317 run: 1,312 passed, five skipped, zero failures/errors**, in 3:19. No test was disabled or relaxed. Existing skips remain unsupported DOS attributes, unavailable Fish/CMake-related tooling, and two opt-in AI profiles. Per-suite counts/reasons are in `regression-suites.json`.

The complete suite retains 0A folding containment, 0B atomic edits/EOF/table/list/permission correctness, 0B.1 deterministic preview synchronization, 1B geometry/source-view tests, FileBuffer/codec/undo/recovery and split/LSP/search/input integrations. These existing tests were not edited. The new test uses ordinary Document and subclass APIs, not Unsafe or a production test hook.

The packaged smoke passed on the measured after-JAR with only that distribution and the independent probe on its classpath. It verifies both Truffle providers, valid/repeated/changed/invalid Mermaid, subsequent valid rendering, math, local images, sanitization and ordinary Markdown. Deliberate invalid DSL logs are expected; they are asserted errors, not empty successful images. Five Python benchmark-tooling tests pass. Exact successful commands and output are in `executions.json` and `verification.json`; quote the wildcard Maven selector in zsh.

Heavy runs remain opt-in. All generated Markdown and profiling files are outside the checkout; only compact evidence is retained here. The 10 MiB exploratory timeout is retained explicitly, with its EDT stack and partial samples. The timeout did not cause test suppression or an increased deadline. Primary before/after pairs match fixture, runner and probe hashes. The measured MarkdownPreviewPane class matches the final tested class; production source remained frozen during timing. No normal execution profiler or monitor was introduced.

## H. Existing capabilities

The actual renderer, sanitization, remote-image restrictions, relative local-image resolution, task/table/link/code extensions, math and Mermaid implementation are unchanged. Existing cache hits are preserved. Links/anchors, source/caret scroll mapping, disabled synchronization, manual scroll restoration and focus/source-pane routing remain the same. Manual refresh still reloads the HTML even if its string is unchanged. The source document, revisions, undo ownership, persistence/recovery, Plain/Vim/Emacs, LSP and cross-platform Java/Swing code paths are not redesigned.

This preservation does **not** mean the preview is now responsive: first-use Mermaid still takes seconds on EDT, and ordinary HTML layout still blocks. Other operating systems and native installers were not executed; the unchanged packaged JAR path was verified on this macOS host.

## I. UI/UX compliance

No design-system, visual-identity, layout, typography, color/theme, navigation, interaction, menu/panel/dialog/setting or production component changes were introduced. The same single initial HTML result is installed with the same CSS and existing Swing preview. Only its redundant repetition is removed. The extra JEditorPane in `preview-install` exists solely in the independently launched opt-in diagnostic and never replaces a production component.

## J. Protected repository state

README before/after SHA-256 is **`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`**; it was not modified. The four pre-existing modified 1B evidence files remain byte-for-byte equal to their starting versions. Original Tranche 1 evidence and previous tranche implementations remain preserved. `git diff --check` passes. No dependencies, settings, eager startup work, background service, document copy/cache or persistent worker was added to normal execution. Benchmark children/windows have exited; their external data files are intentionally retained evidence. No commits or pushes were created.

## K. Remaining limits and smallest next decision

The approximately frame-scale EDT goal is **not met**. In the repeated independent-document experiment, 1 MiB installation maxima remain around 63–71 ms, layout maxima around 1,029–1,032 ms, and queued source-action maxima around 1,110–1,115 ms. Initial unprofiled replays and the 10 MiB timeout corroborate the call path. These stalls cannot be hidden by calling the worker's HTML-ready state "preview ready."

Generated assets still have no LRU/byte budget or displayed-asset leases. Current per-preview maps/resources are not a safe asynchronous ownership design. New lifecycle, cancellation and memory safeguards are therefore deferred along with the unimplemented pipeline, rather than pretending that the one-line correction supplies them. Memory numbers above are unforced occupancy/whole-process samples, not a retained-memory improvement. Startup with preview closed has no new production path; it is not claimed as a statistically qualified startup speedup.

The smallest next step is an **opt-in bounded HTML-layout feasibility prototype**, preserving JEditorPane and existing CSS. Timebox the investigation before application integration:

1. Inspect a narrowly scoped HTML ViewFactory/paragraph-layout adaptation or supported progressive layout. Do not migrate the app to a new framework or access live Swing state off EDT. Compare against the materialized stock view at identical viewport widths/fonts.
2. Require complete 100 KiB and 1 MiB documents, tables, anchors, code, local images, math and Mermaid images. No truncation or ordinary-file disablement. Measure each actual EDT task and queued source-event latency, including input resumed during installation/layout; use at least 100 input events for p95/p99 where practical.
3. Aim for ≤16 ms source-action p95 including EDT queueing during processing. Record maximum installation/layout task occupancy separately; proposed frame-scale work is a target, not an existing guarantee. A prototype that still performs a >50 ms synchronous full reflow is not sufficient merely because most typing samples miss it.
4. Verify settled pixels, text/caret/link hit testing, scroll extent, source/caret/disabled/manual synchronization, image reflow, notification geometry, resize/theme changes and disposal. Estimated/progressive extent needs explicit compatibility review; it must not silently change navigation semantics.
5. If this cannot preserve behavior within a narrow Swing adaptation, present the actual view/presentation alternatives for separate architectural review. Do not assume Swift, WebView or another framework solves the measured problem without comparative evidence.

Only after that presentation decision should the complete pipeline wire immutable snapshots into a lazily initialized isolated render lane, bounded global concurrency/queue capacity, latest-request coalescing, revision/generation/lifetime validation and display-aware asset leases/eviction. It must not use AsyncJobService's shared latency-sensitive queue for multi-second Graal work, nor rely on Future cancellation for immediate renderer termination. Rendering-worker work can be prototyped independently, but must not be described as full 1C acceptance while the demonstrated UI stall remains.

## L. Final verdict

The duplicate-initial-render correction is safe, measurable and verified. The broader requested **asynchronous, bounded, responsive preview is not implemented or accepted**. Source-loop p95 below 16 ms does not refute the explicitly queued input delay during installation. No full preview responsiveness, bounded generated-asset retention or stale-worker guarantee is claimed.

Stop here under the supplied HTML-layout/architecture condition. **Do not advance to Tranche 1D or the semantic-model tranche on the assumption that 1C is complete.** The retained patch, reproducible negative experiment and bounded next prototype are the deliverables of this partial investigation.
