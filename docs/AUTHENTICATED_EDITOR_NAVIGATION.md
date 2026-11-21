# Track A2.2 — authenticated, context-validated editor navigation

**CONDITIONAL GO for the supported existing-pane, Insert-mode headless/component scope.** Implementation contracts, authenticated external CLI use and warm responsiveness pass. Cold-tail metadata timing remains an open/flaky gate: one unchanged probe failed its 4 ms EDT maximum and its repeat passed. This report covers existing-pane caret, selection and reveal operations only. File opening is deferred. Real desktop attachment, complete window/controller integration, macOS/Windows execution and whole-process RSS remain OPEN. Native-shell fixture isolation remains NO-GO; its guards were preserved.

## Supported scope and architecture

The implementation extends the A1/A2.1 listener, private credential, mutual-authentication transcript, bounded transport and CLI. It creates no listener, worker pool, source model, persistent setting or approval panel. The existing authoritative `FileBuffer` document and `EditorPane.surface()` perform navigation. There is no source-reading endpoint, edit, save, undo/redo, command execution, diagnostic access, subscription, preview or capture operation.

Only the **currently active, explicitly identified named source pane**, in **Insert mode**, is supported. Plain and Emacs normally use Insert; Vim must already be in Insert. The command does not change modes. Terminal, alternate/preview, scratch, large-file projection, disposed, hidden/focus-mode and unauthorized contexts remain unavailable. IME composition, completion/snippet sessions, an Emacs prefix, pending Vim input/count/register, additional cursors and macro recording decline navigation. A focused Shed window must have its source area focused. There is no OS-window activation or focus request.

Mandatory review included the A0 control-plane audit, A1 attachment report, A2.1 observation report and their evidence indexes, whole-IDE audit, split-view lifecycle remediation, gutter remediation, native-shell isolation report, applicable `AGENTS.md`, Maven/task configuration, CLI/workspace documentation and actual dispatch/controller code. Existing glyph/Unicode and Markdown qualifications remain limitations, not work undertaken here.

| Actual source and signature | Reused responsibility |
|---|---|
| `AgentWorkspaceController.handle(String)` / `start(boolean, boolean, boolean, boolean)` | Explicit in-editor grant; existing asynchronous publication and stop/revocation |
| `WorkspaceBridgeServer.serveStatus(Socket)` | Existing authenticated loopback transport; no legacy command fallback |
| `WorkspaceRpc.handle(String, BooleanSupplier)` | Versioned dispatch and a lock/IO-free precommit validity gate |
| `EditorMetadata.capture(EditorPane, EditorWorkspaceDocuments, Path, List<Path>, long)` / `authorize(Context)` | A2.1 authoritative identity/coordinate capture and worker-side project authorization |
| `Texteditor.captureEditorMetadata()` / `captureEditorNavigation()` | Live window, pane membership, buffer ownership and bounded composition-root state |
| `EditorUiController.bindActivePane(EditorPane)` | Existing pane/buffer activation path, now invalidating the navigation context |
| `EditorNavigation.prepare(...)` / `apply(...)` | Bounded validation adapter and final EDT revalidation |
| `SwingEditorSurface.select(int dot, int mark)` | Existing caret/selection operation; validation precedes its internal clamp |
| `JTextArea.modelToView2D(int)` / `scrollRectToVisible(Rectangle)` | Existing viewport reveal; no new layout or navigation model |
| `GraphemeBoundary.isBoundary(String, int)` | Existing Unicode/grapheme boundary rules |
| `EditorPane.disposeSourceView()` / `FileBuffer.documentRevision()` | Existing disposal and authoritative source revision |
| `PaneBufferController.openFileCheckedAsync(File, BooleanSupplier, Predicate<FileBuffer>, Predicate<FileBuffer>, Runnable, Consumer<IOException>)` | Reviewed existing opening path; **not exposed** by A2.2 |

## Explicit authorization and CLI

The human explicitly runs:

```text
:agent api start navigation
:agent api stop
```

The grant is a new `scope: "editor-navigation"` attachment with a fresh key/attachment/project ID, bound to the selected canonical project root and its configuration generation. Starting with different permissions requires stopping the current attachment. A running status or metadata grant cannot be elevated by a client parameter, method, CLI flag or descriptor edit. Existing `:agent api start status`, `start metadata`, and legacy workspace/proposal modes retain their prior behavior. The legacy `:agent api start` is not redefined.

The private descriptor keeps schema version 2 and adds no new fields beyond the A2.1 shape: `schemaVersion`, `instanceId`, `windowId`, `attachmentId`, `port`, `expiresAt`, `scope`, `projectId`. A1 role-separated nonce/HMAC mutual authentication and signed, sequenced records bind the new scope/project using the existing A2.1 transcript extension. Status vectors and authentication tests remain unchanged. Older clients continue using their original grant; they reject the new scope rather than downgrading it.

The one-shot external client is:

```sh
shed --workspace-api --navigate --connect <private-descriptor-file> --credential <private-key-file>
shed --workspace-api --navigate --connect <private-descriptor-file> --credential <private-key-file> --request '<JSON request below>'
```

The first invocation obtains a navigation context. Copy its identities/revision/preconditions into one command. Descriptor/key **filenames** are arguments; key bytes never are. No URLs, environment credentials, discovery scan, automatic launch, `--root`, `--stdio`, implicit active-pane command or persistent orchestration is added. Navigation clients can also request the unchanged A2.1 `editor/metadata` schema. Metadata-only clients cannot use `--navigate`.

The existing maximum lifetime is ten minutes; `api stop`, session closure, expiry, project-generation change and application shutdown revoke access. Root identity support must be present; no Windows-specific fallback is invented. The same-user key-possession threat model and integrity-without-encryption limitation from A1 still apply.

## Exact protocol

Wire protocol remains 2; operation `schemaVersion` remains 1. JSON-RPC 2.0 requires an ID: a bounded string (at most 256 UTF-16 units) or integer. Notifications/null IDs do not navigate. Strict field sets reject extra capability, path, command or coordinate fields.

Context request:

```json
{"jsonrpc":"2.0","id":"context","method":"editor/navigation/context","params":{"schemaVersion":1,"projectId":"project-<UUID>"}}
```

Its result is the exact A2.1 17-field metadata object, with capabilities replaced by `["status","editor-metadata","editor-navigation"]` and one additional nonnegative integer `contextRevision`:

```json
{"schemaVersion":1,"protocolVersion":2,"instanceId":"<UUID>","windowId":"<UUID>","capabilities":["status","editor-metadata","editor-navigation"],"projectId":"project-<UUID>","paneId":"pane-<UUID>","documentId":"buffer-<UUID>","documentLength":10,"documentRevision":2,"dirty":true,"caretOffset":0,"selectionStart":0,"selectionEnd":0,"coordinateUnit":"utf-16","available":true,"valid":true,"contextRevision":7}
```

The values above illustrate schema, not a fabricated successful empty document. Unavailable contexts return errors.

Every mutation requires **all eleven common fields**: `schemaVersion`, `instanceId`, `windowId`, `projectId`, `paneId`, `documentId`, `documentRevision`, `contextRevision`, `caretOffset`, `selectionStart`, `selectionEnd`. The last three describe the **expected prior state**, not requested new coordinates. Example:

```json
{"jsonrpc":"2.0","id":"move","method":"editor/caret/set","params":{"schemaVersion":1,"instanceId":"<observed UUID>","windowId":"<observed UUID>","projectId":"project-<observed UUID>","paneId":"pane-<observed UUID>","documentId":"buffer-<observed UUID>","documentRevision":2,"contextRevision":7,"caretOffset":0,"selectionStart":0,"selectionEnd":0,"offset":5}}
```

| Method | Additional required fields | Applied effect / acknowledgment |
|---|---|---|
| `editor/caret/set` | `offset` | Collapse selection at validated offset; resulting caret/start/end all equal offset; `visibility: "caret"` |
| `editor/selection/set` | `start`, `end` | Anchor at start, caret at end; half-open range, including empty range; `visibility: "selection"` |
| `editor/reveal` | `start`, `end` | Scroll existing viewport to range; preserve caret/selection. `visibility: "range"`, or `"start"` when the union cannot fit |

An acknowledgment is a current navigation-context result plus exactly `operation` (the method), `applied: true`, and `visibility`. It retains document/pane/revision/length/dirty state and consumes the prior `contextRevision`, even for a no-op. The client checks exact schema, authenticated identities, revision, increasing context and requested resulting selection/caret. An acknowledgment means the EDT operation completed, not that a native window painted or took focus.

Limits remain **2,048 UTF-8 payload bytes**, **4,096 frame bytes**, **128 exchanges per connection**, **eight clients**. Oversized requests fail; results are never truncated into success. Before a presentation change, the server verifies the maximum possible acknowledgment size, including escaped request ID. One navigation/context request per grant can occupy the EDT admission slot, including a cancelled task awaiting queue consumption. A2.1 metadata retains its existing separate bounded admission. Project roots/open documents/live panes retain limits 32/1,024/64. Tracking itself also caps at 64 weak targets; capacity failure declines observation without blocking human pane creation.

| Condition | Code / semantics |
|---|---|
| Missing/invalid mutual authentication, wrong attachment identity/key | `UNAUTHORIZED` / `SESSION_INVALID`; no navigation metadata |
| Status/metadata grant calling navigation | `UNSUPPORTED`; factory also rejects the wrong scope |
| Expired / revoked grant | `SESSION_EXPIRED` / `AUTHORIZATION_REVOKED` |
| Wrong requested instance/window / project | `UNAUTHORIZED` / `PROJECT_UNAUTHORIZED` |
| No source editor / unavailable or disposed pane | `NO_ACTIVE_EDITOR` / `PANE_UNAVAILABLE` |
| Wrong document, changed interaction/selection/active pane | `CONTEXT_CHANGED` |
| Changed source revision | `STALE_REVISION` |
| Invalid authoritative state / invalid requested offset | `INVALID_CONTEXT` / `INVALID_OFFSET` |
| Unsupported input mode/state | `UNSUPPORTED_MODE` |
| Unsupported bounded line/layout workload | `BOUNDARY_UNAVAILABLE` |
| Missing/nonfinite reveal mapping or viewport extent | `GEOMETRY_UNAVAILABLE` |
| Malformed / oversized / unsupported version / excluded command | `INVALID_REQUEST` / `LIMIT_EXCEEDED` / `UNSUPPORTED_PROTOCOL` / `UNSUPPORTED` |
| Occupied admission / unstarted deadline / interrupted unstarted task | `BUSY` / `TIMEOUT` / `CANCELLED` |
| Already-running operation without a definitive acknowledgment | `OUTCOME_UNKNOWN`, `outcome: "unknown"` |
| Application not ready / shutting down | `APP_NOT_READY` / `APP_SHUTTING_DOWN`; transport can instead close |

Checked navigation-dispatch failures carry JSON-RPC code `-32000` and data `{code,outcome}`; unexpected context errors use `-32603`. Transport/auth/session gates and ordinary pre-envelope/unknown-method errors retain the earlier protocol's code-only/standard shapes. Definitively rejected navigation has `outcome: "rejected"`. The CLI normalizes failures without server paths; exit 79 denotes unknown outcome, 75 a context/coordinate/workload refusal, 77 authorization, 78 session/shutdown, 76 version, 70 timeout, 69 unavailable instance, 64 malformed input, 74 transport/unsupported/IO. Broken output also exits 74. Request IDs are client-supplied; no private server filenames/source are added to errors or ordinary logs.

## Context and human coordination

Opaque instance/window IDs retain A1 lifetimes. Project ID is per explicit grant, pane ID per `EditorPane` lifetime, document ID per canonical buffer registration. There is no target lookup that resurrects an old pane or redirects to a later active pane. The current pane must match the named pane, belong to the live window, own a registered buffer and share that buffer's actual document.

`contextRevision` is a grant-lifetime counter, not parallel editor state. Caret/selection events, pane/buffer binding (including away-and-back), document/caret replacement, viewport changes, source key input, focus and IME events invalidate it. Mode changes also invalidate it. Counter listeners hold weak component targets; they are installed only for an explicit navigation grant. A human edit additionally invalidates authoritative `documentRevision`. Even an away-and-back caret or pane transition cannot reuse the previous context. Changes in an observed inactive pane may conservatively invalidate the global grant counter.

The worker first captures live state, checks filesystem authorization off EDT, validates request preconditions, obtains bounded boundary samples on EDT, validates graphemes off EDT, and rechecks filesystem authorization. The final EDT operation reacquires the actual current context and eligibility, checks the complete prepared state again, resolves reveal geometry if needed, checks state/authorization again immediately before applying, then captures/validates the result. Human changes at either preparation or application are rejected; a stale command never targets the later active pane.

No focus API, tab creation, source-editing key event, jump-history mutation or preferences write is used. Existing surface movement and its normal caret visibility/highlighting behavior remain in place. Reveal preserves caret/selection. Existing Swing repaint/visibility scheduling is not a native completed-paint guarantee.

## UTF-16 and workload contract

All numbers are integer, zero-based UTF-16 code-unit offsets in the authoritative document. For logical length N, insertion positions are 0 through N inclusive. Selection start is inclusive, end exclusive, ordered and in the same document. Negative, fractional/string/overflow, greater-than-N, unavailable `-1`, synthetic EOF and reversed endpoints reject without clamping. Existing observation may report a human-created interior offset; a new navigation target must satisfy existing grapheme-boundary rules.

The adapter checks the existing `GraphemeBoundary` helper for surrogate pairs, combining sequences, CRLF, emoji/ZWJ and Unicode 15.1 GB9c. An ASCII-adjacent pair is a bounded fast path; otherwise it samples the complete logical line, at most 16,384 UTF-16 units per endpoint. Samples are temporary internal boundary-validation data, never serialized, logged, retained in a session cache or used as another buffer. Each target logical line must fit that bound, including ASCII/EOF cases, to constrain existing geometry costs. Documents larger than that bound using complex Swing layout (`i18n`) or wrapping decline conservatively. Large unwrapped ASCII documents with short lines remain supported. No new normalization or Unicode layout remediation is attempted. Line/column inputs are deferred.

## File-opening decision

**No file-opening capability is advertised or accepted.** Valid project files, traversal, absolute paths, directories, missing targets and symlink escapes all receive `UNSUPPORTED`; no path reaches a loader. This is safe exclusion, not qualification of an authorized file-opening API.

The existing checked opening controller is Markdown-oriented. It captures a pane, cancels LSP navigation, uses existing load/watch/recovery/alternate-surface lifecycle and preserves ordinary encoding/unsaved-buffer reuse. However, its checked branches apply project configuration on EDT **before the final current-context check**, have human-facing error behavior, perform alternate-surface/service setup and return an `IOException` callback without a navigation-specific applied/unknown result. Reusing it safely requires separately qualifying those effects, async cancellation and context-checked installation. No second loader or bypass was added. This is the permitted stopping boundary for A2.2 file opening.

## EDT completion, timeout and disconnect

Transport/HMAC parsing, filesystem validation, response-size admission and grapheme scans run on the existing bridge worker. Only bounded state capture, line sampling and existing presentation calls run on EDT. The final validity gate reads atomics/volatile expiry data; it does not acquire the credential-cleanup monitor or socket lock. A targeted test holds that cleanup monitor and verifies EDT expiry access still completes. No LSP, network, IPC, filesystem or client wait is added to the EDT.

Each UI task has queued/running/completed/cancelled-before-start state. A 200 ms queue/capture deadline cancels unstarted work: it cannot later apply. Its admission slot remains occupied until EDT consumes the cancelled item, avoiding repeated timed-out queue growth. A mutation already running gets an additional bounded 1,000 ms result wait. If it still has no definitive result, the response is `OUTCOME_UNKNOWN`; admission remains occupied until the operation exits. Authorization is checked immediately before the linearization point. Revocation/expiry detected after possible application is also classified unknown, not falsely rejected. There is no blind retry or replay/result cache.

The protocol has no cancellation command. Client interruption/timeout can close its connection. Without a new socket-monitor worker, remote disconnect may be detected only at the next read/write: an already authenticated admitted command can complete before detection. Lost acknowledgments must be treated as unknown and followed by a fresh context observation. Context consumption prevents reuse of an already applied request. These semantics deliberately do not promise rollback or cancellation of a running Swing operation.

## Source integrity, lifecycle and security evidence

Component tests exercise real `FileBuffer`, `EditorPane`, `SwingEditorSurface`, document/Undo model and geometry. Before/after checks cover source text, raw revision, dirty state, same Undo manager with both Undo and Redo available, saved baseline and file bytes. Shared-document splits retain independent selection; closing an inactive view leaves the active view usable. Navigation does not call recovery writes, save, LSP document mutation, or editing APIs. Recovery journal, Undo and other relevant existing regression tests remain enabled.

The Insert-mode policy is wired in the real `Texteditor`/`InputController` and inspected in source. Headless adapter tests exercise accepted/denied eligibility and existing Plain/Emacs/Unicode/history regressions. They do **not** instantiate a real `Texteditor` window or prove complete Plain/Vim/Emacs controller behavior, focus/scroll/history callbacks, LSP state or recovery integration on a desktop. Those remain explicit window-integration gates.

Tests cover unknown commands, no client elevation, wrong identities, stale same-length edits, away-and-back selection/pane/buffer changes, human selection/pane changes during preparation, closed panes/documents, project-generation revocation, shutdown, expiry at commit, explicit revocation, geometry failure, oversized acknowledgments before effects, cancelled queue bounds and running unknown outcomes. Sequential failed requests/clients do not grow listeners. Tests use disposable synthetic projects and fixed reviewed Java CLI processes. No developer project is a mutation fixture.

Grant hosts keep only weak window ownership. Request records contain bounded values, not panes/documents. Interaction tracking has weak pane keys and weak component/viewport references; closure detaches exact listeners and clears targets. Start failure/cancellation, explicit stop and expiry close tracking. A completed/cancelled request releases its slot only when worker and EDT ownership both end. Existing split disposal remains unchanged. Measurement disposal results are recorded below.

## Verification and performance

Final commands and raw outputs are archived in [the A2.2 evidence index](performance/agent-tracka22/INDEX.md). The runner admits only reviewed source/helper hashes, uses a disposable home/temp/preferences and copied offline dependency cache, executes Java 21 Maven through its Java entry and in-process Surefire, and keeps native benchmarks BLOCKED. It is trusted-code admission, not an OS sandbox. No full unreviewed Maven/GUI/native-shell suite was run.

| Verification | Result |
|---|---|
| Final safe Maven allowlist, 59 classes | **305 cases: 299 passed, six opt-in skips, zero failures/errors** |
| Dedicated final navigation measurement | **One opt-in case passed**; not added to the default regression count |
| Entry/final status and entry/repeated-final metadata measurements | Four passing one-case probes; initial final-metadata probe also has **one failed assertion**, retained separately |
| Applicable tooling | **86 passed**; unfiltered attempt had 86 passes and **one Linux platform error** |
| Production package / packaged image, math and Mermaid smoke | Passing against the final packaged artifact; tests separately verified |
| External fixed Java CLI | Context → applied caret → resulting context, three separate processes against the production bridge and real component panes; no JFrame |

The six default skips are the existing LSP/gutter/glyph performance opt-ins, A1 status and A2.1 metadata probes, plus the new navigation probe. Ordinary authentication, metadata, Unicode, selection/surface, split lifecycle, Undo, navigation history, Plain/Emacs keymap, recovery and related admitted regressions run normally. Real Vim/window-controller integration is not inferred from adapter eligibility tests.

Initial failures are not hidden: Stage A had three spurious `BUSY` errors from publishing task completion before releasing its EDT admission count; release ordering was corrected and sequential contracts pass. The first transport test compilation omitted an `ArrayList` import. A response-bound fixture initially supplied an ID too short to exceed the bound and incorrectly expected rejection; it now exercises a larger admissible escaped ID. A notification fixture initially expected a transport response even though the client rejects missing IDs locally; it now checks both client and RPC refusal. None required weakening production security or existing assertions. The unfiltered tooling error is exactly `test_native_fixture.NativeFixtureTest.test_profile_no_fork_network_or_user_home_access`: its macOS-only Seatbelt profile generator rejects Linux. The test/backend guard remains unchanged; the applicable selection excludes precisely that case after checking Linux and a single match.

The paired entry probes use an archive of entry HEAD, not a reset or worktree rollback. Entry status, entry metadata, final status, final metadata, and navigation probes ran sequentially in fresh Maven JVMs on the same Linux x64 host/JDK/cache. After the initial metadata maximum failure, an unchanged repeat passed. Full sample arrays, first values and failures are retained, not pooled or silently discarded. The final navigation probe adds a notification-only EDT admission hook: the driver queues human typing when apply begins; the EDT never waits for that driver.

| Operation / component | Warm p50 | Warm p95 | First / worst observed |
|---|---:|---:|---:|
| A2.1 entry status round trip, 80 warm samples | 2.726 ms | 5.137 ms | 24.417 / 24.417 ms |
| A2.2 status, same probe | 2.499 ms | 3.874 ms | 12.590 / 12.590 ms |
| A2.1 entry metadata, 100 warm samples | 4.769 ms | 9.726 ms | 61.654 / 61.654 ms |
| A2.2 metadata, unchanged repeat | 3.786 ms | 6.729 ms | 54.406 / 54.406 ms |
| Caret round trip, 60 warm commands | 4.982 ms | **8.001 ms** | 20.064 / 20.064 ms |
| Selection round trip, 60 warm commands | 3.346 ms | **4.578 ms** | 4.924 / 6.473 ms |
| Reveal round trip, 60 warm commands | 2.708 ms | **3.713 ms** | 2.980 / 4.860 ms |
| Navigation EDT state capture | 0.025 ms | 0.100 ms | 1.308 / 1.308 ms |
| Navigation EDT prepare | 0.064 ms | 0.133 ms | 1.010 / 1.010 ms |
| EDT caret application | 0.275 ms | 0.508 ms | 1.137 / 1.137 ms |
| EDT selection application | 0.241 ms | 0.463 ms | 0.380 / 1.233 ms |
| EDT reveal application | 0.205 ms | 0.341 ms | 0.331 / 0.790 ms |

Round trips exclude the preceding context request and connection/JVM startup. Cold values are separate. A2.2 context queries require two consistent captures. The metadata repeat's final service capture p95 was 0.117 ms; its small-document component capture maximum was 2.564 ms. The earlier probe stopped at its greater-than-4-ms maximum assertion before emitting full metrics, so that failed maximum's precise value cannot be recovered. The cause is **unverified**; a passing repeat does not establish a consistently passing cold-tail gate.

For the final navigation probe's 1 MiB unwrapped, short-line ASCII document, 50 warm insertion-to-completed-offscreen-paint samples produced:

| Typing condition | p50 | p95 | First / maximum |
|---|---:|---:|---:|
| No-traffic control | 1.879 ms | **3.250 ms** | 167.748 / 167.748 ms |
| Navigation racing human edits | 1.908 ms | **2.878 ms** | 2.182 / 4.584 ms |
| Input queued at actual navigation application | 2.242 ms | **4.113 ms** | 2.433 / 6.774 ms |

All 51 racing commands rejected `STALE_REVISION`; all 51 application-series commands applied. This separates stale-command protection from responsiveness behind successful navigation. Stable small-document navigation also applied all 183 commands. The component warm typing p95 stays below 16 ms; the 167.748 ms cold control paint is visible and remains outside that warm gate. Control-first order and sequential operation order warm JIT/layout/fonts, so lower later samples are not speedup claims. The earlier A2.1 historical 6.13/0.11/2.50 ms figures are not substituted for current paired observations. No authorized file-opening latency is claimed.

Median JVM-reported allocation deltas per warm caret/selection/reveal round trip were **181,440 / 185,752 / 182,528 bytes**, including unrelated runtime work. Median EDT state/prepare/caret/selection/reveal allocations were **256 / 640 / 1,072 / 1,320 / 1,552 bytes**. Server-only transport allocation is not isolated. After 24 navigation disposal cycles, **0/168 tracked pane/view/buffer/server/grant/tracker/navigation objects remained** following diagnostic GC; named attachment platform threads returned to zero. Settled used heap changed from 33,422,856 to 33,444,768 bytes (+21,912); threads stayed 21 and open descriptors 198. Committed heap decreased from 134,217,728 to 119,537,664 bytes. This small sample is retention evidence, not a whole-process memory bound or RSS qualification.

Enabled attachment ownership was two named platform threads, using the existing server/deadline machinery; there is no new always-on navigation worker. The approximately 200 ms enabled idle sample recorded zero CPU on those named platform threads, while whole-JVM CPU was 350 ms and compilation time increased 912 ms. The sample is JIT-contaminated and **does not qualify whole-process idle CPU**. Disabled attachment ownership was zero. Raw heap/thread/descriptor/CPU controls remain available in the evidence.


## Repository compliance and remaining gates

Entry HEAD was `a5c8f71ae9f0b674a2cd1d7629fad8c6b022d298`, with a clean index/worktree. External commits observed during execution are preserved; this agent did not commit, push, reset, clean, revert or discard changes. The evidence records observed final HEAD/status and compares entry input hashes, including previously committed A2.1 work. Source changes span the existing cross-platform Java codebase only; dependencies, themes, fonts, saved preferences, native backends, LSP/Markdown implementations and prior reports/evidence are unchanged.

README SHA-256 before and after is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Whitespace and protected-input verification are recorded in the evidence index. No README change is authorized or made.

Production changes: `AgentWorkspaceController`, new `EditorInteraction` and `EditorNavigation`, `EditorMetadata`, `EditorUiController`, `HelpService`, `InputController`, `Texteditor`, `WorkspaceApiException`, `WorkspaceBridgeClient`, `WorkspaceBridgeServer`, `WorkspaceCli`, `WorkspaceRpc`, `WorkspaceStatusAttachment`, `WorkspaceStatusProtocol`. Test changes: new `EditorNavigationTest`, `WorkspaceNavigationTransportTest`, `WorkspaceNavigationMeasurementTest`; `WorkspaceCliTestProcess` adds only the reviewed `--navigate` flag. Tooling changes: the exact reviewed-class set in `scripts/test_native_fixture.py` and the two source-hash admission manifests. Documentation: this report and `performance/agent-tracka22/`; complete inventories and source versions are indexed there.

Real desktop/window attachment and native focus/paint behavior remain OPEN. Linux headless components and fixed external CLI are the supported measured scope; macOS and Windows runtime qualification have No access. Whole-process/native RSS is unmeasured. Native-shell/PTY qualification remains NO-GO and was not retried or relaxed. Existing complex/wrapped-layout, glyph, HTML/Markdown preview and other outstanding editor defects retain their prior qualifications.

**Next recommendation:** a small A2.2 follow-up should qualify the actual window/controller and Plain/Vim/Emacs Insert behavior in an independently safe desktop fixture, then establish a typed, context-checked existing-controller file-opening completion contract. A3 revision-safe editing is not ready to begin on this evidence alone. No A3, source-write operation or other tranche was implemented.
