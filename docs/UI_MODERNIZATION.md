# UI modernization

Current stage: production workbench redesign using Swing (stage four, in progress). Earlier application-boundary work and its separate revalidation remain below as historical evidence. The current implementation/verification ledger is in the stage-four section; earlier passing results do not certify that broader stage.

## Scope and baseline

Starting revision: `94792eabbfe304c10f85ad576baa64eb2ab1be9d` (clean worktree). No applicable AGENTS.md was found in the repository or its ancestors. This stage implements settings read/update and file/buffer palette query/accept boundaries. It retains Java 21, Maven, the Swing presentation and the existing text engine. A framework migration and visual redesign are later work.

The fresh baseline `clean package` used Temurin 21.0.12.1+1-LTS and Maven 3.9.11 returned by `scripts/resolve-toolchain.sh` with `-B -q -Djava.awt.headless=true`: **1,257 tests, zero failures/errors, 85 skips**. Logs, individual Surefire reports, and the original packaged JAR were preserved in `/tmp/shed-ui-stage1-baseline` before implementation; clean builds do not delete them. The first attempted invocation incorrectly used the resolver as a Maven wrapper and exited 2; it ran no tests. The corrected invocation resolves the executable and JAVA_HOME separately.

[Fresh baseline desktop measurements](performance/ui-stage1-baseline-94792ea.json) use the unchanged `scripts/benchmark-editor.py`, five fresh JVMs and 125 warmed edit/picker samples. The JSON records the host, fixture, raw samples, JAR/probe hashes, heap bounds and budgets; the JDK version is recorded above. Builds and tests were finished before timed measurement. Filesystem/font caches and other host activity were not controlled; startup p95 is the maximum of just five samples.

| Metric | Baseline p50 | Baseline p95 | Budget | Result |
| --- | ---: | ---: | ---: | --- |
| Visible window (ms) | 1342.03 | 2926.18 | 2000 | Miss |
| Source ready (ms) | 1830.03 | 4128.18 | 3000 | Miss |
| Full UI ready (ms) | 5656.79 | 11538.18 | 10000 | Miss |
| Edit to synchronous paint (ms) | 2.78 | 5.37 | 16 | Pass |
| Picker ranking (ms) | 15.00 | 18.07 | 50 | Pass |
| Idle heap (MiB) | 52.05 | 52.13 | 384 | Pass |
| Idle RSS (MiB) | 486.05 | 491.03 | 768 | Pass |

The baseline benchmark exited 1 because of the startup budget misses. Historical results in PERFORMANCE_RESULTS.md describe older builds; they are not measurements of this starting revision. No existing misses are silently reclassified as implementation regressions.

## Implemented ownership and dependency direction

| Responsibility | Owner |
| --- | --- |
| Settings use-case contract and immutable descriptor values | `SettingsApplication` |
| Canonical validation, font repair, persistence, and successful-apply ordering | `ConfigSettingsApplication`, using the existing `ConfigManager` |
| Runtime application to widgets, snippets, fonts, menus, etc. | `SessionConfigController.applyRuntimeConfigFromSettings`, injected as an apply callback by `Texteditor` |
| Settings search/category state, controls, theme colors, layout, error dialogs, focus | `SettingsEditorDialog` |
| File candidate ordering, recent/current-path resolution, location parsing, fuzzy query and acceptance checks | `FilePalette` |
| Buffer labels, opaque session identities, fuzzy query and selection validation | `BufferPalette` |
| Immutable candidate snapshot/query/accept/close contract | `PaletteSession` |
| Capturing live buffers/paths, resolving accepted identities against current buffers, invoking existing open/switch operations | Swing integration code in `PaletteController` |
| Picker widgets, debounce, query generations, worker cancellation and EDT result delivery | `PaletteFilterSession`, owned by one picker dialog |
| Application lifetime, service wiring, existing process/job ownership | `Texteditor` and existing lifecycle services |

`Texteditor` creates `ConfigSettingsApplication` with the existing configuration and a narrow apply callback. Both the Settings inspector and `setConfigOptionPersistent` now invoke the same application operation. Descriptor reads cross the interface as immutable records containing strings, rather than exposing ConfigManager or its AWT values. The service does not construct windows, show errors, select widgets, or schedule callbacks.

`PaletteController` captures file labels/paths and open-buffer metadata on the EDT. File and buffer sessions own immutable snapshots, expose pure worker-safe queries, and validate accepted choices before invoking narrow callbacks. Buffer acceptance resolves the captured buffer object in the *current* list; it no longer parses a display label into a potentially stale live-list index. An unavailable buffer cancels selection. File acceptance preserves exact real-label precedence over `file:line:column`, `:line` for the current document, recent-file ordering, and the ability to select an already-open document whose file was deleted. Existing asynchronous open operations remain responsible for loading and stale application state.

## Threading, cancellation and lifetime

- Settings reads/updates are synchronous. Production calls remain on the EDT, including the existing small configuration persistence operation. This stage does not move it to a worker or claim to fix that inherited synchronous I/O. The apply callback executes inline after successful persistence; validation/write failures never notify it. No settings event bus or subscription was introduced.
- Palette snapshots are captured on the EDT; workers query only immutable strings and metadata. Acceptance, live-buffer validation, callbacks, and close belong to the EDT. Callbacks execute inline, so existing async open dispatch and its completion timing remain unchanged.
- `PaletteFilterSession` still owns its 75 ms debounce, request generation, stale-result rejection, pending Enter acceptance and cancellation. Closing the picker cancels work; late completions cannot update it. `showPicker` now closes filtering and disposes the dialog in `finally`, including exceptional paths.
- `PaletteSession.close()` is idempotent, revokes further query/acceptance, and releases callbacks that captured live application objects. Running queries have no application callbacks and their results remain subject to the view's generation/cancellation guard.
- The Settings dialog stops its debounce timer on disposal. Existing deferred Swing layout/focus work remains local presentation work.
- `AsyncJobService` still delivers completions on the EDT; no executor or callback-thread policy was changed.

## Verification

Focused headless checks initially passed: `ApplicationBoundaryTest`, `PaletteFilterSessionTest`, `CommandParsingTest`, and `ConfigManagerTest`. The new tests exercise real settings validation/persistence/reload from disk, immutable settings snapshots, synchronous callback ordering, write failure, file location syntax and recent ordering, deleted/open files, duplicate buffer names and opaque identities, invalid selections, close revocation, and presentation-free contract signatures. Existing palette session tests cover superseded/uncooperative workers, queued Enter and close cancellation without constructing a JFrame.

The final complete source and test tree passed `-B -q -Djava.awt.headless=true clean package`: **1,265 tests, zero failures/errors, 87 skips**. The rebuilt JAR SHA-256 exactly matches the JAR used for the post-change benchmark. Individual final reports are preserved outside target in `/tmp/shed-ui-stage1-final/current-headless-reports`.

Nine selected display-dependent `TexteditorSwingIntegrationTest` cases passed with zero failures/errors/skips, using `-Djava.awt.headless=false` and method selector `settingsDialog*+bufferPickerResolves*+quickOpenNavigates*+paletteAccepts*+workbenchControls*+uiZoomScales*+bufferSwitchRestores*+bufferViewsAre*`. These include actual settings-field submission and runtime tab-size application; persisted command updates; buffer selection after live-list reordering; Quick Open line/column navigation and recent-file cancellation; pending-query acceptance/Escape; theme/zoom styling; and split selection/viewport preservation. Display evidence is local macOS Swing behavior, not a cross-platform or screen-reader certification. No native keyboard/monitor-latency measurement or exhaustive visual gallery comparison is claimed.

[Post-change desktop measurements](performance/ui-stage1-final.json) used the same probe hash, fixture, heap, five-run method and host as the fresh baseline, after tests/builds finished. All seven budgets passed (exit 0):

| Metric | Final p50 | Final p95 | Baseline p95 | Budget |
| --- | ---: | ---: | ---: | ---: |
| Visible window (ms) | 1103.18 | 1508.17 | 2926.18 | 2000 |
| Source ready (ms) | 1576.18 | 1826.18 | 4128.18 | 3000 |
| Full UI ready (ms) | 4822.18 | 7096.18 | 11538.18 | 10000 |
| Edit to synchronous paint (ms) | 3.37 | 5.32 | 5.37 | 16 |
| Picker ranking (ms) | 14.70 | 16.60 | 18.07 | 50 |
| Idle heap (MiB) | 50.11 | 52.10 | 52.13 | 384 |
| Idle RSS (MiB) | 490.33 | 503.64 | 491.03 | 768 |

The final sample does not reproduce the baseline startup misses. This is **not proof of a startup optimization**: OS/font caches and unrelated host activity are uncontrolled, and each startup tail has just five samples. Edit p95 is essentially unchanged; median edits and RSS vary modestly while remaining inside budgets. No measured budget regression or change to benchmark thresholds was introduced. Picker ranking measures the existing ranker, not the complete palette workflow; functional acceptance is covered separately by the use-case and desktop tests.

Baseline JAR SHA-256: `e9d51ff956524c8fcc5901c63a90e107db38227d160fdfde21ecf2bfb6c251e4`. Measured final JAR SHA-256: `381b24dc0c3155ce08abaeeef05bfea0a37390d9f8a209d9582ce564c7cb6fe4`. Raw JSON is retained in the repository; JARs, full logs and test XML are outside target in `/tmp/shed-ui-stage1-baseline` and `/tmp/shed-ui-stage1-final`.

Reproduce with Java 21: resolve `JAVA_HOME` using `scripts/resolve-toolchain.sh home` and Maven using `scripts/resolve-toolchain.sh maven`; run Maven `-B -q -Djava.awt.headless=true clean package`. On a supported display, run the desktop method selector above with `-Dtest=TexteditorSwingIntegrationTest#<selector>`. Finish builds/tests before running `python3 scripts/benchmark-editor.py --jar <preserved-jar> --runs 5 --label <label> --output <outside-target.json>`. Keep the same harness, JDK, heap, host and fixture for comparisons.

## Remaining coupling and next stage

This is a practical boundary for the selected operations, not a claim that the frontend is completely detachable:

- `Texteditor` remains the composition root and shared owner; other controllers still depend on it.
- `SettingsEditorDialog` still uses its Swing owner for presentation styling and explicit Open TOML/Open Snippets/Language Services/Reload actions. Those adjacent workflows are outside this stage; selected settings reads and updates use the new interface.
- `ConfigManager` still contains AWT color/font implementation details, exposed to legacy presentation code but not the new settings contract.
- `PaletteController` remains a Swing view/integration adapter and retains legacy workspace search, symbol, command, and replacement workflows. File discovery is unchanged; this boundary starts at discovered path snapshots.
- `FileBuffer` remains PlainDocument-backed; undo, live positions, selection, per-pane state and painting remain Swing-dependent. No duplicate text store was added.
- External services, approvals, cancellation of application jobs, configuration formats and launch paths were not redesigned.

The next stage should extract document/workspace operations and an editor-surface adapter while preserving shared document/undo ownership and per-pane caret/viewport state. Do not start UI redesign until those contracts have meaningful production callers and behavior tests. This stage introduces no UI framework, DI framework, event bus or plugin platform.

## Stage 1 current-checkout revalidation

The requested settings and file/buffer palette separation was already implemented when this revalidation began. Starting revision: `0c3ff946828fa1f25f43d8a06846720b5a9ad954`, with existing later-stage working changes retained. Those changes are not attributed to this stage or reverted. No applicable AGENTS.md was found in the repository or its ancestors. The starting diff, status, JAR and carried-over reports were preserved in `/tmp/shed-boundary-recheck-baseline`, outside Maven clean output. A fresh Java 21 headless `clean package` then passed **1,279 tests, zero failures/errors, 92 skips**; its reports and exact packaged JAR are preserved there separately from carried-over evidence. Starting and freshly rebuilt JAR SHA-256: `2c63487c9028c5cc889ea1a0e1b1d9fa4f85c284acb29a0d62f302c30777ff17`.

The existing production callers were checked again: Settings inspector reads/updates and persistent setting commands use SettingsApplication; file and buffer pickers query and accept through PaletteSession/FilePalette/BufferPalette. Swing owns rendering, focus, dialogs, selection state, debounce and worker delivery. Application logic owns validation/persistence and deterministic query/acceptance semantics. Texteditor remains composition/lifecycle, supplying narrow callbacks to the application services. The ownership and remaining-coupling tables above still apply. Existing subsequent document contracts are reused, not replaced with a large context object.

Two disposal gaps were corrected without changing appearance or command behavior: PaletteFilterSession now removes its own document/action/mouse listeners on close and guards queued timer work after disposal; PaletteController's `finally` now covers preview and dialog setup as well as modal display. Existing subscriptions belonging to other code are preserved. Worker generations, cancellation, queued Enter, and inline acceptance remain unchanged. New tests cover immutable settings snapshots, worker-side pure queries, inline EDT acceptance, idempotent listener cleanup, and an actual production picker whose preview throws after realizing the dialog. The last case verifies disposal, listener release, no subsequent filtering from disposed widgets, and unchanged active source.

Fresh pre-change desktop measurements use the unchanged five-JVM harness against the freshly packaged starting tree. Visible/source/full-UI p95 **2,598.31 / 4,212.23 / 18,264.23 ms** miss the existing **2,000 / 3,000 / 10,000 ms** budgets. Edit/paint **12.18 ms**, picker **44.69 ms**, used heap **51.98 MiB**, and RSS **509.95 MiB** pass. The harness returned 1. These are measurements of this working checkout, not reused historical results or failures caused by the disposal fixes. Historical measurements elsewhere in this document retain their original artifact/scope.

Final Java 21 headless `clean package` passed **1,282 tests, zero failures/errors, 93 skips**. The separate focused run passed **98 tests, zero failures/errors/skips**: ApplicationBoundaryTest (7), PaletteFilterSessionTest (4), ConfigManagerTest (54), AsyncJobServiceTest (12), CommandParsingTest (11), and TexteditorSwingIntegrationTest (10). The display selector was `settingsDialog*+bufferPickerResolves*+quickOpenNavigates*+paletteAccepts*+paletteDisposes*+workbenchControls*+uiZoomScales*+bufferSwitchRestores*+bufferViewsAre*`; selected Swing behavior ran on the local macOS display rather than being counted as covered by headless skips. The application-boundary cases construct no JFrame and passed in the headless package check as well. Windows/Linux desktop behavior, native IME hardware and external screen readers were not checked in this follow-up.

The final packaged JAR is preserved in `/tmp/shed-boundary-recheck-final/shed.jar`, SHA-256 `82d9a6363943b539aa424a495c4f507a9d3040b3568244cd44bafd8de0932a30`. Comparing all 19,099 common class entries with the freshly built starting artifact found only PaletteController/PaletteFilterSession and their nested classes changed (8 entries); no classes were added or removed. The editing engine, ranker, application boundaries, configuration implementation, approval code and launch implementation remain byte-identical to this follow-up's starting artifact. The toolchain was Temurin 21.0.12.1+1-LTS / Maven 3.9.11, Intel macOS 15.7.7. Full logs and individual XML reports are preserved outside target in `/tmp/shed-boundary-recheck-baseline` and `/tmp/shed-boundary-recheck-final`.

All four desktop runs used the unchanged harness, five fresh JVMs, the same fixture/JDK/heap/host, with builds, tests and class inspection finished before timing. The first final run also missed editing/ranking, so an immediate preserved-baseline control and one unchanged-final repeat were retained rather than overwriting it. Values below are **p95** (startup/memory have only five samples):

| Metric | Fresh baseline | First final | Baseline control | Final repeat | Budget |
| --- | ---: | ---: | ---: | ---: | ---: |
| Visible window (ms) | 2598.31 | 4278.11 | 3369.30 | 2483.97 | 2000 |
| Source ready (ms) | 4212.23 | 5630.40 | 4610.30 | 3242.97 | 3000 |
| Full UI ready (ms) | 18264.23 | 27204.11 | 18637.30 | 12301.97 | 10000 |
| Edit/paint (ms) | 12.18 | 16.72 | 5.09 | 4.51 | 16 |
| Picker ranking (ms) | 44.69 | 123.02 | 30.52 | 21.42 | 50 |
| Used heap (MiB) | 51.98 | 53.13 | 52.89 | 53.00 | 384 |
| RSS (MiB) | 509.95 | 495.72 | 497.31 | 506.98 | 768 |

All four harness invocations returned 1 because of startup misses. The first final invocation's edit/ranking misses are not hidden by the repeat. The control and unchanged-final repeat pass those budgets; the byte-identical engine/ranker and inactive picker-lifecycle paths in the timed loops provide no evidence of a specific introduced engine regression. These samples establish substantial variation, not a controlled speedup or proof of a particular environmental cause. OS/font caches and unrelated host activity were not controlled; existing startup-tail performance remains unresolved. No benchmark definition or threshold was weakened.

Durable evidence: [baseline metadata](performance/ui-boundary-recheck/baseline.json), [fresh baseline desktop](performance/ui-boundary-recheck/baseline-desktop.json), [first final desktop](performance/ui-boundary-recheck/final-desktop.json), [baseline control](performance/ui-boundary-recheck/baseline-control-desktop.json), [final repeat](performance/ui-boundary-recheck/final-repeat-desktop.json), [headless final](performance/ui-boundary-recheck/final-headless-summary.json), [focused/display counts](performance/ui-boundary-recheck/focused-and-desktop-summary.json), [class comparison](performance/ui-boundary-recheck/class-comparison.json), and [verification/exit codes](performance/ui-boundary-recheck/verification.json). Reproduce package and desktop commands as above, using the preserved exact JARs; run the focused class list with the recorded selector and `-Djava.awt.headless=false` on a supported display.

The requested boundary stage is verified: both selected production workflows use presentation-neutral contracts, real application behavior and Swing integration pass, callback timing remains explicit, and the disposal fixes introduce no demonstrated attributable regression. This follow-up changes no appearance and remains confined to that boundary stage; broader workbench redesign already present is separate and is not certified complete by these checks. Next boundary work remains the deliberately retained owner-aware adjacent actions/controller integration described above; no new framework, generic event bus, DI framework or plugin platform was introduced.

# Stage 2 — document/workbench operations

## Starting state and implemented boundary

Stage 2 starts from the uncommitted stage-one implementation above at the same repository HEAD. The prior work was retained. Its measured JAR and test reports were preserved in `/tmp/shed-ui-stage2-baseline`; the starting JAR hash is the stage-one final hash above. Stage-one desktop results are the preceding baseline, not measurements of HEAD without its working changes. A fresh 500,000-line model run against that preserved JAR is also retained there in `large-editing.txt`.

`DocumentWorkspace` is the application-facing workbench contract for document metadata/identity, activation/cycling, asynchronous open/save submission, explicit close decisions, split/window close, history navigation, active editor surface, and root queries. It exposes paths, IDs, immutable metadata, strings, and `EditorSurface`, never FileBuffer, widgets, controllers, or Texteditor. Save/open messages mean submission, not completion.

`LegacyDocumentStore` owns the **single canonical list of FileBuffer objects**, exposed through the existing `Texteditor.buffers` alias solely for legacy integration. It owns weakly keyed, non-reused application document IDs. Removing/reordering list entries cannot redirect an existing ID to another document. Metadata queries read name/path/dirty/read-only/revision fields without copying text. Loading still constructs the original FileBuffer implementation and installs it only after the existing asynchronous acceptance checks.

`DocumentWorkbench` owns identity resolution, document cycling and explicit dirty-close admission/revalidation. Its `Host` is the integration boundary for existing side-effect pipelines. `SwingWorkbenchHost` is the only new owner/controller-aware adapter: it binds and focuses panes through the legacy controller, invokes the existing format-on-save workflow, and retains specialized close, split and navigation effects. This is intentional reuse of the current pipelines, not a replacement text or save engine.

`WorkspaceRoots` remains the canonical ordered root/active-root model, now owned by composition and shared by application queries and the legacy workspace controller. `WorkspaceFoldersApplication` exposes typed folder operations; the existing controller implements these while preserving imported-manifest invalidation, tree/index updates, and explicit activation behavior. Workspace-folder dialogs operate on captured paths rather than live list indexes. Manifest metadata and associated presentation effects remain in the legacy adapter.

`DocumentNavigation` owns the bounded application jump history using the same IDs as the document registry. The legacy edit controller captures eligible positions and renders navigation feedback; jump offsets retain their previous fixed UTF-16 semantics. Closed documents are filtered out. Per-pane selections use separate live anchors.

## Editor surface and production migrations

`EditorSurface` exposes length, directed selection, viewport, focus, replacement, undo/redo, and captured views. `SwingEditorSurface` attaches to the existing JTextArea/JScrollPane and obtains history from the **bound document**, without owning another text store or undo stack. Programmatic replacement rejects read-only projection surfaces. Direct text-entry editability remains mode-dependent.

`EditorSurface.View` hides the existing `BufferViewState` implementation, which still stores Swing `Position` objects for mark/dot. It resolves their updated offsets when restoring, preserving edits made in other splits. No integer snapshot replaced those anchors. Viewport restoration remains bounded by the current layout; delayed restoration still yields to explicit selection changes or a different bound document. The special workspace batch path retains its existing diff-based restoration for replaced content and its original edit transactions.

Production migrations include:

- OpenEditorsBar reads immutable document metadata, activates/closes by ID, and returns focus through the editor surface. It no longer reads FileBuffer or JTextArea or mutates the buffer list.
- File/buffer palettes invoke document activation/open and metadata operations through DocumentWorkspace. The old static candidate helper remains only for existing label-contract tests.
- Save/write-quit, close-window, split, and back/forward command entry points use DocumentWorkspace. Existing menus and shortcuts continue to use the same command semantics; public buffer-cycle/activation/split/close wrappers also enter the contract.
- Plain-profile undo/redo uses the active surface. The native shared undo manager is unchanged.
- Pane switching/splitting uses the surface for selection, deferred viewport restoration and focus. Legacy attachment/rendering still knows the actual Swing Document and widgets.
- The folder manager depends on WorkspaceFoldersApplication, rather than the concrete controller.

`DocumentRequests` owns latest-request tickets per target, with explicit invalidation on pane close. Installation additionally requires that the original target still exists. A superseded load, closed target, cancelled job, or application shutdown cannot install text into a replacement pane. Blocking load work and EDT completion delivery still use AsyncJobService.

## Preservation and residual coupling

FileBuffer, VersionedTextSnapshot, its revision listeners, native bounded undo, text transactions, save snapshots, encoding/line endings/BOM, recovery scheduling, and paged read-only large-file storage are retained. FormatOnSaveController still owns formatting, exact save-snapshot capture, asynchronous writes/backups, stale-save handling, notifications, and post-save actions. This stage adds no full-document copying to ordinary metadata notifications.

The machine/agent `EditorWorkspaceDocuments` API retains its existing reviewed mutation/version/identity protocol. Application tab IDs are local workbench identities; they do not replace machine-facing protocol IDs or bypass approval checks. Existing recovery/session serialization remains in its legacy pipeline and restores the same canonical buffer/root objects.

Remaining legacy integration includes text attachment, detailed editing/motion/IME, syntax/LSP/completion decoration, custom tool/terminal/notebook/preview surfaces, and widget-oriented rendering/layout. Texteditor remains composition and the compatibility entry point for these paths; its existing per-feature callbacks still drive presentation refresh. This is a replaceable boundary for the migrated workbench workflows, not a completely Swing-free application. No visual redesign or UI framework change is included.

All model/surface operations are UI-thread-affine in production. Metadata snapshots and IDs carry no listener subscriptions. Existing job completion, cancellation and stale-state ordering are retained. Async document loading creates a detached FileBuffer on a worker, then attaches it on the EDT after ticket/target validation. Views remain owned by their pane; the canonical document owns content/history, so splits share edits while retaining distinct mark/dot/viewport state.

## Stage 2 verification

Final Java 21 headless `clean package`: **1,272 tests, zero failures/errors, 88 skips**. New headless cases exercise real FileBuffer loading, CRLF save and undo retention, explicit dirty-close admission, identity/reordering and activation revalidation; shared Swing document surfaces without a JFrame; UTF-16 offsets, live anchors and read-only replacement rejection; and asynchronous request policy. ApplicationBoundaryTest verifies the new contract signatures expose no UI/controller/model implementation types. The desktop-only cached-dirty regression test was added after this package run compiled its test tree, and subsequently compiled and passed in the 20-test display run. No production source changed between that package and the final display run. Existing workspace transaction/view tests were updated only to use the new surface signature, preserving their assertions.

**20 selected desktop integration tests passed**, zero failures/errors/skips. The selector was `closingTabChecks*+documentOperationsReject*+openEditorsShow*+bufferSwitchRestores*+bufferViewsAre*+navigationHistory*+splitCloseFocus*+ordinarySave*+openEditWrite*+asynchronousOpen*+quickOpenNavigates*+paletteAccepts*+settingsDialog*+customActionsWork*+searchPanelRetains*+repeatedCompletionUpdates*+conflictSaveAs*+importedWorkspaceFileExclusions*+importedWorkspaceSearchExclusions*`. It exercises the actual application paths for superseded/closed-pane loads, dirty tab closure including the last document, independent split views, navigation branches, save/undo, Save As conflict handling, keyboard/composition routing, search, completion overlays, and imported workspace exclusions. Evidence is local macOS; Windows/Linux native interaction, external screen readers and native IME hardware checks were not performed.

Final package/test logs and XML are retained in `/tmp/shed-ui-stage2-reviewed` (earlier passes in `/tmp/shed-ui-stage2-final`); the baseline JAR is in `/tmp/shed-ui-stage2-baseline`. Raw performance evidence is retained in [performance/ui-stage2](performance/ui-stage2).

Projected read-only surfaces retain their bounded Swing-window coordinates and legacy LargeFileProjection translation. They do not advertise full-source random navigation through EditorSurface. Ordinary saved views are captured only for editable documents; full-source line navigation in large-file mode still reports its existing unavailable message.


## Stage 2 initial performance comparison and investigation

All runs used the preserved stage-one baseline and the final packaged JAR, Temurin 21.0.12.1, the same host and 64–512 MiB heap. Desktop runs used the unchanged probe/fixture, five fresh JVMs and 125 warmed edit/picker samples, with no builds or tests competing for resources. OS caches and unrelated host activity were not controlled.

The first final desktop pass missed full-UI startup (p95 10,189.87 ms versus the 10,000 ms target), while editing/picker/memory passed. The first final 500,000-line model pass missed background convergence (476.831 ms versus 250 ms). These misses are retained, not overwritten. A subsequent diagnostic repeat set overlapped a CPU-heavy JAR inspection and is **not used as a controlled comparison**; its model baseline and final both missed convergence, and its desktop baseline missed startup. Those diagnostic artifacts are retained with `editing-repeat-*` and `baseline-desktop-repeat` names.

Inspection confirmed all 28 benchmark engine classes, including nested classes, are byte-identical in the two JARs; see [bytecode comparison](performance/ui-stage2/final-engine-bytecode-comparison.json). The benchmark does not instantiate the changed workbench. This excludes a changed engine implementation as the explanation for those model timings; it does not prove a particular environmental cause.

After inspection finished, the clean model pair passed all existing thresholds: baseline edit/caret/convergence p95 **0.168 / 0.368 / 82.238 ms**, final **0.163 / 0.332 / 80.458 ms**. The original pre-change model run was **0.225 / 1.723 / 101.618 ms**. All samples and failed diagnostic runs remain available; no thresholds or harness semantics were weakened.

The pre-review desktop repeat and subsequent clean baseline desktop pass both met all seven existing budgets. Values below are **p50 / p95** (startup/memory p95 is the maximum of five samples):

| Metric | Clean baseline | Final repeat | Budget |
| --- | ---: | ---: | ---: |
| Visible window (ms) | 1304.78 / 1356.98 | 1261.87 / 1853.61 | 2000 |
| Source ready (ms) | 1776.78 / 1870.98 | 1766.29 / 2518.61 | 3000 |
| Full UI ready (ms) | 5612.78 / 8818.98 | 5610.87 / 7049.61 | 10000 |
| Edit → paint (ms) | 3.09 / 4.78 | 2.86 / 4.90 | 16 |
| Picker ranking (ms) | 16.17 / 18.52 | 16.15 / 18.11 | 50 |
| Idle heap (MiB) | 51.04 / 51.94 | 50.72 / 52.29 | 384 |
| Idle RSS (MiB) | 486.91 / 495.67 | 492.69 / 497.45 | 768 |

Use [baseline desktop clean](performance/ui-stage2/baseline-desktop-clean.json) and [final desktop repeat](performance/ui-stage2/final-desktop-repeat.json) for this table. Run order, caches and host scheduling prevent causal speedup claims. These results support preserved behavior and no demonstrated attributable performance regression; they do not promise that every future run meets the budgets.

The separate **100 MiB paged-file comparison** used exactly the same generated ASCII input and three iterations per JAR. Both reports completed with zero failures; editing remained explicitly unsupported. Cold-open p95 was 1460.83 / 1361.63 ms (baseline/final), warm-open 983.05 / 983.49 ms, and bounded-scroll 1.61 / 2.16 ms. Cold-open observed heap-delta p95 was 3,213,368 bytes in both builds. Three samples cannot establish a change in tail performance. The benchmark's clean large-file `save` is a no-op under the current FileBuffer guard; its microsecond result is **not streamed-write throughput evidence**. Atomic write behavior remains covered by the existing engine tests.

[Fixture definition](performance/ui-stage2/fixture.json) records the exact byte pattern, byte count and SHA-256. Reproduce it outside the repository, then invoke the preserved JARs sequentially with `java -Xms64m -Xmx512m -cp <jar> shed.LargeFileBenchmark --iterations 3 <fixture>`. Run `shed.EditorResponsivenessBenchmark` with those same JVM flags for the 500,000-line workload, and the documented desktop benchmark with five runs. Do not run builds, tests or CPU-heavy artifact inspection concurrently with timed work.

## Next stage

The next stage is a bounded UI prototype and framework comparison using these contracts. Embed the existing editor through presentation-specific Swing hosting while consuming document/folder contracts for workbench data/actions. Keep the remaining legacy pipelines explicit; replacing the text engine or rewriting specialized editors is not a prerequisite for that experiment. This stage ends before visual redesign.


## Final review correction and packaged-build measurements

Review caught an admission bug introduced while replacing live FileBuffer references with tab metadata: a cached clean flag could have granted discard after the underlying document became dirty. `OpenEditorsBar.closeSelected` now queries current application metadata before prompting; `DocumentWorkbench.close` independently validates current membership/dirty state before removal. The new display regression test caches a clean tab, makes the underlying document dirty without a presentation refresh, closes the tab and cancels the dialog. It verifies that confirmation was shown and the document remains open and dirty. All 20 selected desktop tests passed after the correction.

The final production classes exercised by desktop tests were byte-compared with the packaged JAR (1,272 class files): all matched. Final JAR SHA-256: `c051395ad94d4aa1cbfcc39ddf79b22700091853701e163a9696d5f97fa4d9b1`. The unchanged model benchmark engine was also compared with the preserved baseline: all 28 benchmark classes, including nested classes, remain identical. [Reviewed comparison](performance/ui-stage2/reviewed-engine-bytecode-comparison.json).

The reviewed build's 500,000-line benchmark passed: edit/caret/convergence p95 **0.140 / 0.354 / 81.603 ms**. Its 100 MiB paged-file check also passed with no failures and editing explicitly unsupported. [Model output](performance/ui-stage2/reviewed-editing.txt), [paged output](performance/ui-stage2/reviewed-paged.txt).

The latest reviewed desktop run and the immediately subsequent preserved-baseline control both **missed the three startup targets**, while editing, ranking and memory passed. Both benchmark processes correctly returned 1. These results supersede any implication that every desktop run now meets its budgets. Values are **p50 / p95**:

| Metric | Preserved-baseline control | Reviewed build | Budget |
| --- | ---: | ---: | ---: |
| Visible window (ms) | 1853.54 / 2554.86 | 1666.36 / 2801.32 | 2000 |
| Source ready (ms) | 2567.34 / 3277.54 | 2778.39 / 3348.32 | 3000 |
| Full UI ready (ms) | 8226.86 / 10801.54 | 7441.36 / 13516.39 | 10000 |
| Edit → paint (ms) | 3.95 / 9.54 | 2.95 / 7.96 | 16 |
| Picker ranking (ms) | 16.41 / 28.57 | 16.36 / 23.00 | 50 |
| Idle heap (MiB) | 51.26 / 52.99 | 50.98 / 53.88 | 384 |
| Idle RSS (MiB) | 473.66 / 486.56 | 482.50 / 494.05 | 768 |

[Current baseline control](performance/ui-stage2/baseline-desktop-reviewed-control.json), [reviewed build](performance/ui-stage2/reviewed-desktop.json). All artifacts retain five startup samples and 125 warmed edit/picker samples. There was no build, test suite or artifact inspection competing with these timed runs. The reviewed build's full-UI median is lower and its five-sample maximum is higher than the control; earlier clean runs of both builds met the targets. Internal phase records retain the long full-UI readiness interval after the usable editor appears. This shows substantial existing startup-tail variation, not a controlled causal speed comparison. No specific new startup regression or startup improvement is established; startup profiling remains performance work. Thresholds were not loosened, and the latest misses are not hidden behind the earlier passing samples.

Stage 2 is complete for the documented migrated workflows: application-facing contracts have real callers, tests demonstrate preserved editor/lifecycle behavior, and remaining legacy dependencies and measurement limits are explicit. UI redesign has not started.

# Stage 3 — bounded Swing/Compose comparison

Archive note: during production stage four the rejected Compose implementation/dependencies and duplicate Swing prototype were removed. The measurements and captures below describe the completed experiment at that time. [Archive information](../experiments/ui-comparison/README.md) identifies its historical source and exact core artifact; old experimental launch commands are not current production commands.

**Decision: use the existing Swing approach for the production redesign.** Both runnable prototypes implement the same useful appearance/navigation slice against the real application ports and original editing surface. Compose makes this layout pleasant to express, but has not demonstrated a visual or functional advantage sufficient to justify another renderer, build toolchain, runtime ordering constraint and platform-specific native dependency. This is a choice for Shed's next stage, not a general judgment about Compose.

At the time of this experiment, the implementations were isolated in experiments/ui-comparison and production rollout had not begun. That source is now archived as described in the [archive README](../experiments/ui-comparison/README.md); stage four below describes the subsequent production implementation. Maven, Java 21, `make`, `scripts/shed`, configuration schemas and approval behavior are unchanged by this stage. The production JAR before and after the headless package check has the identical SHA-256 `c051395ad94d4aa1cbfcc39ddf79b22700091853701e163a9696d5f97fa4d9b1`.

## Baseline and experimental scope

The starting revision is `94792eabbfe304c10f85ad576baa64eb2ab1be9d`, with the uncommitted stage-one/two changes retained. The baseline JAR and a fresh production gallery attempt were preserved outside Maven output in `/tmp/shed-ui-stage3-baseline`. The existing gallery produced eight captures, then exited 1 because `assertActiveTool` expected the AGENT tool to be visible. That is a **partial baseline gallery failure**, not a successful full-gallery run. Previous stage-two desktop timings, including the recorded startup-budget misses, remain historical context; the new comparison below measures different work and does not replace those results.

Both variants launch the complete production workbench with an isolated home/config and exactly the same `Renderer.java` and `Theme.java` fixtures. The first contains 20,011 displayed lines. Window dimensions are 1120 × 792, with a 760 × 680 narrow case. Both explicitly pin embedded macOS menu placement; otherwise toolkit initialization can move menus to the OS menu bar and change the available client space. The shared design has a workbench title, document strip with selected/dirty indicators, save/document status, and an appearance side panel with search, per-setting apply, descriptions, inline feedback, theme shortcuts and detach/dock.

The six settings are theme, editor font/family, interface font/family and interface zoom. Validation, runtime application and disk persistence use `SettingsApplication`; document metadata and activation use `DocumentWorkspace`, and editing/focus use `EditorSurface`. The experiment retains the real source component, document, undo history, syntax, gutter, original tools, menu and footer. Neither implementation uses a substitute text buffer or a mock settings store. Rendering/layout choices differ slightly; this is the same design and functionality, not a claim of pixel-identical rasterization.

## State, reuse and lifecycle

| Owner | Responsibility and retained coupling |
| --- | --- |
| Existing Java application | Canonical documents/history, setting descriptors/search/validation/persistence, existing config application and approval pipelines. No new Kotlin application logic. |
| Shared `LabModel` presentation adapter | Immutable document/setting/token snapshots, query, local unapplied drafts, feedback and detach state. A 200 ms EDT timer refreshes metadata, never document text. It privately knows Texteditor only to adapt existing composition/config tokens into this experiment. |
| Swing view | Uses WorkbenchStyle delegates/quiet controls, ConfigManager palette roles, UiZoom and the configured interface font. It owns widgets, input listeners, layout and subscription disposal. Existing editor/tool WorkbenchIcons are retained; the new toolbar uses words and adds no icon collection. |
| Compose view | Kotlin only in `ComposeLabFactory`; maps the same state/tokens into ComposePanel, BasicTextField, text buttons and scroll layout. It owns Compose focus and semantics, and closes its subscription through DisposableEffect and panel disposal. |
| Shared host/composition | Constructs Texteditor and injects the original Swing editing surface beside the panel. Owns split/dialog creation, prepared dialog sizing, focus transfer, keyboard interception and closing. This owner-aware adapter is intentional, not an application-facing contract or a replacement service locator. |

Port calls retain EDT affinity. Compose actions explicitly marshal to Swing's EDT; changing presentation does not change application callback timing. Initial subscription delivery is synchronous on the registration thread (the EDT in these hosts); subsequent publication runs synchronously on the EDT. Detaching closes the old view and creates another against the same model. Shutdown stops the timer, clears subscriptions/actions/drafts and rejects queued model actions after closure. There are no asynchronous query workers in this six-setting slice; no stale worker result can arrive. Existing production asynchronous document operations remain outside this presentation experiment.

Two concrete host adaptations were necessary for **both** variants: use Shed's dialog preparation before showing a detached panel, and intercept appearance/F6 within the experiment's owned windows because the legacy source key listener can consume keys before root-pane bindings. The scoped dispatcher is removed on disposal. Production key handling and dialogs are unchanged. These are examples of remaining composition coupling, not evidence that the entire frontend is detachable.

## Full interface review

Review mode: **full**, bounded to these two presentations and their editor integration. The source/application contracts from the prior stages remain the styling and ownership baseline.

| Category | Evidence and outcome |
| --- | --- |
| Typography | Both use the configured UI family/size, relative heading/body/detail sizes and independent source typography. Friendly setting labels replace machine keys; actual keys remain in the application descriptors. Captures cover light/dark at 100% and 150%. |
| Surfaces and layout | Existing palette roles supply all colors. Quiet document selection, a single appearance surface, per-row separation, scrollable content and pinned feedback distinguish actions from descriptions. Narrow/enlarged controls remain scroll-reachable; this intentionally shows fewer rows rather than shrinking text. |
| Icons and controls | Existing source/tool icons remain. New actions use accessible text labels and 40 logical-pixel control targets; selected documents have an underline. No new icon dependency or competing Swing look and feel. |
| Motion and interaction | No custom entrance, spring or repeated-row animations. Swing uses existing control states; Compose uses its stock material press/focus behavior. Actual native activation, F6, typing, Escape and editor focus restoration were exercised on this host. |
| Rendering performance | Both reuse the same source renderer/document and bounded metadata presentation. Measurements below include real workbench startup, footprint and synchronous source edit/paint; none is inferred from an empty Compose window or a Gradle debug run. |

| Severity | Location | Before | After / evaluation | Why |
| --- | --- | --- | --- | --- |
| MEDIUM | `SwingLabFactory.settings`, `ComposeLabFactory.settings` | A fixed header could consume the enlarged narrow panel, leaving rows unreachable. | Scroll the entire heading/toolbar/query/row region; wrap toolbar actions and retain feedback outside it. | Preserve readable controls and reachability without reducing zoom. |
| MEDIUM | `LabHost.toggleDetached` | Generic dialog auto-packing collapsed the Compose panel to its transient preferred size. | Use existing `EditorUiController.prepareDialog`, then pack the explicit content size. | Respect Shed's existing composition/lifecycle rules. |
| MEDIUM | `LabHost` key dispatcher | The original source listener intercepted Settings/F6 before prototype root bindings. | Scope interception to the prototype's owned focused windows and remove it on disposal. Native event probes verify both paths. | A working layout must also transfer keyboard focus correctly. |
| MEDIUM | `LabModel.Style`, both view factories | Initial mappings ignored the configured UI family and relative font sizing. | Derive shared family/base size/zoom from Shed; map logical font families for Compose. | Appearance configuration must still mean the same thing. |
| MEDIUM | `LabHost.start`, `LabModel.update` | Compose initialization changed the global macOS menu default after the host's initial pin, moving subsequent menus and changing client space. | Normalize after first composition and before settings application; record/assert embedded menu height for every gallery state. | Framework defaults must not make the fixture inequivalent. Earlier measurements are retained as diagnostics, superseded by the final artifact run. |
| LOW | Both settings views | Raw key labels and an unlabeled query made a small form harder to scan. | Friendly titles, explicit search label, setting descriptions, accessible names and clear empty/error feedback. | Distinguish the setting, editable value and result. |
| LOW | Capture harness | AWT-only paint omitted default heavyweight Compose content; native capture without an explicit Swing repaint could omit pending Swing paint. | Keep diagnostic root paints and capture only the actual prototype-owned native window after settling/repainting; separately capture owned popup/dialog windows. | Screenshot completeness is a verification issue, not proof of a framework rendering defect. |

Considered and rejected: **FlatLaf** (no demonstrated limitation in the existing delegates for this slice); **replacing the editing surface** (would change the experiment and undo/performance semantics); **a new icon pack or animated panel transitions** (no task-specific benefit); **requiring experimental offscreen rendering** (evaluated separately; not needed to run the default panel, and not a demonstrated production advantage). Neither prototype is a production-wide visual/accessibility certification.

## Reproduction and platform constraints

Use the [experiment README](../experiments/ui-comparison/README.md) for exact build, launch, capture, keyboard, comparison and package commands. Build Swing with Java 21 `javac`/`jar` and the existing release JAR. Compose uses an isolated checksum-pinned Gradle 8.14.3 bootstrap, Kotlin/compiler plugin 2.3.20 and Compose 1.12.2; the ordinary Maven build does not resolve or include them. Both measured processes run compiled release JARs with the same plain Java invocation and 64–512 MiB heap, without a debugger, Gradle launcher or development reload tooling.

Official [Compose compatibility documentation](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) currently lists ARM64 macOS, while the evaluated Intel macOS 15.7.7 host could resolve and run the published `skiko-awt-runtime-macos-x64` artifact. **Observed x64 execution is not a supported-platform guarantee.** JDK 21 satisfies the documented runtime/packaging requirements. The [Swing interoperability guide](https://kotlinlang.org/docs/multiplatform/compose-desktop-swing-interoperability.html) documents panel-contained Compose popups and experimental alternatives for layering/offscreen rendering, with potential performance costs. Those constraints remain migration work.

Native source popups were actually opened near the panel boundary; on this host Swing chose a separate owned popup window, which was captured separately. Docking, detaching, theme/zoom updates and window resizing were exercised. The experiment does not claim that arbitrary Compose popups escape their panel, or that all lightweight/heavyweight overlay combinations work. No experimental popup-window flag was required. The optional offscreen mode was also exercised; its screenshots/interaction evidence are retained separately from the default-renderer timings.

Accessibility probes traverse the **actual Java accessibility bridges** for both toolkits: query editing, invalid and valid Apply actions, persisted config, and document-tab activation. They also exercise the unchanged source transfer handler using an isolated local clipboard, preserving the user's system clipboard. Native Robot checks send input only while the prototype owns focus and stop on focus loss. Native IME/dead-key composition, OS clipboard integration, assistive-technology output, touch, and Windows/Linux/ARM64 desktop execution were **not** tested. Standard Tab traversal/IME support is inspected in the stock controls, not claimed as a native certification.

## Measurements and final verification

The final normalized run used Temurin 21.0.12.1 on Intel macOS 15.7.7, five fresh JVMs per variant in alternating order, and 125 warmed edit samples per variant. There were no competing builds, tests, packaging jobs or heavy artifact inspections during timing. OS caches, GPU state and unrelated host activity were not controlled. Raw samples, artifact hashes and run order are in [comparison.json](performance/ui-stage3/comparison.json).

Values below are **p50 / observed p95**. For the five startup/footprint samples, p95 is simply the largest observation; it is not a population tail estimate.

| Metric | Swing | Compose (default renderer) |
| --- | ---: | ---: |
| Process → ready (ms) | 6842.10 / 11767.06 | 9532.89 / 12732.50 |
| Host → ready (ms) | 6473.85 / 11328.46 | 9116.44 / 12270.61 |
| Observed heap (MiB) | 38.76 / 40.87 | 32.59 / 35.52 |
| Observed RSS (MiB) | 510.73 / 516.41 | 550.13 / 551.97 |
| Source edit → synchronous paint (ms) | 2.74 / 3.70 | 2.79 / 3.64 |

Process-ready starts immediately before launching Java. Host-ready starts at LabHost construction. Both wait for staged startup to end, both real fixture documents to open, and navigation/settings to paint, then include 500 ms settling. Footprint is observed another 500 ms later without forced GC. The same insertion/`paintImmediately` interval is timed in both; ten warmups precede 25 samples per process, with undo outside timing. Event-queue delay, hardware presentation, Compose-frame completion and asynchronous syntax convergence are **not** included. These meanings differ from Shed's normal desktop benchmark; no historical startup or 500,000-line budget is declared passed by this table.

Both have a slow first launch, and source painting is similar. Compose's observed heap is lower while its median RSS is about 39 MiB higher. These measurements do not justify a general heap-efficiency claim or attribute the complete startup gap to the framework. The earlier [pre-normalization diagnostic](performance/ui-stage3/diagnostic-before-menu-pin.json) is retained, with different prototype hashes and startup medians of 7455/7975 ms (Swing/Compose). Do not interpret the change between sets as a speedup caused by menu normalization. Startup variability remains material.

Like-for-like launch ZIPs were built and **both extracted archives actually launched the application and passed native activation, focus/search, Escape and editor-focus checks**. Both require an external Java 21; neither includes a JRE or signed native installer.

| Packaging observation | Swing | Compose |
| --- | ---: | ---: |
| JAR files shipped, including existing Shed fat JAR | 2 | 47 |
| Uncompressed JAR bytes (MiB) | 47.64 | 85.87 |
| Launch ZIP (MiB) | 43.86 | 80.53 |
| Experimental presentation source | 152 Java lines | 156 Kotlin lines |

These file counts are **not total library counts**: Shed's existing dependencies are inside its fat JAR, and Compose resolves some small bridge/facade JARs. [Packaging sizes](performance/ui-stage3/packaging.json), [Swing runtime manifest](performance/ui-stage3/swing-runtime-artifacts.json), [Compose runtime manifest](performance/ui-stage3/compose-runtime-artifacts.json). The small form offers no decisive source-size advantage in either language; shared host/model/probes remain Java. Compose adds platform-specific Skiko, another build/compiler pipeline, and a newer Kotlin runtime that must precede the older runtime already embedded for Shed's terminal. Terminal compatibility under that override, native installers/signing and other target platforms were not validated. No such override affects the production launch.

The automated real-operation probes passed for [Swing](performance/ui-stage3/swing-gallery.json), [Compose](performance/ui-stage3/compose-gallery.json) and [optional offscreen Compose](performance/ui-stage3/compose-offscreen-gallery.json). They verify accessibility-bridge query/edit/Apply, rejection without mutation, actual persisted settings, document activation, isolated clipboard transfer, a visible real source popup, and exactly three model subscriptions after detach/dock (host, navigation, current settings). Native key checks and both [Swing](performance/ui-stage3/swing-packaged-keyboard.json) and [Compose](performance/ui-stage3/compose-packaged-keyboard.json) packaged-launch checks passed. External human/assistive-technology testing was not performed; this evidence is automated plus code and captured-image review.

Matched native screenshots were visually inspected for the selected theme/scale/narrow/feedback/detached states. The narrow 150% case needs scrolling to reach rows; retaining readable text is intentional, and it still needs production-specific density refinement. This review uses the default interface family/size; arbitrary custom families and extreme font sizes were not separately visually audited.

| Captured state | Swing | Compose |
| --- | --- | --- |
| Dark, 100% | [capture](images/ui-comparison/swing-01-dark.png) | [capture](images/ui-comparison/compose-01-dark.png) |
| Light, 100% | [capture](images/ui-comparison/swing-02-light.png) | [capture](images/ui-comparison/compose-02-light.png) |
| Light, 150% | [capture](images/ui-comparison/swing-03-light-150.png) | [capture](images/ui-comparison/compose-03-light-150.png) |
| Dark, 150% | [capture](images/ui-comparison/swing-04-dark-150.png) | [capture](images/ui-comparison/compose-04-dark-150.png) |
| Narrow, 150% | [capture](images/ui-comparison/swing-05-narrow-150.png) | [capture](images/ui-comparison/compose-05-narrow-150.png) |
| Empty | [capture](images/ui-comparison/swing-06-empty.png) | [capture](images/ui-comparison/compose-06-empty.png) |
| Error | [capture](images/ui-comparison/swing-07-error.png) | [capture](images/ui-comparison/compose-07-error.png) |
| Detached panel | [capture](images/ui-comparison/swing-08-detached-window.png) | [capture](images/ui-comparison/compose-08-detached-window.png) |
| Source popup (separate owned window) | [capture](images/ui-comparison/swing-09-editor-popup-window.png) | [capture](images/ui-comparison/compose-09-editor-popup-window.png) |

The first native captures with incomplete pending Swing paint and the earlier mismatched menu placement are **not the matched evidence above**. The harness now paints the Swing root, settles, then captures the actual owned native window. Diagnostic AWT-only images omit default heavyweight Compose content, as expected; optional offscreen rendering includes it. [Offscreen sample](images/ui-comparison/compose-offscreen-01-dark.png). Old diagnostic runs remain under `/tmp/shed-ui-stage3-*`; screenshots accidentally capturing unrelated foreground content during initial harness development were deleted and are not archived.

Final headless `package`: **1,273 tests, zero failures/errors, 89 skips**. Five selected existing Swing integration tests passed with a display, zero failures/errors/skips. Their selector was `TexteditorSwingIntegrationTest#settingsDialog*+openEditorsShow*+customActionsWork*+splitCloseFocus*` (appearance boundary/theme colors, real tabs/views, profiles/composition routing, split focus). The first headless pass had one JUnit temporary-directory cleanup error in `AgentSessionPanelTest.selectionActionUsesCapturedUnsavedCodeAndTheActualReviewPanel`; the isolated 21-test class rerun and full package repeat both passed. That first error is retained in [verification.json](performance/ui-stage3/verification.json) and `/tmp/shed-ui-stage3-headless-first`; it is not silently counted as a pass. No production change was made to address it.

Reproduce the existing checks with:

```sh
lab_java_home=$(scripts/resolve-toolchain.sh home)
lab_maven=$(scripts/resolve-toolchain.sh maven)
JAVA_HOME="$lab_java_home" "$lab_maven" -B -q -Djava.awt.headless=true package
JAVA_HOME="$lab_java_home" "$lab_maven" -B -q -Djava.awt.headless=false '-Dtest=TexteditorSwingIntegrationTest#settingsDialog*+openEditorsShow*+customActionsWork*+splitCloseFocus*' test
```

Full reports/logs are outside Maven output under `/tmp/shed-ui-stage3-headless-passed`, `/tmp/shed-ui-stage3-desktop-passed` and the corresponding stage-three logs. Default and experimental dependency manifests exactly match the final measured artifacts. **Verdict:** enough evidence to choose Swing and continue incrementally; no evidence justifying a production Compose migration. Cross-platform native interaction, full overlay coverage, installer distribution and the remaining font/accessibility matrix remain unverified.


## Handoff and next stage

Carry forward the **Swing design and the application ports**, with incremental production adoption under existing WorkbenchStyle/WorkbenchIcons/UiZoom/config conventions. Keep the current document/history/approval engines. Consolidate the real menu/key-listener entry points through composition when adopting the panel, rather than copying the experimental dispatcher everywhere. Verify narrow layouts and keyboard/focus behavior for each production surface. Treat existing startup-tail budget misses as separate profiling work; these prototypes do not repair or redefine those budgets.

Remaining coupling includes Texteditor's owner-oriented composition, global config/theme application, source key routing, native dialog preparation, pane attachment, syntax/LSP overlays and specialized tool/editor surfaces identified in stage two. The experiment demonstrates that these selected workbench views can consume the boundary; it does not prove that a full frontend replacement is now routine. No production rollout, global restyling, frontend framework migration or new extension system belongs to this completed comparison stage.

# Stage 4 — production workbench redesign (in progress)

The selected production approach is **Swing with the existing WorkbenchStyle, WorkbenchIcons, UiZoom and Java application boundaries**. No new framework selection is being conducted. This stage is broader than the prototype: it is incomplete until the complete workbench, listed journeys, visual review and performance checks below are verified. Earlier stage-one/two source changes and stage-three evidence were present and retained at the start.

## Fresh baseline

Starting HEAD: `94792eabbfe304c10f85ad576baa64eb2ab1be9d`; the worktree includes prior modernization stages. Starting production JAR SHA-256: `c051395ad94d4aa1cbfcc39ddf79b22700091853701e163a9696d5f97fa4d9b1`. The JAR, pre-change diff, untracked-file hashes and available test reports are preserved in `/tmp/shed-ui-stage4-baseline`, outside Maven clean output. The earlier headless package reports are carried-over evidence for that artifact, not a newly executed stage-four baseline suite.

Fresh measurements against this preserved JAR passed all existing budgets on this host: desktop visible/source/full-UI p95 **1573.36 / 1935.36 / 8971.36 ms**, edit/paint **4.52 ms**, picker **16.67 ms**, used heap **52.39 MiB**, RSS **495.82 MiB**. The fixed 500,000-line workload and three-iteration 100 MiB paged-file workload also returned 0. Raw baseline results are in [performance/ui-stage4](performance/ui-stage4); fixture generation matches the prior stage-two byte pattern exactly. Earlier recorded startup misses remain historical context and are not silently replaced by this passing baseline.

The original gallery returned 1 after eight captures because its enum/string comparison expected `AGENT` while the actual tab is `Agents`. Correcting that one title mapping, retaining the visibility/selection assertions, produced **30 baseline captures with zero control-bounds failures** against the unchanged starting JAR. Both original failure and corrected evidence are retained in `/tmp/shed-ui-stage4-baseline`. This was a verifier mismatch, not proof that the production Agents tool was invisible.

## Implementation and ownership so far

- `WorkbenchHeader` adds a workspace-root chooser, active-document path context, focus/pane cue, and explicit File/Commands/Splits/Tools/Settings actions around the existing editing surface. Folder changes use WorkspaceFoldersApplication; document/split operations use DocumentWorkspace. Legacy owner-aware operations remain at composition. Metadata refresh reuses the existing status update; there is no new polling timer or text snapshot. The focus listener is removed when the owning window closes. Saved tab visibility and focus-mode preferences remain independent of navigation.
- `DocumentLabels` provides deterministic, shortest distinguishing parent suffixes for duplicate filenames. Full paths remain in tooltips/accessibility text. Document cells have bounded width and font-aware height. Switching a document from keyboard navigation retains focus until Enter explicitly returns to editing; dirty-close decisions still revalidate current application metadata.
- The tool strip scrolls rather than allocating additional wrapped rows. Workbench Tools exposes every panel regardless of strip width. ToolSurface focus entry points are presentation-only and invoked by explicit navigation, never refresh or background completion. Escape from the dock returns to the source surface. Returning detached panels to the dock uses their existing disposal/reattachment path.
- Shared outline icons extend the existing icon set and inherit semantic foreground/disabled colors. No new image/icon dependency or animation system is added.

- Settings now use full-width search, category selection, a Modified only filter, per-value Reset, readable titles and inline validation feedback. Invalid drafts survive filtering; reset can recover an invalid draft even when the persisted value is already the default. Persistence and runtime application still use SettingsApplication; typography, borders and scaling use the shared delegates.
- Shared semantic intents (error, warning, success, muted) resolve through the current palette with readable contrast and survive recursive theme application. Invalid fields have an explicit border state. Task, test, Problems, Git, search, replacement and review feedback reuse those roles rather than embedding a second color system.
- Tasks retain job selection and output reading position, distinguish process completion from a nonzero command exit, expose cancellation state, and keep panel-started completion in Tasks even when the dock is hidden. Command-originated task presentation retains its configured scratch-output behavior; the existing remote/container execution routing and explicit-run gates remain intact.
- Problems retain sorted selection by diagnostic identity and support Enter navigation. Tests avoid rebuilding an unchanged tree and preserve expanded groups/selection across ordinary state refresh. Debugger refresh preserves selected stack/watch entries and unchanged breakpoint drafts. Git retains selected paths, rejects diff results from an older selection/snapshot and stops its timer on close. Search and replacement have inline errors/progress and preview cancellation invalidates stale results without changing approval policy.
- Review adds explicit pending/working/dirty/applied/recovery state, keeps proposal-wide Apply and Reject in a full-width footer, and scrolls nonwrapping secondary actions. A compact workspace/proposer/document summary stays visible; full metadata is in Details and the review tabs scroll rather than wrap. The 760-pixel/150% production check caught and corrected clipping from the earlier document-column action row. Opening review still does not apply a proposal. The production test checks explicit Apply to an open buffer remains unsaved on disk and explicit Undo restores it.
- Docked size and selected tool are saved as optional Session V2 data. Detached placement is restored per workspace/owner; returning a panel records DOCKED while retaining its last floating geometry. Lifecycle callbacks bind to their original placement model, and restoration snapshots desired placement before reattaching existing windows. Closing the workbench disposes floating windows and closes tool surfaces. Active split borders/accessibility cues reuse the existing panes; specialized content and undo ownership are unchanged.

Focused verification so far: the 33-test native-display selection passes (9 TexteditorSwingIntegrationTest cases plus AgentReviewPanel/ToolOutput/placement/application-boundary tests). It covers actual task failure/cancellation without losing the source document, unchanged-tree and sorted-diagnostic selection, saved/manual dock sizes and detached-session restoration, normal proposal Apply/Undo, settings invalid-draft/reset behavior, and existing split/search/focus/zoom checks. The first production gallery against an intermediate JAR passed 30 captures and every narrow-tool bounds check. A production journey probe is being completed; its native-key attempt is under investigation and is not counted as a passing native-input journey.

A later candidate gallery exposed ten clipped Debug controls, and short-dock inspection showed that green component tests were not sufficient evidence for constrained content. ToolViewport now retains a usable scaled content height and scrolls it within the chosen dock; the same presentation moves with detached tools. Focus reveal crosses nested viewports. The gallery is being strengthened to scroll each ordinary control into view and verify its complete visible rectangle, rather than treating the new root viewport as an exemption. Final verification remains pending until those checks and actual image inspection pass. Native backing-store captures also proved stale in some background runs; the final state evidence uses actual production Swing root paints, with native attempts retained as a verification limit.

Atomic implementation commits so far: `cba4faff` settings/semantic styles, `3bf0828d` Tasks feedback, `4dbec5af` layout/lifecycle, `19d2f06d` review presentation and `62a6ba2a` tool refresh behavior. The picker-lifetime follow-up is `f7fb84bb`. These are implementation checkpoints, not a declaration that all final gates have passed.

## Requirement and completion ledger

| Required scope | Current state / evidence still required |
| --- | --- |
| 1. Workspace/document/split/tool navigation and active focus | Navigation foundation implemented and focused tests pass; inspect packaged production captures, active split presentation, tool detach/reattach/recovery and session restoration end to end. |
| 2. Command discovery and settings organization | Workbench entry points, responsive settings search/category/modified/reset and inline validation implemented; final captures/journeys and full display run remain. |
| 3. Shared typography/spacing/colors/icons/control/scaling | Shared semantic intent/invalid states implemented across tools and settings; final five-category review and exact-artifact captures remain. |
| 4. Loading/cancellation/empty/error/completion | Task cancellation/exit-state and noninterrupting panel completion tested; search/replace/Git stale-result and output/selection handling implemented. Full final checks remain. |
| 5. Approval/review presentation with identical authorization | Review banner and fixed primary decisions implemented; real production Apply/Undo test passes with unchanged unsaved-buffer policy. Native captures remain. |
| 6. Search/Problems/tasks/tests/debug/Git/agent/dialog conventions | Shared feedback and state-preserving refresh implemented across tools; first intermediate gallery passes. Full relevant desktop suite and final review remain. |
| 7. Narrow/long/enlarged/light/dark/persisted/recoverable layouts | Intermediate 30-capture gallery/all narrow tools and detached/session/manual-size tests pass. Final gallery, native captures and preference journey remain. |
| 8. Keyboard/focus/accessibility/states/restrained motion | Navigation focus regression covered; full journeys and native interaction states remain. No custom motion introduced. |
| Editor journey | Test actual workspace → file finder → edit/undo/redo/save → switch → dirty-close confirmation. |
| Search/diagnostics/tools journey | Exercise actual result navigation and tool open/close. |
| Appearance/layout/session journey | Exercise settings, resize, detach/reattach, persist and restore. |
| Normal approval journey | Exercise an existing proposal through its normal review/approval path; preserve versions/admission checks. |
| Final headless package / desktop / gallery | Run after the complete implementation, preserve exact counts/logs and visual artifacts. |
| Performance | Repeat unchanged desktop, 500,000-line and paged benchmarks against the final artifact; compare with the fresh baseline and resolve attributable regressions. |
| Cleanup | Rejected prototype sources/dependencies removed in worktree; decision samples/images retained and capture/accessibility helpers moved to benchmark sources. Archive commit and final audit remain. |
| Final design/migration handoff | Update INTERFACE_DESIGN and WORKBENCH_LAYOUT to describe actual final behavior; full five-category skill review with source lines and verification verdict. |
| Verification limits / release | Label platform/native/IME/screen-reader evidence accurately. No release publication is authorized or planned. |
