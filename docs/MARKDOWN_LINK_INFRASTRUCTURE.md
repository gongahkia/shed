# Markdown link infrastructure — Tranche 3A.1

This tranche provides headless, project-scoped link facts, resolution and incremental backlinks. It adds no navigation commands or visible components. Tranche 3A.2 and later work have not begun. Reproduction commands, measurements, test summaries, source identities and preserved development failures are in the [evidence index](performance/markdown-tranche3a1/INDEX.md).

## A. Architecture

`MarkdownSemanticParser` now projects bounded wiki tokens from CommonMark-admitted text with verified original source spans. It does not modify the CommonMark AST or the preview parser. `MarkdownOccurrences` exposes these tokens alongside established links, images and reference definitions. `MarkdownLink.project` copies only compact, immutable source-qualified link facts and heading identities; it drops semantic snapshots, document text and ASTs.

`MarkdownLinkResolver` runs on a worker and produces immutable resolution results. `MarkdownLinkIndex.build` discovers a bounded project partition, updates saved and unsaved contributions, resolves outgoing links and constructs reverse edges. `MarkdownProjectLinks` owns explicitly requested partitions on the EDT, captures immutable sources and installs only current results. Queries are memory-only; no filesystem access or parsing occurs in them.

The authoritative document remains `FileBuffer`, with IDs from `LegacyDocumentStore` and revisions/snapshot identity from `MarkdownSemanticSnapshot.Source`. Files use existing normalized real-path identities through `WorkspaceRootResolver`. Disk captures reuse `WorkspaceTextFile` (bounded reads, encoding handling, attributes checked before/after, hashed disk versions). No independent document store or persistent index was added.

## B. Files changed

This inventory covers the entire task, including work incorporated by external commits while the task ran; a final `git diff` alone does not contain every change.

Production additions:

- `src/main/java/shed/MarkdownWikiLinks.java`: bounded recognition inside verified CommonMark contexts.
- `src/main/java/shed/MarkdownLink.java`: compact facts, source identities, coverage and resolution records.
- `src/main/java/shed/MarkdownLinkResolver.java`: project/file/heading resolution and ambiguity.
- `src/main/java/shed/MarkdownLinkIndex.java`: bounded discovery, saved/overlay facts, incremental graph projection.
- `src/main/java/shed/MarkdownProjectLinks.java`: lazy headless lifecycle, worker scheduling, coalescing and queries.

Production modifications:

- `MarkdownSemanticSnapshot.java`, `MarkdownSemanticParser.java`, `MarkdownOccurrences.java`: verified wiki records and precision reporting; existing constructor compatibility retained.
- `MarkdownAnalysisService.java`, `MarkdownController.java`: read-only access to an already-current weak semantic result, without starting analysis.
- `ProjectFileScanner.java`: a streaming bounded Markdown discovery entry point; existing scanning behavior retained.
- `Texteditor.java`: lazy headless accessor and existing lifecycle notification delegates.
- `PaneBufferController.java`: notify an initialized index after existing source/dirty updates.
- `RecoveryController.java`: notify an initialized index when existing external-change detection reports a change.
- `WorkspaceController.java`: reconcile initialized project partitions on existing root updates.

Tests: new `MarkdownLinkInfrastructureTest.java`; extended `TexteditorSwingIntegrationTest.java`; `MarkdownSemanticParserTest.java` updates the formerly deferred-wiki assertion to retain the still-deferred math assertion. Existing tests were not disabled or weakened.

Benchmarks/tooling: new `src/benchmark/java/shed/MarkdownLinkProbe.java`; `MarkdownPerformanceProbe.java` dispatches the opt-in mode reflectively; `scripts/benchmark-markdown.py` admits bounded `project-links` workloads; `scripts/test_benchmark_markdown.py` checks their admission limits.

Documentation: this report and every new file under `docs/performance/markdown-tranche3a1/`, enumerated in that directory's manifest. Prior performance evidence is unchanged. No dependencies, document-model files, lexer, source-view implementation or preview renderer changed.

## C. Link semantics

Ordinary inline links, resolved reference links/definitions, images, fragment-only links, external URLs and simple raw destination/reference spellings reuse the verified 2B occurrence API. Complex raw spelling can remain absent while the parser-decoded destination is known. Definitions are outgoing informational records; actual reference uses create reverse edges. Images create file references but are never rendered here. Unresolved CommonMark reference uses remain unclassified text, without invented destinations or locations.

Wiki forms include `[[note]]`, `[[folder/note]]`, `[[note.md]]`, `[[note#Heading]]`, aliases and combinations, including Unicode and literal percent characters. Recognition uses original UTF-16 source segments, not decoded Text literals. Escapes, inline/indented/fenced code, unclosed fences, Markdown link destinations, existing link/image labels and HTML blocks are excluded. Formatting split across parser spans, table-inline positions, overlong tokens and opaque inline HTML/math contexts do not acquire guessed locations. Their precision/admission loss is explicit. A paragraph containing inline HTML or dollar-bearing text is conservatively opaque for wiki recognition; even literal currency can therefore defer recognition. Malformed tokens are ignored. CommonMark reference collisions inside a valid wiki wrapper are projected once.

The inline extension API was investigated: its scanner does not expose the enclosing parent needed for these exclusions. A focused AST-text projection was chosen. There is no additional full-document regex scan, preview extension or edit-triggered independent scanner.

Resolution rules:

- Ordinary relative paths use the source directory. URI percent decoding uses strict UTF-8 and preserves `+`. Encoded dot components are checked after decoding. Query-bearing destinations, unsupported schemes and backslashes are explicitly unsupported. HTTP(S), mailto, FTP and protocol-relative URLs are classified as external, without network requests.
- Qualified wiki paths use the project root; explicit `./` and `../` use the source directory. Missing Markdown extensions try `.md` and `.markdown`. Basename-only wiki links require a unique discovered target. Multiple candidates, including both extensions, are explicit ambiguity. Incomplete discovery cannot establish uniqueness.
- Candidate lookup uses a normalized name bucket, followed by native `Files.isSameFile` checks; it does not impose case-insensitive file identity. Explicit paths use native real paths. Discovery does not follow symlinks. Explicit in-project symlink targets resolve to their real identity; escaping links are rejected. Dirty sources opened through a directory symlink with an unverified relative-link base are unsupported.
- The deepest configured root owns nested files. Parent partitions exclude configured child roots; link resolution cannot enter them. Hard-link aliases become conflicts when the filesystem supplies file keys, avoiding arbitrary relative-link bases. No rename rewriting or automatic creation occurs.
- Ordinary fragments use exact semantic anchors. Wiki fragments first use exact semantic heading text; duplicate text is ambiguous. Anchor fallback requires compatible verified heading data. Missing, ambiguous, unsupported and pending headings remain distinct from file resolution. A verified file backlink survives a missing heading. Math-bearing headings make preview-anchor compatibility unsupported for that document; preview behavior is unchanged.

## D. Index model

Each requested project has one saved contribution per canonical Markdown path and an effective contribution after overlay reconciliation. Compact immutable outgoing edges are shared by the reverse map. Results sort by source file, source offset and kind. Repeated occurrences at distinct offsets remain distinct; rebuilding never appends duplicate prior contributions. Cycles require no recursive traversal.

Refresh streams bounded metadata discovery, reuses unchanged saved facts and overlays, and parses only admitted changed revisions. An unchanged source/target edge can be reused when the complete discovered catalog is unchanged and its path directly addresses an indexed target. Symlink aliases and non-catalog targets are re-resolved, so independent retargeting/deletion is not hidden by edge reuse. A changed heading invalidates incoming heading resolutions. Additions, deletions and renames reconcile through discovery; a renamed literal destination becomes missing until source is edited.

Partitions are disposable and rebuildable. Source filenames/IDs, versions, destinations, aliases, verified range segments, headings and graph edges are retained; source text, raw file bytes, ASTs and strong semantic snapshots are not. The only retained snapshot references are weak, bounded overlay identity checks.

## E. Unsaved-buffer handling

The facade captures dirty Markdown buffers through the existing store and immutable snapshot APIs on the EDT. Shared splits have one buffer ID and one contribution. Two distinct dirty buffers for the same canonical path are a conflict, never an arbitrary winner. Saved facts remain separate while an unsaved overlay replaces their effective edges.

Existing edits mark the index pending immediately and restart its debounce. Saving forces the changed disk path to be recaptured and removes a now-clean overlay atomically with the new saved facts. Closing/discarding a dirty buffer removes its overlay and restores the saved contribution. Closing a clean buffer does not trigger needless invalidation. Existing workspace batch edits, saves, external changes, buffer closure and root updates supply notifications; no second buffer lifecycle or recursive watcher was introduced.

Untitled documents have no project file identity and are excluded. A named but not-yet-saved dirty source can contribute links into its project; resolution targets must exist on disk. Missing target files are never created. Closed-file changes are discovered on explicit refresh; open-file changes use Shed's existing detection. There is no claim of continuous monitoring of every project file.

## F. Completeness

File facts distinguish complete, pending, partial, unsupported, failed, stale, cancelled and conflicting input. Project queries always carry completeness and incomplete-file information, including empty queries. Discovery caps/failures and buffer admission are represented in state/detail even when the omitted filenames are unknown.

Pending, cancelled or failed facade queries expose no confirmed prior edges. Old snapshots may remain internally for bounded reuse, but their backlinks are withheld. Stale job completions are rejected; a disk snapshot changed during capture becomes stale/partial. Partial projects can expose verified edges alongside incomplete-file information. A complete empty result means no matches in the supported verified syntax, not successful analysis of deferred syntax. Unresolved reference text, unsupported math/HTML wiki contexts and inaccurate table-inline positions are not fabricated into records.

Limits remain: 1 MiB UTF-16 semantic source cap and all 2A structural caps; 2B dense-marker admission is preserved. This index applies that admission without running the legacy heading fallback. Unsupported inputs acquire no legacy-regex backlinks.

## G. Concurrency and lifecycle

Construction is lazy. Opening Shed, opening a file or calling the unrequested facade does not scan a project. Only `request(root)` creates a partition and starts work. The facade accepts configured roots only and admits at most four partitions. `refresh`, `outgoing`, `backlinks`, `resolve` (installed occurrences only), `metrics`, `cancel`, `closeScope` and `close` form the headless API. Resolution results include eligible ambiguous candidates.

One lazily initialized daemon worker uses `AsyncJobService` with a one-thread executor and one queue slot. Project discovery/CommonMark passes can take hundreds of milliseconds, so isolation avoids occupying latency-sensitive IDE pool jobs. The coordinator submits one actual job at a time and retains at most one pending generation per admitted root. A 180 ms quiet period coalesces document notifications. Completion respects the timer rather than restarting cancelled work on each edit. Explicit refresh may bypass the quiet period. Repeated unchanged `request` calls do not start another pass.

Work captures root configuration, source IDs/revisions/snapshot identities and a generation. Cancellation flags are checked during discovery, source capture, admission and graph construction. Installation checks generation and current source identity again. A noncooperative obsolete completion cannot install. Completion hooks retain tickets/service references rather than captured source snapshots in job history. Closing the last scope shuts down its worker service; full disposal also clears suppliers/listeners to release owner references. Query APIs do not depend on active pane, selection, focus or viewport.

Admission limits per partition: 1,024 Markdown files, 32,768 discovered entries, depth 64, 16,384 combined saved/effective link-and-heading records, 32,768 retained range segments, 1 Mi UTF-16 units of retained metadata strings, and 16 Mi source units of parsing work per pass. Each file admits at most 2,048 links and 2,048 headings within 65,536 retained metadata units; each occurrence has at most 64 exact segments. Wiki tokens cap at 4,096 units and 4,096 records per parse. At most 64 dirty buffer snapshots/invalidation paths are admitted at the facade; overflow makes coverage partial. Disk capture caps at 4 MiB bytes before decoding, without expanding the semantic character cap. Limits yield partial coverage, not silent loss. Further explicit refresh can continue pending work; unchanged capped inputs are not repeatedly reparsed without a changed revision or invalidation.

Notifications currently invalidate all admitted partitions conservatively. This can do extra bounded metadata work across roots; it does not merge their graph contents. Discovery reuses `ProjectFileScanner` exclusions, not a new vault policy; it does not add interpretation of imported search filters or `.gitignore`.

## H. Performance

| Files / open source | Cold builder range (3), ms | Refresh p50 / p95, ms | Single-file p95, ms | Overlay p95, ms | Outgoing / backlink p95, ms | Typing inactive / concurrent p95, ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 21 / 4 KiB | 12.14–13.62 | 0.68 / 1.45 | 1.18 | 1.19 | 0.0131 / 0.0118 | 1.32 / 1.41 |
| 101 / 100 KiB | 20.11–27.26 | 1.81 / 2.56 | 2.91 | 2.44 | 0.0150 / 0.0110 | 1.44 / 2.31 |
| 1001 / 1024 KiB | 158.29–228.40 | 15.36 / 21.74 | 15.92 | 15.62 | 0.0141 / 0.0129 | 2.30 / 3.55 |

All measurements are opt-in, with bounded fixtures, a 512 MiB child heap, isolated homes and a watchdog. Project work uses the actual facade and workbench. Builder timings separately measure the production worker function, rather than substituting an isolated CommonMark benchmark. Query percentiles use 100 samples; refresh percentiles use 20. Cold builders have three observations, reported as a range rather than a reliable p95. Filesystem caches are warm/uncontrolled.

Same-source typing cancels obsolete indexing and postpones replacement work until the quiet period. A second test indexes a separate project while typing in the real source editor; its overlap count is recorded, so queued/pending work is not described as continuous concurrent parsing. Typed action to completed Swing paint excludes native keyboard delivery and compositor time. Paired before/after UI runs are preserved, including noisy observations; no general speedup is claimed.

Production budgets for this bounded same-host fixture: cold 1,001-file worker construction below 1 second, incremental refresh p95 below 50 ms, outgoing/backlink query p95 below 0.1 ms, request EDT below 10 ms, typing-to-Swing-paint p95 below 5 ms, and post-GC used-heap growth below 8 MiB with the project retained, plus service collection after disposal. These are regression thresholds for these fixtures, not guarantees for arbitrary projects. Startup requires zero index partitions/jobs until requested. Larger or structurally dense files remain capped. Actual RSS, heap, allocation, cleanup and startup observations and uncertainties are in the evidence index.

The 2B/2C native-memory qualification remains open. Index disposal and weak collection demonstrate lifecycle release; they do not establish native-memory attribution or compliance with the historical whole-process desktop RSS reference. No heap/GC, renderer, font or startup optimization was made.

## I. Correctness evidence

The new headless tests cover wiki forms and exact Unicode ranges; escaped/malformed/code/HTML/math contexts; references/images/definitions; native path case behavior; encoded paths; root boundaries, nested partitions, symlinks and retargeting; hard-link conflicts; unique/ambiguous basenames; duplicate headings, missing headings and anchor incompatibility; saved/unsaved overlays, save/discard reconciliation, duplicate split IDs and conflicting buffers; additions/deletions/renames; unchanged revision and edge reuse; snapshot replacement; 100 KiB/1 MiB support and caps; failures/cancellation/partial precision; stale completion, root reopening, bounded partitions/work and disposal.

Actual `FileBuffer` captures verify no source events, revision changes, undo additions, saved-baseline changes or dirty-state changes from indexing. Disk bytes and directory contents remain unchanged. A workbench integration test verifies lazy initialization, no owned windows, shared source across splits, ordinary edit notifications, save reconciliation, dirty discard and project closure. Earlier source ownership, preview, persistence, recovery, keymap and lexical highlighting cases remain enabled in the full suite.

## J. Regression results

The final suite contains **1,416 cases: 1,411 passing, five existing skips, zero failures/errors**. All 38 new headless link/index tests and the new workbench lifecycle test pass. Eleven Python benchmark-tooling tests pass. The packaged-only Mermaid/math/image smoke passes. Exact commands, per-suite counts, skips and logs are archived in the evidence index.

Development failures are retained: URI fragment decoding, discovery cancellation, native root aliases and the historical deferred-wiki assertion were corrected; an incorrect test offset and an over-cap benchmark edit were corrected without weakening caps; debounce measurements exposed and corrected premature restart; the stale-completion test now waits for both running and pending work to finish. Earlier unsuccessful runs are not counted as passing verification.

## K. UI/UX compliance

No navigation UI, panels, dialogs, menus, settings, framework or visual dependency was added. Existing layout, identity, design system, fonts, typography, themes, syntax colors, preview, outline, picker, navigation gestures, keymaps and split components are unchanged. No non-Markdown symbol extraction, lexer, highlighting pipeline, source view or document mutation boundary changed. This tranche's additional editor/controller calls only notify an already-initialized headless service.

## L. README and repository compliance

README SHA-256 before and after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. README was not edited. Initial status, HEAD, file hashes, external HEAD movement, changed-file inventory and final verification are archived. Previous uncommitted 2C work was preserved; shared controller/test files were extended. No reset, clean, revert, commit or push command was issued by this task. External activity committed work while it ran; that is explicitly distinguished from this agent's actions.

## M. Remaining limitations

- Complete indexing applies to the verified supported syntax. Unresolved CommonMark reference uses remain text. Complex raw link spellings can lack raw destination/form metadata. Wiki formatting across spans, escaped token contents, inline HTML/math contexts and table-inline locations can be unsupported; nothing approximates their source positions.
- Semantic/source/dense-marker/discovery/retention/workload limits remain. A partial index cannot establish basename uniqueness. Very large sources retain no fabricated fallback backlinks.
- Heading fragments require compatible complete target facts. Duplicate wiki heading names are ambiguous. Math-bearing heading anchors are conservatively unsupported, including literal dollar cases. Full preview-anchor unification remains deferred.
- Discovery skips symlinks; in-scope explicit targets can follow them safely. Directory-symlink source bases are unsupported. Hard-link conflict detection depends on native file keys. Case behavior was exercised on this host; I cannot verify runtime behavior on a second OS/filesystem in this environment.
- Closed files rely on explicit refresh. The metadata fast path cannot detect deliberately preserved size/mtime changes without a known-path invalidation, which forces a read. Filesystem changes across an entire pass are not atomic. Future navigation must validate the captured source/target version when opening/jumping.
- No persistent database, recursive watcher, global filesystem scan or target creation exists. No UI command invokes the service yet. Resource admission is intentionally finite. RSS/native-memory attribution remains unresolved; object/heap release is not equivalent to RSS returning to baseline.

## N. Tranche 3A.2 handoff

Connect explicit user actions to `Texteditor.markdownLinks()` and the owning existing project root. Reuse `MarkdownController`/existing link dispatch, `FilePickerDialog` filtering/row conventions and existing outline/symbol navigation components as appropriate. Use existing asynchronous open/jump methods and existing command feedback. Do not add a dedicated backlink panel or a vault abstraction.

First deliver explicit outgoing/backlink picker requests and wiki/ordinary-link follow using the same resolver results. Show ambiguous candidates through an existing picker, preserve source offsets and project identity, and carry partial/pending status through established feedback. Before source-reference or heading jumps, revalidate the open buffer ID/version or captured disk version; then derive line/caret state from the current authoritative snapshot. Preserve independent split selections/viewports. A file-resolved result with unsupported/missing heading can offer the existing file-opening behavior without choosing an arbitrary heading. Re-request stale occurrences rather than applying old coordinates.

Keep 3B media scheduling/rendering, 3C and live Markdown presentation separate. Neither network fetches nor renderer lifecycle belongs in this index. No rename rewriting, transclusion, graph UI, synchronization or persistent notes store is authorized by this tranche.
