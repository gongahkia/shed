# Tranche 5B — Selection-aware link and image insertion

## A. Summary

The existing link/image commands now replace a supported selection with `[selected text](url)` or `![selected text](path)` and select the destination placeholder. They use the existing checked Markdown edit boundary and source document. No navigation, indexing, image loading or semantic parsing is requested by insertion.

Empty selections retain the exact prior templates (`[text](url)` / `![alt](path)`) and collapsed caret after the complete template. They intentionally do not acquire the new placeholder-selection behavior. This follows the explicit requirement to preserve existing empty-selection interactions.

## B. Files changed

Production:

- `src/main/java/shed/MarkdownService.java`: bounded, pure literal-label escaping and immutable replacement/relative destination coordinates.
- `src/main/java/shed/MarkdownController.java`: synchronous capture of the initiating pane, buffer, snapshot and selection; local rejection checks; checked application and existing feedback.
- `src/main/java/shed/MarkdownEdit.java`: selected-result coordinates, exact snapshot validation and two small weakly owned selection-history boundaries inside the existing compound edit.
- `src/main/java/shed/PaletteController.java`: descriptions of the existing two commands; image insertion no longer claims to open an image chooser.

Tests:

- `src/test/java/shed/MarkdownInsertionTest.java` (new): eleven tests covering escaping, parser/preview interpretation, rejected edits, source identity, history, persistence and recovery.
- `src/test/java/shed/TexteditorSwingIntegrationTest.java`: five new tests covering actual commands/palette, profiles, directed selections, native typing actions, rejection, shared panes and recovery. One old assertion expecting template insertion beside selected text now expects selected-text replacement; its empty-selection checks remain intact.

Opt-in benchmarks/tooling:

- `src/benchmark/java/shed/MarkdownInsertionProbe.java` (new): paired packaged-application command, typing, completed-paint and disposal controls.
- `src/benchmark/java/shed/MarkdownPerformanceProbe.java`: reflective dispatch for the new opt-in mode.
- `scripts/benchmark-markdown.py`: compile only the requested helper; cap this mode at 100 samples and 1 MiB fixtures.
- `scripts/test_benchmark_markdown.py`: admission rejection before output creation.

Documentation/evidence:

- `docs/COMMANDS.md`: update descriptions of existing insertion commands.
- `docs/MARKDOWN_SELECTION_INSERTION.md` (this report).
- `docs/performance/markdown-tranche5b/`: index, raw paired reports/logs, measurements, suite details, packaged smoke, initial file hashes and final verification/manifest. The evidence index inventories each artifact.

All existing 4A/5A work was preserved, including the previously uncommitted implementation and performance evidence. HEAD advanced externally from `b90187e50a2b4843af872c8c982e3916c6f82e39` to `c2143e36b5190f4c04f2acfe73cac3e1d2254c5b` during this task. That external commit records prior work; this task created no commits or pushes. Initial-file hashes distinguish content preservation from Git status changes.

## C. Supported selections

Selections may run forward or backward, occur at start/middle/EOF, and contain Unicode, surrogate pairs, combining sequences and leading/trailing spaces. The limit is 4,096 UTF-16 source units, checked before copying selected text. There is no trim or Unicode normalization.

The selected source is interpreted as **literal label/alt text**. Markdown punctuation is escaped, including brackets, backslashes, backticks, emphasis markers, angle brackets and entity punctuation. Existing `**bold**` selected as text consequently displays its literal asterisks; this is not an inline-Markdown restructuring command. Parentheses remain literal without escapes. CommonMark permits backslash-escaped ASCII punctuation and requires label brackets to be paired or escaped. Tests use the already-installed parser to verify the exact rendered text, a single link/image, and destination coordinates. [CommonMark escapes](https://spec.commonmark.org/0.31.2/#backslash-escapes), [links](https://spec.commonmark.org/0.31.2/#links), [images](https://spec.commonmark.org/0.31.2/#images).

Blank-only, multiline, tab/control-character, malformed-surrogate and oversized selections reject before mutation. U+2028/U+2029 also reject. A local preceding backslash/open bracket, an immediately following close bracket, or a preceding exclamation mark for a link rejects conservatively. This avoids an escaping/nesting boundary immediately next to the proposed syntax without scanning the document.

The current preview preprocesses `\(...\)` math without checking whether its backslash was itself escaped. Therefore selected raw `\(` or `\)` sequences reject; ordinary parentheses remain unescaped and dollar signs escaped. A focused preview test verifies that `see (x) and $y$` remains literal text. The renderer is unchanged.

## D. Mutation correctness

The controller captures the active source pane and its canonical FileBuffer, document identity, immutable text snapshot, revision, directed selection and exact selected text. A `MarkdownEdit` captures the expected local range. Immediately before mutation it checks target/pane capabilities, document and revision, snapshot identity, selection identity, expected source, UTF-16 boundaries, supported surface, absence of an incompatible document filter, undo availability and byte-retention limits.

There is no delayed callback or active-buffer lookup after background work. Rejected edits preserve source, snapshot/revision, dirty state, saved baseline, history and caret/selection. Accepted edits replace only the selected range through the existing surface; prefix/suffix minimization and compound source mutation remain owned by MarkdownEdit. Normal FileBuffer/listener behavior handles dirty state, snapshots, recovery and LSP notifications. No duplicate document or separate transaction engine was introduced.

## E. Caret and selection

Destination coordinates are UTF-16 source offsets calculated **after escaping**. Initial insertion and Redo select `url`/`path`; typing replaces that destination. Undo restores the original directed selection and exact source. Existing unrelated history remains available.

Swing's normal plain-document undo does not retain the original directed selection. Two small undo children bracket the source change inside the existing compound: the first/last callbacks capture view eligibility and restore selection in the correct Undo/Redo order. They retain only weak document/text-area references, numeric offsets and a predicate capturing weak host/pane references. The history admission calculation includes their existing-policy estimate of 128 bytes total. They create no separate undo step and retain no source string or semantic snapshot.

Restoration is suppressed if the initiating pane is inactive, detached, rebound or closed. Source undo remains shared according to existing buffer history conventions. Initial application restores the initiating split and other panes' viewport positions using existing pane/surface APIs. It does not impose a selection on another split.

Plain, Vim and Emacs use their existing command routes and input actions. No keyboard routing or shortcut was changed. Existing aliases `:link`, `:img`, `:image` and `:markdown link/image insert` remain available.

## F. Existing functionality

Single-heading and subtree edits, list/table/checkbox commands, lexical highlighting, source layout, links/wiki-links/backlinks, preview, media renderers, document ownership and persistence infrastructure are unchanged. The default editor remains the production source editor; 4A's NO-GO is not reconsidered. Commands do not resolve, index or fetch destinations. Their normal document events can still trigger existing interested consumers, just as other source edits do.

## G. Performance

Same-host paired packaged-JAR probes used the unchanged `mixed` fixtures at 4/100/1,024 KiB, 50 measured repetitions plus five warmups per operation, a 512 MiB child heap, no preview and unwrapped source. Apple M3, eight CPUs, 16 GiB, macOS 26.5.2, JDK 21.0.13/Maven 3.9.11. Runs were serial. The exact runner/probe hashes match across artifacts; raw samples, fixture hashes, host load and commands are archived in the [evidence index](performance/markdown-tranche5b/INDEX.md).

EDT command time includes existing command dispatch, capture/escaping, checked replacement and synchronous listeners. It is not a separate microbenchmark of `Document.replace`; completed source paint is measured separately through the existing production PerfService. Values below are p95 milliseconds, before → after:

| Source | Typing completed paint | Empty link command EDT | Selected link command EDT | Selected image command EDT | Selected link completed paint | Selected image completed paint |
| --- | --- | --- | --- | --- | --- | --- |
| 4 KiB | 2.08 → 1.98 | 0.60 → 0.89 | 0.82 → 1.22 | 0.81 → 1.01 | 1.23 → 1.73 | 1.48 → 1.63 |
| 100 KiB | 1.35 → 1.62 | 0.78 → 0.92 | 0.75 → 1.42 | 0.84 → 1.12 | 1.03 → 1.64 | 1.06 → 1.47 |
| 1024 KiB | 1.42 → 2.21 | 0.89 → 1.32 | 1.20 → 1.60 | 0.80 → 1.54 | 1.77 → 2.47 | 1.71 → 2.68 |

At 1 MiB, selected-link Undo/Redo EDT p95 was 0.60/0.67 ms before and 1.30/2.07 ms after; selected-image Undo/Redo was 0.53/0.61 ms before and 1.02/1.08 ms after. The after-change selected-link command p50/p95 was 1.16/1.60 ms and selected-image 1.03/1.54 ms. Raw summaries retain p50/p95 for all operations, empty-image controls and paints.

The before-selected command inserts a template beside the selection; the after-selected command replaces the range and adds selection-history metadata. These have different source effects and source-event counts. The increased command/Undo costs are reported rather than called equivalent operations or a speedup. Empty-selection controls retain the same behavior. All measured ordinary typing p95 values stayed below 16 ms. These observations do not establish universal timing equivalence; OS compositor/physical-key latency and a second platform are not qualified.

Median EDT allocation at 1 MiB was approximately 113/237 KiB for link insertion before/after and 112/252 KiB for image insertion. Selected replacement emits the existing remove/insert events; the baseline only inserted text. This is total command/listener allocation, not retained-label memory. The pure new escaping work remains bounded by the selected slice. No semantic or link service initialized during any probe, and every case verified exact source restoration.

Settled heap/RSS, MiB (before → after artifacts; phases each follow three explicit GCs):

| Source | Used heap at start | Used heap after operations | Used heap after close | Committed heap after operations | RSS at start | RSS after operations | RSS after close | Sampled peak RSS |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4 KiB | 12.84 → 12.84 | 12.97 → 12.99 | 12.98 → 13.00 | 64.00 → 64.00 | 639.56 → 634.31 | 680.55 → 679.80 | 680.67 → 679.94 | 680.67 → 679.83 |
| 100 KiB | 13.72 → 13.74 | 13.82 → 13.87 | 13.82 → 13.87 | 64.00 → 64.00 | 632.83 → 630.45 | 683.98 → 682.34 | 683.92 → 682.39 | 683.95 → 682.34 |
| 1024 KiB | 23.91 → 23.78 | 23.97 → 23.95 | 23.97 → 23.95 | 84.00 → 84.00 | 663.34 → 668.08 | 718.30 → 717.66 | 718.39 → 717.91 | 823.88 → 760.31 |

The 1 MiB used-heap increase across repeated operations was 0.063 MiB before and 0.170 MiB after, with both artifacts committing 84 MiB afterward. Closed-buffer heap/RSS did not fall to a new empty-workbench baseline in either artifact. Thus these phase readings do not prove complete workbench document release or resolve native-memory attribution. The new history objects hold weak UI/document references and compact selection coordinates; no document-sized retained cache was introduced. Whole-process peak/settled RSS remains high in both controls and cannot be attributed solely to this feature. No new service-specific persistent growth is demonstrated by this bounded workload; this is not a general leak or low-memory certification.

Startup analysis/index fields remained uninitialized. There is no new startup hook; one startup observation per fixture is insufficient for a startup-latency equivalence claim. The probes make no whole-project scan and initialize no Mermaid renderer. Further frame-time or memory optimization is outside 5B.


The new work is proportional to a bounded selected slice and escaped replacement. No ordinary-typing processing, startup initialization, worker, permanent cache, parser pass or full-document materialization was added. The explicit command still invokes existing document listeners, syntax invalidation and layout, whose measured cost can depend on source size. Command cost is reported separately from ordinary typing and completed Swing paint.

## H. Regression verification

Final Maven result: **1,526 run / 1,521 passed / five existing skips / zero failures or errors**, versus the previously verified 1,510 run / 1,505 passed / five skips baseline whose implementation file hashes matched at task start. This task reran the complete suite after implementation; it did not rerun a separate pre-change full suite. New coverage accounts for sixteen tests. Focused final unit run: **55 passed**; focused Swing/edit run: **19 passed** (before the additional preview-math unit case); benchmark tooling: **15 passed**. Packaging and packaged Mermaid/math/image smoke passed against the final JAR. All six benchmark cases passed.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" -B -Djava.awt.headless=true -DargLine=-Xmx512m -Dtest=MarkdownInsertionTest,MarkdownEditTest,MarkdownServiceTest test
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --output /tmp/shed-tranche5b-run/packaged-smoke.json
git diff --check
```

The five retained skips concern DOS hidden attributes, opt-in native-AI acceptance, opt-in AI performance, optional fish startup and the explicit CTest preset integration prerequisite. Exact suite details/skip diagnostics are archived. The initial intentionally failing regression is archived separately and is not counted as a final pass. No final failures/errors occurred. No tests were removed or disabled.

The deterministic regression was first run against the prior implementation and failed: selecting `Important documentation` produced `Important documentation[text](url)`. The same test passes with selected-range replacement. Unit tests additionally cover exact literal parser interpretation, unsupported selections, budget/filter/read-only/stale rejection, Undo/Redo after source changes, rebound documents, and BOM/LF/CRLF/mixed-terminator save/reopen/recovery. The Swing tests exercise the real modal command palette's acceptance/cancellation and existing focus mechanisms, all three profiles and source sharing between splits. Tests synchronize using EDT events, timer state and revision/selection identity; watchdogs bound failures.

## I. UI/UX compliance

No visual component, setting, dependency, menu, panel, shortcut, editor layout, typography, font, theme or navigation convention was added or changed. The existing palette rows and feedback are reused, with descriptions corrected. Production UI differences are limited to the requested selected-text transformation and placeholder selection.

## J. README compliance

Before and after SHA-256:

`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`

README.md was not edited. Final verification checks its bytes against the initial hash, preserves all prior performance artifacts, and checks the small production class scope against the baseline JAR.

## K. Remaining limitations

This is a literal single-line selection operation, not a general Markdown rewriting engine. Multiline labels, tabs/control characters, preview-ambiguous math delimiters and conservative adjacent delimiter cases remain unsupported. The limit bounds worst-case escaped replacement size to approximately 8 Ki UTF-16 units. Complex enclosing Markdown context is not parsed: inserting source within an existing code example remains source editing and does not promise that the containing context renders a live link. No automatic repair, nested-link rewriting, destination escaping/validation or image chooser was added.

Native IME input, physical key delivery/compositor latency and a second operating system were not newly qualified. Existing full-app native-memory attribution and HTML-preview layout remain unresolved; these local source edits do not address either concern. Explicit source commands and unusual host load can exceed a display frame without establishing a typing-path regression.

## L. Next recommendation

Keep Tranche 5C conditional. This tranche confirmed no new keyboard-routing correctness defect; selected-destination typing works in all three profiles. The prior assessment identified fence-language context recognition as a candidate requiring a deterministic opening/closing-fence reproduction before editing keyboard behavior. First isolate and test any confirmed fence-context defect; fix only that local recognition path if a bounded existing lexical context suffices. Next consider independently reproduced Enter/list/table correctness issues one at a time.

Broader Tab/Enter convenience, automatic table formatting and new continuation heuristics are optional enhancements. They should remain deferred until a concrete user-visible deficiency and cheap current-context decision are demonstrated. Do not add full semantic parsing per key, delayed newline mutations, services/caches or new UI to obtain them. No 5C, Tranche 7, live-presentation or appearance work was begun.
