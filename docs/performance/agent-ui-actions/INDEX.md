# Shared UI actions and continuous agent pointer — evidence

Qualified on macOS 26.5.2 arm64, JDK 21. Entry HEAD `7d303dab6986640c5ba317d524839fa198e717c0`; entry worktree was clean. No native shell, real language server, cloud model or unrestricted desktop capture was used. The native fixture is the separately qualified fixed direct-Java owned-window path; B0.1 shell confinement remains NO-GO. Windows/Linux desktop acceptance remains unexecuted.

Implementation and wire contract: [AGENT_UI_ACTIONS.md](../../AGENT_UI_ACTIONS.md).

| Evidence | Contents |
| --- | --- |
| `verification.json` | Artifact/README identity, exact selected-suite counts and skips, exclusions, paired performance summary |
| `raw-results.json.gz` | Full Maven/tooling/package/media logs, failed intermediate attempts, native client/fixture reports, six raw paired performance runs and samples |
| `01-pointer-click.png` | Rounded outlined pointer, Codex logo/name and real Find controls in Shed Light |
| `03-dark-narrow-pointer.png` | Dark theme, narrower window, real search navigation and bounded badge |
| `04-query-typing.png` | Mid-entry outlined I-beam with Claude Code's existing orange logo |

## Results

- Reviewed Maven selection: **642 cases, 636 passed, six existing skips, zero failures/errors**, 108 selected classes. Includes **21 new UI action/contract tests**. `ProjectFileOpenPrerequisiteTest` remains excluded as the retired legacy-path vulnerability characterization; it is not counted as passing or skipped.
- Tooling: **134 passed**. Rejected admission/argument cases intentionally print usage errors.
- Packaged Mermaid/math/image smoke: **PASS**.
- Fixed external Java CLI + real owned Shed window: **PASS**, 25 CLI calls. Agent and human Find-next handlers reached the same source offset. Canonical document identity, exact source, source revision, dirty state, Undo history and file bytes stayed unchanged. One idle session pointer remained between actions; attachment revocation removed it. Zero remaining child processes. Synthetic sentinel SHA-256 and modification time remained unchanged; fixture removed.
- New tests cover action allowlisting, bounded UTF-16 input, stale UI revisions, unknown/future commands and extra fields, existing grant separation, query interruption, source/pane validity, auth revocation, cancelled futures, duplicate requests, reduced motion, morph interpolation, continuous position, click-through behavior, sequential reuse and listener/overlay cleanup.
- Actual light/dark owned-component captures were inspected. No system screenshot/OS pointer automation was used.

## Performance control

The existing source-control performance driver ran **three paired comparisons / six fresh JVMs**, alternating baseline/candidate order, with its original fixture definitions and gates. Baseline JAR SHA-256: `1fedffb99194bbc633e7fc7bb37d68f0d5469725995e0a44fe61f9af2b356268`. Candidate: `3582568e1d9754b12719f897d00be794e76ee08bc092b7c42c2333954eb9f981`.

Each typing workload retains its first sample, five warmups and 100 measured completed-paint/input-queue samples per process. Raw p50/p95/p99 and host-load observations are archived. These are fresh-process, warm-OS-cache measurements, not cold-cache startup, statistical equivalence, native-memory accounting or real Hg/SVN backend qualification.

| Median of per-process metric | Baseline | Candidate |
| --- | ---: | ---: |
| Small-file typing p95 | 2.834 ms | 2.633 ms |
| 100 KiB typing p95 | 2.214 ms | 2.160 ms |
| 1 MiB typing p95 | 1.540 ms | 1.365 ms |
| Split cycle | 4.041 ms | 3.662 ms |
| Startup to completed source paint | 1,890 ms | 2,004 ms |
| Settled used heap | 31.133 MiB | 31.428 MiB |
| Threads | 22 | 22 |
| Open descriptors | 11 | 11 |

**All existing comparison gates passed.** Worst candidate typing p95 across the tested workloads/processes was 3.486 ms, below the existing 16 ms target. Startup and used heap rose modestly in these samples; this does not prove equivalence or a retained-object leak.

The external-client UI tour's uncaptured median elapsed times were approximately 108 ms for `ui-state` (12 samples), 424 ms for button actions (seven), and 771 ms for query entry (two). These include fresh Java CLI startup, transport and intentional animation; they are not dispatch/EDT timings, and the sample counts do not justify tail-percentile claims. Capture-bearing calls are excluded. No standalone active-pointer CPU/RSS/NMT or typing-during-query benchmark was added. Normal editing performance and finite controller/listener lifecycle were verified separately. Complex-text first-use latency remains open.

## Intermediate failures retained

1. Initial complex-script incremental query test exceeded the three-second action deadline. Complex queries now install intact rather than repeatedly shape incomplete scripts; the exact-string/grapheme assertions remain.
2. First native capture exceeded a four-million-pixel limit at 2× display scale. The owned-root capture uses the existing eight-million-pixel cap, without changing rendering or scaling.
3. Theme switching reproduced `DefaultHighlighter.removeHighlight` dereferencing a null component through `InlineFindBar.clearHighlights`. The bar now removes tags only from the highlighter still installed on its recorded text component. Dark/narrow/query navigation subsequently passed.
4. One native tour returned `CANCELLED` at the initial Find action. Its cause was not independently recorded; a whole fresh run later passed. No automatic retry or input-cancellation bypass was added.
5. An intermediate compile check caught a collision with AWT's deprecated `Component.location()` method. The diagnostic accessor was renamed `pointerPosition()`; final compile/tests passed.

These failed attempts remain in the archive. Debug stack printing used for attribution was removed. Existing assertions and safety admission were retained.

## Reproduce

Use the already installed toolchain and existing offline dependency cache. Paths used for this run:

```sh
JDK=/Users/gongahkia/.local/share/shed-tools/jdk-21
MAVEN=/private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11
CACHE=/private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository
```

Safe reviewed selection, using `verification.json` to obtain the exact class names (also present in archived Maven report):

```sh
TESTS=$(python3 -c 'from pathlib import Path; print(",".join(s.split("=")[0] for s in Path("src/test/resources/shed/portable-fixture-tests.properties").read_text().splitlines() if not s.startswith("ProjectFileOpenPrerequisiteTest=")))')
python3 scripts/run-portable-tests.py --jdk "$JDK" --maven "$MAVEN" --repository "$CACHE" --tests "$TESTS" --measure-lsp --output "$PWD/target/new-ui-safe.json"
python3 scripts/run-portable-tests.py --jdk "$JDK" --maven "$MAVEN" --repository "$CACHE" --package-only --output "$PWD/target/new-ui-package.json"
python3 scripts/test-agent-delegation.py --jdk "$JDK" --jar target/shed-2.0.0.jar --output "$PWD/target/new-ui-desktop" --ui
python3 scripts/test-scm-performance.py --jdk "$JDK" --jar target/shed-2.0.0.jar --baseline-jar target/ui-actions-baseline.jar --pairs 3 --output "$PWD/target/new-ui-performance"
python3 scripts/test-packaged-markdown.py --jdk "$JDK" --jar target/shed-2.0.0.jar --output "$PWD/target/new-ui-media.json"
```

The baseline JAR is preserved locally under `target/`; rebuilding it requires the recorded entry version. Do not compare against an arbitrary historical artifact. Each runner requires new evidence paths and isolates generated state.

Tooling was executed with `sys.dont_write_bytecode=True`, a disposable `native_fixture.Fixture` environment, `PYTHONDONTWRITEBYTECODE=1`, `PYTHONNOUSERSITE=1`, and `unittest.defaultTestLoader.discover('scripts', pattern='test_*.py')`; `fixture.close()` ran in `finally`. This is source-reviewed test tooling, not native-shell admission.

For the visible, paused tour: `make demo-ui`. The target was dry-run checked; its underlying direct-Java fixture was run twice successfully after the fixes. The pause-only `--demo` variant has not separately been timed as a performance benchmark.

## Repository compliance and limits

README SHA-256 before and after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Defaults, stored personal preferences and source ownership were preserved; tests used disposable preferences/projects. No new dependencies, OS port, control listener, native shell or persistent worker pool was added. The user-authorized atomic local commits were created; no push was performed. `git diff --check` passed.

The implemented UI surface is Find only. Broader IDE controls, external native shells, real model integration for these new commands, multilingual native capture acceptance and existing Unicode/preview latency issues remain outside this verification.
