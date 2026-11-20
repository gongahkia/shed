# Track B0 evidence index

Read [WHOLE_IDE_AUDIT.md](../../WHOLE_IDE_AUDIT.md) and [the 37-entry inventory](../../WHOLE_IDE_CAPABILITIES.md) for decisions and scope. This is investigation/measurement only. Use `gzip -dc FILE.gz`. No application JAR, heap dump, credentials, personal source document or unrelated desktop image is included.

## Regression and artifact identity

- `full-tests.log.gz`, `full-results.json.gz`: full direct/headful Maven run, 1,621 total / 1,616 passed / five existing skips / zero failures/errors. Exact suite/skip identities retained.
- `focused.log.gz`: 124 LSP/process/terminal/project/API/document/recovery tests passed, no skips/failures/errors.
- `tooling-final.log.gz`: 51 tooling tests passed, including four new finite-plan/output-safety checks. `tooling.log.gz` is the earlier successful run.
- `packaged-smoke.json.gz`, `packaged-smoke.log.gz`: real packaged image/math/Mermaid PASS; no development classpath can rescue the package.
- `artifact-verification.json`: all 1,426 compiled production classes match the frozen JAR. SHA-256 `95f27180c5d955217a8dd9e531d81fc4c1f25d6dc873d77fc0c51dca5ae67f4a`, identical to 8H.
- `baseline.json.gz`: starting clean status, HEAD and 1,782 source/script/doc/asset/build-input hashes.
- `repository-verification.json`, `external-head-history.txt`: final starting-file preservation, README hash, source/UI compliance, exact final status/HEAD and external commits preserved. This agent made no commits/pushes.
- `capability-inventory.json.gz`, `tool-availability.json`: checked source/test owners and dependency qualifications; executable availability is not runtime qualification.
- `manifest.json`, `checksums.sha256`: complete evidence inventory and hashes. Manifest excludes itself/checksums; checksums exclude only themselves.

## Current bounded controls

- `final-matrix-report.json.gz`, `final-matrix-plan.json.gz`, `matrix.log.gz`: all thirteen first-matrix children (one services + twelve desktop scenarios), exact probe/fixture hashes, host/JDK/load/pressure/swap/display observations, argv, 250 ms RSS samples and raw events. Twelve children exit zero; the interference child stops on a **harness success-predicate error**, not a production search failure. Visual exit zero is not a clean-paint certification.
- `final-matrix-*-stdout.log.gz`: all child output. First/warm input are separate samples; source mutations in these synthetic controls are deliberately undone exactly. Newline/delete/selection/scroll events report dispatch/layout/forced paint, not hardware input or key routing.
- `interference-verified-report.json.gz`, plan/log and child stdout: corrected `Status.SUCCEEDED` check; one bounded interference rerun, not retries until a production failure disappears. All search/Git/task/LSP worker trials finish, source exact; every measured warm dispatch starts while its bounded worker remains active. Explicit definition on the EDT separately delays the next queued event 210–216 ms for a 200 ms peer response.
- `startup.json`, `startup.log.gz`: unchanged established three-process desktop harness, parent-clock launch and separate source/full-UI proxies, 75 edit/forced-paint and picker-ranking samples. `SHED_WINDOW` is post-constructor, not a physical compositor event.
- `input-readiness-report.json.gz`, plan/log and stdout: additional one-process parent-epoch/first synthetic input+observed source-paint control. Its 1,917.54 ms is an upper observation bound, not native first-key acceptance certification.
- `summary.json.gz`, `summarize.py.gz`: per-phase statistics and counts, first versus warm split, observed percentiles only where sample counts permit, memory/states and the explicit failing/warning cases. Source raw events remain authoritative; do not sum separate timing percentiles or merge unrelated startup boundaries.

## Service and lifecycle boundaries

- First-matrix `services` events: six actual scan/index/search cycles each at 100/1,000/5,000 files; three synthetic local LSP cycles; 51 requests each for seven methods on the first client (first+50); twelve output tasks, twelve cancellations and twelve real PTY input/resize/output/exit cycles. Counts/expected results asserted. This is not a real language engine, complete task/build UI or terminal repaint benchmark.
- `services.log.gz`, `services-report.json.gz`, `services-driver.py.gz`, `fixtures.json.gz`: earlier exploratory **NMT-enabled service pilot**. Preserved separately; do not pool it with the published first-matrix uninstrumented controls or infer NMT overhead from unmatched phases.
- `shell-isolation-observation.json`: only environment-presence booleans and history-file metadata, not history contents. Native shell fixtures inherited history/startup state; history mtime overlaps the focused test, but no before-hash supports attribution/content comparison. Native runs stopped. **Do not replay the unguarded historical shell controls on a personal environment.** Existing-test isolation is a separate prerequisite.
- `extra-controls.json.gz`, `extra-controls.py.gz`, `extra-controls.log.gz`: exact additional JVM commands, bounded live diagnostic hold, shutdown request/parent observation, known-child liveness, RSS and diagnostic failures.
- `native-memory-stdout.log.gz`, `native-memory-vmmap.log.gz`: one NMT-enabled source-window control. `jcmd` times out at three seconds; no NMT category dump exists. `vmmap` succeeds. Footprint, mapped resident memory, RSS and heap are different metrics; no whole-process leak or profiler-equivalence claim follows.
- `shutdown-active-stdout.log.gz`, `shutdown-server-lifecycle.log.gz`: close with controlled task and LSP child. Task cancelled; server sees initialize/initialized/EOF, no shutdown/exit. Both known children are dead 0.5 s after parent exit observation. No noncooperative/unbounded child experiment or persistent orphan claim.
- `git-control.json.gz`: isolated unborn repository, explicit environment excluding global/system Git configuration, CLI status/diff samples and stage/name-diff/unborn unstage. Synthetic source restored in finally. No commits, remotes or credentials. This is not the complete Shed Git UI path.

## 8H anomalies and Unicode

- `final-matrix-mixed-visual-stdout.log.gz`: four production active-view gutter exceptions with exact pane/document identities and geometry: length 430, bottom mapping 431, not disposed; source unchanged. Separate unlaid-out real JTextArea/gutter maps −1 and reproduces the start-line failure. Full stacks retained; no production fix.
- `visual/*-native.png`: two actual PID/title-owned window captures. **Missing glyphs are visible.** Only the disposable test window is captured, no wallpaper/other applications.
- `visual/*-component-1x.png`, `*-component-2x.png`: same synthetic root at explicit graphics scales. Stronger 1× overprint is not sufficient to attribute actual source geometry; 2× and native pixels are not identical either.
- `capture-permissions.json`: read-only screen-capture preflight, `requested:false`. Existing permission was true. The skill's permission-request branch was not used because B0 forbids adding permissions.
- `capture-comparison.jsonl`, `CompareCaptures.java`: JDK ImageIO pixel comparison, measured native title-bar height 56 pixels. No font/geometry/color normalization, no false equality claim. Optional PIL unavailable; no installation attempted.
- `unicode-control.py.gz`, `unicode-report.json.gz`, `unicode.log.gz`: unchanged production `UnicodeLayoutProbe`, one fresh 1 MiB Java ASCII source, first complex input kept separate from preceding ASCII edits. Dispatch 5,767.01 ms, completed source paint 5,776.45 ms, ~44.74 GiB cumulative EDT allocation, exact final source/Undo. This is not a retained 44 GiB heap or a prototype improvement.

## Probe versions and reproduction

Reusable entry point: `python3 scripts/audit-whole-ide.py --jar JAR --output NEW_OUTSIDE_CHECKOUT_DIRECTORY`. `--plan-only` needs no toolchain/GUI/fixtures; `--group` and `--case` select only declared finite cases. `--capture-owned` explicitly requests the disposable owned-window attempt and never requests OS permission. Exact current commands are in the main report; each actual child argv is in its report.

- `probe-version-verification.json`: all **eight** distinct Java source variants recorded by the three matrices are preserved with exact matching SHA-256. No missing recorded variant. `probe-versions/<hash>-<class>.java.gz` holds those sources. Original failed and corrected interference predicates remain distinguishable.
- `probe-versions/current-guarded-WholeIdeServicesProbe.java.gz`, `guarded-compile.log.gz`: final new benchmark supplies `HISTFILE`, `ENV` and `BASH_ENV` as `/dev/null` for its fixed POSIX PTY fixture. Compiles successfully; **not runtime-retested** after the isolation finding. Published native samples use the explicitly archived earlier variants, not this guarded final source. Existing native tests were not changed by B0.
- `extra-probe-verification.json`, `probe-versions/extra-compiled-*.java.gz`: the two sources for extra controls were independently recompiled; all four produced class files match the actual measured `/tmp` classes byte-for-byte. Their helper logs predate the later added epoch field. No binary classes/JAR are checked in.
- `probe-versions/extra-WholeIdeServicesProbe.java.gz`: own earlier helper source retained separately for provenance, not a new production implementation.
- `diagnostic-limitations.json`: failed optional tool/setup checks and the explicitly corrected new-harness predicate. No hidden tests were weakened.
- `capabilities.py.gz`: inventory generator rejects nonexistent source/test names before publication; early guessed names were corrected against the actual checkout. Its output is an inventory, not code-coverage instrumentation.

Drivers with `/tmp`/repository-specific paths should be adjusted to new scratch roots when reproducing. Extract archived Java variants under their declared class basenames before compilation. Existing output directories intentionally reject reuse; preserve prior failures. Resolve native shell isolation before replaying historical/native-test paths. No new opt-in benchmark is wired into normal production startup/Maven tests. Prior Markdown and appearance evidence is linked and preserved rather than rewritten or pooled with these measurements.
