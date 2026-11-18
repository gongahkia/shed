# Tranche 7B.1 — targeted Unicode editing correctness

## A. Exact finding, investigation gate and root cause

**The confirmed Indic conjunct boundary defect and its character-deletion Undo defect are corrected in the tested paths. This is not full Unicode qualification or a fix for Unicode layout latency.**

[7A](MARKDOWN_FINAL_QUALIFICATION.md#unicode-is-a-release-relevant-counterexample) reproduced severe complex-text view/layout stalls: the new-default 1 MiB fixture took 16.46 seconds to source paint and 10.55 seconds for its first edit. Those observations concern Swing's complex-text presentation; they did not establish grapheme splitting, corruption, or an IME defect. 7A's Unicode source/Undo checks were exact. Its original harness also incorrectly compared UTF-8 byte length with UTF-16 document length; that was a harness error, not source corruption. Native IME, complete grapheme conformance and other platforms remained unqualified.

No earlier committed report located during this investigation contained a specific Indic-conjunct reproduction. The existing [grapheme editing contract](GRAPHEME_EDITING.md) requires extended-grapheme boundaries, but the existing tests covered combining accents, emoji, flags and CRLF without Indic conjuncts. The investigation therefore reproduced the issue independently before changing production code:

```text
source: U+0915 U+094D U+0937 (क्ष)
expected next boundary from 0: 3 UTF-16 units
actual before correction:     2
```

The deterministic `GraphemeBoundaryTest.indicConjunctIsOneNavigationAndDeletionUnit` failed against the original implementation. The same segmentation made `GraphemeEditRange.previous(source, 3)` delete only the last consonant. Production call sites use these helpers for Vim character movement/deletion, Emacs character movement/forward-delete and multiple selections.

Root cause: the existing boundary loop recognized combining marks/ZWJ but lacked **GB9c**. The virama attached to the first consonant, while the following consonant started another cluster. [Unicode 15.1 UAX #29 revision 43](https://www.unicode.org/reports/tr29/tr29-43.html) defines the linking context using `Indic_Conjunct_Break` properties; [DerivedCoreProperties 15.1.0](https://www.unicode.org/Public/15.1.0/ucd/DerivedCoreProperties.txt) supplies those values.

A second deterministic integration failure exposed a closely related source-editing problem. `ClipboardManager.deleteChar`, used by Emacs forward-delete and Vim normal `x`, constructed the remaining whole document and called `setText`. One deletion created two Undo edits: one Undo removed the replacement document instead of restoring the deleted character. The failing Emacs test and its edit-history diagnostic are retained. The correction replaces only the verified deletion range, creating one removal edit. Other clipboard/word/line operations are unchanged.

## B. Severity and affected paths

Both corrected defects are **P2 editing correctness issues**: bounded but important for users of the affected scripts and character-deletion commands. A conjunct could be split by a helper-driven motion or deletion; one-step Undo behaved unexpectedly on the shared forward-character path. No irreversible data loss was observed. The layout problem from 7A remains a separate **P1 performance blocker**.

Verified affected paths are Vim insert Backspace, Vim normal `x`, Emacs `Ctrl-F` / `Ctrl-D`, and Plain multiple-cursor Backspace. Directional selection rounding is verified at the helper boundary. Plain single-caret native Swing actions, mouse hit testing and visual bidi movement are not routed through this helper in all cases and are **not certified** by this patch. Their existing routing is preserved. This tranche does not establish complete Plain/Vim/Emacs Unicode conformance.

## C. Implementation and files changed

Production changes:

- `src/main/java/shed/GraphemeBoundary.java`: carry a small three-state Indic linking context through the existing cluster scan; no second scan, retained document, iterator allocation or backward search is added. A joined consonant starts a fresh linking context; non-InCB context resets it.
- `src/main/java/shed/IndicConjunctBreak.java` (new): private, immutable range tables for exactly Unicode **15.1.0** InCB Consonant, Linker and Extend, with binary lookup. The tables cover 240 consonants, six linkers and 884 extending code points, represented by 202 ranges.
- `src/main/java/shed/ClipboardManager.java`: the character-delete path calls `replaceRange` instead of building a complete replacement string and calling `setText`. Existing clipboard/register behavior and caret destination are retained.
- `pom.xml`: package the Unicode data license alongside existing license notices. No dependency, Java version or build framework changed.

Tests, data, diagnostics and documentation:

- `src/test/java/shed/GraphemeBoundaryTest.java`: pre-change failing conjunct regression.
- `src/test/java/shed/IndicConjunctBoundaryTest.java` (new): exhaustive property lookup comparison, all official GB9c cases, six-script examples, negative linking contexts and exact UTF-16 range/rounding checks.
- `src/test/java/shed/UnicodeEditingRegressionTest.java`: extend the existing fixture with Indic, variation-selector and supplementary characters; verify Markdown-adjacent deletion, BOM/CRLF, Undo/Redo, snapshot reconciliation and save/reopen.
- `src/test/java/shed/TexteditorSwingIntegrationTest.java`: actual key routes, one-step Undo/Redo, multiple selections, dirty recovery, shared-document split state and retained Light/JetBrains Mono defaults.
- `src/test/resources/unicode/15.1.0/GraphemeBreakTest.txt` (new): complete pinned official corpus, unchanged.
- `src/test/resources/unicode/15.1.0/IndicConjunctBreak.txt` (new): the exact InCB section extracted from the pinned derived-properties file, retaining provenance/copyright notices.
- `src/benchmark/java/shed/GraphemeQualificationProbe.java` (new): opt-in full-corpus reporting and paired timing/allocation observations; not included in the application or normal Maven tests.
- `docs/UNICODE_LICENSE.txt` (new), `docs/GRAPHEME_EDITING.md`, this report, and the complete file inventory in [evidence](performance/unicode-tranche7b1/INDEX.md).

The generated lookup is tested against every Unicode code point, so the literal table cannot silently diverge from the pinned property extract. The data's license is included in the packaged JAR.

## D. Supported behavior and limits

GB9c uses Unicode **15.1.0**, not a claim of the latest Unicode release or complete 15.1 conformance. Supported linking examples include Devanagari, Bengali, Gujarati, Oriya, Telugu and Malayalam; repeated viramas, intervening supported marks and ZWJ are tested. Missing linkers, ordinary adjacent consonants, non-consonant prefixes, ZWNJ and line breaks do not fabricate a conjunct link. Text remains unchanged; only boundary interpretation changes.

Other legacy boundary rules are retained. They combine hand-written approximations with Java character categories; [JDK 21 `Character`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Character.html) uses Unicode 15.0 data. This implementation is consequently not a uniformly versioned, fully conformant extended-grapheme implementation. Later Unicode additions and the remaining corpus failures are unsupported here.

Maintained alternatives were evaluated narrowly on the actual JDK 21.0.13 runtime: both `BreakIterator.getCharacterInstance(Locale.ROOT)` and regex `\X` return first boundary **2** for the reproduced three-unit conjunct. Substituting either would not correct this case on the supported runtime. A general ICU dependency or segmentation-engine replacement is not justified for this small correction; no such dependency was added.

The existing API's UTF-16 offset and malformed-surrogate conventions, ASCII fast paths and direction-based interior-offset handling are unchanged. Grapheme clusters are not glyphs, bidi visual order, display columns or every language's orthographic syllables.

## E. Correctness and conformance evidence

| Check | Before | After |
|---|---:|---:|
| Official Unicode 15.1 corpus cases | 1,187 | 1,187 |
| Cases matching expected boundaries | 1,129 | 1,136 |
| Cases with boundary mismatches | **58** | **51** |
| GB9c-positive official cases passing | 0 / 7 | **7 / 7** |
| New corpus failures introduced | — | **0** |

All 51 remaining failure records, including expected and actual UTF-16 boundaries, match their pre-change records. This diagnostic is **not a passing full conformance test**. It explicitly reports failure counts; it does not skip cases, suppress mismatches or change the official corpus. Routine JUnit strictly asserts all GB9c cases and the independently reproduced editing behavior, rather than asserting known incorrect results for unrelated rules.

Remaining helper counterexamples include Prepend/control precedence (`U+0600` followed by a space) and legacy emoji-modifier handling. These are confirmed **helper conformance deviations**; their exact production input effects were not reproduced during this narrowly scoped tranche and are not automatically classified as production editing failures. Their full records are retained for a later investigation gate.

The new property test compares all **1,114,112 code-point values** against the pinned property extract. Boundary tests exercise `next`, `previous`, `floor`, `ceiling`, `isBoundary`, outward selection rounding and forward/backward deletion, including reversed range direction. Existing accent, emoji modifier, ZWJ, flag, CRLF and unpaired-surrogate tests remain enabled. No existing assertions were removed or weakened.

## F. Input, Undo, persistence and IME verification

Focused Swing tests invoke actual existing key dispatch: Vim insert Backspace, Vim normal `x`, Emacs movement/forward-delete, and Plain multiple-cursor deletion. For the tested commands one Undo restores exact source, another Undo is unavailable after an isolated operation, and one Redo restores the deletion. Two Plain carets delete two conjuncts in one existing compound Undo transaction. Splits share the same source document while retaining separate caret positions.

The expanded existing fixture traverses and edits accents, emoji/ZWJ, flags, multiple scripts, conjuncts, variation selectors and supplementary characters using exact UTF-16 assertions. A Markdown `**क्ष**` fixture verifies that only the conjunct range changes and that UTF-8 BOM / CRLF bytes survive save/reopen. Isolated `FileBuffer` tests explicitly reconcile their snapshots because they have no production workspace listener; actual Swing tests use the normal workspace listeners and persist an exact dirty recovery entry.

Existing synthetic composition and action-routing tests passed. They verify that established composition-sensitive routes remain intact; they are not native IME or composition-aware deletion certification. No input-method listener, keymap, selection architecture, focus policy or document ownership changed. Live external LSP and Windows/Linux native interaction were not newly exercised; existing regression coverage remains in force.

## G. Paired performance, allocation and memory

Same host/runtime as 7A: Apple M3, 16 GiB, macOS 26.5.2, JDK 21.0.13. Both paired JARs use Shed Light and bundled JetBrains Mono; saved preferences were not modified. The unchanged opt-in Markdown harness used isolated homes, `-Xms64m -Xmx512m`, source-only mixed ASCII documents, 100 samples/10 warmups per size, and attributed completed Swing paint. These are sequential developer-machine observations, not cold-cache or hardware scanout measurements.

| Ordinary typing | Before p50 / p95 | After p50 / p95 |
|---|---:|---:|
| 100 KiB | 0.697 / 1.260 ms | 0.657 / 1.001 ms |
| 1 MiB | 0.888 / 1.224 ms | 0.950 / 1.228 ms |

The measured representative target **p95 ≤16 ms** passes. These ASCII controls do not qualify Unicode first-use layout or preview-active typing. Those 7A failures remain open; expensive layout was not rerun as though this boundary correction fixed it.

A separate opt-in helper probe measured 100 batches, using 1,000 operations per batch for tiny operations and one per batch for long EOF traversal. Thread allocation counters showed **zero allocated bytes per boundary call** in these probes. Local conjunct-next p95 was about 48 ns before / 55 ns after once warmed. The existing 1 MiB-prefix backward traversal remained approximately 21.04 ms before / 20.46 ms after (p95). This **pre-existing linear Unicode traversal exceeds 16 ms** and is not described as ordinary-typing success or a newly bounded algorithm. No new scan or source materialization was introduced; improving existing traversal requires separate evidence and scope.

Raw static table payload is 404 integers, **1,616 bytes**, plus fixed array/class overhead. There is no per-document state, cache or worker. Paired source-workload observations:

| Fixture | Sampled peak RSS before / after | Heap used after document close, no forced GC, before / after |
|---|---:|---:|
| 100 KiB | 688.95 / 718.62 MiB | 24.43 / 23.62 MiB |
| 1 MiB | 741.02 / 754.84 MiB | 41.56 / 38.22 MiB |

Committed heap at those final observations was 64/64 MiB and 69/69 MiB respectively. Single-process sampled RSS differences of about 14–30 MiB are **not evidence of memory equivalence, a significant persistent regression or a leak**. There is no retained-heap ownership attribution for that difference. Peaks, used heap without GC and fixed table size measure different things. Existing 7A native-memory uncertainty and closed-buffer retention remain unresolved; this tranche does not claim otherwise.

## H. Regression verification

Final complete Maven suite: **1,548 run, 1,543 passed, five existing skips, zero failures/errors**. Final tooling discovery: **19 passed**. Packaged-only Mermaid/math/image smoke: **PASS**. The combined focused run passed **22 tests**, followed by a passing final route/recovery test and the full suite. Suite names, skip reasons, complete commands and logs are in [evidence](performance/unicode-tranche7b1/INDEX.md).

```sh
source /tmp/shed-tranche2c-run/toolchain.env
"$MVN" -Dtest='GraphemeBoundaryTest,GraphemeEditRangeTest,IndicConjunctBoundaryTest,UnicodeEditingRegressionTest,MultiSelectionEditServiceTest,MultiSelectionMotionServiceTest,TexteditorSwingIntegrationTest#indicEditsUseExistingVimEmacsAndPlainMultiSelectionRoutes+customActionsWorkInEachProfileWithoutInterceptingOtherFieldsOrComposition+markdownEnterRespectsCompositionPrefixesAndUnrelatedKeys' test
"$MVN" test
"$MVN" -DskipTests package
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --output /tmp/shed-tranche7b1-run/packaged-smoke.json
git diff --check
shasum -a 256 README.md
```

The new failing-before regression and Emacs Undo reproduction are preserved. Intermediate test development also caught a wrong test API name and an isolated snapshot assertion without workspace reconciliation; these were corrected in the test setup, without weakening the boundary/source assertions. There are no unexplained final Maven or tooling failures. The full-corpus diagnostic still has the 51 explicitly documented pre-existing mismatches above.

## I. UI/UX and appearance

No visual, layout, typography, theme, component, navigation or shortcut changes. Light and JetBrains Mono defaults and all saved-preference precedence code are byte-for-byte unchanged from the task baseline; the new Swing test asserts the actual default theme/font. No live-presentation, preview, media, indexing, buffer-retention, settings or appearance work was implemented.

## J. README and repository safety

README SHA-256 before and after:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

Initial HEAD: `a7633bd7a5ac6a4b68544a4c1854412e8ac68c68`. Initial uncommitted 7A report/evidence and all prior benchmark evidence were preserved. HEAD advanced externally during the task; the baseline hash inventory distinguishes existing work from these changes. No reset, clean, revert, commit or push was performed by this agent. Exact final HEAD, before/after artifact hashes, file inventory and `git diff --check` result are recorded in `verification.json`.

## K. Completion verdict

**Complete for the investigated narrow correction:** GB9c conjunct boundaries and atomic character deletion through the verified existing paths. Full Unicode conformance, native input qualification, all Plain/Swing single-caret paths, linear boundary traversal, Unicode layout stalls and overall release qualification remain incomplete. The patch must not be represented as completing 7A's Unicode performance remediation.

## L. Next independently scoped remediation

Prioritize **closed-buffer lifetime cleanup before preview latency**. Recommend a separate **7B.2** limited to the demonstrated completion-index and completed-job callback roots: invoke existing index removal at every real close path and retire completed cancellation callbacks without breaking exactly-once cancellation delivery. Use the unchanged eight-file retention probe and deterministic lifecycle tests; require collection without a later completion query or unrelated 200-job history turnover. Preserve shared splits, recovery, cancellation and 16 ms ordinary typing, then rerun full regressions and packaged smoke. No cache, queue or worker expansion is justified.

Unicode complex-text performance still needs its own bounded source-view diagnosis, preserving shaping/bidi/IME. Preview latency remains a P1 concern for unrestricted preview responsiveness, but its stock-HTML layout scope and operating-limit decision must be handled separately. Neither issue is fixed here, and no follow-on remediation, final qualification or appearance implementation has started.
