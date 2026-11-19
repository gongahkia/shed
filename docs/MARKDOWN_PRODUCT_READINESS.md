# Tranche 7C — final Markdown qualification and product decision

## A. Executive verdict

**Personal/development use: CONDITIONAL GO. General public release: NO-GO. Original performance-first ambition: NO-GO.** Shed delivers substantial, useful Markdown source editing within its general IDE. Those working features are not invalidated by the performance failures, but they do not excuse them.

Two confirmed **P1 production problems remain release-blocking**: multisecond complex-text source-layout stalls, including the first Unicode insertion into an otherwise ASCII file, and synchronous HTML-preview work that blocks source input. Unicode conformance, low-system-memory operation, native-memory attribution and cross-platform desktop performance also remain incomplete qualifications. No P0 data-loss/corruption defect was observed in this verification; that is not a universal data-integrity guarantee.

7B.4's isolated paragraph-range experiment remains **NO-GO for production integration**. Its smaller allocation/read counts are not a production improvement. 4A's live-presentation migration remains NO-GO. No optimization, production code change, prototype activation, appearance feature or architecture replacement is delivered by 7C.

Recommendation now: **retain the existing production editor and explicitly limit performance claims** (Option C implemented as a product-positioning recommendation, retaining A's code). Do not present a general-release or exceptionally low-memory qualification. If those goals remain priorities, commission a separate source-layout architecture decision after a small maintained-JDK compatibility screen; do not continue speculative local patches.

## B. Implemented capabilities

The production routes were reviewed against the current checkout, then the full Maven and packaged-media suites were rerun. The [evidence index](performance/markdown-tranche7c/INDEX.md) distinguishes current verification, historical measurements and prototype-only results. Infrastructure existing in the JAR is not counted as an installed user-facing mode.

| Delivered capability | Current route and verification | Supported boundary |
| --- | --- | --- |
| Plain Markdown source editing | `FileBuffer` / `PlainDocument`, shared snapshots, existing Swing surface and listeners; document/Undo/persistence/Swing tests | Canonical source remains text; no production live typography or inline media |
| Checked atomic formatting | `MarkdownController` → `MarkdownEdit`; formatting, rejection and Undo tests | Supported local range edits use existing capabilities, expected-text/revision and history-budget checks |
| Single heading and subtree editing | Four `markdown heading` commands and palette actions; heading/subtree plan and real keyboard tests | Verified ATX markers; subtree limits 512 headings / 65,536-unit splice; reject H1/H6 violations, targeted Setext/container ambiguity and stale context |
| Tables | Existing insert/align/sort and row/column commands, checked table ranges and tests | Bounded rectangular standalone pipe tables; existing Vim table-cell Tab route, not a new cross-profile table-navigation system |
| Lists, tasks, checkboxes and blockquotes | Existing helpers plus shared Enter route in Plain/Emacs/Vim insert; keyboard-context/Swing tests | Supported nesting and list/task grammar; unknown fenced/container context falls back to ordinary editing |
| Fence completion | Existing registry/input route and checked opening-context completion | Verified standalone backtick openers only; closing fences reject; tilde completion is not delivered |
| Link/image insertion | Existing command/palette routes; selection-insertion tests | Selected single-line text becomes escaped literal label/alt; destination selected; empty `[text](url)` / `![alt](path)` behavior preserved |
| Heading outline/navigation | Shared semantic consumers, existing outline/TOC/symbol components | Current complete supported snapshots; explicit limits and conservative fallback/rejection; no per-keystroke full parse |
| Wiki-links and link following | `markdown link follow`, existing Vim `gf`, current resolver and file opener; navigation tests | Relative Markdown/wiki paths, aliases and verified fragments; ambiguous files require picker choice; unsafe/missing/stale targets reject |
| Outgoing links/backlinks | `markdown outgoing` / `markdown backlinks`, existing palette picker and project index | File-scoped project identity, source-location navigation, current unsaved overlays, explicit completeness; first 200 rows when capped |
| Syntax highlighting | Existing grammar worker/checkpoints, viewport token installation and revision checks | Bounded incremental/viewport work, with conservative invalidation and indivisible long-line tokenization limitations |
| Rendered Markdown preview | Existing separate preview component and renderer; preview/synchronization tests | Functional HTML preview with local media/math/Mermaid; its synchronous responsiveness remains defective |
| Packaged media | Packaged-only real Mermaid, math and image smoke **PASS** | Existing preview rendering/security policy; successful rendering does not imply low-latency first use |
| Async media infrastructure | Bounded service/request/cache/lease implementation and service tests | **Infrastructure only:** existing preview was not rewired, and the editor does not install inline assets |
| Persistence, recovery and shared splits | Codec, BOM/newline/EOF, atomic-save, dirty state, recovery, shared-owner and stale-result suites | Tested contracts pass; native IME, live external LSP and other OS qualification remain separate |
| Source-preserving folding containment | Existing 0A regression tests and retained disabled unsafe Markdown fold binding | Source is not deleted to simulate presentation; complete visual Markdown folding is not delivered |

Relevant implementations include `CommandHandler`, `PaletteController`, `InputController`, `MarkdownService`, `MarkdownController`, `MarkdownEdit`, heading edit plans, semantic coordinator/parser, link resolver/index/navigation, `GrammarHighlightService`, `SyntaxUiController`, preview/media classes and document lifecycle controllers. Existing command names, components, focus, navigation and keyboard conventions remain intact.

## C. Capability limitations

| Capability | Final status |
| --- | --- |
| Ordinary mixed-ASCII source responsiveness | **Meets the measured controls**, including 100 KiB/1 MiB/10 MiB; not universal text qualification |
| International-text source responsiveness | **Fails first-use qualification**; warm short-paragraph controls can pass |
| Complete Unicode grapheme conformance | **Fails:** 51 unchanged official-corpus mismatches; GB9c subset passes |
| Markdown correctness | **Meets tested supported contracts**; not universal syntax, IME or Unicode correctness |
| Heading/semantic coverage | **Limited:** full-source cap 1,048,576 UTF-16 units plus line/depth/node/span and density limits; not an editor-size limit |
| Link navigation/backlinks | **Meets bounded tested scope**; normalized project/file identities, not a vault or database; untitled sources excluded |
| Responsive preview | **Fails**; separate functional preview remains usable with significant synchronous-work limits |
| Async media in the existing preview | **Not delivered**; reusable 3B service does not make current preview asynchronous |
| Inline image/Mermaid/math editing | **Deferred / unsupported** on the production editing surface |
| Source-preserving live typography/concealment | **Deferred / unsupported**; 4A production migration rejected |
| Fully editable 50–100 MiB files | **Deferred / unsupported**; default large-file thresholds lead to read-only projection; 10 MiB results do not qualify this range |
| Low idle/native memory claim | **Inconclusive**; a diagnostic RSS ceiling is not a low-memory product guarantee |
| Genuine low-system-RAM usability | **Inconclusive**; constrained Java heap is a different experiment |
| Cross-platform performance/native interaction | **Incomplete**; this verification is macOS/JDK 21, not Windows/Linux headful certification |

Other explicit boundaries remain: selected-text insertion rejects multiline/over-limit inputs; table editing is not a general Markdown table parser; quote/fence context is conservative; duplicate or unsupported heading fragments do not acquire guessed destinations. Semantic limits are not raised to imply support for all editable files. Optional 5C.2 table ergonomics remains skipped, rather than treated as a release defect without a concrete reproduction.

## D. Performance results and methodology

### Current final controls

Frozen production JAR SHA-256: `6ee56710a5c6961860d8533bd916f9deef4ca445df71dadf5a9fa550c12bf3ee`. Host: Apple M3 / Mac15,12, 16 GiB RAM, macOS 26.5.2, JDK 21.0.13+9, Maven 3.9.11, Retina desktop. Source controls identify JetBrains Mono 16/Shed Light, preview closed and no external LSP clients. Initial host load was approximately 2.71/4.16/3.88. Environment/pressure/swap observations, exact commands and artifact/probe hashes are archived. No unrelated applications, system settings or caches were changed.

Established tools were reused unchanged. Five fresh desktop JVMs use separate homes, 64–512 MiB heaps and a 20,000-line source fixture. Filesystem/font caches are uncontrolled: **fresh process is not cold-cache startup**. The desktop edit control measures document mutation plus synchronous visible paint, not the full input path. The Markdown UI control separately measures controller/native-action dispatch through attributed completed Swing paint, 100 samples per size after ten warmups. Neither measures physical keyboard-to-photon latency.

| Five-launch desktop control | p50 | Observed nearest-rank p95 / maximum | Existing gate | Verdict |
| --- | ---: | ---: | ---: | --- |
| Window visible | 1,641.54 ms | **3,602.04 ms** | ≤2,000 ms | **Fails this sample set** |
| Source forced paint | 1,924.54 ms | **4,118.04 ms** | ≤3,000 ms | **Fails this sample set** |
| Full UI ready | 1,942.54 ms | 4,139.04 ms | ≤10,000 ms | Meets measured gate |
| Document edit + forced paint, 125 samples | 1.257 ms | 2.485 ms | ≤16 ms | Meets this control |
| Picker ranking, 125 samples | 7.389 ms | 8.458 ms | ≤50 ms | Meets ranking control, not complete picker latency |

The first launch missed the visibility/source budgets; the remaining four were approximately 1.62–1.64 s visible / 1.90–1.94 s painted. **The first observation is retained**, not discarded as warmup or labeled a controlled cold launch. Its cause is not attributed from elapsed time. There is no new startup-regression claim without a matched older-artifact experiment, and no full startup certification from five processes on one host.

| Current mixed-ASCII Markdown | Completed-paint p50 / p95 / p99, n=100 | Sampled active peak RSS |
| --- | ---: | ---: |
| 100 KiB | 0.658 / **0.869** / 0.954 ms | 723.11 MiB |
| 1 MiB | 0.800 / **1.130** / 1.422 ms | 751.47 MiB |
| 10 MiB | 1.712 / **2.359** / 2.823 ms | 957.59 MiB |

All three ordinary typing controls pass 16 ms. The 10 MiB fixture is editable and restores exact source; its size exceeds semantic-analysis eligibility. Active peak RSS is a different boundary from the desktop post-ready observation below. It must not be subtracted from heap or presented as settled idle RSS. No whole-product memory pass follows from these source timings.

The three separate Markdown children accepted the probe's first controller/native-action edit at **1.907 / 1.977 / 2.066 seconds from launch** for 100 KiB / 1 MiB / 10 MiB. These are single ordinary-source observations with preview closed, not five-launch distributions, physical keyboard latency or Unicode input-readiness qualification. They do not replace the failed first desktop launch.

### Carried evidence, explicitly not new 7C trials

No production code or packaged artifact changed after 7B.2, and the 7B.3/7B.4 controls identify the same current JAR. Repeating their expensive source stall profiles would add little to this decision. Earlier reports retain their own hosts, artifact epochs, fonts, instrumentation and sample counts.

| Area | Relevant evidence and current conclusion |
| --- | --- |
| Production Unicode | 7B.4 **stock controls**, 1 MiB mixed first edit **6.39–6.54 s**, first complex insertion into ASCII **4.44–4.61 s**; warm mixed p95 7.30–8.16 ms. Production remains defective. Candidate values 2.25–5.19 s are isolated experiment results and are **not** production improvements. |
| Highlighting | 1D preserved lexical checkpoints/viewport-only tokens; historical Intel 10 MiB end convergence p95 136.67 ms includes 120 ms debounce, not EDT typing. 7A M3 adverse fence-worker convergence around 39 ms is a separate worker metric. Current regression passes; no new convergence distribution is claimed. |
| Semantic analysis | Existing bounded, on-demand shared snapshots/coordinator and worker admission; stale results reject. Full parsing is not ordinary keystroke work. Oversize/dense input can be unsupported; post-AST caps are not hard transient-memory guarantees. |
| Link index/navigation | 7A 1,001-file fixture complete, outgoing/backlink query p95 0.007/0.006 ms; cold builds three observations, p95/max about 572 ms. These are index queries, not picker-to-paint or unlimited-project guarantees. |
| Index interference | 7A independent-project typing p95 2.24 ms with 50/50 genuine overlap samples. Same-project nominal indexing had only one actual overlapping sample; do not claim broad concurrent qualification from that label. |
| Async media | 7A 50/50 overlapping queued/running samples: typing p95 2.65 ms active / 2.08 ms inactive; bounded queue eight. Cold Mermaid request-to-result 3.62 s (one), changed warm diagrams p95 1.08 s (ten), cached result 0.208 ms (50). These are different operations, not a cached-versus-cold speedup estimate. |
| Preview | 7A 1 MiB plain open/queued input about **826 ms**; refresh EDT p95/max 453.6 ms, queued input 542.5 ms; resize-queued input 379.9 ms. A 100 KiB table refresh blocked about 353 ms; small rich first-use open delayed input about **2.08 s**. Functional tests passing does not resolve these P1 stalls. |
| Lifecycle | 7B.2's demonstrated index/callback/watch roots were corrected: 24 retired buffers/snapshots/Undo managers reached zero in the tested cycles; requested-GC heap approximately 248→31 MiB. This does not certify every future lifecycle. |
| Constrained heap | 7A 128 MiB / 1 MiB source persistence/recovery/Undo passed. Its 10 MiB diagnostic OOM retained duplicate verification state; disk integrity was independently checked. That experiment neither proves production corruption nor qualifies supported 10 MiB operation at 128 MiB. No system-RAM exhaustion or new low-memory claim was attempted. |

Current verification includes the existing rendering, lifecycle, semantic, navigation and highlighting suites, but no new preview/media/index performance investigation. Known source/preview stalls are carried forward without changing their severity or measurement boundaries.

## E. Reliability evidence

Final current suite: **1,565 total; 1,560 passed; five existing skips; zero failures/errors**. Tooling: **25 passed**. Packaged-only real Mermaid/math/image smoke: **PASS**. No assertion was disabled or weakened. The total includes isolated prototype tests; it is not a count of delivered user-facing features.

Tests cover checked edits/one-step Undo, fenced-code subtree containment, exact UTF-16 source, EOF/BOM/line endings, atomic-save failure handling, recovery, read-only capabilities, source/index/render non-mutation, shared splits, stale revisions/cancellation, normalized project boundaries, saved/unsaved backlinks, media leases/cancellation and Plain/Vim/Emacs routes. Current command and picker registrations were reviewed as well as pure service implementations.

The five environmental/opt-in skips are DOS hidden attributes unavailable, Fish unavailable, CMake/CTest/CPack unavailable, and two native/performance AI opt-in tests not enabled. Exact class names and reasons are in [verification.json](performance/markdown-tranche7c/verification.json). Native IME/accessibility, live external LSP interaction, all grapheme paths and Windows/Linux desktop behavior remain missing verification. Existing synthetic IME and protocol mocks do not replace those gates.

## F. Unicode blocker

**P1 source performance remains release-blocking**, including ordinary legitimate international text in editable-size documents. The failure is on production Swing complex-text layout, not only the rejected Markdown prototype. Repeated root-range break reads, i18n hierarchy reconstruction and glyph shaping/measurement are established call paths. The shared EDT means source layout can delay wider UI interaction. [Inference] Other source languages using the same surface can be affected too; this report does not claim a separately measured international-code-file matrix.

7B.1 corrected GB9c and one-step shared forward-delete Undo. It did not repair text layout or establish complete Unicode editing conformance. A fresh read-only diagnostic against the current packaged JAR and pinned Unicode **15.1.0** corpus reports **1,187 cases, 1,136 matches, 51 mismatches; all seven GB9c cases pass**. Every remaining failure record matches the previous report. The diagnostic exits normally while reporting failure counts; it is **not** a passing conformance test. Its independent helper timing is not promoted to a source-layout measurement.

The 51 are confirmed helper conformance deviations; their individual production effects require investigation. They are **P2 correctness qualification debt**, not all proven data-loss bugs and not an explanation for the layout stalls. Complete Unicode conformance and universal/native input correctness cannot be claimed while they remain. No Unicode support is disabled, font changed or workload limit silently altered to obtain a passing result.

## G. Memory qualification

The five fresh desktop controls observed **31.92–44.44 MiB used heap / 66–73 MiB committed heap** after the existing one-second post-ready settling interval, without forced GC. RSS at that observation was **617.50–673.45 MiB**. These fit the historical 384 MiB used-heap / 768 MiB RSS diagnostic ceilings in that fixture, not a product-approved settled/private-memory budget or proof of low-system-memory usability. The source benchmark's 10 MiB **active peak** of 957.59 MiB is reported separately.

7B.2 demonstrates real Java-object ownership improvements, rather than relying on RSS decay. Shared active buffers and dirty/recovery ownership were preserved. The later 7B.4 short weak-reference observations did not identify new retaining paths and were inconclusive in both variants; they do not overturn the stronger differently synchronized 7B.2 cycle evidence. General lifecycle/native-resource qualification is not thereby complete.

7A's matched JDK-only controls showed roughly **52→535 MiB RSS** during font/drawing initialization while Java heap stayed around 6 MiB. This attributes a material fixed cost to this host's JDK/macOS fonts/shared/native mappings, not a proven Markdown cache leak. NMT is incomplete RSS accounting; committed stacks, heap, resident mappings and private footprint are different quantities. Matched service/legacy-renderer media controls peaked around **1.27 GiB**, and later controls remained above 1.2 GiB. Those are active/profiling peaks, not settled idle memory or proof of a service-specific leak.

**Conclusion: heap-retention remediation is verified for the identified roots; whole-process/native memory and genuinely constrained-system operation remain unqualified.** Do not label all unexplained RSS a leak, claim memory equivalence from allocations, or change JVM limits to hide the problem.

## H. Three-tier readiness

| Level | Verdict | Evidence and limits | Required before claiming the next level |
| --- | --- | --- | --- |
| Personal/development use | **CONDITIONAL GO** | Productive tested source/IDE workflows, strong ordinary controls and source persistence/navigation. Developer accepts known first-Unicode/long-line stalls, preview delays and incomplete platform/memory qualification. This is a use-with-known-limitations judgment, not a new Unicode restriction or public readiness claim. | Keep limitations explicit; retain existing source/Undo/recovery contracts. General readiness still requires P1 remediation and broader qualification. |
| General public release | **NO-GO** | Eligible international-text editing and preview activity can freeze the EDT for seconds; no justified production fix. Native/cross-platform input and lifecycle/memory evidence remain incomplete. | Resolve the two P1s without losing source/glyph correctness, verify international first use and actual native input, reproduce stable startup, qualify target OSs and define/measure memory budgets. A narrower product contract would require an explicit separate decision and does not silently resolve Unicode. |
| Original performance-first ambition | **NO-GO** | Fast ordinary ASCII samples are insufficient; source/preview fail, fixed/active RSS remains material, low-system-RAM and cross-platform goals unproven. Rich live editing/very large editable files remain deferred. | Meet supported first-use/warm targets, preview interference limits, memory/pressure and cross-platform gates; separately demonstrate any deferred architectural features before describing them as delivered. |

The ≤16 ms ordinary warm target and proposed <100 ms representative Unicode first-edit target remain unchanged. Five-process startup results do not establish population percentiles. No tier's conditional use verdict authorizes shipping a failed performance claim.

## I. Architectural decision

| Option | Assessment |
| --- | --- |
| A: retain current production editor | Lowest migration risk; preserves proven source ownership, 1B/1D, keymaps, overlays and functional Markdown. Does not resolve Unicode/preview stalls. Appropriate for continued development, insufficient alone for general release. |
| B: separate source-layout architecture project | Potential benefit extends to international code/comments, long lines, wrapping and geometry beyond Markdown. Requires a different paragraph/view creation/extent strategy, not another local cache. High correctness, memory and cross-platform regression burden; no demonstrated latency guarantee or trustworthy delivery estimate yet. |
| C: limit performance claims while retaining code | **Recommended now**, alongside A's implementation. State exactly which measured source workloads pass and which fail; keep legitimate Unicode functionality available. This changes claims, not the P1 classification or release verdict. |

If B is funded separately, preserve `FileBuffer`, `PlainDocument`, immutable revisions, source offsets, save/recovery/Undo and existing UI. Likely seams include `WorkbenchStyle.TextAreaUI`, `SourcePlainView`, `EditorUiController`, `SwingEditorSurface`/`EditorSurface`, caret/line-number and IDE overlay geometry, wrapping and inline-completion presentation. It must verify stock glyphs/fonts, hit testing/selection, IME/bidi/graphemes, tabs/extents, splits, accessibility and Windows/Linux/macOS. Memory must include layout metadata, stale revisions and disposal, not just text bytes.

A bounded future alternative is paragraph-local, viewport-lazy source views with exact extent bookkeeping. Exact offscreen scroll metrics without a second unbounded layout/source cache are an unresolved requirement, not an assumed solution. This is a separate source-view project and **not** a restart of 4A typography/media presentation. No WebView, new editor engine, JDK patch/vendor code or default migration is authorized here. A maintained compatible JDK should first be screened with the existing reproducer; no particular newer runtime is claimed to solve the issue.

## J. Remaining defect and qualification register

| Priority / status | Evidence and impact | Explicit next action |
| --- | --- | --- |
| **P0: none observed** | No new loss/corruption reproduction in current tests | Keep persistence/recovery gates; do not translate absence of a finding into a guarantee |
| **P1 open: Unicode first-use / long-line layout** | Production 4.44–6.54 s representative first use; long Unicode lines also fail warm latency | Small maintained-JDK compatibility screen, then explicit source-layout project decision; no unverified local patch or Unicode disablement |
| **P1 open: synchronous preview stalls** | Hundreds of ms queued input; rich first-use seconds | Separate product decision on preview operating envelope, then narrowly reviewed remediation plan; no restriction or renderer change implemented here |
| **Resolved for demonstrated roots: 7B.2 retention** | 24-cycle owner/reference evidence and current lifecycle tests | Retain regressions; investigate new roots only if reproduced, not from RSS alone |
| **P2 open: 51 grapheme mismatches** | Fresh corpus reproduces exact old failures; specific production effects not all established | Rank/reproduce actual input defects independently; do not call them a layout fix |
| **P2: startup stability** | First of five current launches misses 2/3 s targets; remaining four pass | Preserve outlier; repeat/profile first-use under recorded conditions before attributing a cause or claiming stable target compliance |
| **P2 qualification: native/idle/active memory** | High fixed font costs, high active media/Unicode peaks, incomplete attribution | Set product memory budgets; paired uninstrumented/private-footprint and retained-owner checks; no general JVM tuning or leak claim from RSS |
| **P2 qualification: constrained heap / low system RAM** | 1 MiB/128 MiB historical success; 10 MiB diagnostic confounded | Bounded single-owner persistence/recovery repeat on current artifact; genuine system-pressure work only on an explicitly supervised spare-machine plan |
| **P2 qualification: OS/native integration** | macOS/JDK 21 tests; native IME/accessibility/live LSP and other desktops incomplete | Cross-platform packaged/native input, geometry, save/recovery and performance matrix |
| **P2 untriaged diagnostic warning** | 7B.3 caught line-number `BadLocationException` around EOF scrolling; source exact | Reproduce separately before classifying/fixing; not established corruption |
| **P2 historical verification gap: early-start recovery ordering** | Original baseline observed a recovery prompt for the benchmark's own very early edits during staged startup; no data loss reproduced | Recheck the applicable staged-start/configuration path before treating this as a current-default defect; do not infer corruption or ship a speculative fix |
| **P3 deferred research/features** | Live typography/concealment, inline media, editable 50–100 MiB, optional table ergonomics and appearance | Remain separate initiatives; no implied delivery from infrastructure or passing tests |

No numerical speedup is promised for proposed work. Native-memory uncertainty, incomplete platform coverage and deferred features remain visible without falsely classifying all of them as confirmed production defects.

## K. Appearance readiness

**CONDITIONAL GO for an isolated 8A feasibility study; NO-GO for shipping appearance features on this qualification.** This is narrower than 7A's refusal of baseline/release qualification: research can compare against a recorded failing baseline without concealing it. It does not resolve or waive any P1, and this tranche does not begin the study.

Use the current opaque Shed Light/JetBrains Mono artifact as the fixed control, preserving saved-preference precedence. Baseline includes the startup miss, one-second phase heap/RSS, active source peaks and failed Unicode/preview cases above. Study activation must remain isolated/opt-in; production appearance stays unchanged. A research result cannot claim that transparency or blur made the source editor qualified.

Future study gates:

- Pair opaque/control and experimental runs with identical fonts, themes, fixtures, display scale, heap/runtime and input attribution. Retain failed first-use/startup samples; do not compare only warmed ASCII.
- Measure CPU/EDT/completed paint, compositor/GPU behavior, IOSurface/private footprint/RSS and repeated window lifecycle. Transparency can require additional buffers and larger damaged regions; live blur can add capture/compositing and native-resource costs. No fixed overhead or available budget is assumed.
- Preserve the existing default; require bounded native handles/surfaces and deterministic disposal. Unsupported platforms/renderers need a safe opaque fallback. Native macOS work must respect AppKit/EDT threading and existing window ownership.
- Verify text/selection/diagnostic contrast, accessibility, screenshots, IME/keymaps/splits, resizing, multiple displays and HiDPI. Do not redesign the workbench or replace rendering infrastructure to obtain an effect.
- Keep focus/centering, automatic themes/preview, density and pane emphasis independently reviewable from transparency/vibrancy. Approval of six concepts is not proof that their combined resource cost fits.

The existing 2/3/10-second startup and 16 ms warm-source budgets remain the references. Because baseline startup and Unicode/preview already fail parts of qualification, a paired regression test alone cannot establish release readiness. Study-specific non-regression/resource thresholds must be agreed before implementation; no thresholds are relaxed here.

## L. Required future work

**Essential for general release:** address Unicode source and preview P1s, reproduce stable startup, qualify supported native input/OS behavior, establish memory budgets and verify disposal/low-memory persistence. Confirm any corpus mismatch's editing effect before choosing a narrow correctness correction. Keep canonical source, checked edits, keymaps and packaging regressions mandatory.

**Optional:** demonstrated table-navigation deficiencies or ergonomic polish. Optional 5C.2 remains skipped absent evidence. **Deferred research:** source-layout architecture, 4A-style rich live presentation, inline images/Mermaid/math, fully editable huge files and appearance. These are not secretly funded by this assessment.

## M. Repository verification

Only this new report and its evidence directory are added; the [inventory](performance/markdown-tranche7c/inventory.json) lists every file. Production, test, benchmark, configuration, dependency, previous evidence and rejected prototype files are unchanged from the start inventory. Git already contained uncommitted 7B.2/7B.3/7B.4 work; it is preserved, not presented as a clean checkout or as new 7C implementation.

Starting/final HEAD: `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`. README SHA-256 before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Current JAR remains byte-identical. `git diff --check` and new-evidence whitespace/checksum checks pass. No resets, cleans, reverts, commits or pushes were performed. 4A/7B.4 prototypes remain absent from the packaged application.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
"$MVN" test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche7c-run/production.jar --output /tmp/shed-tranche7c-run/packaged-smoke.json
python3 scripts/benchmark-editor.py --jar /tmp/shed-tranche7c-run/production.jar --label tranche7c-production --runs 5 --output /tmp/shed-tranche7c-run/desktop.json
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche7c-run/production.jar --mode ui --shapes mixed --sizes 102400 1048576 10485760 --samples 100 --warmups 10 --output /tmp/shed-tranche7c-source
git diff --check
shasum -a 256 README.md
```

The desktop command returned **1 for its recorded startup-budget failures**; the Markdown source command returned 0. Neither is hidden by the passing regression suite. The existing read-only `GraphemeQualificationProbe` was independently compiled against the JAR and run with `-Xms32m -Xmx128m` on the pinned corpus; its successful process exit is explicitly separate from its **51 failed conformance cases**. Exact commands, logs, provenance, environmental skips and failure records accompany the evidence.

## N. Final recommendation

Retain the current editor for conditional development use with precise claims. **Do not declare general-release or performance-first readiness.** The next smallest justified engineering task is a time-boxed, isolated **maintained-JDK compatibility screen of the existing 1 MiB Unicode reproducer**, preserving Java 21 compatibility, fonts, source, geometry and the same gates. Verify candidate runtime suitability first; do not upgrade production or vendor a patch. If it cannot remove the failure, make an explicit funding decision for the separate source-layout project rather than continuing local experiments.

The preview operating-envelope decision remains independently necessary. An 8A study may be separately authorized under the research gates above, but no appearance feature or architectural replacement starts automatically. This assessment stops here.
