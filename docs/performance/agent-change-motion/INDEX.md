# Agent-change motion evidence

Starting HEAD: `8d53bd889411eacf70b23601d8e9b861cb114920`, clean worktree. See [the implementation report](../../AGENT_CHANGE_MOTION.md) and [the usage guide](../../LIVE_AGENT_VISUALIZATION.md). No prior evidence was replaced.

## Reproduce

Use the existing JDK 21.0.13.1+2, Maven 3.9.11 and offline dependency cache recorded in the JSON command arrays. No installation is required. New canonical output paths are mandatory.

```sh
python3 scripts/run-portable-tests.py --jdk <JDK> --maven <Maven> \
  --repository <offline-cache> --tests <reviewed-class-list> --output <new.json>
python3 scripts/run-portable-tests.py --jdk <JDK> --maven <Maven> \
  --repository <offline-cache> --package-only --output <new.json>
python3 scripts/test-agent-desktop.py --jdk <JDK> --jar target/shed-2.0.0.jar \
  --output <new-directory> --git-fixture --visual-only --qualification --motion \
  --live-codex <existing-native-codex-binary>
```

The reviewed class list is every entry in `src/test/resources/shed/portable-fixture-tests.properties` except `ProjectFileOpenPrerequisiteTest`. The excluded prerequisite invokes a separately gated acquisition qualification; it is not silently admitted by this run. Seven suite skips cover six opt-in measurements and unavailable DOS hidden attributes. The full native-dependent Maven suite is excluded by existing native-shell admission policy.

Python tooling uses `python3 -m unittest discover -s scripts -p 'test_*.py' -v` in a disposable `native_fixture.Fixture` environment, user-site and bytecode disabled. Packaged media uses `scripts/test-packaged-markdown.py` with explicit JDK/JAR under that same isolation. Original proposal regression adds `--replay-payload docs/performance/agent-editor-control/codex-generated-demo.java.txt --git-fixture --visual-only`; launcher acceptance uses `--launch-only` instead of editing flags.

## Results and interpretation

- `safe-suite.json`: final source-reviewed suite, 415 run / 408 passes / seven skips / zero failures or errors.
- `focused.json`: initial new snapshot/tour/presentation cases, 23 passes. `watch-focused.json` and `reload-focused.json` retain subsequent narrow verification before the final full reviewed run.
- `tooling.json`: 95 passes, including rejection of a motion flag without its qualified disposable fixture before execution.
- `package.json`, `artifact.json`: final packaged application and host identity. Desktop, proposal, launcher and media results record the exact JAR hash.
- `live.json`: visible workbench with actual low-reasoning Codex editing three distant blocks, plus safe-config, moved-directory continuation, Explorer ghost-row navigation, refreshed Git patch, forced observation-before-reload ordering, scrolling, manual cancellation and existing editing/lifecycle matrix.
- `repeat-desktop.json`: a second independent visible-workbench run passed the moved-directory continuation and controlled reload-ordering scenarios after both corrections.
- `review-regression.json`, `launcher.json`, `packaged-media.json`: independent existing flow checks against the same packaged artifact, all PASS; media reports 14 events.

The native matrix uses bounded disposable files, a 5,000-file project, 21 editor-action-to-completed-paint typing samples, 24 source decoration cycles, and 12 watcher start/stop/diagnostic-GC cycles. It verifies source and Undo results rather than deriving correctness from pixels. Java used heap after diagnostic GC does not establish RSS/native-memory equivalence. No new startup service, idle animation, document-sized cache or keystroke density/layout work is introduced.

The accepted real-model run recorded first typing paint 3.28 ms and warm p95 1.05 ms. Baseline capture took 170.04 ms and the 142-change burst converged in 2,384.12 ms. The tour reached Y offsets 0, 2,075 and 4,268; the human caret/selection and source state were unchanged by presentation. Twelve watcher cycles retained zero stopped watchers after bounded diagnostic GC (used heap 79,329,800 before / 16,912,160 bytes after). These are single-host results; no unmatched historical regression ratio is claimed.

Only the application's owned showing root pane is captured. The numbered PNGs show Explorer marks/config/move grouping, the existing Git summary, and the distinct agent marker at the first/middle/final blocks after scrolling settles. These are not unrestricted desktop screenshots or proof of complete multilingual glyph coverage.

## Failed attempts and attribution

Failures are retained rather than counted as acceptance:

- `initial-tree-mount-failure.json`: Explorer was assigned after split rendering and could remain absent from the component hierarchy. Rerendering after assignment corrected the mounting order.
- `initial-watch-timeout.json`, `watch-repeat-timeout.json`, `watch-stale-registration.json`: the moved-folder watch sometimes stopped observing all subsequent changes; its old baseline remained intact.
- `watch-provider-lock-stacks.json`: both blocked stacks establish the JDK polling-provider map/key lock inversion while re-registering the moved directory. The stale watch still referred to `before-folder`. Retiring missing keys before subtree registration, and cancelling keys before provider close, address this specific path. No custom filesystem poller or native dependency was added.
- `late-tour-observer-failure.json`: Codex changed the fixture successfully, but the test waited until its final response before observing a finite animation. Observation now starts concurrently with the model turn.
- `reload-ordering-before.json`: source eventually contained the model output, Follow remained enabled and no marker appeared. Observation had reached the already-open buffer before its normal external reload. Presentation now waits for the authoritative reload event with current-context checks; a controlled reload barrier verifies this ordering explicitly.
- `live-tour-first-pass.json` preserves the earlier passing real-model run. Its transition captures are not substituted for the final settled-view assertions.

## Execution boundary

This reuses the previously qualified fixed direct-Java workbench/client, fixed Git and restricted actual Codex paths. HOME, preferences, temp/project files and Git configuration/hooks are disposable. Native interactive-shell benchmarks remain BLOCKED. No real language server, personal repository, shell-history probe, cloud model integration in the GUI or new external endpoint was introduced.

The model process uses its existing authentication copied only into its owner-private disposable home. Shell tools, hooks, plugins, browsing and agents are disabled; apply_patch remains enabled. Original authentication content and mtime are checked without logging credentials. The exact child environment is validated before launch. This is a trusted local fixture, not an OS sandbox against a privileged same-user process.

Windows/Linux GUI, physical input/scanout, native-memory attribution and long-soak behavior remain unqualified. Event coalescing, unsupported large/binary auto-open, physical-font glyph gaps and HTML-preview/Unicode layout qualifications remain explicit. No binary comparison or Git-operation timeline was added.

README SHA-256 before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Local atomic commits only; no push.

Implementation commits: `713ce5848` (config/moves), `582e9d4b2` (watch lifecycle), `479da1755` (visual motion and regressions). `source-hashes.json` records the implementation HEAD and changed-source identities. Final worktree compliance is checked after the documentation commit.
