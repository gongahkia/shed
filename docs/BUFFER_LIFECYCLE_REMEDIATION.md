# Tranche 7B.2 — buffer retention and resource lifecycle

## A. Root-cause findings

The [7A qualification](MARKDOWN_FINAL_QUALIFICATION.md) identified two concrete Java-heap retaining paths, rather than inferring a leak from RSS:

1. `Texteditor → InputController.openBufferCompletionIndex.snapshots → FileBuffer / Snapshot.text` retained closed buffers and their source. `removeOpenBufferWordIndex` existed, but no close path called it. A later completion query pruned the map; closing the buffer did not.
2. `Texteditor → AsyncJobService.jobs → JobRecord.cancelledBeforeStart → completion closure → FileBuffer / captured request` retained completed request payloads until the 200-entry history turned over. This history is useful; its completed cancellation callback is not.

The original eight-file probe was rerun against a frozen pre-change packaged JAR before implementation. All eight closed 1 MiB buffers remained reachable, with one open buffer. A normal completion query reduced index entries from nine to one but left all eight buffers alive through the callback root. After 201 unrelated jobs evicted history, all eight collected. Requested-GC heap was 22.49 MiB initially, 98.51 MiB after eight closes, 97.74 MiB after query, and 57.55 MiB after history turnover. This reproduced 7A with the current baseline artifact.

Deterministic failing-before tests also established:

- Successful jobs retained registered `JobToken.cancelHooks`, including objects whose job lifetime had ended. This is another completed-job payload path.
- Queued jobs discarded by `shutdownNow` could retain RUNNING state and never deliver cancellation completion. Cancellation racing with executor submission needed the same terminal cleanup protocol.

After those corrections, a broader unchanged lifecycle control exposed a **third buffer root**:

```text
editor.fileWatcherService.listeners.value[…][…].arg$2
```

The closed initial file buffer was still retained by recovery's file-change callback; the manually attached second buffer collected. Directly attached copies in the eight-file probe never registered file watches, so their successful collection did not establish ordinary file-opening cleanup. The watcher concern was reproduced before its production correction: registering a buffer three more times plus an independent subscription yielded **five callbacks instead of two**. A stopped watcher also retained its listener/directory metadata maps.

Recovery discarded the `WatchRegistration` handles returned by `FileWatcherService.watch`. It therefore could neither replace its own duplicate registration nor unregister that buffer at close. Existing global `unwatch(file)` was unsuitable: it would remove independent subscribers sharing the path.

Full before/intermediate/final artifact identities, retaining paths, negative tests and raw observations are in [the evidence index](performance/buffer-tranche7b2/INDEX.md). Intermediate results are deliberately distinguished from final results; the watcher finding was not hidden by the earlier successful eight-file test.

## B. Severity and memory classification

**P1:** cumulative closed-document heap retention. Before correction, three bounded batches of eight retired buffers retained **8, 16, then 24** buffers, snapshots and Undo managers. The 1 MiB Markdown case reached approximately **248 MiB used heap**; a 1 MiB Java comment-file control reached **346 MiB**, with only one open buffer. The general IDE ownership path, not Markdown parsing alone, was responsible.

**P2:** completed job hooks/closures retained payloads unnecessarily; history limits bounded some retention by job count, not by payload bytes. Queued shutdown completion was also a bounded lifecycle correctness failure. Recovery watch callbacks accumulated and survived source closure, another confirmed owner-lifetime failure.

These are Java-object/reference and subscription-lifecycle findings. They do not establish a new GraalJS, image, AWT or JVM-native leak. Committed heap and RSS may remain high after the objects are gone. No data loss, forced production GC or general runtime tuning is part of this correction.

## C. Files changed

Production:

- `AsyncJobService.java`: retire completion closures and cancel hooks after their lifetime; preserve pending EDT delivery; complete queued cancellation/shutdown; contain submission races; execute external cancellation hooks outside the token lock.
- `InputController.java`: remove a closed buffer's completion snapshot, stop its pending index timer, and cancel/invalidate only its active index job. One field identifies that existing job's buffer and clears at completion; no extra index/cache is introduced.
- `Texteditor.java`: small `releaseClosedBufferResources` facade coordinating those two proven cleanup responsibilities: word-index removal and recovery watch unregistration.
- `PaneBufferController.java`, `SessionConfigController.java`, `JobQuickfixController.java`, `LspController.java`, `TerminalController.java`, `TreeGitController.java`: invoke the facade at existing buffer removal/replacement boundaries, including session replacement. No changes to discard guards or document content.
- `RecoveryController.java`: own one returned watch registration per open file buffer, replace registrations without duplication, unregister only that owner, and reject late notifications for closed/disposed context. Conflict Save As uses that registration ownership instead of removing all listeners for the old path.
- `FileWatcherService.java`: release callback and directory metadata maps when the service stops; its existing WatchService close still owns native shutdown.

Tests:

- New `AsyncJobPayloadLifecycleTest.java`: eight deterministic job ownership, shutdown, cancellation, submission-race, pending-EDT and noninterruptible-work tests.
- `TexteditorSwingIntegrationTest.java`: four new scenarios for split/buffer ownership, dirty close rejection, final close, session replacement, queued index cancellation/reopen, duplicate watch ownership and independent subscriptions.
- `FileWatcherServiceTest.java`: stopped-service registration/metadata release.

Opt-in tooling and documentation:

- `src/benchmark/java/shed/MarkdownQualificationProbe.java`: additive bounded three-batch retention and watched-retention modes, weak buffer/snapshot/history observations and separate attach/close/allocation measurements. Original eight-file and other existing modes remain independent controls.
- `scripts/qualify-markdown.py`: admit those opt-in modes for small Markdown, 1 MiB Markdown and a 1 MiB source-code control.
- This report and all paths in the [evidence inventory](performance/buffer-tranche7b2/inventory.json).

No dependencies, settings, permanent cleanup worker, source model, renderer, preview layout, media cache or backlink implementation changed.

## D. Ownership and corrected cleanup

The open-buffer list remains an owner even when no visible pane shows a buffer. A split owns its view/caret state, not an independent source copy. Closing one ordinary split must leave a registered shared buffer, its completion data, Undo and watch valid. Buffer deletion is different: after existing discard checks succeed, all views are replaced using the existing paths, and buffer-specific completion/watch resources retire. Session replacement and special-buffer replacement also end those old registrations.

The cleanup facade does **not** clear source, saved baseline, Undo, recovery content or active parser/media data. A canceled dirty close returns before cleanup. Explicit forced discard retains its existing meaning. Tests verify that a test-held closed object still has its exact source and Undo contents; collection follows removal of stale owners, not destruction of the buffer's internals.

Recovery's small identity map owns subscription handles for open buffers only. It is a listener-registration registry, not a document-content cache. Re-registering the same buffer closes its previous handle first; independent listeners remain. Late queued notifications revalidate editor lifetime, buffer membership and file binding on the EDT. File watches are not removed merely because a pane becomes hidden or one split closes.

For jobs, running task state and pending EDT result delivery remain legitimate owners. Completed history keeps status/description/timing/error metadata, but loses payload-capturing cancellation closures and hooks. Finished tokens ignore new cancellation-hook registration. An already canceled, still-running task can still receive immediate cancellation registration. Noninterruptible work retains the execution references it actually needs until it returns; cleanup does not claim to stop it instantly.

The existing atomic delivery guard remains responsible for at-most-once completion. Cancellation before the executor returns a Future is rechecked after assignment. Shutdown cancels queued jobs as well as running jobs and accounts for submissions accepted during shutdown. Brief token-state synchronization protects ownership; external hooks run outside it. No locks are held across rendering, parsing, filesystem hooks or EDT dispatch.

## E. Before/after measurements

The final numerical tables and all raw samples are in [measurements.json](performance/buffer-tranche7b2/measurements.json). Diagnostic phases distinguish used heap, committed heap, sampled peak/phase RSS and allocations. They are not additive memory categories.

The same-host methodology uses the existing JDK 21 resolver, packaged JARs, isolated homes, disposable fixtures and serial children. Controls use `-Xms64m -Xmx512m`; plain source and comment-heavy Java fixtures contain no personal source. Each child has a watchdog. No heap dump or system-wide memory pressure was generated. NMT/JFR/histograms are confined to separately labeled controls. Each GC observation requests three collections in the isolated probe after normal jobs/EDT callbacks settle; it is supplemented by deterministic ownership assertions, not treated as a one-GC leak proof.

**Repeated retention gates:** three batches of eight closes, one original buffer kept open. Weak observations cover one buffer, one captured immutable source snapshot and one Undo manager per retired file; these counts overlap the same graph and are not independent retained-byte totals. The probe invokes no completion query or history turnover between batches. Registered-watch variants exercise the real recovery registration API in addition to attachment; direct-attachment variants intentionally remain separate.

| Fixture, after 8 / 16 / 24 closes | Before used heap MiB | Final used heap MiB |
|---|---:|---:|
| 4 KiB Markdown, direct attachment | 13.77 / 15.19 / 15.76 | 13.28 / 13.31 / 13.33 |
| 1 MiB Markdown, direct attachment | 97.48 / 173.20 / 247.97 | 30.69 / 26.58 / 30.71 |
| 1 MiB Java comment-file control | 132.55 / 239.53 / 345.57 | 43.40 / 31.98 / 43.46 |
| 4 KiB Markdown, registered watch | 13.79 / 15.18 / 15.76 | 13.22 / 13.24 / 13.26 |
| 1 MiB Markdown, registered watch | 97.63 / 173.30 / 247.95 | 30.63 / 26.52 / 31.66 |

In every fixture, baseline tracked buffers/snapshots/Undo managers alive grew **8 → 16 → 24**; final tracked counts were **0 → 0 → 0**. Completion entries grew **9 → 17 → 25** before and remained **1 → 1 → 1** after, for the one legitimate open buffer. Registered-watch file entries likewise remained one in the final watched cycles. Active source/Undo were not cleared to achieve this.

| Workload | Final-phase committed heap before / final MiB | Sampled peak RSS before / final MiB |
|---|---:|---:|
| 1 MiB Markdown, 24 direct closes | 422 / 110 | 1033.41 / 787.91 |
| 1 MiB Java, 24 direct closes | 512 / 154 | 1136.23 / 841.75 |
| 1 MiB Markdown, 24 watched closes | 420 / 107 | 1027.66 / 785.91 |
| 4 KiB Markdown, 24 watched closes | 64 / 64 | 646.47 / 650.44 |

After the harness settling/GC phase following 24 closes, `ps` RSS was **1032.02 / 727.17 MiB** before/final for 1 MiB Markdown and **1136.28 / 779.48 MiB** for the Java control. These are phase observations after a fixed quiescence window and repeated requested GC, not a long-term idle-memory certification.

These are observed improvements in the affected large-buffer fixtures, not a general RSS guarantee. Small-file RSS did not improve consistently. Peak RSS includes startup and active work; exact phase `ps` observations and last-window samples remain in raw reports. The final original eight-file control used 30.70 MiB heap after close versus 98.51 MiB before and collected all eight before any query/history turnover. Residual heap is not attributed entirely to a particular cache.

The independent 1,002-file low-density index diagnostic collected its service before and after; used heap after index disposal was 25.02 / 23.77 MiB. This is not the denser 1,001-file index performance fixture and does not replace its budget. Matched NMT/JFR media-service peaks were 1292.11 / 1311.00 MiB; legacy renderer controls were 1282.59 / 1297.05 MiB. Media assets/sessions/queued/running work reached zero and the service collected with zero media workers in both epochs. The still-high matched renderer RSS is not a newly proven service-specific leak or a solved native-memory concern.

The watcher control is important: final collection must cover the original file that went through normal opening, not only artificially attached copies. Both initial and secondary closed sources collected in the final plain and NMT lifecycle controls. The plain control's post-source-close requested-GC heap was 19.71 MiB versus 26.78 MiB before. All ten picker windows collected before and after, and preview open/close cycles remained functional. The intermediate index/job-only patch still retained the initial source through the watcher; those observations are preserved separately.

Only the fixed owner paths justify the release conclusion. The root walker starts from the live editor, traverses bounded known Java containers/fields and skips weak references; it is not an exhaustive VM GC-root dump. Successful weak collection plus zero stale index/registration ownership and terminal callback-state assertions provides the evidence here.

## F. Correctness and lifecycle evidence

- **Shared owners:** closing one split leaves the shared buffer and completion entry; separate caret/view state survives. Existing open buffers stay registered while hidden.
- **Dirty source:** rejected non-forced close preserves source, index and Undo. The accepted close does not zero the test-held source/Undo. Existing save, BOM/encoding/newline and recovery suites pass.
- **Index cancellation:** closing with a queued word-index request cancels it and invalidates its serial. Reopening the same file creates a different buffer owner; the old result cannot install into it. The new owner's completion snapshot populates normally.
- **Session replacement:** retired buffer entries disappear; a restored file with the same path receives its own valid index. Empty-buffer, tree, special, terminal and LSP-result replacement paths use the same narrow resource release facade.
- **Watcher subscriptions:** repeated registration does not accumulate callbacks; closing one owner preserves an independent subscription. Service stop releases its Java callback/directory maps. No shared source is disposed just because a split closes.
- **Jobs:** success, failure, rejection, queued cancellation, shutdown, cancellation during submission and active noninterruptible work all have deterministic tests. A result posted to a deliberately blocked EDT remains deliverable after history cleanup. Another test allows task completion while a cancellation hook waits on an external resource, checking that cleanup does not wait on a hook-held token lock.
- **Prior features:** the full suite retains Unicode 15.1 GB9c, forward-delete Undo, Markdown edits, highlighting/checkpoints, semantic revision checks, unsaved backlinks, navigation, media leases, preview synchronization, splits, LSP mocks, persistence and recovery coverage.

Native IME, Windows/Linux headful behavior and live external language servers were not newly qualified. Existing synthetic IME and mock/protocol tests are regression evidence only. No claim is made that this patch exhaustively proves every possible application object lifetime.

## G. Responsiveness and trade-offs

The established source-typing gate is **p95 ≤16 ms**. The unchanged UI harness uses 100 samples and ten warmups for 100 KiB and 1 MiB mixed ASCII Markdown, both with Shed Light/JetBrains Mono. Completed Swing paint is distinct from source mutation, forced paint and display scanout.

| Source-only typing, 100 samples per fixture | Before p50 / p95 | Final p50 / p95 |
|---|---:|---:|
| 100 KiB | 0.825 / 1.163 ms | 1.049 / 1.651 ms |
| 1 MiB | 1.337 / 2.405 ms | 0.911 / 1.424 ms |

Observed typing passes the 16 ms gate. EDT allocated bytes per insert p95 were approximately 47.20 / 47.16 KiB at 100 KiB and 48.94 / 48.79 KiB at 1 MiB; these are allocation, not retained-byte measurements. Startup markers in those two fresh-process controls were 1.61–2.36 seconds to visible before and 1.64–1.99 seconds final. The baseline 2.36-second observation exceeded the existing two-second window diagnostic target. No startup threshold was relaxed; this small noisy sample does not establish release startup qualification or a causal startup speedup.

Opening/closing diagnostics separately measure off-EDT `FileBuffer` construction and EDT attachment/close, not full production asynchronous open-to-paint latency. There are 24 operations per fixture. | Explicit operation p95, 24 operations | Before / final |
|---|---:|
| 4 KiB Markdown decode outside EDT | 1.724 / 1.986 ms |
| 1 MiB Markdown decode outside EDT | 36.685 / 22.951 ms |
| 1 MiB Markdown EDT attach | 12.475 / 8.168 ms |
| 1 MiB Markdown EDT close | 6.806 / 8.191 ms |
| 1 MiB Java EDT attach | 8.453 / 9.606 ms |
| 1 MiB Java EDT close | 5.414 / 9.046 ms |
| 1 MiB watched Markdown EDT registration | 0.193 / 0.110 ms |
| 1 MiB watched Markdown EDT close | 6.340 / 8.178 ms |

Close samples are somewhat slower in the final pair, while the confirmed retained graphs disappear and ordinary typing remains within budget. These are bounded explicit operations and no open/close speedup is claimed. EDT close allocation p50 remains approximately 59 KiB in the cycle probes; detailed raw samples include normal view switching and existing listeners.

Allocation samples cover the EDT operation only, not all workers. The closed-buffer registry removal and finished-token cleanup add no periodic work or scan on typing. There is one bounded active word-index buffer pointer and one watch handle per legitimately watched buffer. Cancellation with no hooks avoids a new hook-copy allocation. Useful active Undo, completion entries, index data and media leases are retained.

There is no claim that every explicit open/close got faster. Paired observations can vary with GC, decoding, filesystem/font caches and host load; the first intermediate cycle runs had slower attach/close samples despite successful collection. Raw intermediate measurements remain available. No benchmark thresholds or fixture admission limits were changed to improve results.

The existing Unicode first-edit/layout problem, approximately 20 ms long-prefix boundary traversal from 7B.1 and stock HTML preview stalls are not solved by cleanup. No material ordinary-typing regression is observed in the final pair; this is narrower than overall editing qualification.

## H. Regression verification

Final complete Maven suite: **1,561 run, 1,556 passed, five existing skips, zero failures/errors**. The final focused ownership run passed **30 tests**. Final performance-tooling discovery: **19 passed**. Final packaged-only Mermaid/math/image smoke: **PASS**. Every final opt-in probe case completed successfully.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
"$MVN" -Dtest='AsyncJobPayloadLifecycleTest,AsyncJobServiceTest,OpenBufferCompletionIndexTest,FileWatcherServiceTest,TexteditorSwingIntegrationTest#completionIndexEndsWithBufferOwnershipRatherThanSplitVisibility+closingCancelsPendingWordIndexesAndReopeningCannotReceiveTheOldResult+replacingASessionDropsOnlyTheRetiredBufferCompletionSnapshot+fileWatchRegistrationEndsWithItsBufferAndPreservesIndependentListeners' test
"$MVN" test
"$MVN" -DskipTests package
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --output /tmp/shed-tranche7b2-run/final-packaged-smoke.json
git diff --check
shasum -a 256 README.md
```

Failing-before tests are preserved, including six index/job ownership failures and the two watcher-registration/stop failures. The first passing full run (1,554 passed) preceded the watcher correction and is explicitly intermediate; it is not substituted for the final run. Exact commands, exits, suite/skip details and raw diagnostic logs are in the evidence directory. Heavy probes remain opt-in.

## I. UI/UX and appearance compliance

No visual or interaction redesign. Layout, component system, editor and preview surfaces, typography/themes, syntax colors, menus, navigation, Plain/Vim/Emacs routes, splits and command conventions are unchanged. Shed Light, bundled JetBrains Mono and saved-preference precedence remain byte-for-byte unchanged. This patch adds neither appearance features nor production diagnostic instrumentation.

## J. README and repository safety

README SHA-256 before and after:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

Initial HEAD: `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`; the initial checkout was clean. Exact final HEAD, artifact/file identities, preserved prior evidence and final `git diff --check` status are in `verification.json`. Existing/external work is preserved. This agent performed no reset, clean, revert, commit or push. There are no dependency or JVM-option changes, source-ownership changes, cleanup polling workers or startup cleanup scans.

## K. Remaining limitations

- Whole-process native memory remains only partly attributed. JVM/font/graphics/GraalJS/Batik caches, committed heap and RSS are not interchangeable; close-time collection does not prove every native resource was released.
- FileWatcherService's directory registrations retain their existing service lifetime, until its WatchService stops. This tranche retires source-specific listeners and clears stopped-service maps; it does not redesign directory watch admission/eviction or claim a per-directory native-memory bound.
- Finished job metadata remains in bounded history. Active or pending work and queued EDT completions legitimately retain their inputs/results until their actual lifetime ends. Global executor scheduling policies are unchanged.
- Recovery continues to own serialized unsaved content according to existing policy; release of stale FileBuffer graphs is not permission to discard recovery state.
- Unicode still has the 51 pre-existing conformance-corpus deviations documented in 7B.1, unqualified native paths and the separate complex-text layout/first-edit latency blocker.
- Preview rendering/reflow remains synchronous and can block input. Inline rich media/live presentation and very large editable documents remain deferred.
- These bounded fixtures do not prove constant memory for arbitrary projects, file counts, media sizes or prolonged real-world sessions. Cross-platform/native input qualification remains outstanding.

## L. Final verdict

**Resolved for the confirmed buffer-retention blocker and associated terminal-job/subscription defects.** Final tracked closed graphs collect without a completion query, forced history eviction, lost Undo, discarded dirty state, or premature shared-buffer disposal. The final normally opened-file lifecycle control also collects the watcher-held source, closing the gap exposed by the intermediate test. The deterministic ownership/race tests and repeated GC-controlled observations support this bounded verdict; they do not assert the absence of all possible future leaks.

The result concerns confirmed Java ownership and watcher registration lifetimes. It is not a declaration that Shed now meets all low-memory, native-resource or preview responsiveness objectives.

## M. Next recommendation

Do not start broad memory optimization. The next independently scoped remediation should investigate the **Unicode source-view first-edit/layout stall** from 7A, with a deterministic representative reproducer, stack/allocation evidence and a strict stop if correct shaping/caret/IME support would require an editor replacement. Preview latency and any supported operating limit need a separate explicit product decision.

The known P1 performance blockers prevent treating 7C as a final release sign-off today. Additional bounded diagnostic qualification can inform those decisions, but this tranche does not begin it. No other 7B, 7C, live-presentation or appearance work has started.
