# Track A2.1 — Authenticated, bounded editor observation

## Verdict and scope

**CONDITIONAL GO for the qualified headless/component scope. Real desktop attachment remains OPEN.** A2.1 extends A1's production CLI, bridge, private attachment and mutual authentication with an explicitly granted `editor/metadata` operation. No navigation, source reading, edits, rendering, subscriptions, provider integration or additional transport is implemented.

The verification and measurements below apply to the current Linux x64/JDK 21 headless host. They do not qualify a visible `Texteditor` window, native input, Windows/macOS behavior, whole-process RSS or the blocked native-shell fixture. I cannot verify those outcomes from these headless runs. The final evidence inventory is [agent-tracka21/INDEX.md](performance/agent-tracka21/INDEX.md). Existing A0/A1, 8G.1, B1A, gutter and glyph evidence remains historical and preserved.

## Authorization and use

The human explicitly selects a workspace folder and runs:

```text
:agent api start metadata
:agent api status
:agent api stop
```

The existing command feedback supplies an owner-private descriptor path and a **separate** private credential path. An external agent must explicitly target both:

```sh
shed --workspace-api --metadata --connect <descriptor-file> --credential <credential-file>
```

This one-shot command authenticates, obtains one metadata observation and exits. It neither discovers nor launches Shed. It accepts no `--root`, `--request`, `--stdio` or simultaneous `--status`. There is no persistent setting or new approval dialog.

The grant captures the selected workspace root and its configuration generation. The worker validates canonical filesystem identity before creating the endpoint. The opaque project ID is fresh per grant and discloses neither a name nor a path. Adding, removing, replacing or switching configured folders changes the existing workspace model's new small generation counter; a subsequent observation permanently revokes the old metadata grant. Stop and explicitly grant again. A grant cannot follow another project. Nested configured roots do not inherit parent-root authorization.

A1 status attachments remain `scope: status`, use their original transcript/schema and expose no editor metadata. Their clients cannot elevate capabilities through parameters, flags, descriptor changes or method calls. Metadata requires a new explicitly initiated attachment/key; a running attachment must be stopped before changing modes. The existing legacy `:agent api start`/`propose` workspace APIs remain separate, with their previous source/proposal behavior and credentials; they are not the A2.1 observation route.

## Protocol and bounds

The descriptor remains schema **2**, with A1's exact status fields retained. Metadata descriptors have exactly these fields:

```json
{"schemaVersion":2,"instanceId":"<UUID>","windowId":"<UUID>","attachmentId":"<UUID>","port":12345,"expiresAt":1700000000000,"scope":"editor-metadata","projectId":"project-<UUID>"}
```

A1's `connection/hello` → server proof → client proof → finish proof and signed, sequenced data records are reused. For metadata, the existing length-prefixed HMAC transcript has `editor-metadata` in its scope field and appends one length-prefixed `projectId` after the payload field. This binds project, instance, window, attachment, expiry, role, challenge, session, sequence and exact payload. The original status transcript remains byte-for-byte unchanged. The [A1 threat model](AUTHENTICATED_CONTROL_ATTACHMENT.md#c-authentication-and-threat-model) remains in force: authentication proves key possession, signed records provide integrity rather than encryption, and privileged same-user/root access is outside this boundary. A1's independent authentication vector and replay/reflection/response-correlation tests remain enabled. Old A1 clients continue using status descriptors; they fail closed on metadata descriptors. No downgrade to the legacy bearer handshake exists.

The authenticated inner request has an explicit bounded non-null ID and this schema:

```json
{"jsonrpc":"2.0","id":"metadata","method":"editor/metadata","params":{"schemaVersion":1,"projectId":"project-<UUID>"}}
```

Only `expectedPaneId` and `expectedDocumentId` are optional additional parameters. These constrain the observation to the current active context; they do not select or activate a different object. Unknown fields, arrays, notifications, malformed types and unsupported versions fail. Project/pane/document identifiers are bounded to 64 characters and the protocol's opaque identifier form.

Successful result fields are exactly:

```json
{"schemaVersion":1,"protocolVersion":2,"instanceId":"<UUID>","windowId":"<UUID>","capabilities":["status","editor-metadata"],"projectId":"project-<UUID>","paneId":"pane-<UUID>","documentId":"buffer-<UUID>","documentLength":10,"documentRevision":2,"dirty":true,"caretOffset":4,"selectionStart":1,"selectionEnd":4,"coordinateUnit":"utf-16","available":true,"valid":true}
```

Examples use symbolic UUID placeholders. There are no optional result fields or source excerpts. Unavailable/invalid contexts produce errors, not successful placeholder documents. The client checks the complete result field set, target identities, capability set, integer ranges, selection/caret consistency and any expected identities before accepting a successful result.

| Limit | Contract |
|---|---|
| Inner request or response | **2,048 UTF-8 bytes**, inherited from A1; oversized records are rejected, never truncated into a success. A valid request whose escaped ID would make its result too large receives a correlated, authenticated `LIMIT_EXCEEDED` response; its connection remains usable |
| Signed record, authentication, private file read | **4,096 bytes**, newline-framed; truncated EOF rejected |
| Attachment/client lifetime | A1 ten-minute wall/monotonic grant, three-second authentication/client operation, five-second server frame/idle deadlines |
| Requests/connections | A1 128 signed exchanges per connection; eight concurrent connections |
| Metadata work | One admitted observation and at most one queued EDT capture per attachment; concurrent/repeated blocked queries receive `BUSY` |
| EDT acquisition | At most 200 ms waiting on a worker per capture; timed-out tasks are cancelled and retain their queue slot until consumed |
| Context bounds | 32 configured roots, 64 panes and 1,024 open documents; larger production contexts fail with `LIMIT_EXCEEDED` |
| Internal paths | At most 8,192 characters; never serialized into metadata responses or errors |

No open-document list, line/column conversion, viewport mapping, focus state, diagnostics, terminal state or event stream is exposed. No source-dependent cache, polling mechanism or new worker pool is introduced.

## Identity, privacy and UTF-16

Instance/window identities come from A1's owning `ApplicationStatus`. Document identity reuses `EditorWorkspaceDocuments`' weak buffer identity registry through a targeted EDT accessor that neither reconciles nor materializes source. Each pane lazily owns a UUID for its own lifetime; there is no strong pane registry or externally meaningful list index. Shared splits have the same document identity and separate pane identities. Close/reopen produces a new document identity.

The production composition-root host uses the existing active-pane controller and canonical open-buffer membership. It rejects closing windows, missing/disposed panes, retired/unowned buffers, terminal/preview/alternate surfaces, scratch/pathless documents and large-file projections. Only named source buffers bound to existing regular local files under the approved canonical root are supported. The existing `AgentPolicy.checkPath` rejects Git administration and recovery artifacts; environment-file patterns also remain outside this observation scope. This path-only check creates no workspace-query or source-reading route. Modified **metadata** of those documents is permitted; their saved or unsaved text is never requested or copied.

Filesystem validation occurs off the EDT. Root device/inode-equivalent `BasicFileAttributes.fileKey` is captured and revalidated; missing identity support fails closed. The captured spelling must equal its canonical real path. Symlink aliases, escaped paths, unavailable files and unrelated/nested projects fail without disclosing their names. This deliberately conservative contract excludes not-yet-created named files and roots on providers without a stable file key. Windows metadata support is not claimed: A0 documents that the JDK Windows provider may lack this identity primitive. No platform-specific fallback was improvised.

Coordinates are zero-based **UTF-16 code units** in `FileBuffer.getDocument()`, inclusive from zero through its actual `getLength()`. Selection is half-open, `0 <= selectionStart <= selectionEnd <= documentLength`; caret must be one of those endpoints. Empty selections and actual EOF are valid. Negative values, `-1` unavailable mappings, the root-element implied EOF (for example 431 for length 430), reversed selections and all larger offsets are invalid. No clamping, grapheme scans, normalization, code-point/visual-column conversion or model/view geometry is used. Observation reports the existing primary caret/selection, not additional cursors. Revision is the existing raw monotonic `FileBuffer.documentRevision()`, not a saved revision or the workspace adapter's opaque source-version token.

## Consistency, human coordination and lifecycle

A worker queues one bounded immutable metadata capture on the EDT, validates project/path ownership on the worker, then obtains another EDT capture. Both records must match, including pane/document identity, revision, length, selection, dirty state, path and root generation; otherwise the result is `CONTEXT_CHANGED` or a more specific unavailable/revoked error. A change before the first acquisition is represented by the current context. A successful response is a point-in-time observation and can become stale immediately afterward; it is not a lease or permission for later navigation.

`Texteditor.captureEditorMetadata` uses existing controller/model references. `EditorMetadata.capture` is the same production pane adapter exercised by the component fixtures. It does not call full workspace snapshots, document text getters, rendering, layout, LSP or a command dispatcher. The two records contain paths/identifiers/scalars only, never editor objects or source strings.

Explicit stop revokes the host reference immediately before the existing asynchronous server cleanup. The server rechecks expiration and metadata authorization before publishing a result. Application shutdown is checked before every capture and before publication. Root changes revoke metadata permanently; status health queries may continue until that attachment itself is stopped or expires. Timed expiration reuses A1's cleanup executor and closes credentials/connections/host. No capture can reactivate or reattach a disposed pane.

The production host holds the editor weakly; close clears its callable. Cancelled queued captures retain no document/view reference. Pane IDs live on their panes, and document IDs use the existing weak-key registry. The existing 8G.1 retirement path is unchanged.

Tests compare exact source, raw revision, dirty state, Undo availability, selection, viewport position, focus owner, document listeners, snapshot identity and saved fixture bytes across repeated observations. They exercise shared splits, active/document switches, closed active/inactive panes, final document-owner removal, close/reopen, root changes/replacement, expiry, pending human edits and shutdown. A same-length human edit changes the revision and is rejected. These are component/controlled-EDT tests; actual `Texteditor` start/stop/window-controller orchestration is source-inspected and remains a real-window acceptance gate.

## Errors

Errors retain JSON-RPC 2.0 `error.data.code`; messages contain bounded generic descriptions, never source, filenames, paths, environment, keys or proofs. The one-shot CLI normalizes peer errors and emits one bounded response.

| Condition | Domain code / behavior |
|---|---|
| Missing/wrong credential, invalid proof or wrong instance/window | `UNAUTHORIZED`; no metadata before mutual authentication |
| Status attachment requests metadata | `PERMISSION_DENIED`; CLI metadata factory rejects a status descriptor as `UNAUTHORIZED` |
| Client asks to elevate capabilities | Strict parameter/handshake validation rejects it; no grant update |
| Wrong/unapproved project, escaped/symlinked source | `PROJECT_UNAUTHORIZED` |
| Root configuration/identity changed or explicit metadata revocation | `AUTHORIZATION_REVOKED` |
| Expired grant | `SESSION_EXPIRED`; closed/invalid session may produce `SESSION_INVALID` or a closed transport |
| Application not ready | `APP_NOT_READY` |
| No supported source editor | `NO_ACTIVE_EDITOR` |
| Disposed/unavailable or mismatched expected pane | `PANE_UNAVAILABLE` |
| Document identity/revision/caret/context changed | `CONTEXT_CHANGED` |
| Invalid authoritative source coordinates/model state | `INVALID_CONTEXT` |
| Malformed request | `INVALID_REQUEST` |
| Oversized request/response or context | `LIMIT_EXCEEDED`; no partial success |
| Unsupported schema/authentication version | `UNSUPPORTED_PROTOCOL` |
| EDT/client deadline | `TIMEOUT`; no action was requested or applied |
| Concurrent request or occupied cancelled queue slot | `BUSY` |
| Window/application shutting down | `APP_SHUTTING_DOWN`; shutdown can instead close the socket |
| Any excluded operation | `UNSUPPORTED`; no fallback into legacy workspace or ex-command dispatch |

Metadata CLI exits: success 0, arguments/schema request 64, absent instance 69, timeout 70, other transport/limit 74, transient context/unavailable/busy 75, unsupported version 76, authorization 77, revoked/expired/invalid/shutting-down session 78. Existing A1 cases retain their exit mappings.

## Verification and performance

Final commands, exact counts, raw first/warm samples, allocation counters and exclusions are recorded in the evidence index. The safe runner executes only source-reviewed classes, offline from a bounded dependency-cache copy, with isolated home/temp/preferences and in-process Surefire. The new tests and the existing architecture-boundary test were reviewed before admission. The fixed child launcher gains only `--metadata`; all main/classpath/environment/path guards remain intact. Existing A1 assertions and native admission exclusions are unchanged.

Final safe Maven verification: **276 cases, 271 passed, five opt-in skips, zero failures/errors**, across 56 admitted classes. The current status and metadata measurement cases each passed separately with their opt-in enabled. The initial full tooling discovery ran 87 cases: 86 passed and the macOS-only Seatbelt profile-generation case errored on Linux. The subsequent explicitly filtered applicable suite passed **86/86**, with that one platform case excluded and its original error retained. No test or guard was changed to hide it. Offline packaging, packaged image/math/Mermaid smoke, artifact/class identity and `git diff --check` passed.

The five default opt-in skips are `LspDefinitionPerformanceTest`, `GutterPerformanceTest`, `GlyphRenderingMeasurementTest`, `WorkspaceStatusMeasurementTest` and `WorkspaceMetadataMeasurementTest`. Full native/GUI/PTY Maven execution is excluded, not represented as passing. No separate formatter/linter is configured for this change; Java compilation and whitespace checks are reported explicitly.

Final measurements used three sequential fresh test JVMs after other checks completed: the archived entry revision's unchanged A1 probe, that same probe against the final source, then the metadata probe. They use the same JDK, Maven, offline cache, host and fixture isolation. These are single bounded runs, not statistical equivalence or native latency qualifications.

| Metric | Frozen-code observation |
|---|---:|
| Status warm p50 / p95, entry source | 1.919 / 3.131 ms (80 warm requests) |
| Status warm p50 / p95, final source | 2.171 / 3.369 ms (80 warm requests) |
| Metadata warm p50 / p95 | **3.266 / 6.127 ms** (100 warm requests) |
| Metadata first / worst observed round trip | **49.633 ms**, retained separately |
| EDT component capture p95 / maximum, small document | 0.104 / 1.894 ms |
| EDT final capture measured inside the production service, p95 / maximum | 0.110 / 0.207 ms |
| EDT component capture during 1 MiB typing, p95 / maximum | 0.080 / 0.188 ms |
| 1 MiB ordinary typing p95, control / metadata traffic | **3.411 / 2.501 ms**, 50 warm inputs each |
| First control / traffic insertion-to-paint | **93.416 / 2.029 ms** |
| Metadata outcomes overlapping human edits | 51 `CONTEXT_CHANGED`, no mixed successful snapshots |
| Median client-thread allocation per round trip | 50,288 bytes |
| Median JVM-reported total-allocation delta per round trip | 104,840 bytes; includes unrelated runtime work |
| Median allocation per EDT component capture | 232 bytes; first-use maximum 19,928 bytes |
| Retained tracked pane/view/buffer/server/grant objects | **0/120** after 24 disposal cycles and diagnostic GC |
| Settled used heap before / after those cycles | 29,933,832 / 29,958,424 bytes (+24,592) |
| Settled committed heap / threads / open descriptors | 100,663,296 bytes / 21 / 198, unchanged across cycles |
| Remaining named attachment platform threads | **0** |

The status p95 rose 0.238 ms and its median rose 0.252 ms in this pair. This single run does not establish the cause or universal status-latency equivalence; both remain below the proposed 50 ms local round-trip target. Metadata capture meets the adopted 1 ms p95 / 4 ms observed-maximum component gates. Control-first ordering warms font/layout/JIT state for traffic, so lower traffic typing times are **not a speedup claim**. First paints are not hidden, and the 16 ms target applies to the warm p95 workload. All 51 synchronized edit observations correctly refused a context that changed during acquisition; stable-context success is demonstrated by the preceding 101 metadata queries and the external CLI test. This conservative consistency check can refuse observations during continuous human editing.

Server-only allocation and native RSS attribution remain unmeasured. JVM allocation deltas are observations, not an exact server allocation budget. Measurement methods distinguish client round trip, actual EDT capture, queued insertion-to-completed component paint, first use, warm percentiles and post-disposal diagnostics. Allocation observations distinguish client thread, EDT capture and whole-JVM counter deltas; the latter include unrelated compiler/runtime work and are not exact server-only allocation.

The initial probe reused the Unicode contract document for ASCII typing. **[Inference]** The retained Swing complex-text flag explains its slowdown, based on the fixture construction, `FileBuffer` setter and installed JDK source; the initial probe did not directly record that flag. The measured no-traffic control produced a **61,100.43 ms first control paint and 39.26 ms warm control p95**, without metadata traffic. Its traffic p95 was 14.21 ms. This unsupported ordinary-typing fixture is retained in `initial-measurements.json.gz`; it is not a production regression attribution or a successful ordinary-typing baseline. The corrected probe uses a fresh ASCII document, matching A1's workload, and checks both control and traffic p95 against 16 ms. It changes no font, layout or editor behavior. First paint costs remain reported separately. The known complex-text layout defect remains OPEN.

No forced collection occurs in production. Test-only requested GC and 24 create/attach/query/dispose cycles measure tracked Java references, threads and heap. They do not establish native window/resource cleanup, settled RSS or universal memory equality.

## Repository scope and remaining gates

Production changes are limited to the existing attachment/CLI/controller/identity boundaries and the new `EditorMetadata` adapter. Tests/tooling add bounded fixtures, measurement and source-hash admissions. The evidence inventory records every changed/new file. No dependency, source/Undo model, keyboard behavior, appearance, font, theme, saved preference, LSP or Markdown implementation changes.

README SHA-256 before and after is:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

The final integrity evidence compares 2,206 pre-existing repository inputs: 18 authorized files changed, 2,188 remain byte-identical, none are missing, and no unexpected input changed. It records HEAD/status, `git diff --check` and preservation of prior evidence. All 1,441 packaged production classes match current compiled classes; no test fixture is shipped. No reset, clean, revert, discard, commit or push was performed.

Real-window command authorization, external desktop attachment, focus/input/window shutdown and multi-platform acceptance remain OPEN. Native-shell confinement remains **NO-GO**; no blocked native/PTY/full-suite path was run. Existing missing-glyph fallback, Unicode conformance/complex-layout, HTML-preview stalls and native-memory findings are unchanged. An optional thread-dump attempt found that the completed test JVM no longer existed; it produced no qualification result (recorded in the failure notes).

## Smallest subsequent recommendation

Close the independently admitted real-window attachment gate first. A separately authorized A2.2 could then add one explicitly granted, revision-checked caret movement in the already active, named local source pane, initially in a proven Plain-mode/IME state. It needs a view-context generation to reject human away-and-back/caret races, strict UTF-16 boundary validation, existing surface/controller routing and unchanged focus/Undo/source tests. No file opening, pane switching, selections, saves or edits should be bundled into that first navigation slice. No A2.2 work is implemented or started here.
