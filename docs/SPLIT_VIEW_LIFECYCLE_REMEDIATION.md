# Split-view safety and lifecycle remediation — Tranche 8G.1

**GO for the identified split-view defects within the tested scope.** Retired source views detach from their documents; the original Undo exception no longer reproduces; and an independently discovered wrong-buffer Vim Undo route now binds the correct document history. No history is cleared or replaced. This does not establish general release readiness or complete native/window-memory qualification.

## A. Root cause of the Undo exception

The original sequence was reproduced before production changes: open `body\n`, insert `draft `, Undo, select UTF-16 offsets 1–3, repeatedly create/switch/close shared splits, Redo, then Undo.

The exception was a stale **view notification**, not a duplicate Undo manager:

```text
PlainDocument document-event Undo
  → DefaultCaret$Handler.removeUpdate on a closed view
  → JTextComponent.fireCaretUpdate
  → EditorUiController's source caret listener
  → activateEditorPane(closedPane): rejected because the pane is no longer listed
  → updateMatchingBracketHighlight(): continues against the surviving active view
  → VersionedTextSnapshot.charAt(5), while the shortened snapshot length is 5
```

The surviving caret was transiently at offset 6 before its own remove-update notification. The bracket guard checked document/snapshot length consistency, but the obsolete callback was looking at another view's not-yet-updated caret. A two-live-split control passed; the original closed-view sequence failed.

The diagnostic records document, buffer, pane and history identities, source revision, caret, listener counts and exact document/snapshot text. Before Redo the revision was 3. Redo completed at revision 4. The failed Undo changed text back to `body\n` but left revision 4 and caret 6. Notification delivery had been interrupted. This is a release-blocking editing-state/revision correctness problem; no disk data loss was established.

Retiring the view removes its obsolete caret callbacks and document attachment. The original reproduction then passes **without changing** `SyntaxUiController`, `FileBuffer`, `BoundedUndoManager` or Unicode boundary handling. No exception is swallowed in production and no Undo history is disabled.

## B. Root cause of closed-view retention

Closing a split removed its pane/layout entry and inline-completion session, but left the old `JTextArea`, custom caret and Swing UI listeners attached to the still-open canonical document.

The bounded reference diagnostic verifies a rooted path through existing production objects:

```text
Toolkit AWT listener registry
  → listener capturing EditorUiController
  → Texteditor / canonical open-buffer list
  → FileBuffer → PlainDocument
  → BlockCaret$1 document listener → installed BlockCaret
  → closed JTextArea / its view callbacks
```

It observes one Toolkit-to-controller edge while the window is legitimately open and checks document-listener/installed-caret identities against weak references to retired text areas. Reflection reads Shed's own captured fields; it does not patch OpenJDK or require module exports. This is a verified bounded reference path, not an exhaustive heap/native-root census.

In both ASCII and Unicode controls the starting JAR retained 8, 16 and 24 closed text areas across three batches. Each retained view had a corresponding document-to-retired-caret edge. The corrected JAR retained **zero** at every batch, while retaining the active document and its history.

This differs from 7B.2: those fixes retire **closed buffer** registrations. Here the buffer is legitimately still open/shared; its retired **views** were unnecessary observers. Recovery ownership of the buffer does not justify retaining those views.

Two additional presentation references were reproduced: a minimap still pointing at its closed source view, and a pending-paint measurement still pointing at that text area. Both now clear only when their owner is the retiring pane. A surviving pane's minimap is retained.

## C. Additional correctness finding and severity

The required multiple-document investigation reproduced a separate **P1 wrong-buffer Undo** defect. Vim normal-mode Undo uses the legacy `editor.undoManager` alias; pane activation rebound text/search widgets but did not rebind this alias. With edited documents A and B, switching to A and pressing `u` left `A first\n` unchanged and rolled B back to `second\n`.

`bindActivePane` now aliases the active pane's existing buffer-owned history. The regression presses the actual Vim Undo key route, verifies both documents, redoes through the existing surface, switches panes, closes B and repeats Undo from the survivor. This is independent of the stale-view exception. Plain/Emacs surface routing and history ownership remain unchanged.

Classification: the exception/revision interruption and wrong-buffer mutation are **P1 release blockers before correction**; cumulative closed-view retention is a **P2 lifecycle defect**. Covered reproductions pass after correction. The separate 7C product release blockers remain open.

## D. Files changed

| File | Rationale |
|---|---|
| `src/main/java/shed/EditorPane.java` | Idempotent source-view retirement; detach document/caret observers, clear captured views and obsolete buffer/projection references |
| `src/main/java/shed/PaneBufferController.java` | Reuse one narrow retirement helper for pane closure/failed splits; invalidate pending requests; reject attachment to a retired pane |
| `src/main/java/shed/EditorUiController.java` | Bind legacy Undo alias correctly; retire views on window closure; release owner-specific minimap/paint references |
| `src/main/java/shed/SessionConfigController.java` | Retire replaced session views before clearing the pane list |
| `src/main/java/shed/MarkdownController.java` | Retire the unused source widget if preview split creation fails; rendering/media behavior unchanged |
| `src/main/java/shed/MinimapPanel.java` | Identity-only query for its existing source owner |
| `src/test/java/shed/TexteditorSwingIntegrationTest.java` | Nine new deterministic lifecycle, source, history, routing and composition regressions |
| `src/benchmark/java/shed/SplitLifecycleDiagnostic.java` | New opt-in rooted-reference, source/revision and repeated Undo/Redo diagnostic |
| `src/benchmark/java/shed/ActivePanePerformanceProbe.java` | Explicit lifecycle-only mode while retaining original native-focus default |
| `scripts/benchmark-active-pane.py` | Expose/report the opt-in lifecycle-only measurement mode |
| `scripts/test_active_pane_tooling.py` | Verify that explicit mode can generate fixtures without native desktop work |
| `docs/SPLIT_VIEW_LIFECYCLE_REMEDIATION.md` | This report |
| `docs/performance/appearance-tranche8g1/` | Raw successful/unsuccessful evidence, artifact identities, reference counts, measurements and preservation checks; inventory in `INDEX.md` |

No existing test assertions were weakened. No dependencies, source-model, renderer, font, theme, settings or keybinding changes were made.

## E. Lifecycle and ownership contract

A live pane borrows the canonical `FileBuffer` document and Undo manager. Closing a pane retires **that view**, not the buffer. The existing buffer list, saves, recovery, file watches and shared history remain authoritative.

Retirement invalidates pending pane requests, removes that view's inline-completion session and owned presentation references, and deinstalls its custom caret. It removes retired caret/key/mouse callbacks, replaces the text component's document with an empty private Swing document, makes the retired component noneditable, and clears its captured per-buffer view state. The `FileBuffer`'s `PlainDocument` is never replaced or cleared. Surviving panes remain attached to it.

The empty component is an inert disposal placeholder, not another editable document representation. Its ordinary caret/UI remain valid for already-queued Swing callbacks. An intermediate null-caret/UI cleanup emitted asynchronous `NullPointerException`s despite passing assertions and was rejected; its log is preserved. Focus-lost delivery is deliberately kept to finish the existing IME composition protocol. A targeted synthetic composition test reproduced and corrected an intermediate cleanup regression that removed this callback.

Retirement is idempotent. Late `loadBufferIntoPane` calls reject disposed panes; existing request invalidation, buffer identity guards and split-membership checks continue to reject stale completions. Session replacement and actual window closure use the same helper. Window disposal detaches the active document callback and marks the workbench closing; normal quit/save/recovery flush behavior remains in its existing path.

No new processing is added to ordinary keystrokes. New checks occur on pane binding, loading and disposal. No polling, cleanup worker, production GC, permanent cache or layout framework is introduced.

## F. Exact editing and persistence evidence

The nine new tests cover:

- Exact original failed sequence, shared document/history identity, repeated closure and valid Redo/Undo afterward.
- A live-split control and explicit removal of the retired custom-caret listener from the shared document.
- Real asynchronous split-open completion after target closure, queued viewport/load callbacks, idempotent retirement and no resurrection.
- Session pane replacement, refusal to close the only live window, final window disposal, retained dirty source and model Undo/Redo afterward.
- Plain/Vim/Emacs configurations, Unicode/emoji/Indic labels, atomic Markdown link edits, multiple buffers, bounded history (three entries / 65,536 estimated bytes), dirty shared source, exact LF/CRLF and UTF-8 BOM save bytes, reopen and recovery-journal capture.
- Retiring only the matching minimap/pending-paint references while retaining a survivor's minimap.
- Actual Vim Undo dispatch with different document histories, correct target after activation/closure and unchanged other-buffer content.
- Pending composition focus-lost reset without source mutation. This tests the Java event protocol, not a complete native IME session.

The final isolated ASCII diagnostic completes 51 Redo/Undo pairs after 24 closures, with exact text and the same history object. Revision advances from 3 to 105, with a valid final caret. The starting JAR fails its first Undo after successful Redo and completes zero pairs. The Unicode diagnostic completes 51 exact pairs in **both** JARs; its baseline still retains all 24 views. The exception is workload-dependent, not a claim that every Unicode edit failed.

Existing source-view, bounded Undo, buffer disposal, atomic Markdown edit, persistence/recovery, navigation, appearance and keymap suites also pass. Source/model/history implementations are unchanged; cleanup does not create source revisions or history entries.

## G. View retention and memory

Host: Mac15,12 / Apple M3 / eight CPUs / 16 GiB, macOS 26.5.2, Temurin Java 21.0.13+9, `-Xms64m -Xmx512m`. Initial load was 3.90 / 4.38 / 4.65, reported free-memory percentage 49%, existing swap use 1007.5 MiB. No system memory-pressure experiment or setting change was performed.

The rooted ASCII and Unicode controls each observe:

| Closed-view cycles | Starting retained views / document-to-retired-caret edges | Corrected retained views / edges |
|---|---:|---:|
| 8 | 8 / 8 | 0 / 0 |
| 16 | 16 / 16 | 0 / 0 |
| 24 | 24 / 24 | 0 / 0 |

Document listener counts grow 7 → 40 → 72 → 104 in the starting JAR. The corrected JAR goes 7 → 8 and stays at 8 through the remaining batches; the active view's one-time listener initialization is retained. Direct closed-caret edges remain zero. This demonstrates release of the confirmed stale observer graph, not removal of useful active listeners.

Paired packaged-editor measurements use 4 KiB, 100 KiB and 1 MiB fixtures, two/three panes, twelve lifecycle cycles per process, requested-GC controls and 250 ms RSS sampling. Used Java heap, MiB:

| Size | Ready, starting / corrected | After cycles, starting / corrected | After disposal, starting / corrected | Sampled peak RSS, starting / corrected |
|---|---:|---:|---:|---:|
| 4 KiB | 13.12 / 13.10 | 13.66 / 13.30 | 13.68 / 13.33 | 686.28 / 681.08 |
| 100 KiB | 13.93 / 14.01 | 14.50 / 14.13 | 14.56 / 14.19 | 689.97 / 689.14 |
| 1 MiB | 24.03 / 24.07 | 24.60 / 24.26 | 24.67 / 24.32 | 710.34 / 733.75 |

Committed heap is 64 MiB for small/100 KiB cases; after the 1 MiB workload it is 84 / 87 MiB. Each starting process retains twelve tracked closed text areas; each corrected process retains zero. Closing a view saves its observer graph rather than a whole duplicated source document, so the heap reduction is modest.

The main-editor weak reference remains reachable at the short post-disposal observation in **both** artifacts. Whole-window GC-root/native cleanup is not established by this probe. These results do not overturn previous buffer-lifecycle evidence with different ownership boundaries. RSS moves in both directions; the higher corrected 1 MiB peak is retained, not dismissed as a proven native-memory non-regression. Sampled peaks are not settled idle RSS or exact retained heap. GC requests are restricted to the opt-in child diagnostics, never production.

## H. Performance

Six paired fresh processes alternate artifact order, using identical fixtures, isolated homes, explicit Shed Light and Plain input mode, and bounded timeouts. They run separately from Maven desktop tests. Source input uses production dispatch and the existing completed-Swing-source-paint metric. It is synthetic source-key dispatch, not a new OS-input/compositor measurement.

Split dispatch p95, milliseconds, twelve samples per operation:

| Size | Create, starting / corrected | Close, starting / corrected | Close including validation/forced paint, starting / corrected |
|---|---:|---:|---:|
| 4 KiB | 5.53 / 3.53 | 2.23 / 1.63 | 8.63 / 3.82 |
| 100 KiB | 4.55 / 2.83 | 1.52 / 1.76 | 4.29 / 4.04 |
| 1 MiB | 72.60 / 4.83 | 1.67 / 3.17 | 4.34 / 5.20 |

The 1 MiB close dispatch is approximately 1.5 ms slower at p95 with deterministic deinstallation. Corrected close EDT allocation p95 is approximately 235–237 KiB. The starting 72.60 ms create outlier is not a basis for promising a universal creation speedup. Dispatch, validation, paint, allocation and all individual samples are separate in the raw evidence.

Typing-to-completed-source-paint p95, 50 warm samples per phase:

| Size | Before cycling, starting / corrected | After cycling, starting / corrected |
|---|---:|---:|
| 4 KiB | 4.00 / 5.59 | 2.31 / 1.93 |
| 100 KiB | 6.20 / 4.78 | 1.32 / 1.25 |
| 1 MiB | 5.99 / 9.02 | 1.48 / 2.00 |

All measured p95 values meet 16 ms. The corrected 1 MiB initial warm set has p99/max 23.24 ms; not every input is below 16 ms. First source-input observations are separate: starting 6.08–8.52 ms, corrected 6.37–8.20 ms. These controls do not requalify the known first-complex-Unicode stall.

History-operation timings below measure EDT mutation/listener work, not completed paint. After 24 closures, corrected ASCII first Redo/Undo is 2.80 / 1.21 ms; warm p95 is 1.01 / 0.78 ms (50 samples). Starting ASCII fails its first Undo, so a successful baseline Undo p95 is unavailable. Unicode first Redo/Undo is 15.21 / 3.37 ms starting and 5.57 / 3.23 ms corrected; warm p95 is 3.23 / 2.59 versus 1.80 / 1.18 ms. No expected speedup is inferred beyond these observations.

The inherited native Robot focus control timed out on the **unchanged starting JAR** in the first attempt. Rather than retrying or treating it as passed, an explicit `--lifecycle-only` mode records native focus as not requested and retains the original native-focus behavior by default. Final timing/resource comparisons use this declared mode for both artifacts. No fresh native-focus qualification is claimed.

## I. Regression results and commands

Java/Maven use `/tmp/shed-tranche2c-run/toolchain.env`. Python uses `PYTHONDONTWRITEBYTECODE=1`. Final relevant commands:

```sh
"$MVN" '-Dtest=BoundedUndoManagerTest,FileBufferTest,DocumentRequestsTest,SourcePlainViewTest,ThemePreviewTest,UiDensityTest,MarkdownEditTest,TexteditorSwingIntegrationTest#splitLifecycle*+activePaneIndicator*+completionIndexEnds*+semanticMarkerEdit*+bufferViewsAreIndependent*+ordinarySave*+markdownCommandsPersist*+markdownKeyboardEditsPreserve*+density*+focus*+centered*+liveThemePreview*+livePreviewCancellation*' test
"$MVN" -q -DskipTests package
"$MVN" test
python3 scripts/benchmark-active-pane.py --jar /tmp/shed-tranche8g1-run/candidate.jar --baseline-jar /tmp/shed-tranche8g1-run/baseline.jar --output /tmp/shed-tranche8g1-run/verified-matrix --lifecycle-only
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche8g1-run/candidate.jar --output /tmp/shed-tranche8g1-run/packaged-smoke.json
git diff --check
```

Final focused run: **78 passed**, zero failures/errors/skips. Complete final Maven suite: **1,616 passed, five existing skips, zero failures/errors** (1,621 total). Tooling: **47 passed**. Packaged Mermaid/math/image smoke: **PASS**. Final test logs contain no uncaught EDT exceptions.

All six paired lifecycle processes and all four rooted/history child diagnostics complete. The ASCII starting diagnostic intentionally records its production exception; child completion is not a passing Undo result. Final candidate diagnostics require successful execution and exact history pairs. The measured artifact is checked against the final compiled production classes.

The five existing skips are DOS hidden-attribute support, opt-in AI performance, unavailable Fish, unavailable CMake/CTest/CPack integration, and opt-in native AI acceptance. Exact identities are archived.

Preserved unsuccessful checks include the original exception/listener failures, wrong-buffer routing reproduction, minimap/paint roots, rejected null-caret/UI cleanup and its asynchronous errors, and the composition reset regression. A new minimap test initially closed whichever pane became active during shared-document caret notifications rather than its captured target; it was corrected to close the explicitly captured pane. No existing test assertion was weakened. Intermediate successful suites/measurements are distinguished from the final artifact, not substituted for it.

## J. UI/UX preservation

No visible treatment, component hierarchy, layout, setting or shortcut is added. The active-pane border/accessibility behavior from 8G is preserved. Focus mode, centering, density, reversible theme preview, Markdown commands/navigation/media, and existing keymaps pass their regressions. The minimap is only dereferenced when its own pane retires; active owners are preserved.

Shed Light and bundled JetBrains Mono defaults, saved preference precedence, source geometry and lexical/semantic performance infrastructure remain unchanged. No new worker, polling mechanism, persistent cache, startup scan, native resource or dependency is introduced. Existing `FileBuffer`, Undo, rendering and Unicode implementation files remain byte-identical.

## K. README and repository safety

Before and after README SHA-256:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

Entry HEAD was `9b11e7bfcb1881e04378b0868d52398d1374ddc9`, with a clean working tree. Starting hashes cover 1,669 existing files. Ten existing files changed; the other 1,659 remain identical and none is missing. Repository verification records these paths, prior-evidence preservation, source/probe/artifact identities, final status and whitespace checks. README was never modified.

HEAD advanced externally to `020a881237ecb15ce83b91954edf3a43452bbb75` during the task, capturing intermediate work. That commit was preserved. An entry-to-final diff includes changes captured by it, not only the remaining working-tree diff. The agent performed no reset, clean, revert, discard, commit or push.

## L. Outstanding limitations

These tests establish the identified view-lifecycle and history corrections, not all possible reference paths. Main-window reachability after short disposal controls, native-memory attribution, actual native IME composition, Windows/Linux runtime behavior, and broader physical-focus/platform performance remain unqualified. The pending-composition test covers existing Java event semantics only.

The remaining 51 Unicode conformance mismatches, multisecond production Unicode first-edit stalls, stock HTML-preview layout stalls and deferred rich live editing remain unchanged. Existing large-file/read-only and wrapped-source presentation limits remain. No universal RSS, startup or editing speedup is claimed.

## M. Verdict

**GO — the confirmed split-view retention and Undo defects are remediated in the covered workflows**, including the independently found wrong-buffer Vim route. Active shared source/history/recovery ownership is preserved; repeated tracked view retention is zero; exact Undo/Redo and persistence checks pass; ordinary measured typing p95 remains within budget.

This is a scoped remediation verdict. It is not a declaration of full product release readiness or exhaustive native/window cleanup.

## N. Tranche 8H readiness

8H combined qualification can begin as a separately authorized verification tranche. It should use this final artifact and combine pane lifecycle/history with focus/center, density, theme preview, real keyboard/focus/IME paths and supported platform controls. Carry forward the unresolved Unicode, preview, native-memory and release-readiness findings; passing these regressions does not remove them. No 8H or further appearance work was begun here.
