# Tranche 5C.1 — Context-safe Markdown keyboard correctness

## A. Root-cause findings

Both problems were reproduced before production changes. `listContinuation("> > nested")` returned `"> "`: its last branch found only the first marker. Fence completion trimmed the current line, accepted any three-backtick prefix and selected the first registered language for an empty suffix. A closing fence therefore generated a source edit. The initial two failing regressions are preserved in the evidence archive.

Nested quote prefixes now retain their verified depth. Fence completion requires a current, conservative opening-fence context. This does not use full semantic analysis to authorize a keyboard edit.

The existing colouring lexer is intentionally permissive: it remembers three delimiters and can treat a shorter marker or one with trailing language text as a closer. That state alone cannot safely authorize source edits. Its colours/tokens remain unchanged. A small companion fence state travels with the existing revision-aware checkpoints instead of replacing that lexer or adding a parser/cache/service.

## B. Files changed

Production:

- `src/main/java/shed/MarkdownKeyboardContext.java` (new): immutable conservative fence state, exact delimiter-run recognition and bounded checkpoint-to-line reads. No AST, retained source or cache.
- `src/main/java/shed/GrammarHighlightService.java`: carry companion context in existing checkpoints and suffix candidates; verify both states before suffix convergence. Existing tokenization, viewport emission, cache bounds, cancellation and weak snapshot ownership remain.
- `src/main/java/shed/SyntaxUiController.java`: read-only lookup of an already-existing buffer cache; no cache/job initialization for a keyboard request.
- `src/main/java/shed/MarkdownService.java`: bounded nested quote-prefix recognition and composition with existing list/task continuation rules; supported empty quote detection.
- `src/main/java/shed/MarkdownController.java`: bounded current-line reads, context-aware Enter/completion and checked source-range validation through existing MarkdownEdit.

Tests:

- `src/test/java/shed/MarkdownKeyboardContextTest.java` (new): eight tests covering quote rules, fence lengths/tails, conservative contexts, budgets, snapshot invalidation, deep checkpoint reuse and comparison with the existing semantic parser.
- `src/test/java/shed/MarkdownServiceTest.java`: the initially failing nested-quote regression.
- `src/test/java/shed/TexteditorSwingIntegrationTest.java`: five new tests for the reproduced closing-fence edit, real profile/shortcut routes, source/selection/history, splits, rejection, save/reopen and recovery. Existing assertions remain intact.

Opt-in tooling:

- `src/benchmark/java/shed/MarkdownKeyboardProbe.java` (new): paired actual input/command, completed-paint, Undo/Redo, highlight convergence and memory observations.
- `src/benchmark/java/shed/MarkdownPerformanceProbe.java`: reflective opt-in dispatch.
- `scripts/benchmark-markdown.py`: bounded `keyboard-edits` mode, compiling only its requested helper.
- `scripts/test_benchmark_markdown.py`: admission-limit tests before creating output.

Documentation/evidence:

- `docs/MARKDOWN_KEYBOARD_CONTEXT.md` (this report).
- `docs/performance/markdown-tranche5c1/`: evidence index, raw paired reports/logs, measurements, suite details, packaged smoke, baseline hashes and final verification/manifest. The index lists every artifact.

No change to InputController, MarkdownEdit, FileBuffer, source layout, renderer/media/link services, keymap definitions, dependencies or production UI was needed. Existing 5B edits and earlier work are preserved independently of Git status. Initial HEAD was `c2143e36b5190f4c04f2acfe73cac3e1d2254c5b`; final identity and status are recorded in `verification.json`. This task created no commits or pushes.

## C. Context strategy

The existing highlighting worker already advances through Markdown source lines. During that same pass, checkpoints now also retain a tiny immutable state: normal text, an exact backtick/tilde fence run, or unknown; plus a conservative flag recording earlier list context. No second full-source traversal, token list, parser tree, background job or cache is introduced.

On Enter/completion, the current snapshot must match the existing cache's weak snapshot identity. The query captures the nearest trusted prefix checkpoint under a short lock, releases the lock, then advances through at most **16,384 UTF-16 source units / 128 complete lines**. Individual keyboard lines are capped at approximately **4,096 units** before reading them. Unverified suffix candidates are never queried. A first-line request has a known empty prefix; absent/stale/deep-out-of-budget context is unknown. The query does not bind/create a cache or request highlighting/semantics.

Normal text becomes fenced only on a supported standalone opener; closure requires the same delimiter and a sufficient run followed only by permitted closing whitespace. Opening backtick info containing backticks is inactive. Root indentation is limited to three ASCII spaces. These rules follow the CommonMark fence/quote contracts and are checked against Shed's installed parser in tests. [Fenced code](https://spec.commonmark.org/0.31.2/#fenced-code-blocks), [block quotes](https://spec.commonmark.org/0.31.2/#block-quotes).

HTML-looking block starts, container fence-like lines and indented fences following earlier lists conservatively make context unknown. Resolving container termination or HTML ownership is outside this helper's contract. Unknown remains conservative for the remaining prefix rather than guessing where a complex block ends. Oversized lines/prefix budgets also produce unknown. This can suppress continuation later in such a document; it does not claim full CommonMark block parsing.

On ordinary document changes the existing prefix/suffix invalidation carries this metadata with its exact revision. Background publication checks the existing generation/snapshot. Suffix reuse requires both colouring and keyboard context to converge. Worker scanning remains viewport-driven and checkpoint-bounded; keyboard queries perform no document-start traversal when a trusted nearby checkpoint exists, and reject rather than crossing their fixed read budget.

## D. Behavior

Supported quotes include one/two/three levels and compact `>>>` forms, up to 32 levels; zero-to-three leading ASCII spaces; supported inter-marker spaces; ordinary Unicode paragraphs; and quoted existing unordered/ordered/task forms. Existing list/task rules are reused, including increment/reset behavior. No new `1)` continuation or checkbox grammar is introduced.

`> > nested` followed by Enter becomes `> > nested\n> > `. Prefixes are calculated from source before the caret/selection. Selections after a complete supported prefix replace only that range; all unrelated source remains exact. Existing final newlines remain present when Enter splits before them. Supported empty nested quotes use the existing empty-item exit action: remove the empty quote line's complete prefix and insert one ordinary newline, as for a single empty quote.

Tabs/ambiguous deep indentation in quote prefixes, excessive nesting, code fences, unknown block context and oversized lines fall back to ordinary Enter. Plain/Emacs return control to their existing default action; Vim retains its existing synchronous source newline/autoindent fallback on admitted lines; oversized lines use a bare source newline. No Markdown markers are fabricated in those cases. Unicode whitespace content is not treated as an empty quote merely because Java considers it blank.

Completion retains the existing language registry and first-match convention. Supported collapsed carets anywhere on a verified standalone opening backtick-fence line can complete its info prefix. Exact longer backtick runs are preserved; spaces between marker and language are preserved. Closing fences, lines inside an open fence, escaped/indented/container/HTML/unknown contexts, selections and excessive lines reject without mutation. Tilde fences are tracked for context safety but **tilde language completion remains unsupported**. No new command, shortcut or completion UI was added.

## E. Editing correctness

All accepted custom edits still use `MarkdownEdit`: canonical document/pane capture, revision, expected local source, caret/selection, editability/surface/filter checks, undo-byte admission and one compound operation. Enter additionally validates UTF-16 selection boundaries. No completion callback or delayed newline exists. Rejected edits preserve source, revision/snapshot, saved baseline, dirty state, history, caret/selection and document-event count.

Tests cover exact one-step Undo/Redo, selected-range replacement, Unicode/surrogates/combining text, source at EOF and before a final newline, shared document/history across splits, read-only rejection, current/stale cache identity, source edits changing fence context, subsequent buffer switching, BOM and LF/CRLF save/reopen plus recovery capture. Normal listeners remain responsible for revisions, LSP changes, dirty state, recovery and persistence. No source ownership or codec changes were made.

The persistence fixture contains an actual line terminator before its EOF quote, so CRLF metadata exists even without a final newline. A final adversarial audit also exposed an empty-bullet container case in the initial context helper; bare list markers now retain conservative container awareness. An earlier test incorrectly assumed a newline-free file could convey a CRLF preference; that test harness assumption was corrected without changing the codec. Early test compilation/fixture failures remain archived. A split-switch test asserts the other authoritative buffer remains untouched rather than assuming which shared pane retains focus after ordinary lifecycle callbacks.

## F. Keymap compatibility

Plain and Emacs already share `handleMarkdownEnterKey`; Vim insert invokes the same synchronous route. InputController and its existing IME composition, modified-key/prefix and multiple-cursor guards are unchanged. The real Vim insert Ctrl+J route still recognizes the existing backtick line and now reaches a checked opening-fence completion or existing feedback. Supported quote/list/task actions and directed selections are tested across all three profiles. Existing normal-mode/non-Markdown, IME, split and key-routing regression suites are rerun; no global shortcut or mode convention changes.

## G. Performance

Paired packaged-JAR controls use the unchanged mixed fixtures at 4/100/1,024 KiB, 50 measured repetitions plus five warmups per keyboard operation, 512 MiB child heaps and the existing production input/paint instrumentation. The primary probe edits the last matching quote/list/fence/prose region, preparing its viewport through normal asynchronous highlighting. No semantic/link service initialized during keyboard controls; exact source restoration is asserted. Both artifacts use the identical final probe/runner hashes.

Environment: Apple M3, eight CPUs, 16 GiB, macOS 26.5.2, JDK 21.0.13/Maven 3.9.11. Workloads ran serially, with no preview or media renderer initialization. One-minute load rose from approximately 2.9–4.2 in the before group to 8.1–10.4 in the after group. These are noisy same-host observations, not proof of timing equivalence or a universal speedup. All raw samples, environment observations, exact commands and artifact hashes are in the [evidence index](performance/markdown-tranche5c1/INDEX.md).

Primary p95 milliseconds, before → after:

| Source | Typing completed paint | Quote Enter EDT | Quote Enter completed paint | List Enter completed paint | Plain Enter completed paint | Opening completion EDT | Closing completion EDT |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 4 KiB | 1.743 → 2.898 | 0.714 → 1.012 | 1.292 → 1.916 | 1.349 → 4.428 | 1.132 → 3.287 | 0.517 → 1.070 | 0.213 → 0.024 |
| 100 KiB | 1.917 → 4.945 | 1.101 → 2.869 | 1.373 → 3.458 | 1.544 → 2.618 | 1.936 → 2.111 | 1.377 → 1.713 | 0.693 → 0.021 |
| 1024 KiB | 2.781 → 3.438 | 1.650 → 3.679 | 1.993 → 4.442 | 2.207 → 2.547 | 1.624 → 2.073 | 1.386 → 1.297 | 0.768 → 0.022 |

At 1 MiB, quote Enter EDT p50/p95 was 1.572/3.679 ms. Opening completion completed-paint p95 was 1.474 ms. Quote Enter Undo/Redo EDT p95 was 1.450/1.501 ms before and 2.911/2.578 ms after; list Enter Undo/Redo was 3.245/2.134 before and 1.885/1.966 ms after. Full p50/p95, CPU, queue, allocation and paint summaries are archived for every operation.

Before-quote continuation loses a level; after-quote continuation preserves it. Before-closing completion incorrectly mutates source; after-closing completion rejects. Their differing output is verified, not presented as equivalent transformations. No completed-paint measurement is attached to a correctly rejected no-mutation completion. Command EDT timings include capture, bounded context, checked mutation and normal listeners rather than an isolated parser microbenchmark.

All primary typing and supported Enter/command p95 values remain below 16 ms, although several tails increased and are retained above. To qualify host-load uncertainty, the unchanged existing `semantic-edits` probe was run in reverse artifact order. Its separate beginning-of-source typing phase at 100 KiB / 1 MiB measured **1.155 / 1.144 ms before** and **0.823 / 1.209 ms after** (50 samples each). This is an independent source-typing control at a different position, not a substitute for the primary deep-position results. That probe also runs its pre-existing explicit semantic-heading phase; those requests are separate from keyboard/typing activity.

At 1 MiB, median total EDT allocation for typing remained approximately 118 KiB in both artifacts. Quote Enter increased from 49.4 to 54.2 KiB, list Enter from 44.8 to 50.2 KiB and opening completion from 40.3 to 44.6 KiB, including ordinary listeners/layout. Keyboard context retains no copied source. Reads are limited to one admitted local line plus at most a 16 KiB trusted prefix slice; an unavailable context does not trigger a larger scan.

Five highlight-convergence observations per operation at 1 MiB were approximately 122–137 ms after versus 124–140 ms before. They include the unchanged debounce/scheduling and 10 ms polling resolution; five diagnostics do not establish reliable percentile limits. The independent unchanged lexical replay used 25 viewport samples and up to ten edit replays per scenario. End-viewport lexical p95 at 100 KiB / 1 MiB was **0.170 / 0.177 ms before** and **0.148 / 0.423 ms after**. The increased 1 MiB warm-worker cost is reported. Both controls retained identical maximum checkpoint counts (17 / 149), zero outside-context tokens and reference-equal visible tokens. Other replay timings/scan counts and invalidation scenarios remain in the raw reports. Source layout and viewport token emission were not replaced.

Post-GC phase memory, MiB (before → after artifacts):

| Source | Used heap at start | Used heap after operations | Used heap after close | Committed heap after operations | RSS after operations | RSS after close | Sampled peak RSS |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 4 KiB | 13.18 → 13.18 | 13.40 → 13.40 | 13.41 → 13.41 | 64.00 → 64.00 | 674.88 → 579.73 | 674.89 → 574.33 | 674.89 → 669.23 |
| 100 KiB | 13.64 → 13.65 | 13.98 → 14.01 | 13.90 → 13.91 | 64.00 → 64.00 | 669.12 → 672.89 | 669.12 → 673.02 | 669.12 → 672.91 |
| 1024 KiB | 23.90 → 23.78 | 24.16 → 24.11 | 24.13 → 24.09 | 84.00 → 84.00 | 700.69 → 575.47 | 700.72 → 576.44 | 772.20 → 690.77 |

Each settled phase follows three requested GCs; RSS is also sampled independently during the process and may miss a short peak. At 1 MiB, settled starting RSS was 664.95 MiB before / 657.91 MiB after. Used-heap growth across operations was approximately 0.267 / 0.329 MiB, with 84 MiB committed afterward in both controls. Closing did not return either control to an empty-workbench memory baseline. These bounded observations demonstrate no substantial new retained-heap growth, but do not certify full workbench cleanup or attribute native memory. Lower RSS in an after phase is not claimed as a feature-specific optimization. The earlier global native-memory qualification remains open.

No new startup hook or service exists; startup cache/service diagnostics remain unchanged. A single startup observation per fixture does not establish startup-time equivalence. Physical input/compositor timing and a second platform remain unqualified.


No source-sized retained plan, eager startup task, semantic request, new worker or persistent cache was added. Companion state stays within the existing checkpoint/cache counts. Current-line and prefix-copy costs are bounded independently of file size. Accepted edits still incur normal listener, layout, syntax invalidation and undo costs; those are measured separately from pure context metadata.

## H. Regression tests

Final full Maven: **1,540 run / 1,535 passed / five existing skips / zero failures or errors**. Fourteen added tests relative to the verified 5B baseline. Initial hashes matched all 5B implementation/manifest entries and the before JAR matched its final packaged smoke artifact. A separate pre-change full suite was not rerun; both defects were reproduced before production changes. Final focused run: **70 passed**. Tooling: **16 passed**. Packaging and packaged Mermaid/math/image smoke passed against the final guarded JAR. All fourteen primary/lexical/independent-control benchmark cases passed.

The initial candidate's two reproduced failures, a test compilation/import mistake, fixture/caret assumptions, the first full passing run and the subsequent adversarial context audit remain archived. The final 98-case guard was followed by another focused and complete full run. No tests were disabled or weakened. Existing skip categories remain DOS hidden attributes, opt-in native-AI acceptance, opt-in AI performance, optional fish and a CTest preset prerequisite; exact diagnostics are in `regression-suites.json`.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m '-Dtest=MarkdownKeyboardContextTest,MarkdownServiceTest,MarkdownHighlightTest,MarkdownEditTest,TexteditorSwingIntegrationTest#markdownNestedQuotesUseAtomicEnterAcrossProfilesAndDirectedSelections+markdownFenceCompletionVerifiesOpeningsAndTheExistingVimShortcut+markdownKeyboardContextRejectsCodeStaleAndReadOnlyTargetsAndSharesSplits+markdownKeyboardEditsPreserveBomNewlinesSavingAndRecovery+markdownClosingFenceCompletionDoesNotInsertALanguage+markdownEnterUsesOneCapturedEditAcrossPlainVimAndEmacs+markdownRangeCommandsPreserveEofAndAtomicUndoRedo+markdownEnterRespectsCompositionPrefixesAndUnrelatedKeys' test
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --output /tmp/shed-tranche5c1-run/packaged-smoke.json
git diff --check
```


The eight context unit tests include a 98-case matrix using the existing semantic parser as an oracle: whenever the lightweight helper authorizes normal text, the target must not be inside a parser-verified code region. Additional tests demonstrate sparse checkpoint use beyond 4,000 lines and unknown rejection when no nearby trusted checkpoint exists. Highlight differential tests keep the existing token oracle and bounds. Swing tests prepare existing metadata deterministically when testing classification, and separately exercise the actual asynchronous worker's installed state. No production full parser runs on the EDT.

## I. UI/UX compliance

No panel, dialog, setting, menu, component, font, theme, layout, syntax colour, navigation pattern or keyboard binding changed. Existing source editing, palette, preview and workbench design remain. The only intended visible changes are correct supported quote continuation and safe fence completion/rejection through existing feedback.

## J. README compliance

Starting and final SHA-256:

`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`

README.md was not edited. Final verification compares initial content hashes, previous performance evidence, allowed production class families and whitespace checks. No existing user work was reset, cleaned, reverted or discarded.

## K. Remaining limitations

This conservative helper cannot establish arbitrary nested list/quote fence ownership, HTML block termination, all lazy container continuation or unusual tab geometry. It returns unknown, possibly for the remainder of that source prefix, and ordinary Enter remains available. A cold/stale/far-away missing checkpoint can temporarily suppress smart continuation until normal highlighting supplies usable metadata. No worker is launched just to satisfy a keyboard command.

Quote prefixes above the documented limits, multiline/ambiguous prefix editing, tilde language completion, context outside read budgets and incompatible document surfaces remain unsupported by these smart actions. Existing lexical colours are not a semantic assertion. Native physical IME/key/compositor latency and another operating system are not newly qualified. The global native-memory concern and stock HTML-preview layout limitations remain unresolved. Tranche 4A's production NO-GO remains in force.

## L. Next recommendation

Skip optional 5C.2 unless there is a reproducible table-navigation correctness defect affecting normal keyboard use. Existing table commands already have escaped-pipe/ambiguous-context tests; additional navigation convenience alone does not justify expanding keyboard routing now. A future proposal should isolate one failing interaction, reuse current cell navigation, and show bounded current-row reads before implementation.

Proceed next to a separately authorized Tranche 7 performance/native-memory/reliability qualification, emphasizing comparable settled-versus-peak memory, repeated buffer/project lifecycle and representative editing workloads. Do not treat this tranche's bounded observations as full-app memory attribution. No 5C.2, Tranche 7, live presentation or appearance work was begun.
