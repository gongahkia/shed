# Tranche 5A — semantic-safe heading subtrees

## A. Root cause

The previous `MarkdownController.markdownSubtreeShift` walked source lines, classified every line with `MarkdownService.headingLevel`, and applied promotion/demotion to that classification. It had no fenced-code context. A `### Example heading` inside a Markdown code example could change, and a lower-level example could incorrectly terminate the subtree. Individual level clamps could also change only part of a hierarchy.

Before production changes, `markdownSubtreePreservesFencedHeadingExamples` failed against the original controller. Its expected source preserves the fenced example and shifts only the real root/child headings. The same test passes after migration. The original failure is preserved in the [evidence index](performance/markdown-tranche5a/INDEX.md).

The production path now uses complete semantic headings to identify the root, descendants and first genuine following heading of equal or lower level. Exact verified opening-marker ranges authorize the changes. Complete no-match, unsupported, failed or cancelled analysis never enters the old subtree regex loop.

## B. Files changed

Production:

- `src/main/java/shed/MarkdownSubtreeEditPlan.java`: new immutable bounded subtree proposal, marker validation, exact source splice, undo cost and caret mapping.
- `src/main/java/shed/MarkdownEdit.java`: checked subtree-plan application through the existing range edit and compound undo.
- `src/main/java/shed/MarkdownController.java`: share the existing pending-heading lifecycle between single and subtree commands; remove the legacy subtree source loop; restore inactive split extents before viewport clamping after a subtree splice.
- `src/main/java/shed/CommandHandler.java`: four heading subcommands under the existing Markdown command registration.
- `src/main/java/shed/PaletteController.java`: four action rows using the unchanged palette components and dispatch.

Tests:

- `src/test/java/shed/MarkdownSubtreeEditPlanTest.java`: pure planning, source precision, caps, checked application, history, codec/recovery and palette registration tests.
- `src/test/java/shed/TexteditorSwingIntegrationTest.java`: reproduced fenced-code regression, command/profile coverage, rejection and split/recovery/highlighting checks; extend the existing pending-edit lifecycle matrix to subtree requests. Existing assertions remain in place. Pre-existing 4A additions are preserved.

Benchmarks/tooling:

- `src/benchmark/java/shed/MarkdownSubtreeProbe.java`: opt-in paired production-command, planning, paint, highlighting and lifetime measurements; reflection supports the unchanged baseline JAR.
- `src/benchmark/java/shed/MarkdownPerformanceProbe.java`: reflective dispatch for that probe; pre-existing 4A modes remain.
- `scripts/benchmark-markdown.py`: three deterministic subtree fixtures and bounded opt-in mode; pre-existing fixture bytes and modes remain unchanged.
- `scripts/test_benchmark_markdown.py`: fixture/admission checks; existing tests remain.

Documentation: `docs/COMMANDS.md`, this report and the evidence directory. `manifest.json` inventories every evidence file and implementation hash. No dependency, document model, lexer, source view, preview, media renderer, link index, input controller or live-presentation prototype changed.

## C. Edit-plan architecture

`MarkdownSubtreeEditPlan.plan` is pure. Its source is the established document ID, revision and exact immutable snapshot; it owns no buffer, pane, Swing component, service or AST. It captures direction, structural subtree bounds, ordered marker replacements, expected/replacement text and directed caret/selection state. `undoBytes()` expresses the required existing history payload budget; capabilities and the current budget are checked during application.

The structural subtree extent is descriptive. The actual replacement starts at the first verified marker and ends at the last verified marker. The planner copies each intervening character unchanged and checks that the proposed splice consists only of the recorded marker changes. A parser navigation envelope is never used as mutation authorization. Container spans are used solely to reject ambiguous hierarchy.

Admission is at most **512 headings** and **65,536 UTF-16 units each for expected and replacement source**. The source document remains subject to the unchanged 1,048,576-unit semantic cap and structural admission rules. A large body after the last targeted marker does not enlarge the splice. No whole-document string is needed for production planning.

## D. Supported operations

- Promote/demote one supported standalone ATX subtree by exactly one level.
- Support zero-to-three leading spaces, empty/duplicate/formatted/Unicode headings, absent final newline, genuine sibling/parent boundaries and both backtick/tilde code exclusions.
- Include genuine descendant headings in source order, regardless of duplicate titles; preserve body, indentation, trailing/closing hashes and line terminators.
- Reject the complete operation if any targeted level would leave H1–H6.
- Reject targeted Setext headings, quote/list-container headings, unverifiable markers, cross-line selections and operation-size limits. A verified standalone Setext heading outside the subtree can establish its boundary without being edited.
- Unsupported/capped/incomplete/failed/cancelled semantics reject; stale source/context rejects. No legacy editing fallback is used.

Single-heading 2C semantics remain: their supported container-heading cases and hash-free ordinary-line indentation branch are unchanged. New single-heading command aliases route to that existing behavior. Vim `<<`/`>>` and `<r`/`>r` retain their respective single-line/subtree meanings. Non-Markdown indentation routing is unchanged.

The old public string-array `MarkdownService.promoteSubtree`/`demoteSubtree` utilities remain for compatibility with their existing tests. They have no authoritative document ownership and no production mutation caller; the controller no longer uses their lexical interpretation. They are not a semantic editing API.

## E. Atomic mutation and undo

The existing range-edit boundary was inspected before choosing an implementation. Repeating independent mutations would require additional partial-failure/rollback and notification semantics. This tranche instead uses one bounded, fully validated contiguous splice through `MarkdownEdit`, with its existing single compound undo. It introduces no multi-edit transaction framework.

Before mutation, application checks document/snapshot/revision identity, document attachment, selection, every marker range and expected marker, the complete expected splice, current capabilities, document filters and history byte capacity. The plan constructor has already verified level bounds and exact replacement derivation. Application then uses the existing edit boundary, which checks the live target again and minimizes shared prefix/suffix before source replacement.

The logical operation is all-or-nothing for supported input and checked rejections. One Undo/Redo restores exact source. Marker-aware position mapping retains directed selection/caret offsets even when the splice contains unchanged body text. Rejected plans leave source, revision, snapshot, saved baseline, dirty state, history, selection and document-event count unchanged in the deterministic tests.

The maximum source-splice payload implies an upper undo estimate of 262,272 bytes before common-prefix/suffix reduction. Actual current history policy still decides admission; no history limits were changed. Normal document listeners continue to update snapshots, highlighting, LSP, dirty state and recovery. This is the existing Swing edit boundary, not a new exception-transaction guarantee for arbitrary third-party document listeners.

## F. Concurrency and lifecycle

Both operations share 2C's `PendingHeadingEdit`, owner token and current-context predicate. Current compatible analysis is reused; otherwise the existing 2B coordinator runs analysis off the EDT. Planning/application run in the checked completion, with bounded splice work. No synchronous full parse is added to an interactive command.

Only one heading edit is pending. Repeated requests receive existing busy feedback; no delayed edit queue is added. Source/revision, selection, pane, profile/mode, key-input epoch and lifetime checks remain. Focus/caret listeners are temporary and are detached after completion/cancellation; buffer closure/disposal uses the existing cancellation hooks. The coordinator still owns one running job, one newest pending source, eight subscribers and one weak result.

A heading command submitted from Vim's Ex prompt may complete after that prompt's normal COMMAND-to-NORMAL exit. That expected transition is explicitly compatible; later key input, incompatible mode/profile changes and focus/context changes still invalidate it. A gated test covers both existing command-entry routes and checks normal exit against subsequent Escape input, Insert-mode change and focus loss. No input-routing implementation was changed.

Shared source/history remain document-owned; selections and viewports remain pane-owned. A contiguous splice can temporarily remove lines before reinsertion. After successful application, the controller restores the initiating pane, refreshes each inactive shared viewport's standard Swing extent, and restores its saved position. Both the existing single-marker viewport test and the new subtree test pass. No source-view implementation or painting architecture changed.

## G. Correctness evidence

The new deterministic cases cover hierarchy depths, sibling/parent boundaries, duplicates, empty headings, EOF, formatted/Unicode headings, real/fake boundaries in backtick/tilde/unclosed/different-length fences, escaped/indented code, raw CRLF source, Setext/container rejection, absent/invalid markers, selection boundaries and complete no-match.

Application tests cover prior history, exact budget accounting, shared split source, caret/selection mapping, revision changes, undo/redo returning to old text, foreign documents, capability/filter/history rejection and read-only planning. Actual UTF-8 BOM, LF/CRLF/mixed terminator and EOF save/reopen comparisons pass; recovery journals preserve transformed source.

Workbench tests exercise all four commands through the palette's registered command mapping in Plain/Vim/Emacs, existing Vim subtree routes, existing single-heading and ordinary-line behavior, unsupported all-or-nothing rejection, initiating-pane/viewport preservation, actual highlight convergence and recovery scheduling. The existing eight-scenario pending-edit matrix is also run for subtree edits: typing, caret return, buffer return, mode/input, focus, closure, split switching and undo/redo.

Final result: **1,510 Maven tests run; 1,505 passed, five existing skips, zero failures/errors**. Nineteen new tests cover the new planner and workbench behavior. The earlier focused run passed 32 cases; the final cold-command/pending-lifecycle run passed two expanded cases.

## H. Performance and memory

Times are milliseconds. Primary pairs use 100 measured samples after five warmups; wrapped pairs use 30 after three warmups. Off-EDT planning runs on the probe thread. Checked-application, cold and highlighting diagnostics have five samples and are reported as maxima. Highlight convergence starts after command paint and includes the remaining existing debounce.

| Source / subtree | Typing → paint p95, before → after | Current command EDT p95 | Command → paint p95 | Undo → paint p95 | Redo → paint p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4 KiB / small | 2.07 → 1.23 | 1.08 → 1.34 | 1.82 → 1.78 | 1.86 → 1.73 | 1.47 → 1.37 |
| 100 KiB / small | 1.00 → 6.77 | 1.58 → 3.32 | 2.74 → 5.47 | 2.72 → 3.96 | 2.15 → 4.38 |
| 1024 KiB / small | 2.24 → 1.34 | 1.16 → 2.16 | 1.98 → 3.22 | 1.40 → 1.67 | 1.18 → 1.51 |
| 4 KiB / wide | 1.33 → 7.63 | 1.25 → 4.43 | 2.00 → 5.84 | 1.95 → 4.05 | 1.61 → 4.44 |
| 100 KiB / wide | 1.27 → 9.42 | 3.98 → 6.77 | 8.22 → 13.46 | 4.61 → 11.75 | 10.73 → 11.81 |
| 1024 KiB / wide | 1.44 → 11.09 | 13.38 → 27.82 | 15.00 → 30.20 | 15.38 → 26.00 | 14.83 → 28.52 |
| 100 KiB / mixed | 1.35 → 1.05 | 1.52 → 1.18 | 1.82 → 1.36 | 1.60 → 0.83 | 1.30 → 0.59 |
| 1024 KiB / mixed | 1.17 → 9.42 | 0.89 → 1.27 | 1.24 → 9.38 | 0.97 → 8.55 | 0.63 → 0.79 |
| 1024 KiB / wide wrapped | 1.34 → 1.74 | 4.99 → 7.96 | 5.28 → 8.75 | 6.06 → 6.17 | 5.94 → 6.21 |

| Source / subtree | Pure plan p50 / p95 | Checked application EDT maximum (n=5) | Cold dispatch maximum | Cold completion maximum, before → after | Highlight convergence maximum, before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4 KiB / small | 0.07 / 0.14 | 0.78 | 0.07 | 1.46 → 12.88 | 135.27 → 141.54 |
| 100 KiB / small | 0.22 / 0.49 | 1.08 | 0.07 | 3.15 → 12.91 | 136.53 → 137.64 |
| 1024 KiB / small | 0.17 / 0.59 | 0.94 | 0.24 | 2.49 → 392.52 | 135.55 → 132.63 |
| 4 KiB / wide | 0.22 / 0.47 | 0.97 | 0.13 | 1.84 → 13.07 | 136.84 → 129.65 |
| 100 KiB / wide | 0.50 / 1.56 | 3.26 | 0.09 | 4.20 → 24.83 | 133.79 → 131.66 |
| 1024 KiB / wide | 0.82 / 4.35 | 5.49 | 0.06 | 11.25 → 239.24 | 134.14 → 126.01 |
| 100 KiB / mixed | 0.11 / 0.29 | 0.68 | 0.09 | 6.64 → 12.79 | 147.48 → 136.23 |
| 1024 KiB / mixed | 0.32 / 0.59 | 0.54 | 0.11 | 1.93 → 53.33 | 136.46 → 134.25 |
| 1024 KiB / wide wrapped | 0.61 / 1.42 | 3.50 | 0.12 | 5.99 → 220.83 | 134.16 → 125.30 |

The before artifact is the preserved production JAR captured before changes. Both artifacts run the same probe, fixtures, JDK and heap configuration. Small and wide fixtures contain supported ATX hierarchies without the fenced-heading correctness defect, allowing comparable accepted transformations. The rejected-limit fixture intentionally distinguishes the old broad operation from the new bounded refusal.

Warm controls explicitly request the same semantic analysis before commands for matched setup. This does not imply the old production command required parsing; cold dispatch/completion separately records the old synchronous route and the new asynchronous cost. Off-EDT pure planning, production EDT command work, real completed Swing paint and highlight convergence are separate metrics. Forced/source paint timing excludes physical key delivery and compositor latency.

The checked-application diagnostic runs five additional accepted plans directly through `MarkdownEdit` on the known active fixture, with reflection overhead and a caller-supplied valid-capability predicate. It separates checked application from planning; ordinary production command measurements still use the controller's full pane/capability predicate. Small-sample maxima are not robust tail estimates. Raw summaries retain p50/p95/counts for all phases.

An initial implementation repeatedly calculated each target's snapshot line position. The 1 MiB wide case measured 21.73 ms planning p95 and 27.87 ms EDT command p95. The final planner instead checks at most four preceding source characters to verify standalone indentation, retaining complete semantic/container validation. A separate 30-sample check measured 2.42 / 8.34 ms respectively; the final 100-sample matrix above is the acceptance evidence. Initial measurements are preserved with their earlier artifact hash. No snapshot, line-index, cache or lexer implementation was changed to obtain this reduction.

The final near-limit fixture has **501 targeted headings and a 56,790-unit source splice**. Planning p95 is 4.35 ms, but the complete repeated command still reaches 27.82 ms EDT p95 / 30.20 ms to paint in the 1 MiB wide run. The bounded prefix correction does not establish a 16 ms deadline for large explicit commands. Do not expand the operation limits on this evidence. Cold completion can be substantially slower than the old unsafe synchronous transform (up to 392.52 ms in the sparse 1 MiB case), while cold dispatch remains below 0.25 ms in these observations.

All final source-typing p95 observations remain below 16 ms, ranging from 1.05 to 11.09 ms. Several primary pairs nevertheless show increased tails; equivalence is **not** established. An independent reverse-order control using the unchanged Tranche 2C `semantic-edits` probe measured typing p95 of **1.71 / 2.02 ms after** versus **2.91 / 3.58 ms before**, at 100 KiB / 1 MiB mixed source (100 samples each). Thus the increased tail was not consistent across probes/order. Host load ranged above the eight available CPUs and varied substantially between cases. The ordinary input, source-view and highlighting implementations are byte-preserved, and zero ordinary-typing semantic requests are asserted; this does not assign every timing difference to a proven cause.

After every Undo/Redo repetition, the probe resets the caret to the root. Initial pilots omitted that reset and timed out after later iterations targeted a different heading; those failed probe attempts remain archived. They are not treated as production command failures or performance samples.

All memory values are MiB; phase snapshots follow three requested GCs and short settling waits. Sampled process RSS is not retained heap or a guaranteed kernel high-water mark.

| Artifact / 1 MiB workload | Heap used / committed before | Heap used / committed after operations | Heap used / committed after closure | RSS before / after / closed / sampled peak |
| --- | ---: | ---: | ---: | ---: |
| release-before / subtree-small | 22.84 / 66 | 23.18 / 81 | 22.93 / 77 | 658.78 / 707.11 / 703.38 / 707.12 |
| release-before / subtree-wide | 23.71 / 66 | 24.24 / 84 | 24.17 / 84 | 656.84 / 665.67 / 662.98 / 796.70 |
| release-after / subtree-small | 22.63 / 66 | 24.03 / 84 | 22.79 / 80 | 658.95 / 575.45 / 577.64 / 756.69 |
| release-after / subtree-wide | 25.65 / 67 | 26.24 / 90 | 24.34 / 84 | 610.14 / 665.62 / 665.66 / 782.55 |
| mixed-before / mixed | 23.84 / 66 | 24.16 / 84 | 24.14 / 84 | 657.16 / 724.81 / 721.98 / 803.61 |
| mixed-after / mixed | 23.88 / 66 | 24.26 / 87 | 24.19 / 87 | 657.16 / 732.98 / 733.16 / 841.02 |

Heap and phase RSS use controlled requested-GC snapshots, not a claim about long-term idle equilibrium. The probe validates exact source restoration, zero semantic requests from ordinary typing, no extra request during current-result commands and released short-lived plans. It closes the source through the existing lifecycle. RSS/native attribution remains unresolved; no whole-app low-memory or fixed-footprint claim follows from these measurements. The benchmark's original/expected full-text validation strings are probe-owned, not new production caches.

After closing the 1 MiB source, used heap is 24.34 versus 24.17 MiB in the wide pair and 24.19 versus 24.14 MiB in the mixed pair. The final wrapped workload reaches 886.64 MiB sampled peak RSS, retaining the prior native-memory qualification. Released plan references are collected; these observations do not establish full native-resource equivalence or a general JVM memory fix.

## I. Command accessibility

Existing `:markdown` / `:md` dispatch now accepts:

```text
:markdown heading promote
:markdown heading demote
:markdown heading promote-subtree
:markdown heading demote-subtree
```

The unchanged command palette displays Promote/Demote Markdown Heading and Promote/Demote Markdown Subtree. Registration uses its existing duplicate-label/command validation and ordinary dispatch. No shortcut, popup framework, picker style, focus convention or global key route was added. Unsupported arguments receive the existing usage-message feedback.

## J. Regression verification

Commands (JDK 21.0.13 / Maven 3.9.11; exact artifacts, paths, outputs and skip reasons are archived):

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar
git diff --check
```

Before implementation the actual current baseline passed **1,491 tests run / 1,486 passed / five skips / zero failures/errors**. The pre-fix fenced-code regression failed as intended. Early new-test fixture errors and the inactive-viewport failure are retained, along with their corrections; no old assertions were weakened. The original 4A tests/helpers/evidence remain isolated and preserved.

The final full headful suite passed **1,505 tests, with five existing skips and zero failures/errors**. Packaging and the actual packaged Mermaid/math/image smoke passed. All **14 Python benchmark-tooling tests** passed. Exact suite totals, skip names, commands, development failures, fixture/JAR hashes and raw benchmark samples are archived. No test was disabled or weakened. Both current-HEAD and starting-HEAD `git diff --check` passed.

## K. UI/UX compliance

Shed's design, visual identity, layout, typography, themes, syntax colors, editor surfaces, component styling, preview, navigation, split ownership and keymaps remain unchanged. The only visible additions are four existing-style command-palette actions and their feedback. No new UI component, setting, dependency, renderer, worker pool, startup pass or persistent cache was introduced. Tranche 4A remains a production NO-GO.

## L. README and repository compliance

Before/after README SHA-256:

`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`

README was not edited. Starting HEAD was `b90187e50a2b4843af872c8c982e3916c6f82e39`; 1,190 file hashes and the existing uncommitted 4A status were captured. Shared test/benchmark files were extended; unrelated files, prototype helpers and all historical evidence are checked against their original hashes. No reset, clean, revert, commit or push was issued. Final HEAD/status and checks are archived.

## M. Remaining limitations

This is a deliberately bounded standalone-ATX operation. Targeted Setext/container headings, cross-line selections, larger splices and more than 512 targets reject. A complete semantic snapshot is still necessary even for a small subtree in a large source; sources above semantic/admission limits reject without legacy editing fallback. Weak-result collection can require another explicit analysis.

A bounded contiguous splice retains body characters verbatim but generates a normal replacement spanning them; downstream invalidation and undo payload are proportional to that bounded range. It does not provide marker-only document events for multiple disjoint headings. Broader or denser accepted operations require new measurements, not silently raised limits. Large explicit commands can exceed a frame; typing timing equivalence remains qualified by the variable-load comparisons above.

Cold commands can cancel after intervening user activity, and busy requests do not accumulate. Native IME, physical keyboard/compositor timing and a second OS are not newly qualified here. Existing HTML-preview layout and native-memory concerns remain open. No live-presentation or appearance work was resumed.

## N. Next recommendation

Tranche 5B remains worthwhile as a small independent selection-aware link/image insertion improvement through `MarkdownEdit`. Keep it separate from this correctness patch; it requires no new parser, service or UI.

Keep 5C conditional and narrow it to one verified correction at a time. Fence-language completion should first distinguish genuine opening fences. Enter/list/table improvements must demonstrate cheap, correct current-context decisions before changing keyboard routing. Do not add full semantic parsing on Enter, delayed newline edits or a new syntax service to obtain that behavior. Neither tranche has begun.
