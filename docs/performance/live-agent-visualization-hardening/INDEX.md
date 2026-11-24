# Live editing hardening: evidence

See [the qualification report](../../AGENT_VISUALIZATION_QUALIFICATION.md). Starting HEAD was `d44927fccdc15f5a762baf4935656f2689eb811e`, with a clean worktree. Code commits are `a3687d0b8` and `5022dd98b`. README before/after SHA-256 is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

## Reproduce

Use the already available JDK 21.0.13.1+2 and Maven 3.9.11 recorded in `environment.json` and the JSON command arrays. No installation is part of these checks. Output directories must be new and canonical.

```sh
python3 scripts/run-portable-tests.py --jdk <JDK> --maven <Maven> \
  --repository <offline-cache> --tests <reviewed-class-list> --output <new.json>

python3 scripts/run-portable-tests.py --jdk <JDK> --maven <Maven> \
  --repository <offline-cache> --package-only --output <new.json>

python3 scripts/test-agent-desktop.py --jdk <JDK> --jar target/shed-2.0.0.jar \
  --output <new-directory> --git-fixture --visual-only --qualification \
  --live-codex <existing-native-codex-binary>
```

The reviewed class list is every entry in `src/test/resources/shed/portable-fixture-tests.properties` except `ProjectFileOpenPrerequisiteTest`. Newly admitted DataLossRegressionTest and TestWatchServiceTest use disposable files and Java components; they do not launch terminals or servers.

The independent existing proposal regression uses `--replay-payload docs/performance/agent-editor-control/codex-generated-demo.java.txt --git-fixture --visual-only`, without `--qualification`. Launch-only uses `--launch-only`. Python tooling is `python3 -m unittest discover -s scripts -p 'test_*.py' -v` under the disposable Fixture with user site/bytecode disabled. Packaged media uses `scripts/test-packaged-markdown.py` under that same isolation policy.

## Final results

| Evidence | Result |
| --- | --- |
| `safe-suite.json` | 408 run, 401 passes, seven skips, zero failures/errors. Six opt-in measurements and unavailable DOS hidden attributes account for the skips. |
| `tooling.json` | 95 passes; includes rejection before execution for invalid modes, unexpected credential environment, outside working directories and symlinked Codex home. |
| `live-codex.json` | PASS, actual visible Swing workbench plus actual installed Codex 0.172.0 using low reasoning. All requested file outcomes independently verified on disk and in the live diff. |
| `review-regression.json` | PASS, original reviewed Apply/Undo/Redo/save, source pulse, external A/M/D/R, isolated Git patch and cleanup. |
| `packaged-media.json` | PASS, packaged Mermaid/math/image smoke, 14 events. |
| `launcher.json` | PASS, direct external Java launcher/authenticated watch status; one fresh-process sample 2,018.57 ms, not a cold-cache percentile. |
| `package.json`, `artifact.json` | Packaged artifact identity. Final desktop, launcher, review and media records identify the same JAR. |

On the final live run, baseline capture took 209.49 ms and the atomic/multi-file burst converged in 2,339.15 ms. The 5,000-file active-watch workload used 21 typing samples: first 3.30 ms and warm p95 1.02 ms. This measures editor actions to completed component painting; it does not measure physical input or scanout. No unmatched historical performance ratio is claimed. OS event delivery contributes to elapsed update time; a one-second service batch bound does not promise one-second filesystem-to-screen latency.

The GUI collision used the same dirty document and two different external versions. Rechecking the acknowledged version did not reopen the dialog; the newer version did. Ordinary Save continued to reject overwriting. Exact Undo/Redo and source bytes were checked. A separate deterministic worker barrier queued a real file load, then changed the human source before releasing it; Follow did not install into the changed context.

The native matrix includes light/dark, narrow and wide windows, three splits, Compact/Comfortable density, focus/centering, custom editor/UI fonts and a source hit test. There were 24 decoration start/clear cycles and one actual animation expiry. Twelve complete watcher cycles retained zero stopped watcher objects after bounded diagnostic GC. Post-diagnostic used Java heap was 17,317,216 bytes; no whole-process RSS/native-memory or long-soak equivalence follows from that result.

## Native safety boundary

The application and fixed Git processes use a disposable home, config, preferences and project. Git hooks/config are empty and prompting/signing/fsmonitor are disabled. Codex receives the exact checked child environment, ignores user config/rules, is ephemeral and uses workspace-write sandboxing. Shell tools, shell snapshots, agents, hooks, plugins, browsing and apps are disabled; its normal tool host remains enabled for apply_patch. No shell command event is accepted as a successful restricted run.

Only the necessary existing Codex authentication file is copied into the owner-private disposable home and retired afterward. No credential appears in arguments, ordinary logs or committed evidence. The monitored original auth content and modification time matched before/after. This is not a claim that reading a file preserves every metadata field, nor a sandbox against an already privileged same-user process. Personal shell history was not read as an unrelated integrity probe.

Java/Codex requests and runner execution have bounded deadlines; the runner terminates only processes it created. Fixture removal and zero Shed descendants are recorded. Native-shell benchmark admission remains BLOCKED. No real language server or interactive shell was launched. Windows/Linux desktop, whole-process native attribution and parent-runner-crash soak behavior remain unqualified.

## Failed and incomplete attempts

- `initial-watch-tests.json`: the original two-second continuous-write test timed out. That interval confounded provider polling with service batching. The revised check allows six seconds while the writer remains active; the maximum-batch contract is also directly visible in the service. Do not treat the initial timeout as a valid paired macOS latency improvement.
- `first-desktop-finding.json`: the old probe returned PASS despite an uncaught ConcurrentModificationException at RecoveryController.checkForExternalChanges after View Both opened a scratch tab. This is classified as failed qualification. Buffer-list snapshot iteration corrects that path; the final probe fails on uncaught desktop exceptions.
- `first-incomplete-client-probe.json`: the runner stopped before reaching the model phase without preserving enough phase diagnostics. Cause remains unverified. Failure-log preservation was added; this attempt is not acceptance evidence.
- `proposal-context-rejection.json`: the old proposal replay received CONTEXT_CHANGED during UI activity. Production context checks were retained. The ordinary-filesystem qualification was separated from that route, and the original route also passed independently afterward.
- `visual-state-assumption.json`: the first extended assertion assumed Undo made the dirty flag false. The revised visual check compares the actual incoming dirty state, source and revision. It also exercises the repeated-conflict notification path explicitly rather than leaving a dialog undiscovered.
- `disabled-codex-host.json`: Codex exited normally but made no changes because disabling its tool host disabled apply_patch. The ordinary tool host was restored, while shell/tool permissions and workspace sandboxing remained restricted. The harness now requires actual expected file changes in addition to CLI exit status.
- `contrast-before.json`: the regression asserted 4.5:1 against the actual line tint and failed before the foreground calculation was corrected. It passes in `safe-suite.json`.

## Captures

The numbered PNGs are only the application's owned root pane at its actual display scale. They include both-version conflict review, light/dark splits, focus/centering, custom fonts and the final real Codex diff. Screenshots do not replace exact source/disk assertions or establish complete multilingual glyph correctness.

The final report carries the remaining omissions: safe dotfiles, edited-rename/folder grouping, binary comparisons, metadata-only changes, agent Git operation history, per-hunk revert and durable session history. The watcher also has a 4,096-directory registration bound and depth 32; dynamic registration exhaustion and substantially larger directory trees are not qualified by this fixture. Do not use these results as a complete filesystem audit guarantee.

No dependency or default theme/font change. No README edit, push, external repository mutation or implementation of a new engineering track.
