# Track A2.2.1 — project-scoped file opening: prerequisite report

> Historical result under the original stronger acquisition contract. The later [portable integration](PORTABLE_PROJECT_FILE_OPEN_INTEGRATION.md) explicitly revised that threat model, and [reviewed editor control](AGENT_EDITOR_CONTROL.md) implements the subsequently authorized external workflow. This does not invalidate the race reproduction or establish the original confinement guarantee.

## A–B. Verdict and delivered scope

**NO-GO for authenticated agent file opening. Production file opening remains unavailable through the A1/A2.1/A2.2 control API.** This is a prerequisite review and defensive reproduction, not a completed file-opening implementation or conditional feature acceptance. No production Java, authorization behavior, listener, credentials, editor model, preferences or UI changed. A3 is not started.

The stopping condition is established by a disposable, deterministic reproduction: an approved canonical path is resolved, its ancestor is temporarily replaced by a symlink to an unapproved **synthetic sibling**, the normal loader reads that sibling's bytes, then the original directory is restored. Root and target filesystem identities match again afterward, and the loaded buffer still names the approved path. **The failed invariant is that the bytes admitted to a project-scoped editor open must come from the authorized project resource throughout acquisition.** Pre/post pathname containment alone does not establish that invariant.

The four new characterization cases pass because they assert the reproduced boundary conditions and continued protocol refusal. Their passing JUnit status is **not** a passing containment gate. No outside fixture document was installed into an editor or authoritative store; no personal file, credential or developer project was used. The original reproduction output and the later extended reproduction are retained separately.

This follows the user's fail-closed instructions in sections 6, 20 and 21, and the subsequent instruction to keep production opening disabled, execute no further filesystem security probes, and finish documentation/existing safe verification. After that instruction, no further new security probe was executed and no source/admission code was changed. Already-running existing verification finished; its results are below.

Evidence: [A2.2.1 index](performance/agent-tracka221/INDEX.md). Prior A2.2's conditional navigation verdict is unchanged; it does not extend to file opening.

## C. Authorization and compatibility

Existing explicit commands remain `:agent api start status`, `:agent api start metadata`, `:agent api start navigation`, and `:agent api stop`. **There is no user command that authorizes this proposed file-opening capability.** No new scope, key type, descriptor field, CLI option or remote grant mutation was introduced. `editor-file-open` occurs only as a rejected capability string in the defensive test.

Real authenticated loopback tests verify that status, metadata and existing navigation credentials all reject `editor/file/open`, including attempts to supply a file-open capability. A separate `authorization/grant` request is also rejected. Dirty source, saved baseline, Undo manager/availability, file bytes, current pane and open-document count stay unchanged across those refusals. Authentication, instance targeting, project authorization, expiry and revocation retain the existing A1/A2.1/A2.2 contracts and regressions.

A future implementation must require a fresh, explicit grant through that existing human-owned infrastructure; an upgrade cannot broaden old navigation keys. The exact new grant name is deliberately **not specified as an implemented command**. Adding another listener, authentication protocol, persistent credential store or approval panel is unnecessary and outside this report.

## D. Containment finding and established root cause

| Existing boundary | Source evidence / observed limitation |
|---|---|
| `WorkspacePaths.resolve(Path, String)` | Resolves existing ancestors with `toRealPath()` and uses component-aware `Path.startsWith(root)`. Correctly rejects the tested static outside-root links/traversal. Returns a **Path**, not a held authorization to later reads. Also intentionally accepts in-root absolute paths, `file:` URIs, directories and missing targets: it is broader than this tranche's contract. |
| `LegacyDocumentStore.load(Path)` | Tests `file.exists()` and chooses `new FileBuffer(file, config)` or a named unsaved-buffer constructor. No strict-existing-only load or project-bound open handle is accepted. |
| `FileBuffer.load(ConfigManager)` | Reopens the pathname through size inspection, line-limit scanning, `Files.readAllBytes`, file-type/format initialization and external-file stamp observation. It receives no approved-root/read capability. |
| `LargeFileStore.exceedsLineLimit(Path, int)` | Performs another pathname-based `FileChannel.open`. Declaring a small intended file does not bind later reads to the previously checked object. |
| `WorkspaceTextFile.capture(...)` | Has bounded channel reads, cancellation and final-component `NOFOLLOW_LINKS`, but also follows a replaced **ancestor** in the collected reproduction. It produces a disk snapshot, not a canonical editor installation contract. |
| `WorkspaceRootResolver.normalizedIdentity(Path)` | Contains a best-effort lexical fallback after resolution failure; it is not an authorization fallback for this feature. |

The reproduction records unchanged root/target identities after restoration, an apparently approved loaded path, bytes matching the synthetic unapproved sibling, and zero installed documents. The extended reproduction also exercises the bounded snapshot helper during that substitution. Logs contain outcome booleans, not source text or private paths.

This establishes a time-of-check/time-of-use gap **between path authorization and the existing loader's actual acquisition**. It does not prove an exploit of a shipped agent endpoint: those grants cannot invoke file opening. It also does not mean every possible post-read identity check is useless. A comparison with a captured loaded-file stamp could detect some substitutions **after** acquisition; it cannot undo reading the unapproved resource through an unchecked ancestor. This report makes no speculative claim of a portable containment guarantee.

A second characterization confirms the existing store's normal missing-file behavior: a missing target, or a validated target removed before load, produces an empty named in-memory buffer. **It does not create a physical file.** That human-oriented behavior is unsuitable for an operation promising to open an existing file. It must not become a successful response for a vanished target.

Static path cases also cover nested directories, spaces, a Unicode filename, inside-root absolute/URI/parent-normalized forms, a missing name, a directory, outside traversal, direct/nested escaping links and a cycle. These characterize existing APIs; they are not an accepted A2.2.1 path grammar or a complete cross-platform/path-race qualification. Case-insensitive providers, Windows drive/UNC/reparse forms and new adversarial cases were not exercised after the stopping instruction.

## E–F. Context, ownership and ordinary opening lifecycle

The repository already has the correct owners: `FileBuffer`/`PlainDocument` for source/history, `LegacyDocumentStore` for canonical registrations, `EditorPane` for views, `PaneBufferController` for opening/reuse, `DocumentRequests` for per-pane load tickets, and `AsyncJobService` for background work/EDT completions. No replacement or agent-owned source representation is justified.

Actual reviewed signatures and anchors are in [source-map.json](performance/agent-tracka221/source-map.json), including:

```text
PaneBufferController.openFileCheckedAsync(File, BooleanSupplier,
    Predicate<FileBuffer>, Predicate<FileBuffer>, Runnable, Consumer<IOException>)
PaneBufferController.loadBufferIntoPane(EditorPane, FileBuffer, int)
PaneBufferController.findBufferByPath(File)
LegacyDocumentStore.load(Path)
FileBuffer.load(ConfigManager)
DocumentRequests.issue(T) / current(T, long) / invalidate(T)
AsyncJobService.submit(String, JobTask<T>, JobCompletion<T>) / cancel(int)
EditorNavigation.verify(Command, State)
Texteditor.captureEditorNavigation()
```

`findBufferByPath` compares absolute path spellings and the opening controller reuses an existing buffer before loading. A correct new operation must preserve that authoritative dirty buffer, with alias/identity handling prepared off EDT where necessary. It must not reload disk over unsaved content or manually populate a new scratch buffer to bypass the store. New loads ordinarily reuse the existing codec, encoding/BOM/newline metadata, source revision, saved baseline and subsequent recovery/watch/LSP lifecycle.

The existing checked controller captures an active target and uses tickets/predicates. However, its checked branches call `applyProjectConfigurationForFile` **before the final current-context check**. That enters `ensureProjectTrustForFile`, which can display the existing trust dialog and persist a trusted root, then can apply runtime configuration. A later context refusal does not undo those earlier effects. This ordering is verified in source; no trust dialog/configuration change was executed by these tests.

Installation also includes remembered selection/viewport restoration, jump history, recent files, file watches, syntax/gutter work, LSP open synchronization and alternate-surface selection. Some callbacks are queued after binding. These are real lifecycle behavior, not a side-effect-free `setDocument`. They require a typed acceptance boundary before exposing file opening. Existing raw completion messages/`IOException` callbacks do not provide the requested structured applied/rejected/unknown contract.

For a future request, reuse A2.2's instance/window/project/pane/document identities, raw document revision, interaction revision, expected caret and selection; retain the captured target and recheck it immediately before **any** permitted installation effects. `DocumentRequests` alone does not detect human caret/selection changes. Reject disposed/changed targets rather than falling back to whichever pane is active. An empty pane needs a genuine defined context contract; no placeholder document should be invented. Until that exists, an initial operation can retain A2.2's supported existing named source-context restriction.

Plain/Vim/Emacs opening, focus mode, another visible shared pane, saved view restoration, and complete native controller behavior are **not newly qualified**. No mode switch, focus request, buffer activation or file-open UI operation was performed by the blocked remote command.

## G–H. Current protocol and asynchronous boundaries

No new request/success schema exists. The tested method is deliberately unsupported, for example:

```json
{"jsonrpc":"2.0","id":"open-check","method":"editor/file/open","params":{"schemaVersion":1,"path":"src/Example.java"}}
```

Authenticated status/metadata/navigation dispatch returns the existing refusal:

```json
{"jsonrpc":"2.0","id":"open-check","error":{"code":-32601,"message":"Status operation is not supported","data":{"code":"UNSUPPORTED"}}}
```

No resulting document/pane or fabricated empty success is returned. There are no distinct new file-missing/type/load errors, pending-operation IDs, cancellation commands or file-open acknowledgments. Those remain unimplemented, rather than claimed through illustrative commands. Existing protocol 2/schema 1 clients and their 2,048-byte payload/4,096-byte frame, eight-client, 128-exchange and lifetime bounds remain unchanged.

The normal controller already loads on `AsyncJobService` and completes on EDT. But its checked opening method returns `void` and does not expose its job handle to a new protocol owner. Reusing it needs request-lifetime cancellation/cleanup and a typed completion, not a success reply on queue submission. No wait, filesystem operation, new worker or polling loop was added to production EDT behavior.

The existing A2.2 distinction remains relevant: a lost transport reply is not proof that an admitted UI action did not occur. A future open must define cancellation before acquisition, before installation and after its commit point; known cancellation must not install late, and an already-applied action must not be reported as rejected. This report does not claim those new operation semantics are implemented.

## I–K. Integrity, performance and retention evidence

For the refused remote requests, disposable dirty source, raw revisions, saved baseline, the same Undo manager with Undo/Redo available, file bytes and current view are preserved. The unsafe-load reproductions create detached objects through existing loaders; their store lists remain empty. The existing split lifecycle corrections and production source/Undo/recovery implementations are byte-identical to entry. This is not proof of safe new-file installation, LSP startup or recovery integration on a real window.

**New file-open validation/reuse/first/warm latency, EDT installation, completed-editor-state latency, typing/status/metadata under file-open traffic, allocation and retained heap: BLOCKED / NOT MEASURED.** Small/100 KiB/1 MiB/mixed-script authorized opens and 10 MiB qualification were not run as feature acceptance. No unsafe endpoint was enabled for benchmarking.

Existing safe controls completed with unchanged production code:

| Existing control | Current observation |
|---|---:|
| Status warm p50 / p95 | 2.486 / 3.670 ms; first/max 15.108 ms |
| A2.2 typing queued at actual navigation application, 1 MiB ASCII | p50 2.013 / p95 **2.531 ms**; first 2.420 / max 3.537 ms |
| A2.2 tracked retention after 24 disposal cycles | **0/168**, zero remaining named attachment platform threads |
| A2.1 retention per metadata repeat | **0/120** in each run; zero named attachment platform threads |

These are regression/component controls, **not file-opening performance** or an improvement over A2.2's historical 4.11 ms typing figure. First and complete sample arrays remain in raw reports; RSS is unmeasured.

The existing metadata anomaly received exactly three fresh-JVM sequential repeats without changing its source, limits, timeout, assertions or workload definition:

| Repeat | Metadata warm p50 / p95 | First/max round trip | EDT capture p95 / max | Typing p95 with metadata |
|---|---:|---:|---:|---:|
| 1 | 4.562 / 7.899 ms | 58.194 ms | 0.138 / 2.140 ms | 4.119 ms |
| 2 | 5.028 / 11.017 ms | 74.365 ms | 0.208 / 3.844 ms | 5.471 ms |
| 3 | 3.544 / 6.590 ms | 48.450 ms | 0.111 / 2.700 ms | 2.480 ms |

Observed new frequency: **zero failures in three repeats**. The preserved A2.2 record remains **one maximum-duration failure followed by a passing unchanged repeat**; these groups are not pooled or relabeled as resolution. All three current capture maxima occur on their first capture, with 19,784–19,928 bytes first-capture allocation versus 232 bytes warm median. **[Inference]** first-use initialization and scheduling/JIT variation are plausible contributors. The exact cause of the historical greater-than-4-ms sample is **unverified**: that failed probe did not print full metrics before its assertion. Outer host-load/CPU observations cannot attribute an individual EDT sample. No reproducible severe warm regression was established by these three controls, and consistent cold-tail qualification remains OPEN.

## L. Verification and preserved failures

Exact expanded commands, selected classes, logs and fixture cleanup are in the [evidence index](performance/agent-tracka221/INDEX.md).

| Execution | Result |
|---|---|
| Initial four prerequisite characterizations | Four passed; the required containment invariant failed as reproduced; zero documents installed |
| Initial safe Maven run | 309 cases: **302 passed, one failed, six opt-in skips** |
| Failure | Unchanged `LspDefinitionResponsivenessTest` line 41: sampled EDT stack did not satisfy its `LspClient.waitForResponse` assertion; no production/test assertion changed |
| Focused repeat after extending the already-collected loader characterization | **Five passed**: four characterizations plus unchanged LSP diagnostic |
| Final safe Maven allowlist, 60 classes | **309 cases: 303 passed, six opt-in skips, zero failures/errors** |
| Existing status / metadata / navigation opt-in controls | Five one-case probes passed separately; not added to the default suite count |
| Applicable tooling | **86 passed** |
| Unfiltered tooling | 87 attempted: **86 passed, one platform error**; macOS-only Seatbelt profile generation rejects Linux |
| Production package and packaged image/math/Mermaid smoke | Passed; no new test fixture shipped |

The original LSP failure and its focused and full-suite passing repeats are all retained. Its failed sampled stack was not emitted, so its precise cause cannot be verified. **[Inference]** Sampling before the client enters `waitForResponse` is one possible explanation; it is not an established root cause or proof that the failure is harmless. No LSP remediation was attempted.

The six default skips remain glyph, gutter, LSP, status, metadata and navigation measurement opt-ins. The final tooling selector excludes exactly `test_native_fixture.NativeFixtureTest.test_profile_no_fork_network_or_user_home_access`, with the Linux/single-match checks preserved. The unfiltered error is not counted as a pass. No guard, assertion or timeout was weakened.

## M–N. Platform and repository compliance

Protocol refusal and loader characterizations ran in disposable Linux x64/JDK 21 headless fixtures. Existing guarded external Java CLI status/metadata/navigation regressions ran as part of the safe suite. **No successful external file-open acceptance exists.** Real desktop integration, native focus/completed paint, complete editing-mode behavior, Windows/macOS filesystems and whole-process RSS remain OPEN / unverified. Native-shell benchmark isolation remains NO-GO; no bypass, interactive shell, real language server, model provider or native window was launched.

Entry HEAD: `fe5f960c7439f5708d919d945367d7ccfb266404`; clean worktree/index. External capture commits observed during execution are preserved. This agent did not commit, push, reset, clean, revert or discard changes. All production sources, build/dependency inputs, fonts, themes, UI/keymaps, prior reports and evidence are preserved byte-for-byte; final hashes/inventory are indexed.

Changed inputs are only new `src/test/java/shed/ProjectFileOpenPrerequisiteTest.java`, its source-hash entry in `src/test/resources/shed/portable-fixture-tests.properties`, and the exact reviewed-class set in `scripts/test_native_fixture.py`. Documentation is this report and `docs/performance/agent-tracka221/`. No new helper permissions or authorization behavior changed.

The current packaged JAR has SHA-256 `92d68ac6b8a921f7dcc907706421cff22e33678aee91e8bf93d2b349acdc80d6`; all 1,455 compiled production classes match it and the new test fixture is absent. Its whole-JAR hash differs from A2.2's recorded artifact despite unchanged production inputs. The reason is unverified; historical whole-JAR byte identity is not claimed or repaired in this tranche.

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Final whitespace, source preservation and compiled-artifact checks are recorded in the evidence.

## O. Smallest prerequisite and reconsideration gate

**One internal prepared-open contract must span checked acquisition and checked installation before any public file-open grant is added.** Keep the existing editor, buffer, codec, store, job service and opening controller. The smallest required correction has two connected parts:

1. **Checked existing-file acquisition in `LegacyDocumentStore`/`FileBuffer`.** Admit an existing regular file through an authorized project-bound read with bounded bytes/cancellation and a stable binding to the read object/ancestor traversal. Decode through the existing `TextFileCodec` and initialize the normal `FileBuffer` source/format/saved-disk state from that admitted acquisition. No path-based fallback or reread may silently substitute another object or turn disappearance into an empty named buffer. Fail closed on unsupported filesystem guarantees. Do not add a second agent file loader, source cache or scratch-buffer population route. The exact provider mechanism still needs independent review/qualification; this report does not assert that a proposed handle technique is universally portable.
2. **A typed, cancellable checked-install seam in `PaneBufferController`.** Use an explicit live target and the complete A2.2 context, reuse authoritative dirty buffers, and check freshness/permission before configuration/trust work and all permitted visible effects. Define behavior for configuration/modal/alternate-surface/service side effects; decline configurations that cannot meet the narrow contract. Preserve normal approved history/recent-file/recovery/watch/LSP ownership. Return definitive rejection/applied state, or an accurately classified unknown result after the commit point. Reuse `AsyncJobService` cancellation and `DocumentRequests`, with bounded admission and no fallback to a newer active pane.

Verification required **before reconsidering** public file opening:

| Gate | Required evidence, not executed after the stopping instruction |
|---|---|
| Acquisition containment | Direct/nested/changed/cyclic links; ancestor and final-entry substitution including restore-to-original cases; root replacement/unavailability; no unapproved bytes acquired, no missing-file fallback; bounded growth/cancellation |
| Portable input policy | Strict project-relative grammar; absolute/URI/traversal/drive/UNC rejection; spaces/Unicode/normalization; real case-sensitive and case-insensitive provider behavior; regular-file/type/size limits; no authorization writes |
| Grant separation | Fresh explicit file-open grant; status/metadata/old navigation keys remain restricted; no self-elevation; wrong instance/project, expiry and revocation before read and commit |
| Human context | Pending caret/selection/document/pane/project changes, away-and-back transitions, disposed targets, shared documents/splits and focus mode; no implicit retarget or mode switch |
| Canonical ownership/integrity | Existing dirty/open-elsewhere reuse; concurrent/duplicate requests without duplicate authority; unchanged unrelated text/revision/dirty/Undo/Redo/saved/recovery/file bytes; correct LSP binding |
| Completion/cleanup | Deterministic delayed I/O and install races; cancellation/timeout/disconnect/shutdown; no late cancelled installation or false queued success; zero closed-view retention and bounded jobs/handles/listeners |
| Performance | Separate first/warm small, 100 KiB and 1 MiB opens, Unicode names/content; EDT occupancy, completed state/paint distinction, status/metadata interference, typing p95 ≤16 ms, allocation/heap/threads; 10 MiB only when independently safe |
| Acceptance | Component and guarded external file-open acceptance first; independently safe real-window and provider/platform acceptance later; native-shell guards remain intact |

The next recommendation is this bounded loader/controller prerequisite, followed by reassessment of A2.2.1. Do not proceed to broad mode qualification or A3 on this evidence. A read-only A3 architecture review, native desktop acceptance and further implementation were not begun.
