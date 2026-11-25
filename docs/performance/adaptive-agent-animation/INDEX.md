# Adaptive agent animation evidence

The requested paced demonstration finished before implementation began. Adaptive presentation is now implemented and qualified for the bounded layouts below. Core commit: `5857344fd`; opt-in demo tooling: `1cf721284`. Starting HEAD was `cd7028a35edacd6e37107d7053091d827133e920`. See [behavior and usage](../../ADAPTIVE_AGENT_ANIMATIONS.md).

## Earlier checks completed first

- `earlier-workflow-timeout.json`: the broad existing workbench probe passed its first-file/two-file reading prelude, then timed out opening `Demo.java` at `AgentWorkflowDesktopProbe.java:485`. Exit 1. This remains unresolved.
- `earlier-review-context-failure.json`: a second broad run opened the proposal fixture, then the older replay client received `CONTEXT_CHANGED` while reading. Exit 2. This remains unresolved; no source checks were weakened or retries added.
- `earlier-paced-pass.json`: all stages of the requested friend demonstration completed, including reads at three source locations, edits, creation, modification, rename, deletion and the Git view. Exit 0, explicit `DEMO_PASS`, 73.116 s. This used the starting artifact, before adaptive animation implementation. Its disposable probe/runner are preserved as `.txt` evidence; they are historical tools, superseded by the reviewed opt-in probe.

An earlier temporary runner returned before observing completion; it was not counted as a pass. A separate normal-launch attempt also lost its process/connection after the tool invocation finished; its cause was not established. The supervised direct-Java acceptance below does not qualify that unsupervised launcher lifetime.

## Final tests and artifact

| Evidence | Actual outcome |
| --- | --- |
| `focused.json` | 15 focused Maven passes, zero failures/errors/skips. |
| `safe-suite.json` | 440 run: **433 passes, seven existing skips**, zero failures/errors. Includes final partial-grapheme and presentation-retirement changes. |
| `tooling.json` | **95 Python tests passed**; includes incompatible animation-demo argument rejection. |
| `package.json` | Final production JAR built successfully, existing compiler/shading warnings retained. |
| `packaged-media.json` | PASS, 14 Mermaid/math/image/runtime events against the final JAR. The intentionally invalid Mermaid case prints an expected renderer error before subsequent valid renders pass. |
| `desktop-final.json` | PASS, real Swing workbench + real authenticated external Java CLI + disposable filesystem/Git. All three edit modes and directions observed. Fixture removed, generated final Java source compiled. |
| `desktop-intermediate.json` | Earlier animation PASS, before final grapheme and explicit-read retirement guards. Kept separately, not substituted for final-source qualification. |
| `initial-compile-failure.json` | Intermediate duplicate-field compile failure; corrected before focused and final suites. |
| `identities.json` | Host, starting HEAD, implementation/tooling commits, source/JAR/test-tool hashes, README identity and selected capture hashes. |

Final JAR SHA-256: `4739ce10d67f023145fa75a6c756f63982ff6f5b98df95cede86706a96f03a16`. `AgentAnimationDesktopProbe` SHA-256: `fe9056ee7962c2e86db56eafdacdba57c7a8115d8d406c4a7e0c238b793af61d`.

The seven safe-suite skips remain six opt-in measurement tests and unavailable DOS hidden attributes. The unrestricted full Maven/native-dependent suite was not run. These results are not a full GUI regression pass; the broad failures above remain open.

## Rendering and source ownership

The final desktop run opens the real editor and Explorer, keeps management controls hidden, and performs:

1. Authenticated reads of `Demo.java` at UTF-16 offsets 0, 2,200 and 4,400.
2. Three separated small edits and five new methods, with character reveal and scrolling.
3. Removal of a 30-line block, with a bounded red removed-text preview and right-to-left erasure.
4. A larger replacement, with removal followed by incoming green lines and left-to-right sweep.
5. A new explicit read during an active tour. It returns exact source, retires the paint-only helper, displays reading direction and retains Follow. A later new file is followed normally.
6. File creation, notes modification, rename, whole-file deletion, and the existing Git view.
7. Synthetic human typing that cancels replay and retains ordinary Undo availability.

For every captured edit frame, the probe asserts that source, revision, dirty state, human caret/selection and Undo availability match their post-observation state. Unit pixel checks show increasing visible glyph ink and decreasing removed-text ink; they also assert zero document events, unchanged source/caret and no animation Undo entries. Grapheme tests cover supplementary/combining text, partial-cluster no-mask fallback and late callbacks after interruption. Reduced motion retains static presentation.

Selected captures are owned root-pane paints, not unrestricted desktop screenshots. PNG encoding happens off the EDT. They show intermediate frames; the final report and deterministic pixel/geometry assertions establish progression:

- [Small typing](typing-2.png).
- [Removal hold](deleting-2.png), [right-to-left erasure](deleting-4.png).
- [Rewrite in progress](rewriting-6.png), [replacement completion](rewriting-12.png).
- [Reading start](reading-1-top.png), [reading band later](reading-1-down.png).

The visual overlay does not temporarily edit the source to simulate typing. Document ownership, source coordinates and human Undo remain in the existing editor. Other panes and source queries see the actual completed external change. Whole-file deletion keeps existing Explorer/Git semantics rather than manufacturing an emptied source buffer.

## Responsiveness and resource scope

Host: Apple M3, 16 GiB RAM, arm64 macOS; exact OS is in `identities.json`. Existing JDK 21.0.13.1+2 and Maven 3.9.11, offline dependencies; no tool installation. Concurrent system load, native RSS and physical input/scanout were not measured.

Final desktop run lasted 98.473 s. The small ASCII fixture recorded 21 synthetic source-edit-to-completed-component-paint samples: first **0.820292 ms**, warm p95 across the remaining 20 **1.382625 ms**. This meets the existing 16 ms target for this workload. The initial edit interrupts an active adaptive tour. This is neither a large-document benchmark nor a claim that live animation and human typing compete indefinitely: human input intentionally wins.

The intermediate run recorded first 0.885334 ms and warm p95 0.638084 ms. These are two local observations, not a controlled historical speedup comparison. First complex-text layout, background-load interference and animation-specific EDT occupancy were not independently benchmarked.

No new worker, timer service, cache or document listener is owned by `AgentChangePlayback`. It reuses the existing finite tour/highlighter lifecycle. Limits remain 64 Ki UTF-16 source units, 32 diff hunks, at most 64 Ki removed units across hunks, a 1,024-unit removed preview, eight drawn removal lines and 100 visible-line masking iterations. The existing tour removes its input/focus/document listeners on completion/interruption. Tests exercise close, human interruption and late callbacks. The desktop fixture is removed after termination; no native-shell benchmark was admitted. This tranche did not establish a new retained-heap, native-memory or RSS equivalence measurement. Previous lifecycle evidence remains separate and intact.

## Reproduction

Raw reports preserve exact executed command arrays. Paths below name existing tools; substitute valid existing locations on another host. No shell-based fixture or real model is required.

```sh
python3 scripts/run-portable-tests.py \
  --jdk /private/tmp/shed-a222c1-tgamqlld/tools/jdk-21.0.13.1+2/Contents/Home \
  --maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --tests AgentChangeTourTest,AgentEditPresentationTest --output /private/tmp/new-focused.json
```

For the full safely admitted set, use the exact class list in `safe-suite.json`: every reviewed manifest entry except `ProjectFileOpenPrerequisiteTest`. The manifest binds reviewed tests by SHA-256; do not remove admission checks to run additional classes. Build the final package with the same runner's `--package-only` flag and a new JSON output.

```sh
python3 scripts/test-agent-desktop.py \
  --jdk /private/tmp/shed-a222c1-tgamqlld/tools/jdk-21.0.13.1+2/Contents/Home \
  --jar /Users/gongahkia/Desktop/coding/projects/shed/target/shed-2.0.0.jar \
  --output /private/tmp/new-adaptive-demo \
  --git-fixture --visual-only --animation-demo
```

Python tooling: `python3 -m unittest discover -s scripts -p 'test_*.py' -v`. Packaged media: `python3 scripts/test-packaged-markdown.py --jdk <existing-JDK> --jar target/shed-2.0.0.jar --output <new.json>`. Both were launched under a validated `native_fixture.Fixture`, inherited only its reviewed environment, set `PYTHONNOUSERSITE=1` and `PYTHONDONTWRITEBYTECODE=1`, closed stdin, and used a 120-second bound. The media probe additionally isolates Java home/preferences/temp. Direct desktop fixtures use fixed Java/Git, empty owned Git config/hooks, disposable project/preferences and bounded source/captures; this is not a general OS sandbox.

## Limits and compliance

Wrapped, bidi/complex text and ranges splitting grapheme clusters use ordinary source rendering plus directional highlights, without masking/deletion ghosts. This preserves existing shaping and source geometry; it does not qualify missing glyphs, Unicode first-edit latency or stock HTML-preview stalls. Windows/Linux native GUI and whole-process/native-memory qualification remain open. Raw filesystem reads remain invisible unless the agent reports them using the supported read/activity API. Observed diffs are replayed after arrival, not captured model tokens or keystrokes.

No new fonts, theme defaults, settings, dependencies, panels, source model or persistent workers were added. Saved preferences and prior evidence remain. README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. The original untracked `Created.txt` was preserved without inspection or staging. Local atomic commits only; no push. `git diff --check` passes.
