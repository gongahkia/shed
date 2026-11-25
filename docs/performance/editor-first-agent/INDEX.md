# Editor-first agent UI and smooth scrolling evidence

Starting HEAD: `8ccd8bf032e5da6bb7d49111e58d32bbe73cb6c5`, clean worktree. See [the implementation contract](../../EDITOR_FIRST_AGENT_VISUALIZATION.md) and [usage guide](../../LIVE_AGENT_VISUALIZATION.md).

## Commands and boundary

Use the existing JDK 21.0.13.1+2, Maven 3.9.11 and offline cache recorded in the JSON command arrays. No install is required; output directories must be new and canonical.

```sh
python3 scripts/run-portable-tests.py --jdk <JDK> --maven <Maven> \
  --repository <offline-cache> --tests <reviewed-class-list> --output <new.json>
python3 scripts/run-portable-tests.py --jdk <JDK> --maven <Maven> \
  --repository <offline-cache> --package-only --output <new.json>
python3 scripts/test-agent-desktop.py --jdk <JDK> --jar target/shed-2.0.0.jar \
  --output <new-directory> --git-fixture --visual-only --qualification --motion \
  --live-codex <existing-native-Codex-binary>
```

The reviewed class list is every entry in `src/test/resources/shed/portable-fixture-tests.properties` except `ProjectFileOpenPrerequisiteTest`. `SmoothScrollPaneUITest` is newly source-reviewed and hash-admitted; review/presentation test hashes were updated for their new assertions. Python tooling uses `python3 -m unittest discover -s scripts -p 'test_*.py' -v` under a validated disposable Fixture with user site/bytecode disabled. The original reviewed flow uses `--replay-payload docs/performance/agent-editor-control/codex-generated-demo.java.txt --git-fixture --visual-only`; the launcher uses `--launch-only`.

These reuse the existing fixed direct-Java/client, fixed Git and restricted low-reasoning actual Codex fixtures. Homes, preferences, files and Git config/hooks are disposable. Necessary existing model authentication is copied privately and retired; original content and mtime are checked without logging secrets. No interactive native shell, real LSP, personal repository mutation or unrestricted desktop capture is used. Native-shell benchmark admission remains BLOCKED.

## Final results

| Evidence | Result |
| --- | --- |
| `safe-suite.json` | 430 run, 423 passes, seven skips, zero failures/errors. |
| `review-and-motion-focused.json` | 28 passes covering proposal ownership, source marker rendering, smooth scrolling and cleanup. |
| `tooling.json` | 95 passes. |
| `package.json`, `artifact.json` | Final JAR and host identity. |
| `live.json` | Real-model desktop acceptance with panels closed, direct source tours, quiet watch, explicit tool access, configured ACP choices, shared scroll UI and existing editing/lifecycle matrix. |
| `review-regression.json` | PASS. Proposal stays hidden until explicit request; reviewed Apply/Undo/Redo/save and original external-change/Git flow pass. |
| `launcher.json` | PASS. Independent authenticated launch with opaque assets and prompt text-budget rejection. |
| `packaged-media.json` | PASS. Packaged Mermaid/math/image smoke, 14 events. |

The desktop prelude checks that neither fresh startup nor a synthetic saved-visible Agent dock opens management. Starting a watch and observing a newly created source file keep the dock closed. It verifies shared smooth scrolling, source/selection/revision preservation, an explicit `:agent changes` opening and a configured synthetic ACP choice without pressing Connect. It inspects 64 standard Swing scroll panes across the retained workbench after explicit access.

For the real model turn, the fixture closes the dock again before Codex writes. The source viewport tours three changed blocks without reopening management. Manual source scrolling cancels Follow. The retained matrix covers dirty-buffer conflict review, delayed file loading, 5,000-file watching/typing, edited moves, deleted Explorer rows, same-status Git patch updates, light/dark themes, density, focus/centering, splits, custom fonts, 24 decoration cycles and 12 watcher disposal cycles.

Unit cases test source/Undo/caret preservation, interpolation with deterministic frame time, precise fractional and horizontal input, reversal, bounds, nested handoff, reduced motion, disabling/hiding, programmatic navigation, document/view replacement, repeated input, listener removal and late wheel callbacks after uninstall. A pixel check places the first-line agent label outside the source glyphs. A separate contract case confirms that manually exposing a queued workspace proposal does not change its ownership to a conversation.

Only the app's owned showing root pane is captured. Exact source/state assertions supplement images; these captures do not qualify complete multilingual glyph rendering or physical input/scanout.

## Failed and intermediate attempts

- `initial-scroll-fixture-failure.json`: two fixtures supplied actual size but not a scrollable preferred extent, so an empty JPanel/JTree correctly had no range. The fixture now supplies both; assertions were retained. Frame tests also use the actual recorded start time rather than host scheduling delay.
- `initial-workspace-fixture-failure.json`: the new editor-first prelude started a project watch after opening only a file, before registering its workspace. The watch correctly remained unavailable. The prelude now explicitly adds its disposable workspace, as the real visualize launcher does.
- `first-desktop-pass.json`: earlier successful native matrix before final first-line label/late callback coverage.
- `first-model-pass.json`: earlier actual model success before the final defensive retired-wheel-callback check. Final artifact acceptance is recorded separately.

The seven safe-suite skips are existing opt-in measurements and unavailable DOS hidden attributes. The unrestricted full/native-dependent Maven suite was not executed. Terminal grids retain their own row/protocol scrolling; no terminal-grid smoothing or unsafe shell acceptance is claimed. Windows/Linux desktop, full RSS/native-memory attribution, long-soak behavior, Unicode first-edit/glyph qualifications and stock HTML-preview latency remain open. Source typing budgets are checked independently from intentional 140 ms wheel interpolation.

README SHA-256 before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No dependency, default font/theme or source-ownership change. Local atomic commits only; no push by this agent.

## Measurements and implementation commits

The final actual-model matrix inspected 64 standard scroll panes using the shared UI delegate. Its 5,000-file active-watch typing workload used 21 action-to-completed-component-paint samples: first 2.72 ms, warm p95 0.82 ms. The direct source tour reached Y offsets 0, 1,972 and 4,158 with the dock closed and human source/caret/selection state preserved. These are synthetic editor actions in the actual showing workbench, not physical device/scanout measurements or unmatched historical speedup ratios.

Twelve watcher cycles retained zero stopped watchers after bounded diagnostic GC; used heap was 86,239,176 bytes before and 17,394,952 bytes afterward. This does not establish whole-process RSS/native-memory equivalence. Smooth scrolling has no running idle timer; lifecycle cases verify model/view listener removal and ignored retired wheel callbacks.

Production commits: `661637672` (shared scrolling), `ac844f964` (source-label placement), `16ddc568d` (opt-in agent UI and reviewed ownership). `source-hashes.json` records the final implementation identity. No push by this agent.
