# Track B0 — Whole-IDE capability, performance and regression audit

## A. Executive assessment

**Personal/development IDE use: CONDITIONAL GO. General public release and a universal “Lightspeed” performance claim: NO-GO.** Shed has a substantial general-purpose IDE implementation, not only Markdown tools: canonical multi-document editing, shared splits/history, language-server integration, project search, terminals, Git, tasks/tests, debugging, notebooks, remote integrations and reviewed agent proposals. Their qualification varies significantly. An implemented protocol client is not a qualified external toolchain.

The full regression suite passes, ordinary measured source input remains fast, and the existing split/Undo corrections remain covered. No new source-corruption or wrong-buffer Undo failure was observed. These strengths do not override confirmed production problems:

- First complex-text insertion into a representative 1 MiB Java source takes **5.78 seconds to completed Swing paint** in the current packaged application.
- The default-font production window visibly lacks glyphs for parts of the small international-text fixture.
- An explicit LSP definition request waits on the EDT: a controlled 200 ms server response delays queued input **210–216 ms**.
- The existing stock Markdown HTML-preview EDT stalls remain release blockers.

The 8H gutter exception is now reproduced and attributed to an invalid model offset in an **active** view. Its source/history checks pass. The old document-load timeout did not reproduce in three fresh matched-fixture trials; its original cause remains unknown. Mixed-script capture uncertainty is partly resolved: missing glyphs are visible in native window captures, while scale/context-dependent overprinting is not fully attributed.

A native-shell **test-isolation gap** also blocks further unisolated native runs. The existing PTY test and the initial new connector control inherit shell history/startup environment. A real history file's modification time overlaps the focused-test window; no before-hash exists, so attribution or content changes cannot be established. No history contents were inspected or restored. Native runs stopped; only the newly authored benchmark received a startup/history guard, compile-checked but not rerun. Test isolation must precede more native acceptance/performance work.

**No production fix, new feature, agent endpoint or appearance change is delivered.** B0 adds opt-in measurement tooling and this audit. The [capability inventory](WHOLE_IDE_CAPABILITIES.md) and [evidence index](performance/ide-trackb0/INDEX.md) are part of the report. Unknowns and failed checks are retained, rather than converted into passing qualification.

## B. Verified capability inventory

The companion inventory contains **37 checked entries**, with source owners, existing tests, dependencies, delivered contracts, performance evidence and missing verification. Named files were checked against the repository; source routes and assertions, not filenames alone, establish each listed behavior.

| Subsystem | What actually exists | Qualification boundary |
| --- | --- | --- |
| Editor/documents | Open/close/tabs; canonical `FileBuffer`/`PlainDocument`; immutable versioned snapshots; independent split selections/viewports; bounded Undo/Redo; multi-selection; Plain/Vim/Emacs; search/replace; codecs, saves and recovery | Supported tested operations pass. International layout/glyph/conformance limits remain; native IME and all OS interactions are not certified |
| Syntax/structural navigation | Built-in lexical highlighting, checkpoints/viewport tokens, local symbols and selected structural operations | This is not a bundled compiler/typechecker for every language; pathological lines/layout remain qualified |
| Language intelligence | LSP initialize/sync, push/pull diagnostics, completion/resolve/signatures, definition/type definition/implementation/references/hover, rename/format/code actions, symbols and advertised advanced requests | External compatible servers needed. Capability/model/protocol tests are extensive; actual server algorithms and several synchronous UI routes remain unqualified |
| Projects/workspaces | Folder startup, lazy tree, file operations, multiple roots/deepest-root routing, sessions/manifests, bounded VS Code task/launch/editor-setting import | Strict supported subset, not general VS Code compatibility or an extension host |
| Search/index | Ignore-aware file index, watch updates, bounded streaming search/replace, explicit local symbol fallback | An indexed file list is not a semantic language database. Result, file, path and scan limits apply |
| Terminals/processes | JediTerm/pty4j PTYs, input/resize/output/exit, shell profiles/metadata, clickable locations; async jobs, bounded output, cancellation, quickfix | Real PTY controls run here. Complete terminal-render throughput and every shell/platform/process-tree pattern remain unqualified |
| Git | Local detection/status/diffs, stage/unstage/hunks, branches/history, conflict/repository/worktree/stash interfaces; explicit remote operations | Models/routes and UI smoke tests exist. B0 CLI controls do not certify every committed-history or hosted-remote workflow |
| Build/run/tests | Explicit workspace tasks and dependencies, process/shell/environment plans, output/error navigation; Test Explorer/adapters/watch/coverage and DAP launch integration | Installed tools/appropriate reports required. Opening the test panel is not executing/discovering a project automatically |
| Additional IDE integrations | DAP debugging; notebooks; explicit remote/SSH/Docker/WSL/Dev Container flows; PostgreSQL/SQLite/MySQL CLI wrappers; GitHub PR/review operations | Mostly external-tool/provider-dependent. No credentials, servers, containers, database sessions or hosted AI were activated for this audit |
| AI/workspace control | OpenCode ACP Assistant/inline completion; captured unsaved context and reviewed proposals; window-free workspace CLI; explicitly started authenticated local bridge | Opt-in provider workflows and a useful existing machine boundary, not yet full external editor control. Existing API startup is read-only or proposal-only |
| Appearance/Markdown | Qualified focus/center, manual theme preview, density, active-pane emphasis; source Markdown commands, links/backlinks and separate preview/media | 8H/7C limitations unchanged; transparency/blur/OS-following/live source media and editable 50–100 MiB are not delivered |

Generic terminals can run a user-invoked local coding-agent executable, and file watching can observe resulting changes. That is not a dedicated agent-control API or an operating-system sandbox. Existing application policy does not contain arbitrary external executables.

## C. Performance baseline

### Method and environment

Current checkout entry HEAD **`1c8968c01060f514a38cbcf11f354cc1b9e0b29e`**, initially clean. Frozen packaged JAR SHA-256 **`95f27180c5d955217a8dd9e531d81fc4c1f25d6dc873d77fc0c51dca5ae67f4a`**, identical to 8H's measured artifact. Production sources are unchanged. Host: Apple M3 / Mac15,12, eight CPUs, 16 GiB, macOS 26.5.2 arm64, Temurin 21.0.13+9, Maven 3.9.11. Exact per-matrix host load, pressure, swap, display information, JDK output, argv, fixture and probe hashes are archived. Load/caches and unrelated applications are uncontrolled; no settings were changed or RAM intentionally exhausted.

Fresh JVMs use isolated homes, existing **64 MiB initial / 512 MiB maximum** heap controls, Shed Light, JetBrains Mono and Plain mode. No heap increase was used to hide a failure. The established desktop harness measures launch from its parent process. New multi-scenario probes also report **probe-main-relative** construction/load/paint times; those exclude early JVM startup and must not be relabelled full process launch. Fresh process is not cold-cache launch.

Source input uses existing controller/default typed action through attributed completed source Swing paint. It is not physical key-to-photon. Explicit operation controls separately time source mutation/dispatch, layout and forced root paint, not the complete queued paint lifecycle. Canned LSP timings measure local transport/client parsing, not real language analysis. Direct Git controls are CLI references, not full workbench latency.

Each ordinary-input phase has one separately retained first input followed by 50 samples. These are chronological first/warm groups, not discarded failures. Service requests similarly retain sample zero separately. Small sample sets use ranges/medians or explicitly observed maxima; no reliable population p99 is inferred from three launches or five scans. Raw samples remain authoritative.

### Startup, readiness and shutdown

| Boundary | Current observation | Meaning |
| --- | --- | --- |
| Established desktop harness, three fresh file launches | Constructed-window proxy p50/max **1,673.00 / 1,691.64 ms**; source **2,003.32 / 2,078.00 ms**; full UI **2,016.32 / 2,088.00 ms** | This set meets existing 2/3/10 s diagnostic gates; only three processes; not physical first-display timestamps |
| Desktop edit/forced paint and picker ranking | p95 **2.37 / 8.29 ms**, 75 samples each | Document+forced-paint and ranking boundaries, not complete hardware input or picker opening |
| No-file first scenario | Main→constructed **3,917.04 ms**, main→paint **4,152.01 ms** | Slower observation retained; misses the corresponding constructor/explicit-paint proxy budgets even before JVM startup. Actual first native visibility was not independently observed |
| 1,000-file folder launch | Main→constructed **1,735.20 ms**, main→ready painted workspace **1,945.64 ms** | One process; lazy visible tree, not fully expanded project indexing |
| Three 4 KiB code launches | Main→loaded **1,907.97–1,925.15 ms**, painted **1,917.21–1,934.23 ms** | Repeated current production controls |
| Three 100 KiB Markdown launches | Main→loaded **1,917.05–2,046.43 ms**, painted **1,926.51–2,055.69 ms** | All pass the loaded-editor wait; old 30 s timeout not reproduced |
| Additional input-readiness control | Parent launch→first successful synthetic input and observed completed paint **1,917.54 ms** | One 4 KiB process; upper bound including observation/polling, not physical first-input readiness |
| Close with no active external work | Instrumented control: shutdown request→parent exit observation **≤154.3 ms** | One process, coarse parent observation; no general p95 |
| Close with controlled task and LSP peer active | **≤502.9 ms** to parent exit observation; both recorded children gone at the later 0.5 s check | Task cancellation succeeds; protocol-shutdown gap described in E |

No startup regression is attributed to Markdown/appearance from unmatched historical values. This is the same artifact as 8H; its three successful startup launches do not erase 8H's timeout, 7C's slower first launch or B0's slow no-file observation.

Methodology caveat: the established harness prints `SHED_WINDOW` after the EDT constructor returns. Its “visible window” budget is therefore evaluated against that proxy, not a captured native visibility/compositor event. Source readiness is an explicit forced paint. Keep those existing metrics for continuity, and add actual event/input timestamps before claiming precise first-visible/first-painted product readiness.

### Ordinary source editing

Completed source paint, milliseconds, n=50 warm inputs per listed process:

| Fixture | First input | Warm p50 | Warm p95 | Observed p99/max |
| --- | ---: | ---: | ---: | ---: |
| Empty editable document | 4.58 | 0.71 | 1.18 | 1.46 |
| 4 KiB code, first of three launches | 3.92 | 1.04 | 1.79 | 2.75 |
| 100 KiB Markdown, first of three launches | 5.82 | 0.69 | 1.68 | 1.90 |
| 1 MiB code | 5.26 | 1.02 | 3.21 | 3.70 |
| 10 MiB code | 6.10 | 0.82 | 1.44 | 1.69 |

Other small-code/Markdown trials' warm p95 values are **1.08–2.03 ms**. These are short-line unwrapped fixtures, not qualification for every document of that size. Newline+Undo, one-character deletion+Undo, selection/caret and scrolling run 20 forced-paint samples per file process, with exact final source checks. Their distinct component timings are archived, not mislabeled native keymap or grapheme benchmarks. Existing 8G.1/8H split creation/closure, source selection, Undo, density/focus/theme compatibility and view-retention evidence is retained rather than redundantly recreated.

**Current Unicode failure:** the existing `UnicodeLayoutProbe` was rerun once on the 1 MiB ASCII **Java** fixture, preview closed. After ordinary edit/Undo/Delete/Newline controls, its first complex-text Swing typed action takes **5,767.01 ms EDT dispatch / 5,776.45 ms input-to-completed-paint**. EDT cumulative allocation is **48,040,152,448 bytes (~44.74 GiB)** during that operation, not simultaneously retained heap. Final used/committed heap is **423.75 / 512 MiB**, with 608 observed JVM collections across the control. Source and Undo round trips are exact. This is a general production source-view problem, not evidence that Markdown parsing alone is responsible. Prior 7B.3 profiles and 7B.4 NO-GO remain relevant; no new stack attribution or optimization is claimed. Warm mixed-script layout across all scripts remains incompletely qualified.

### Language, project, Git and process controls

| Control | First / subsequent observations | Boundary |
| --- | --- | --- |
| Synthetic LSP process initialize, three cycles | First **48.20 ms**; later ≤**39.90 ms** | Java child process, advertised capabilities; no semantic engine |
| didOpen→diagnostic-read after hover barrier | First **0.66 ms**, later ≤**0.41 ms** | Diagnostic present and asserted; not exact publication-arrival timestamp |
| Completion/definition/references/hover/workspace symbols/format/code action | First **0.23–2.92 ms**; warm p95 **0.22–0.34 ms**, 50 each | Nonempty/expected parsed results asserted; no completion/edit application inferred from transport speed |
| LSP stop, three cycles | First **12.79 ms**, later ≤**22.60 ms** | Explicit client stop; child confirmed dead |
| Scanner 100 / 1,000 / 5,000 files | First **6.24 / 8.33 / 45.04 ms**; later maximum **1.45 / 7.70 / 39.15 ms**, five each | Bounded actual `ProjectFileScanner`, not full rendered Explorer |
| Persistent file-index build, same counts | First **67.22 / 25.16 / 70.31 ms**; later maximum **20.49 / 27.12 / 74.88 ms** | Actual service, exact output counts, closed afterward; source metadata/ignore checks are not language indexing |
| Literal rare-target search, same counts | First **15.99 / 33.12 / 223.04 ms**; later maximum **6.93 / 46.88 / 156.33 ms** | Actual indexed search reads, exactly one match; tiny synthetic files and bounded results |
| Git status, 1,000-file synthetic unborn repo | First **38.38 ms**; 25 subsequent median/max **10.60 / 14.66 ms** | Direct Git CLI reference; no repository commits |
| Git worktree diff after stage + external modification | First **13.51 ms**; subsequent median/max **8.80 / 9.79 ms** | Fixed single-file diff, not whole workbench or large history |
| Stage / staged-name diff / unborn unstage | **11.98 / 8.93 / 8.18 ms**, one each | Synthetic index only; source fixture restored in finally; not HEAD-based unstaging qualification |
| Fixed task output/environment | First **7.34 ms**; 11 later ≤**7.12 ms** | `BackgroundTaskProcess`, bounded output and expected env value asserted; no real compiler build |
| Ready watch-task cancellation | First **5.78 ms**; later ≤**7.35 ms** | Owned bounded local process; not every descendant pattern |
| PTY create→input/resize/output→exit | First **594.47 ms**; 11 later ≤**19.17 ms** | Real pty4j/connector, `stty` and output asserted; not JediTerm GUI throughput |

Full real language-server projects, rendered tree expansion, complete Git UI refresh, debugger throughput, real compiler/test-engine overhead, terminal repaint/scrollback and repeated project-switch memory are **unmeasured** here. Existing deterministic tests validate supported behavior but do not supply those performance numbers.

### Background interference

One source window and one finite existing-service worker at a time; each worker runs for up to ~600 ms. All 50 measured warm input dispatches in each set occurred while that worker was still active:

| Background operation | Typing-to-paint p95 |
| --- | ---: |
| Project text search | **1.73 ms** |
| Git status CLI through bounded process backend | **1.34 ms** |
| Bounded command-output streaming | **1.42 ms** |
| Local LSP requests on worker, 50 ms controlled responses | **0.62 ms** |

These are service/process interference controls, **not full terminal-widget or build/test-panel interference certification**. Current full-suite tests cover panel completion/cancellation source preservation. Markdown indexing, Mermaid first-use and preview interference retain their separately bounded earlier evidence; they were not reinterpreted as B0 whole-IDE passes.

The production explicit **`:lsp definition` path** is different: `LspController.lspGoToDefinition` calls the synchronous client on the EDT. Three 200 ms peer responses give queued-EDT barriers **210.47 / 215.70 / 212.64 ms**. A queued barrier is not hardware input, but it directly demonstrates that source-input dispatch cannot run during that wait. Both worker-success and EDT-blocking results are retained; one does not excuse the other.

## D. Regression coverage matrix

Legend: “desktop” means actual Swing/window or native process execution here, not Windows/Linux qualification. “Partial” means specific tests/controls, not a complete user journey. Representative exact test owners are in the 37-entry inventory and full-results JSON.

| Area | Unit/service | Integration | Actual desktop/native | Performance | Resource lifecycle | Other OS GUI |
| --- | --- | --- | --- | --- | --- | --- |
| Documents/Undo/codecs/recovery | Yes | Yes | Swing + local files | Current source controls; previous save/split controls | Buffer/watch/history and split tests | Unverified |
| Keymaps/Unicode/selection | Yes, known corpus gaps | Yes | Swing actions; native IME incomplete | ASCII current; complex first-use fails | Shared source/view tests | Unverified |
| Syntax/source geometry | Yes | Yes | Root and native captures | 1B/1D plus current controls | View/checkpoint tests | Unverified |
| LSP | Capability/parsers/edits | Local stdio peers | Real child processes; selected UI routes | Canned transport and explicit EDT delay | Start/stop/late-result tests; shutdown gap | Unverified |
| Project/tree/search/multi-root | Yes | Yes | Folder/tree/search UI tests | Scanner/index/search + one project startup | Index cancellation/disposal, roots/watch tests | Unverified |
| Terminal | Emulator/profile/link tests | PTY/shell | PTY + Swing terminal tests | Connector roundtrip; GUI output missing | Close/exit/input ownership tests | Unverified; Fish unavailable |
| Git | Structured model/validation | Local command/UI smoke | Workbench dialogs | Direct CLI reference; UI missing | Some cancellation/project tests | Unverified |
| Tasks/build/tests | Plans/importers/parsers | Actual fixed processes, UI completion/cancel, selected task graphs | Local subprocesses | Backend only; real engines missing | Bounded cancellation/watch tests | Unverified; CMake tools skipped |
| Debug/notebook/remote/database | Models, plans, policies | Fake/local peers and selected UI | Some Swing/adapter execution | Mostly missing | Specific session tests, not all providers | Unverified |
| AI/workspace API | Policy/protocol/proposals | Official SDK/local peers and bridge | Synthetic peers; native AI opt-in skipped | Existing opt-in AI suite skipped | Sessions/context/bridge/recovery tests | Unverified |
| Appearance/Markdown | Extensive | Extensive | Headful/manual-theme/geometry/media | Current + retained 7C/8H | Galleries/views/media/index tests | Unverified |

**Coverage findings:**

- `make test` explicitly forces headless mode. This B0 full Maven run is direct/headful; headless-only CI must not be described as GUI qualification.
- Multiple performance probes duplicate reflective metric access, EDT dispatch, scratch-home setup and cleanup. Consolidation should follow a written metric/lifecycle contract; changing definitions mid-comparison would invalidate evidence. No cleanup refactor is implemented here.
- Existing 7C/8H startup variability and native Robot focus timeout remain timing-sensitive evidence. The 8H load failure has no saved timeout stack; it cannot be retroactively attributed.
- Source-preservation assertions are strong, but they do not detect all rendering defects: B0 exact-source controls pass despite visible missing glyphs and a caught gutter exception. Exit zero is not a visual pass.
- Bare protocol-result parsing and Git model tests do not prove real provider behavior or complete UI latency. In particular B0 LSP format/action controls do not apply their canned edits.
- The initial new interference harness incorrectly expected `getErrorMessage()==null` on success, where the existing job model uses an empty message. It stopped after successful search. The corrected check requires `Status.SUCCEEDED`; the failed run is retained and only that bounded case is rerun. No production behavior/assertion was weakened.
- Native shell isolation was insufficient: `PtyTerminalConnectorTest` launches `/bin/sh` without startup/history environment overrides; the initial new service probe did likewise. Java `user.home` isolation does not establish native shell history isolation. The observed history mtime is **2026-11-20 11:39:27 +08**, inside the focused-run period, but without an entry hash it cannot establish which process changed it or whether contents changed. Stop further native runs; do not overwrite personal history to manufacture preservation. The final new probe supplies `HISTFILE`, `ENV` and `BASH_ENV` as `/dev/null`; it is compile-checked only. Existing tests remain unchanged for a separate test-only remediation.

## E. Resource-lifecycle findings

The service child performs six scan/index/search cycles at each project size, three LSP processes, twelve task-output, twelve ready/cancel and twelve PTY cycles. Children return to **zero**. File descriptors go **4→7** after initial project/framework use and stay **7** after LSP/process cleanup; threads **6→10→13** as JVM/process infrastructure initializes. After requested diagnostic GC, used/committed heap is **5.54 / 64 MiB**, versus **2.03 / 66 MiB** at initial main. This is bounded endpoint evidence, not proof that every remaining descriptor/thread is a leak or every resource returned to its pre-class-loading baseline.

Normal desktop endpoint examples, without requested GC:

| State | Used / committed heap | Threads / open FDs | Sampled process peak RSS |
| --- | --- | --- | --- |
| Empty after bounded edit/Undo | **26.77 / 64 MiB** | **17 / 11** | **617.5 MiB** |
| 1,000-file folder | **25.91 / 64 MiB** | **21 / 11** | **617.6 MiB** |
| 4 KiB code | **35.54–36.94 / 64 MiB** | **19 / 11** | **654.7–661.2 MiB** |
| 1 MiB code | **32.48 / 66 MiB** | **19 / 11** | **668.2 MiB** |
| 10 MiB code after edits/history | **232 / 319 MiB** | **19 / 11** | **923.9 MiB** |

Do not apply the 768 MiB startup diagnostic ceiling automatically to a different active 10 MiB editing boundary. Conversely, these footprints do not support a universally low-memory claim. Settled long-idle RSS, low-system-RAM usability and complete native attribution remain **unqualified**; short child peaks and live heap are not substitutes.

One separate instrumented source-window control uses NMT-summary mode and a bounded five-second post-workload hold. `jcmd VM.native_memory summary` **times out at three seconds**; no NMT category result is claimed. `vmmap -summary` succeeds and reports physical footprint **468.8 MiB**, peak **509.7 MiB**, graphics mapping **73.6 MiB virtual / 36.2 MiB resident**, and mapped resident total ~**1.3 GiB**. These OS measures include shared/mapped categories and differ from RSS, committed heap and physical footprint. They cannot be summed or subtracted to label the remainder a leak. Profiling/attach changes the workload boundary; no claimed overhead or normal-memory improvement follows from this one control.

Existing [7B.2](BUFFER_LIFECYCLE_REMEDIATION.md), [8G.1](SPLIT_VIEW_LIFECYCLE_REMEDIATION.md) and [8H](APPEARANCE_FINAL_QUALIFICATION.md) evidence remains valid for its stated scope. The identical 8H artifact completed 24 closures with zero retired views/caret edges and 51 exact Undo/Redo pairs. Current full tests reverify shared owners, late callbacks and wrong-buffer Vim routing. That does not establish whole-main-window collection or native graphics release; the earlier reachable-main-window observation remains unresolved.

**Shutdown finding:** with a live controlled task and LSP peer, `closeEditor` cancels the task and exits. The shutdown hook sees one child still alive; both known children are gone at the later half-second check. The LSP fixture records `initialize`, `initialized`, then **EOF**, not `shutdown`/`exit`. Source inspection agrees: `LspController.shutdown()` cancels scheduling/decorations but does not stop every client in the map. Explicit service `LspClient.stop()` does send protocol shutdown/exit and terminates its child. This is a confirmed lifecycle-contract gap, **not a demonstrated persistent orphan leak** with this cooperative peer. Noncooperative servers/descendants are unqualified; no unbounded or hostile process experiment was run.

## F. 8H anomaly findings

### F1. Gutter exception — reproduced and attributed

Disposable current production window; small 430-UTF-16-unit mixed-script Markdown, two live shared-document panes, Shed Light/JetBrains Mono, density Comfortable, center/focus on→off, narrow resize then restore. All **four** cycles log the same caught `BadLocationException` from `LineNumberPanel.paintComponent:106` → `JTextArea.getLineOfOffset` during queued repaint. Full stack traces and geometry are archived.

At each failure the active pane is **not disposed**, document length **430**, visible bottom mapping **431**. `LineNumberPanel` directly converts this out-of-document offset into a line number. The inactive pane maps to 430. Source snapshot/revision remains identical after all transitions. A separate real unlaid-out `JTextArea`/production gutter control maps to **−1** and reproduces the adjacent start-offset check at line 105, independently of appearance or split disposal.

Confirmed cause at the failing boundary: unchecked invalid range input to `getLineOfOffset`. The source-view/layout lifecycle can temporarily supply an implied-end or unavailable mapping; B0 does not claim to have fully attributed the Swing view's sentinel geometry. **P2 reliability/rendering defect**, with possible skipped gutter paint; no source/history corruption observed. The next correction should validate/clamp only appropriate mappings and safely skip unavailable geometry, with deterministic queued-paint and source-preservation tests—not swallow arbitrary errors or rewrite layout.

### F2. Document load timeout — not reproduced; unresolved attribution

Original 8H failure: 100 KiB wrapped Markdown **reference** process, same production artifact, 30-second loaded-editor wait, before density workload events. Its old log has no stack/state snapshot. B0 runs three fresh 100 KiB instances generated by the same established mixed fixture; SHA-256 matches 8H exactly (`a19e41d1bdcc694464a5a2dfea8b8e0823ca1c8614b01b0d601ad09a9c834971`). All load in ~1.92–2.05 s from probe main, with literal path equality and filesystem identity confirmed. Post-load wrap/sidebar settings were not yet applied at the original failed wait. Host conditions and surrounding scratch project layout are not identical. No EOF/source/dirty mismatch appears.

B0's wait distinguishes actual same-file identity from lexical path equality, logs pending state, and would record an EDT stack at timeout. It does not reproduce the old 30-second stall. **Cause remains unknown:** disk, scheduling, layout, focus/load ownership and host contention cannot be distinguished retrospectively. It is not declared fixed, blamed on density, or reduced to a harmless cache miss. The slow no-file startup is a separate retained observation, not proof of the same cause.

### F3. Mixed-script captures — partly confirmed production defect

Two PID/title-owned native window captures and both 1×/2× component-root captures show the exact same synthetic source; no unrelated desktop was captured and no OS permission was requested. Actual device scaling is 2×. Native output **does show missing glyphs** for Indic/emoji/CJK portions. The configured physical JetBrains Mono font reports its first unsupported UTF-16 offset at **28**; document `i18n=true`. Source remains exact, so this is not inferred file-encoding corruption or a grapheme-boundary result.

The pronounced overprinting in the old 1× component image is scale/context-sensitive. Native and 2× images were inspected independently, but they are **not pixel-equivalent**: after removing the measured 56-pixel title-bar height, ~157,000 root pixels differ, including the recorded heading region. Native LCD/compositing, Graphics/font context, syntax overlay and observation timing were not isolated further. Therefore do not claim either that all overprinting is a capture-only artifact or that every source glyph has wrong production geometry. The failed optional Pillow import led to no installation; existing JDK ImageIO performed the numerical comparison.

**P1 for confirmed unreadable supported international text; remaining overprint attribution is P2 investigation.** Missing font coverage and the first-edit layout stall are distinct findings; B0 establishes no causal equivalence between them. No font, renderer, syntax color or text metrics were changed to improve captures.

## G. Cross-feature workflows

| Workflow | Verified evidence | What is not claimed |
| --- | --- | --- |
| Human open/edit/Undo/save/close | Current source round trips + full document/save/recovery/navigation tests, including ordinary save preserving history | Not a newly timed complete human journey with every file format/OS |
| Language intelligence | Real local process initialize/didOpen/diagnostic/completion/definition results, existing application/edit tests; production definition navigation delayed queue reproduced | Not hosted/real semantic-server quality, every language or complete completion acceptance timing |
| Build/test | Existing graph/env/prelaunch/panel completion/cancellation tests; B0 real bounded output/readiness/cancel processes | Not real Maven/Gradle/compiler build throughput across arbitrary projects |
| Git/edit/external changes | Synthetic stage→external append→worktree/staged diff→unborn unstage, restored fixture; existing status/UI, clean external reload and dirty-conflict Save As regressions | Not a complete committed-history/conflict/remote workflow or external Git refresh latency |
| Shared splits | Full 8G.1 exact history/retirement/keymap suite and same-artifact 8H rooted control | Not native IME/platform certification; gutter painting still fails |
| Appearance combination | Full focus/center/density/preview/active-pane regressions; B0 actual resize/focus cycle diagnostic | Passing source checks do not establish a clean visual result |
| Markdown commands/navigation/media/save | Full semantic/edit/link/backlink/persistence/media suites and packaged real image/math/Mermaid smoke | Component/test composition is not a newly benchmarked single all-in-one journey; preview responsiveness remains defective |

A composed set of passing steps is not called a fresh end-to-end user acceptance run. Missing journeys belong in Track B, not invented B0 passes.

## H. Agent-readiness inventory

| Intended operation | Existing reusable boundary | Current external availability / gap |
| --- | --- | --- |
| Launch/open project | `Texteditor.main`, launcher file/folder args; `DocumentWorkspace` | Local launch exists; no universal single-instance discovery/connect-and-open contract |
| Discover/connect | `:agent api start [propose]`, owner-only descriptor in session `agent-api` directory, `WorkspaceCli --workspace-api --root ... --connect ...` | Explicit attach exists; no automatic broadcast/discovery or agent-control server added by B0 |
| Read files/unsaved documents/search | `WorkspaceQueryService`, `EditorWorkspaceDocuments`, `WorkspaceDocument`, RPC | Workspace/document IDs, opaque version, UTF-16 offset/format/dirty/source metadata, bounded reads and provenance already available |
| Inspect active pane/cursor/selection | `DocumentWorkspace.activeDocument/activeSurface`, `EditorSurface.Selection/View/Viewport` | Internal EDT-owned seam exists; not exposed by inspected RPC query dispatcher |
| Move/select/navigate/open files | `EditorSurface`, `DocumentWorkspace`, navigation/lifecycle admission | No checked external navigation API; generic ex commands are unsuitable because of implicit focus/dialog side effects |
| Checked source changes | `WorkspaceProposalService`, `WorkspaceTextEdits`, `WorkspaceEditTransaction`, change journal/recovery | Existing reviewed proposals and generic capability-gated operations. Startup read-only/proposal-only policy **does not grant APPLY**, OPEN_UI or arbitrary shell execution |
| Diagnostics | `ProblemsService`, cached LSP diagnostics, quickfix/test sources | Internal models exist; scoped versioned external diagnostics/event subscription absent from inspected RPC |
| Render/observe/capture | Existing separate Markdown preview; opt-in `OwnedWindowCapture` benchmark | No production rendering/capture endpoint. Existing preview stalls and native capture permissions/privacy require explicit gates |
| Human/agent coordination | Captured versions, root/path policy, review/accept/rebase/recovery; cancellation generations | Strong foundation, not blanket concurrency safety for new focus/navigation/render actions |

The existing transport uses bounded JSON-RPC frames, authenticated loopback connection, private descriptor and workspace identity, eight-client limit, cancellation/disposal and policy checks. Disk-only CLI is window-free by default; live attachment is explicit. Protected/internal paths, symlink/root containment, excluded paths and stale versions must remain enforced. Inspecting these source/tests is not a security penetration certification.

**Minimum direction:** extend the existing local CLI/bridge contracts, first read-only state/diagnostics, then explicitly authorized navigation and checked writes, then bounded observation. Reuse the application boundaries and existing transaction owner. No second document model, unrestricted desktop automation, arbitrary command bridge or required ACP/MCP adapter is justified. ACP already serves an Assistant-provider purpose; MCP may be an optional future adapter, not a prerequisite for first local control.

## I. Ranked defect and qualification register

| Priority | Finding / evidence | Smallest justified action |
| --- | --- | --- |
| P0 | No new source corruption, data loss or wrong-buffer Undo observed | Retain exact source/history/persistence/recovery and stale-target gates; absence in tests is not a universal guarantee |
| P1, existing release blocker | Current first complex edit **5.78 s**, ~44.74 GiB cumulative EDT allocation; 7B.4 local prototype NO-GO | Separate runtime/source-layout decision, exact rendering/geometry and first-use/heap gates; do not revive rejected live Markdown presentation |
| P1, now confirmed display defect | Missing international glyphs in native default-font window | Isolate font coverage/fallback vs stock Swing/syntax overlay; propose supported fallback preserving existing Latin typography only after evidence, no default-font substitution in B0 |
| P1, confirmed explicit route | Definition command blocks EDT behind 200 ms valid peer response | Move that request off EDT through existing job infrastructure, with captured document/pane/client identity and stale/cancelled result rejection; independently test other synchronous routes |
| P1, existing | Stock HTML preview causes major input stalls | Separate preview operating-limit/product and architecture decision; no preview changes here |
| P2 | Gutter uses active view offset 431 for length 430; four repeated exceptions, unlaid-out −1 control | Bounded mapping validation/available-geometry guard and queued-paint regression; preserve source and gutter correctness |
| P2 qualification gap | Original load timeout not reproduced; first no-file launch slow | Retain failure, add state/EDT admission evidence and repeated bounded launch matrix; do not retry until green or assert density cause |
| P2 | Normal close relies on peer EOF rather than LSP shutdown/exit | Explicit owned-client shutdown/disposal plan with nonblocking UI, late-result and bounded child cleanup tests. Cooperative peer exited; persistent leak not proven |
| P2, verification-safety blocker | Native shell fixtures inherit real history/startup environment; history mtime overlaps test window, actual attribution/content change unverified | Isolate all native test shell environments before any further such run. Do not read or restore personal history contents; no data-loss claim without evidence. Guard in new opt-in probe compiled, not runtime-qualified |
| P2 | 51 corpus mismatches, native memory/main-window disposal, real-provider/performance/accessibility and Windows/Linux gaps | Independent triage and platform/resource test scopes; no broad “low-memory” or cross-platform release claim |
| P3 / deferred | Full external GUI control, live source rich media, editable 50–100 MiB, transparency/blur/automatic themes | Separate product/architecture work only after safety and supported-workload gates; not cosmetic work to fill a tranche |

No regression is attributed to recent Markdown/appearance changes solely from different historical benchmark numbers. B0 establishes current failures and evidence boundaries, not a causally proven historical regression in every subsystem.

## J. Engineering performance budgets

“Existing” means a documented diagnostic/representative target, not automatically an accepted CI SLA. “Proposed” requires adoption with fixture/host/platform and percentile definitions; it is not a measured achievement or predicted speedup.

| Area | Existing target / current evidence | Proposed next gate |
| --- | --- | --- |
| Launch/readiness | Visible ≤2 s, painted source ≤3 s, full UI ≤10 s; three desktop launches pass, no-file observation misses first two | Parent launch→first accepted synthetic input/paint ≤3 s over repeated no-file/code/Markdown/project launches; retain first-run misses and native acceptance separately |
| Ordinary typing | **p95 ≤16 ms**, current ordinary controls pass | Preserve per-mode/split/provider-interference sets; proposed p99 ≤50 ms; report first-use separately |
| Complex first use | 7B.4 aimed <100 ms at 1 MiB, failed; current 5.78 s | Keep first-use goal separate from warm typing; no multisecond EDT task, exact geometry/source and bounded retained state |
| Memory | Existing baseline diagnostics: heap-used 384 MiB / RSS 768 MiB for their fixed startup workload, not all documents | Proposed reference-host empty opaque one-pane idle gate: after 5 s settling, sample 30 s with no providers/media/jobs; used heap ≤64 MiB, committed ≤128 MiB, RSS ≤768 MiB, zero stale closed owners. These provisional ceilings have **not** been qualified by B0's short controls; adopt/adjust only with native attribution and explicit host/platform boundaries, not as a universal low-memory SLA |
| Split operations | Prior source-view/lifecycle controls pass; no product-wide accepted split SLA | Proposed warm create/close and first activation p95 ≤50 ms on 1 MiB, warm activation ≤16 ms, zero retired-view accumulation |
| File open/save | Current launch includes asynchronous file loading; pure open/save p95 not isolated | Proposed warm open-to-painted ≤100 ms at 100 KiB, ≤250 ms at 1 MiB, ≤1 s at 10 MiB; atomicity/codec/recovery mandatory regardless of speed |
| Project/search | Scanner/index/search controls are bounded and fast here; ranking existing ≤50 ms | Proposed first useful project/tree ≤500 ms after usable workbench for 1,000 files; indexed search first useful batch ≤250 ms at 5,000 tiny files, complete ≤1 s. Specify regex/large-content exceptions |
| LSP | Client timeouts are failure ceilings, not responsiveness goals; canned peer <1 ms warm | Proposed UI dispatch/cached-state work ≤16 ms; source p95 ≤16 while a 200 ms/slow peer is pending; separately budget real provider response and cancellation |
| Git | CLI status ~10.6 ms median; full UI not measured | Proposed useful status/diff view ≤250 ms for fixed 1,000-file local repository, zero source mutation and no input blocking during refresh |
| Terminal | First connector roundtrip 594 ms, later ≤19.2 ms; full GUI not measured | Proposed first usable local PTY ≤1 s, warm ≤250 ms; source input ≤16 ms during bounded terminal output; cap retained scrollback/resources |
| Tasks/tests | Backend ~7 ms, real compiler/test duration external | Proposed UI acknowledgement ≤50 ms, cancellation/resource completion ≤1 s for controlled children; distinguish tool startup/work/report parsing |
| Shutdown | Two controlled close observations ≤155/503 ms | Proposed ≤1 s controlled app close, protocol-owned children terminate within bounded grace, no stale callback/view resurrection; dirty/recovery policy outranks speed |
| Background work | Current service interference sets pass | One-worker and bounded concurrent supported feature matrix; no new queue/cache budget merely to hide latency |

## K. Three-track roadmap

Complexity is relative: **S** localized boundary/test change, **M** controller/service plus integration/lifecycle work, **L** architecture/platform project. These are estimates, not schedule promises. Each tranche requires its own approval and evidence; none begins here.

| Tranche / essentiality | Deficiency, evidence and expected benefit | Bounded scope / components | Dependencies, risks, complexity | Tests / acceptance gate |
| --- | --- | --- | --- | --- |
| **B0.1 — native fixture isolation**, essential before further native testing | Confirmed environment isolation gap and overlapping history mtime; make verification safe for user data | Test-only PTY/shell fixture environment ownership, explicit temporary history/startup inputs; new benchmark guard already compile-checked, existing tests not changed by B0 | Prerequisite for native B1–B5/A acceptance runs. Preserve shell-integration behavior under test without sourcing personal startup files. **S** | Owned temp history/startup sentinels, explicit environment assertions, bounded input/output/resize/exit; no personal-file restoration or weakened functional assertions |
| **B1 — asynchronous explicit definition**, essential | Measured 210–216 ms EDT queue delay; keep editing responsive while navigation waits | Existing `LspController` definition route + `AsyncJobService`, captured immutable pane/document/client request and checked navigation completion; no new worker framework | Before exposing that navigation to agents. Stale results/focus changes are correctness risks. **S–M** | Controlled delayed/failed peer, typing completed-paint ≤16 ms, buffer/split switching, cancellation/disposal, no wrong-document navigation or source changes |
| **B2 — gutter mapping safety**, essential bounded reliability | Four active-view failures and −1 control; reliable gutter painting during transitions | `LineNumberPanel` available/range-checked mapping only, unless further diagnosis establishes another root | Independent of B1/M architecture. Avoid clipping last line or hiding general exceptions. **S** | Deterministic 430/431 and unavailable geometry, queued focus/resize, wrapped/mixed script, no source/history mutation and no logged paint exceptions |
| **B3 — owned language/process shutdown**, essential qualification | Missing LSP shutdown/exit; cooperative EOF cleanup works but ownership incomplete | Close/restart/project-disposal ownership using existing client/job owners; graceful bounded disposal outside EDT and late-result guards | Follow B1 request identity/cancellation contract. Avoid EDT waits/deadlocks or killing shared clients. **M** | Repeated shared-root client cycles, active jobs, child exit, protocol log, supported noncooperative bounded fixture, no orphan processes or retained closed owners |
| **B4 — genuine whole-workflow regression/performance**, essential before broad claims | Real terminal paint, project switching, provider/build/Git UI journeys missing | Existing fixtures, terminals, tree/search, Git/tasks/test/LSP/DAP panels; separate external-tool opt-ins; shared benchmark contract before deduplicating probes | B1–B3; no arbitrary project commands or credentials. **M**, split by subsystem | Exact human/agent-like open/edit/navigate/save/recovery journeys, actual UI input/paint and worker queues, exit/descriptor/listener counts, documented failure paths |
| **B5 — startup/native-memory/platform qualification**, essential release gate | Slow first launch, old load timeout, NMT attach timeout and unqualified OS/resource claims | Bounded parent-clock startup/load diagnostics, native/process attribution, stable-idle/disposal controls, dedicated Windows/Linux/macOS scaling/IME/accessibility runs | Requires appropriate hosts/tools, not automatic installs or runtime migration. Profiling overhead/caches/platform divergence. **M–L** | Existing startup/input gates, no silent timeout removal, comparable instrumented controls, defined memory boundary and no cumulative stale ownership, provider/platform-specific result matrix |
| **M1 — international glyph diagnosis/remediation gate**, essential | Native default-font missing glyphs at small size; make supported source readable | Stock-production overlay/font-fallback controls and supported bounded fallback proposal preserving default typography; keep capture scale explicit | Independent of corpus fixes; may intersect M2. Font metrics/caret/accessibility risks. **M**, stop if architecture replacement needed | Native+component supported-script corpus, fallback/selection/hit-testing/IME/Undo/source checks, no font-default change or worse first-use/heap/layout |
| **M2 — source-layout architecture decision**, essential release blocker | Current 5.78 s first edit; previous bounded prototype rejected | Supported-runtime compatibility screen and separately scoped presentation/layout alternatives preserving canonical documents; do not resume live Markdown prototype | M1 geometry requirements; new runtime/tool installs require separate scope. **L** | First complex edit <100 ms representative aim, warm p95 ≤16 ms, exact bidi/grapheme/source/caret geometry, retained-memory/disposal and cross-platform gates; explicit NO-GO acceptable |
| **M3 — preview operating limit and responsiveness**, essential release blocker | Retained synchronous HTML layout stalls | Explicit supported workload/product decision and targeted existing-preview isolation/architecture proposal | No silent functionality restriction or renderer replacement. **M–L** | Source input during open/refresh/resize/media, exact source sync/security/media disposal; no unacceptable EDT stalls in claimed supported workloads |
| **M4 — Unicode effects / deferred Markdown decisions**, essential correctness triage; rich extensions optional | 51 corpus gaps are not automatically 51 editing failures; rich live presentation/large editability deferred | Reproduce actual affected motions/deletions first, pinned conformance subset, separate product ADRs for optional live/50–100 MiB work | M1/M2 affect geometry but do not license rewriting grapheme logic. **M** for triage, optional research **L** | Exact source/UTF-16/Undo/persistence and keymaps; no untested approximation; optional feature research cannot bypass release blockers |
| **A0/A1 — existing control contract and read-only state**, essential for agent promise | Queries/bridge already exist; active pane/selection/diagnostics missing externally | Security/capability audit, versioned bounded window/pane/document/selection/cached-diagnostic snapshots through existing CLI/bridge; explicit discovery descriptors | Can audit now; reuse B4 lifecycle/perf baseline. No arbitrary ex/shell bridge. **M** | Default-deny, wrong root/token/path/symlink, stale owner/version, bounded outputs, no source/focus changes, no retained disposed windows; no ACP/MCP prerequisite |
| **A2 — explicit checked navigation**, essential for agent promise | Agent cannot currently perform full file/cursor selection workflow through inspected RPC | Named pane/document open/goto/select/viewport operations using `DocumentWorkspace`/`EditorSurface`, completion/result identities and human focus policy | B1/B2 and A1 contract; UTF-16/grapheme validation, async open admission, no focus stealing. **M** | Human edit/tab/split races, cancelled/disposed requests, exact caret/selection, predictable focus, no implicit dialog or wrong-window action |
| **A3 — coordinated checked edits**, essential for agent promise | Existing proposals do not automatically authorize external apply | Reuse proposal/transaction/journal infrastructure; decide explicit actor APPLY/review policy, checked edit/version/result contract, conflicts and recovery | A1/A2 identity model and existing source safety; never silently expand proposal-only authority. **M** | Concurrent human edits, stale captures, multi-file failure/recovery, exact atomic Undo/Redo/save/format, policy and protected-file rejection |
| **A4 — rendered observation and event streams**, optional until prerequisites; needed for full promise | Production view/capture/events API absent; native capture and preview have limits | Bounded owned-view rendering/capture and cached diagnostic/state events, explicit permissions/privacy/limits; optional future protocol adapters | M1/M3, B5 and A1 identity. No global screenshots/unrestricted automation; compositing/stale-frame risks. **M–L** | Correct owner/revision/scale, cancellation/disposal, frame/output caps, fail-closed unavailable permissions, no unrelated content or source/input regression |

**Dependency order:** known P1 correctness/performance work and B1 begin before shipping agent navigation/write controls. B2 is independently small. B3/B4 establish reusable lifecycle/acceptance gates; B5 is needed for public “lightweight/cross-platform” claims. A0 read-only/security planning can proceed in parallel as a separately scoped audit; it must not postpone or conceal M/B release blockers. Optional rich Markdown/native appearance work remains deferred.

## L. First implementation recommendation

**First prerequisite: B0.1, test-only native-shell fixture isolation, before any further native acceptance/performance run.** The observed history mtime cannot be safely undone or attributed without a baseline; no restoration is attempted. Keep that separate from product remediation.

**First production correction: B1, make only the explicit LSP definition path asynchronous and target-safe through existing infrastructure.** This is a reproduced ordinary IDE input-blocking issue with a bounded correction boundary and a deterministic peer fixture. Capture request identity before submission, keep synchronous provider work off the EDT, and reject stale results before navigation. Do not turn it into a general LSP rewrite or expose raw ex-command execution to agents.

B2's gutter guard is the next independently small reliability fix. The larger Unicode/glyph/preview P1 release blockers remain essential Track M work, not waived while B1 is addressed. No new P0 source-integrity failure was demonstrated that would require halting all read-only planning. **Shipping Track A navigation/mutation should wait for the relevant B1/B2 and source/lifecycle gates; a read-only capability/security audit need not wait for an editor rewrite.**

## M. Reproducibility and verification

Full artifacts and unsuccessful/intermediate checks are indexed under `docs/performance/ide-trackb0/`. Fixtures are synthetic: 100/1,000/5,000 tiny files, one rare search target, short ASCII code at 4 KiB/1 MiB/10 MiB, established 100 KiB mixed Markdown, and a 430-unit Unicode fixture. No personal project was used. Git setup is an isolated unborn repository; B0 controls make no commits or pushes. Existing regression tests use their own temporary fixtures.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" test
"$MVN" '-Dtest=LspServiceTest,LspCompletionApplicationTest,LspWorkspaceFormatTest,TaskServiceTest,BackgroundTaskProcessTest,PtyTerminalConnectorTest,TerminalControllerTest,FileWatcherServiceTest,WorkspaceIndexServiceTest,WorkspaceTextSearchServiceTest,WorkspaceBridgeTest,WorkspaceCliTest,WorkspaceQueryServiceTest,WorkspaceProposalServiceTest,DocumentLifecycleTest,RecoveryJournalTest' test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-trackb0-run/baseline.jar --output /tmp/shed-trackb0-run/packaged-smoke.json
python3 scripts/audit-whole-ide.py --jar /tmp/shed-trackb0-run/baseline.jar --output /tmp/shed-trackb0-run/final-matrix --capture-owned
python3 scripts/audit-whole-ide.py --jar /tmp/shed-trackb0-run/baseline.jar --output /tmp/shed-trackb0-run/interference-verified --group desktop --case interference
python3 scripts/benchmark-editor.py --jar /tmp/shed-trackb0-run/baseline.jar --runs 3 --label track-b0 --output /tmp/shed-trackb0-run/startup.json
python3 scripts/audit-whole-ide.py --jar /tmp/shed-trackb0-run/baseline.jar --output /tmp/shed-trackb0-run/input-readiness --group desktop --case code-small-1
# Exact additional native-memory/shutdown/Git and Unicode argv, sources and bounded drivers are archived.
git diff --check
```

Use new output directories when repeating. The first matrix used the initial recorded probe version and retains its harness failure; the corrected script is the current reusable entry point. Source hashes/verified archived source variants identify each stage. None of these opt-in controls is added to normal Maven startup/test execution. The four new Python safety tests generate only a finite plan and check existing-output/checkout rejection; they launch no IDE or service.

**Actual verification:** full Maven **1,616 passed, five existing skips, zero failures/errors** (1,621 total); focused **124 passed**, zero skips/failures/errors; tooling **51 passed**; packaged Mermaid/math/image smoke **PASS**. Full Maven logs have no uncaught EDT exception. Benchmark logs deliberately retain the caught gutter failures and the initial harness assertion error.

Those runtime results predate the native-shell isolation finding. No further native/Maven run followed that finding. The final new benchmark history/startup guard was independently compiled successfully, but is not runtime-qualified; existing production code and native tests were not changed.

Five skips: DOS hidden attributes unavailable, Fish unavailable, CMake/CTest/CPack unavailable, opt-in AI performance unset, opt-in native AI acceptance unset. Windows/Linux GUI: **No access**. Credentials/network services were not needed or activated. Optional Pillow unavailable, no installation; Java ImageIO used instead. NMT attach timed out; vmmap succeeds. No unsupported capability is silently counted as passing.

## N. Repository compliance

No production Java, dependencies, fonts, themes, controls, defaults, document ownership or source/preview architecture changed. Every GUI child uses a synthetic Java home for Shed configuration; existing preference behavior remains unchanged. This does **not** establish that all native-shell user data was untouched: the history-isolation uncertainty above remains explicit. Screen capture is limited to the disposable process-owned window, with pre-existing permission confirmed read-only (`requested:false`). No new production network listener or permissions were introduced; existing test transports remain tests.

B0 authors only these files and indexed evidence:

- `src/benchmark/java/shed/WholeIdeServicesProbe.java`
- `src/benchmark/java/shed/WholeIdeDesktopProbe.java`
- `scripts/audit-whole-ide.py`
- `scripts/test_whole_ide_tooling.py` (four fast plan/safety tests)
- `docs/WHOLE_IDE_CAPABILITIES.md`
- `docs/WHOLE_IDE_AUDIT.md`
- `docs/performance/ide-trackb0/` (individual files enumerated in its manifest).

Exact files, before/after hashes, current HEAD/status and probe/JAR/class comparisons are in `repository-verification.json` and `manifest.json`. All **1,782 starting source/script/doc/asset/build-input files** remain byte-identical; prior Markdown/appearance evidence is preserved. Git status was clean at entry. An external process advanced HEAD through commits `175fb16df04fc7989b2f139f3d20f376be82c9ea` and `ffb866cc44af441a27a0ab36240449b3604692cc`, capturing some newly authored audit files during the session. Those commits are preserved. **This agent made no commits or pushes.**

Protected README before/after SHA-256, identical:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

B0 ends with a qualified baseline, explicit failures/gaps and a reviewable roadmap. It does not implement B1, Track M remediation, Track A controls, a new renderer or deferred appearance work.
