# Markdown asynchronous media infrastructure — Tranche 3B

## A. Architecture

Tranche 3B adds an explicitly owned, reusable media service. It is not wired into the source editor or preview. The flow is a verified immutable source revision → small media plan/request → one bounded worker → independently owned raster → checked result/lease. It installs no document listeners, timers, picker hooks, startup registration, project scanner, parser cache or presentation components.

`MarkdownMedia` defines typed image/Mermaid/math requests, options, eligibility outcomes and revision stamps. `MarkdownMediaService` owns scheduling, handles, consumer sessions, the asset cache and leases. `MarkdownMediaRenderer` adapts the existing libraries and file resolver. The authoritative `FileBuffer`, undo, persistence, recovery, semantic parser and lexical highlighter are unchanged.

Requests contain document ID, revision, a **weak reference to the exact snapshot**, complete-source flag, caller generation, ordered half-open UTF-16 source segments, an optional verified content range, bounded content, a UTF-16 content digest, options and local-image resolution context. No entire source snapshot, AST, buffer or Swing component is retained strongly. Content identity is independent from revision identity; cache reuse never authorizes installation.

Plans distinguish pending/admitted from unsupported, failed, cancelled, superseded and resource-limited input. Runtime handles additionally distinguish ready and closed. No failure is represented by a successful null/empty asset. Successful leases expose kind, asset identity, rendering options, dimensions, raster bytes/pixels, source stamp and source ranges. The cached mutable `BufferedImage` never escapes; consumers can paint it through a lease.

## B. Files changed

Production:

- Added `src/main/java/shed/MarkdownMedia.java`: immutable request/option/stamp/plan contracts and small read-only semantic adapters.
- Added `src/main/java/shed/MarkdownMediaService.java`: lazy single-worker scheduling, admission, coalescing, revision checks, cache, leases and disposal.
- Added `src/main/java/shed/MarkdownMediaRenderer.java`: worker-local image, Mermaid and math adapters, resource checks and image-version validation.
- Modified `src/main/java/shed/MarkdownPreviewAssets.java`: expose reusable request-local math/raster/scaling helpers and shared Mermaid serialization; serialize JLaTeXMath access. Existing preview cache, URI output, rendering route, sizes, styling and remote policy remain in place.

Tests:

- Added `src/test/java/shed/MarkdownMediaServiceTest.java`: 31 deterministic service, renderer, range, ownership, source-preservation and lifecycle tests.

Benchmarks/tooling:

- Added `src/benchmark/java/shed/MarkdownMediaProbe.java`: actual packaged service/renderer workload, worker allocation, typing overlap, cache, queue and disposal measurements.
- Added `src/benchmark/java/shed/MarkdownMediaControlProbe.java`: matched existing-renderer/memory control that compiles against the before and after artifacts. Its diagnostic worker is not a production preview change.
- Modified `src/benchmark/java/shed/MarkdownPerformanceProbe.java`: reflective dispatch for the two opt-in modes.
- Modified `scripts/benchmark-markdown.py`: bounded `media-service` and `media-control` modes, at most 100 samples and 1 MiB source fixtures.
- Modified `scripts/test_benchmark_markdown.py`: reject excessive service workload before creating output.

Documentation: this report and `docs/performance/markdown-tranche3b/INDEX.md`, `cases.json.gz`, `measurements.json`, `regression-suites.json`, `packaged-smoke.json`, `verification-logs.json.gz`, `renderer-inspection.json.gz`, `baseline-files.json.gz`, `verification.json` and `manifest.json`. Their hashes and purposes are in the evidence index. No dependency or UI file was changed.

## C. Existing renderer reuse

Images resolve on a worker through `MarkdownLinkResolver` and `WorkspaceRootResolver`, without requesting an index or scanning a project. Ordinary relative destinations use the source directory, native canonical identities and the deepest configured project root. Nested configured roots and symlink escapes remain outside the permitted scope. Remote URLs, absolute/file-scheme paths and unavailable targets are explicitly unsupported. A future host must supply its existing approved roots and cancel the session when that authority changes.

Raster decoding uses the existing ImageIO reader, header-before-decode checks and aspect-preserving scaling. The new path uses a memory-only input stream, not ImageIO's optional temporary file cache. First frames of supported ImageIO formats are used; animated playback is not added. Supported safe local SVG is rasterized with the existing Batik dependency and restrictive resource policy. No remote fetch, HTML resource loader or HTTP client is created by this service.

Mermaid uses the existing `Mermaid.render` SVG generation/fixup, global serialization lock, Batik dependency, baseline-attribute cleanup and existing `autoCrop` behavior. Rasterization is bounded **before** image allocation, instead of allocating an arbitrary diagram bitmap and checking it afterward. Default rendering uses the existing Mermaid default configuration; dark/neutral options prepend a whitelisted Mermaid theme directive. Content, including any authored directives, remains part of the key. Unsafe/unsupported generated SVG is rejected rather than bypassing resource restrictions. The packaged Truffle provider correction is untouched.

Math uses existing JLaTeXMath, a request-local `TeXFormula`/`TeXIcon`, existing antialiasing, padding, default TeX fonts and foreground handling. Default inline size remains 16 and display size 20. Options can change foreground and size; arbitrary font substitution is not introduced. A pixel-exact differential test compares supported default output with the existing preview renderer.

JLaTeXMath exposes global command maps and resource-loading commands. The service therefore admits a documented conservative command set (ordinary fractions, roots, Greek letters, operators, accents, text/font styles and relation symbols), rejects definitions, external graphics/converters and unknown commands, and checks brace nesting/balance. It does not mutate those registries. Shared math rendering is serialized; preview sessions/maps are not passed to the worker. Existing preview syntax is not restricted by the new service's command admission.

## D. Background execution

The ordinary IDE `AsyncJobService` has shared workers and a separate per-task long-running route. Long Graal rendering must not occupy those latency-sensitive workers or create an unbounded per-task workload. This service instead has **one dedicated daemon worker**, initialized only by an accepted request, with eight actual executor queue slots. Its core thread expires after one idle second. There are at most 16 explicit sessions and 64 combined outstanding handles/leases by default. Rejected work returns an explicit resource-limit outcome.

Duplicate in-flight content/options/context requests share one physical job. Consumer cancellation detaches only that subscriber. Removing the last subscriber removes queued work, or marks running work abandoned. An abandoned running renderer still owns the physical worker until it actually returns. Newer work cannot start a second renderer merely because a handle is cancelled. At most one worker plus the existing library's serialized, per-invocation Graal helper executor can be active in this service path.

The inspected Mermaid library creates/closes a Graal `Context` and shuts down its helper executor per call, including error/timeout paths. Its own cached JavaScript preamble is library-global and has no service-level disposal API. No replacement engine, forced thread stop, artificial successful timeout or assumption that `Future.cancel` stops Graal was introduced.

All filesystem capture, hashing, XML handling, image decoding, math/diagram generation, crop, scaling and owned-raster copying run on the media worker. Service locks protect only bounded metadata/cache operations; they do not cover renderer execution or EDT callbacks. The service posts no callbacks. Callers poll nonblocking handles and perform checked `take`; the optional bounded wait helper explicitly rejects EDT use.

## E. Revision and lifecycle safety

`open(source,generation)` creates a presentation-consumer session. A host uses existing document/pane/project notifications to call `update` or `close`; this headless tranche does not create a second document lifecycle. Different panes may use independent sessions over the same source and share an asset. Different documents can share identical content while keeping separate revision stamps and leases.

`update` supersedes old handles and closes that session's leases. `take(currentSource,currentGeneration)` checks document ID, revision, complete-source status, exact snapshot identity and generation, including pending requests and cache hits. A stale request cannot acquire a lease. `Lease.isCurrent` remains the host's required check before subsequent presentation. Closing a session releases all its handles/leases; closing the service closes every session, clears cached assets and shuts down admission immediately, without blocking the EDT for running noninterruptible work.

Image capture checks canonical scope, size, identity, file key, modification time and a content digest. It verifies the resource again after decoding, before publishing a new asset. This detects deletion/retargeting/changes during rendering, including changed bytes with preserved size/time on repeated requests. The result records the actual captured resource identity/version. A later external file change still requires the future host's existing resource invalidation/refresh and a new request; a cache key is not a filesystem watcher or transaction. Filesystem races cannot be made globally atomic by snapshot metadata checks.

The service never receives a mutable document or editor reference. Unit evidence observes zero document events, unchanged source/snapshot/revision, dirty flag, undo availability and saved baseline. Existing LSP text-change paths are not called. The rendering path creates no asset files or source/filesystem writes; normal library font/runtime initialization remains the existing JVM behavior.

## F. Cache design and resource budgets

| Default admission/retention | Limit |
| --- | ---: |
| Concurrent media jobs | 1 |
| Queued jobs | 8 |
| Consumer sessions | 16 |
| Combined handles/leases | 64 |
| Cached entries | 32 |
| Cached raster bytes | 16 MiB |
| All resident raster bytes, including active leases | 32 MiB |
| All resident decoded pixels | 8 Mi pixels |
| Per-raster dimensions/pixels | 2,048 × 2,048 / 4 Mi pixels |
| Local compressed image input | 4 MiB |
| SVG source / elements / XML depth | 2 Mi UTF-16 units / 16,384 / 128 |
| Copied request content / segments | 64 Ki UTF-16 units / 64 |
| Mermaid renderer admission | 16 Ki UTF-16 units / 256 lines |
| Math content / brace depth | 8 Ki UTF-16 units / 32 |

These are service budgets, not expanded semantic/editor limits or settings. The original approximately 1 MiB semantic cap and structural limits remain unchanged. A request can pass semantic planning yet be rejected by tighter renderer admission.

Cache identity includes media kind, content digest, display style, theme, color, size and dimensions. Image keys additionally include canonical path, file key/version and digest of the actual captured bytes. UTF-16 string digests preserve distinct surrogate code units; no source normalization is performed. Image cache hits still validate/capture the local resource on a worker.

Entries use access-order eviction. Inactive assets can be released to admit new assets; cached entries may also lose cache membership while remaining pinned. Handles/leases pin the same raster rather than duplicating it for each pane. Pinned rasters count toward the resident-byte/pixel budget; if those pins exhaust admission, new results are rejected rather than exceeding the budget. Closing the final owner releases an uncached raster. Failures are not retained in a negative-result cache.

Raster bytes are exact ARGB payload accounting, not a claim that all JVM/native memory fits 32 MiB. Metadata is bounded separately by entry, request, content and consumer limits. The running worker can temporarily hold compressed bytes, parser/renderer state and a renderer image plus an owned copy. Graal/Batik/JLaTeXMath transient allocation is not hard-capped by the resident cache budget; input/output admission and single-worker isolation constrain that workload without pretending to impose a JVM memory quota. Fonts, JIT/compiler memory and the library-global preamble can survive service disposal.

## G. Correctness evidence

The 31 new deterministic tests cover:

- Exact CRLF/Unicode source and content ranges; weak snapshot identity, foreign IDs/revisions, unsupported/failed/cancelled semantic data, absent table-inline positions and the unchanged semantic cap.
- Closed/unclosed Mermaid fences and unsupported container mappings; narrow standalone math recognition versus escaped/code/multiple/multiline forms.
- Local/relative raster and safe SVG, native aspect ratio, no upscaling, content changes with preserved metadata, missing/renamed files, unsupported formats, compressed/header limits, project/symlink boundaries, remote/file URL rejection, and deletion during decode.
- Real Mermaid valid/invalid/valid recovery, identical-result reuse, actual dark-theme identity and old-lease invalidation.
- Real math, invalid expressions, rejection of resource/stateful commands, color/size changes and exact default pixels versus preview output.
- Duplicate subscribers across splits, independent cancellation, noninterruptible execution, repeated identical requests after abandonment, bounded queue/consumer/session admission, worker failure, cache eviction under active leases, stale pending/completed results and service shutdown.
- No source events, undo/dirty/baseline/revision changes, no generated project files, no eager worker on construction and no EDT wait.

Latch/generation synchronization establishes ordering; arbitrary sleeps do not establish test correctness. Opt-in memory probes separately use requested GC/settling to observe collection, without calling those observations deterministic leak proofs. The full regression suite retains existing Markdown, preview, navigation, split, lifecycle, undo, persistence, worker, source-view, highlighting and folding tests.

## H. Performance and memory

| Measurement (ms; p50 / p95) | 4 KiB | 100 KiB | 1 MiB |
| --- | ---: | ---: | ---: |
| Before legacy worker typing to paint (n=100) | 0.611 / 1.261 | 0.965 / 1.739 | 1.068 / 1.379 |
| After legacy worker typing to paint (n=100) | 0.715 / 1.622 | 1.051 / 1.543 | 1.090 / 1.814 |
| New service typing to paint (n=100) | 0.613 / 1.201 | 0.984 / 1.337 | 0.939 / 1.546 |
| New service inactive typing to paint (n=100) | 0.612 / 0.919 | 0.965 / 1.247 | 0.943 / 1.193 |
| Cached Mermaid dispatch EDT (n=100) | 0.017 / 0.032 | 0.016 / 0.033 | 0.015 / 0.035 |
| Cached Mermaid request/result (n=100) | 0.131 / 0.211 | 0.126 / 0.175 | 0.105 / 0.208 |
| Cold Mermaid renderer (n=1) | 1845.085 / 1845.085 | 1863.311 / 1863.311 | 1898.755 / 1898.755 |
| Warm/changed Mermaid renderer (n=11) | 315.468 / 566.639 | 310.602 / 554.279 | 322.144 / 562.914 |
| Changed Mermaid request/result (n=10) | 316.579 / 567.782 | 311.731 / 555.559 | 323.401 / 564.114 |
| Queued math wait behind Mermaid (n=8) | 1911.193 / 1911.411 | 1926.803 / 1927.101 | 1958.693 / 1958.953 |
| Math renderer, includes first use (n=8) | 0.077 / 65.441 | 0.087 / 62.441 | 0.079 / 58.790 |
| Cached math request/result (n=20) | 0.099 / 0.134 | 0.059 / 0.092 | 0.062 / 0.110 |
| Local image decode (n=1) | 0.373 / 0.373 | 0.487 / 0.487 | 0.377 / 0.377 |
| Image capture/validation (n=20) | 0.205 / 0.420 | 0.196 / 0.362 | 0.224 / 0.442 |
| Cached image request/result (n=19) | 0.336 / 0.548 | 0.295 / 0.564 | 0.317 / 0.598 |

The actual packaged service runs beside Shed's real source editor. All 100 measured typing samples at each size overlap an active cold Mermaid worker, rendering an independently captured media-document revision. One semantic analysis prepares that small media revision; ordinary source keystrokes do not trigger semantic rendering/analysis through this service. Source typing is restored through normal undo, with only the expected 200 revision changes. This is completed Swing paint, not physical keyboard/compositor latency.

The matched `media-control` runs exercise the existing renderer operations in before/after JARs, with the same cold/changed/themed diagrams, math and image. Their diagnostic worker measures renderer/native footprint and typing interference; it does **not** claim that the old preview was asynchronous. Legacy timings include its existing PNG file generation; new service timings include bounded in-memory rasterization. Cache lookup, cold initialization and warm rendering are distinct operations. The evidence index retains every raw sample, queue/worker duration, allocation and control result.

| 1 MiB source phase | Before legacy control heap used / committed (MiB) | After legacy control heap used / committed (MiB) | New service heap used / committed (MiB) |
| --- | ---: | ---: | ---: |
| Before media | 22.10 / 66 | 22.01 / 66 | 22.07 / 66 |
| Retained | 49.50 / 174 | 49.48 / 170 | 66.39 / 230 |
| Disposed | 49.09 / 174 | 49.07 / 170 | 47.96 / 167 |

| 1 MiB source RSS (MiB) | Before legacy control | After legacy control | New service |
| --- | ---: | ---: | ---: |
| Before media | 668.75 | 676.75 | 672.44 |
| Retained | 914.77 | 906.75 | 957.84 |
| Disposed | 914.69 | 906.75 | 893.48 |
| Sampled peak | 1221.70 | 1234.75 | 1226.30 |

All three service cases remove every tracked raster, handle, session and queued job on close, and the disposed service weak reference is collected. There are eight queue-limit rejections, one duplicate coalescing event and one stale-request rejection per case. Recorded resident raster payload is 16,087,236 bytes before disposal and zero afterward. Request/renderer execution remains off the EDT.

Native-memory attribution remains **unresolved**. These richer repeated-render controls exceed the historical 912.69 MiB RSS observation on both the before artifact and new service; differences cannot be attributed solely to the service cache. Collection and zero raster accounting do not imply that fonts, native resources, committed heap or RSS return to startup levels. No significant persistent service-owned asset/thread retention was established by these observations; whole-process memory equivalence is not proven. See the evidence index for all sizes, sample-count caveats, startup observations and regression budgets.

## I. Packaging

The existing packaged Mermaid/math/local-image smoke passes against the final JAR, preserving both Truffle provider registrations. The new service workload is independently compiled against and executed with only that packaged JAR plus probe classes, so Maven's development dependency classpath cannot rescue its renderer. The control probe also compiles against the preserved before artifact. No packaging/dependency/resource registration was changed.

## J. Regression verification

Final full Maven result: **1,471 total, 1,466 passing, five existing skips, zero failures/errors**. New service tests: **31 passing**. Benchmark tooling: **12 passing**. Packaged smoke, nine matched renderer/service cases and six empty-startup controls passed. Exact commands, suite counts, skip reasons and actual artifacts are archived.

```sh
"$MVN" -B -Djava.awt.headless=true -DargLine=-Xmx512m \
  -Dtest=MarkdownMediaServiceTest,MarkdownPreviewRendererTest,MarkdownPreviewPaneTest test
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar
```

The five existing skips concern platform DOS attributes, unavailable Fish/CTest tooling and two opt-in AI checks. No test was disabled or weakened. Development failures remain archived: an incorrect test constructor and JLaTeXMath accepting an unbalanced incomplete expression; the latter led to explicit adapter balance validation rather than treating that output as an error-free formula. Final current and starting-HEAD `git diff --check` passed.

## K. UI/UX compliance

No editor, preview-pane, navigation, picker, command, layout, visual component, menu, theme, typography, font choice, syntax color or keybinding changed. There is no inline rendering, new setting, panel, dialog or UI dependency. Plain/Vim/Emacs, split ownership and IDE capabilities remain on their existing paths. The new service has no production call site or eager startup work.

## L. README and repository compliance

README SHA-256 before and after:

`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`

README was not edited. Starting HEAD was `fa144c0450eb364c21060584d33f0313c9b7111f`, with a clean working tree. A preimplementation inventory captured 1,143 file hashes and a preserved before JAR. External activity advanced HEAD during execution; final identities and preservation checks are archived rather than assuming HEAD stayed fixed. No reset, clean, revert, commit or push was issued by this task. Previous performance evidence remains byte-identical. No dependency upgrade or source/document ownership change occurred.

## M. Remaining limitations

- General inline/multiline math source mapping remains unsupported. The narrow adapter handles exactly one existing expression form occupying a verified single-line paragraph. Unknown/stateful/resource-loading commands are unsupported by this new service; existing preview syntax remains unchanged.
- Mermaid rendering is cooperative only before/after the library call. Cancellation may wait for its actual return/library timeout; a pathological library render is not a hard per-request heap/time guarantee. Source/output admission is deliberately conservative.
- The current preview remains synchronous and shares renderer serialization. If a future consumer runs media concurrently with that preview, the preview can wait for the Mermaid/math lock on the EDT. Do not claim simultaneous-preview responsiveness from the source-only media measurements. That integration needs its own scheduling/ownership evaluation before shipping.
- Safe SVG excludes active/resource-bearing elements, filters/masks/patterns and CSS escapes/imports; unsupported constructs receive explicit outcomes. Raster animation, remote media, live-editor sizing/layout and arbitrary math font substitution are not delivered.
- Source/project/theme/resource lifecycle notifications remain the responsibility of a future host using its existing notifications and session API. The service is not a watcher. A lease is not authority to present stale content; check current context before presentation.
- Native-memory attribution is unresolved. Only this macOS/Java 21 host and packaged JAR were exercised; I cannot verify runtime behavior on another OS/filesystem or native installer.

## N. Tranche 1C disposition

**Tranche 1C remains incomplete.** This service removes expensive media work from the EDT only for callers using the new API. It does not rewire `MarkdownPreviewPane`, move live `setText` off EDT, replace the HTML reader or solve stock HTML View layout, large-table callbacks or resize/reflow. The earlier duplicate-initial-render and HTML-reader range-copy improvements remain unchanged. No end-to-end preview responsiveness claim follows from these media measurements.

## O. Next tranche recommendation

The headless service is a building block for a separately authorized source-preserving presentation prototype, not evidence that general Tranche 4 rendering is ready to ship. Before inline integration, establish a narrow host adapter that captures existing document/pane identities, uses current semantic results, maps supported media only, closes/updates sessions on existing lifecycle notifications, validates leases at presentation and budgets visible assets/painting. Verify concurrent legacy-preview behavior explicitly; avoid EDT lock waits by evaluating scheduling/preview coordination first.

General math ranges need a separately verified semantic adapter; unsupported forms can remain explicit during a limited image/Mermaid prototype. Retain the native-memory qualification and establish representative media count/decoded-pixel retention gates before persistent visible consumers. Do not route highlighting through full parsing or replace source, undo or persistence. No Tranche 3C, 4, large-file editing or Appearance & Personalization work was begun here.
