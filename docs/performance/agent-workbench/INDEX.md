# Agent workbench qualification

## Scope and verdict

The authenticated, project-approved workbench API extends the real Shed controllers with semantic scrolling, keyboard/text interaction, pointer controls, navigation and existing tools. It keeps one document/Undo history and one continuous branded presence. The chosen defaults are session-private clipboard, explicit project capability approval, and Shed-owned project pickers. Human-interruption arbitration was not expanded.

**Qualified scope:** source-reviewed in-process tests and bounded direct-Java owned-window/client fixtures on this macOS host. Native-shell benchmarking remains **NO-GO**; no real shell, language server or coding model was launched. The source-animation demo additionally uses a fixed Git executable only inside its disposable repository with isolated configuration/hooks. This is application-level path/permission validation, not an OS sandbox.

The API does not export arbitrary desktop controls or every custom Ex command. Native PTY, real VCS mutation latency, Windows/Linux and other display configurations remain unqualified. Legacy Git mutation callbacks can still synchronously wait on their backend. Existing glyph/Unicode first-use and HTML-preview latency limitations remain open.

See [the contract and commands](../../AGENT_WORKBENCH_CONTROL.md).

## Reproducibility

Host: macOS 26.5.2 arm64, eight logical CPUs, Temurin JDK 21.0.13.1+2. Each fixture supplies its own HOME, configuration, preferences, temporary directory, project and bounded execution deadline. Exact JVM arguments, source fixture hashes, machine load and sentinel checks are in the raw reports. Cache state is warm OS caches; fresh JVM startup is not cold-cache startup.

Toolchain used:

```sh
JDK=/Users/gongahkia/.local/share/shed-tools/jdk-21
MAVEN=/private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11
CACHE=/private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository
```

Safe regressions and packaged artifact:

```sh
python3 scripts/run-portable-tests.py --jdk "$JDK" --maven "$MAVEN" \
 --repository "$CACHE" --measure-lsp --output "$PWD/target/<new-report>.json"
python3 scripts/run-portable-tests.py --jdk "$JDK" --maven "$MAVEN" \
 --repository "$CACHE" --package-only --output "$PWD/target/<new-package-report>.json"
python3 scripts/test-packaged-markdown.py --jdk "$JDK" \
 --jar target/shed-2.0.0.jar --output "$PWD/target/<new-media-report>.json"
```

Owned-window end-to-end and denied-edit variants:

```sh
python3 scripts/test-agent-delegation.py --jdk "$JDK" --jar target/shed-2.0.0.jar \
 --output "$PWD/target/<new-workbench-directory>" --workbench --demo
python3 scripts/test-agent-delegation.py --jdk "$JDK" --jar target/shed-2.0.0.jar \
 --output "$PWD/target/<new-readonly-directory>" --workbench --workbench-readonly
python3 scripts/test-agent-delegation.py --jdk "$JDK" --jar target/shed-2.0.0.jar \
 --output "$PWD/target/<new-context-directory>" --workbench --workbench-context
python3 scripts/test-agent-desktop.py --jdk "$JDK" --jar target/shed-2.0.0.jar \
 --output "$PWD/target/<new-source-directory>" --git-fixture --visual-only --animation-demo
```

`make demo-workbench`/`make demo-ui` run the first tour; `make demo` plays the source tour followed by the workbench tour. `make demo-find` retains the small previous Find demo. Demos are deterministic fixed Java clients, not claims that a real model followed every instruction. No `--animation terminal` was run.

Matched performance:

```sh
python3 scripts/test-scm-performance.py --jdk "$JDK" \
 --jar target/shed-2.0.0.jar --baseline-jar target/workbench-agent-baseline.jar \
 --pairs 3 --output "$PWD/target/<new-performance-directory>"
```

The unchanged performance probe uses six alternating fresh JVMs, three baseline/candidate pairs, exactly five warmups and 100 timed typing samples per workload. Controls cover small, 100 KiB and 1 MiB source, selection/caret/scrolling, Undo/Redo, split cycles, buffer/file opening, and synthetic pending/installing/failed/5,000-row source-control workloads. Baseline JAR is preserved locally in ignored target output. No JVM heap/graphics or OS settings were adjusted to improve results. This run does not repeat the earlier 35-second idle-worker qualification.

## Regression coverage

The final source-reviewed Maven selection passed **680 tests out of 686; zero failures/errors, six skips**. Five are opt-in measurement tests (glyph, gutter, workspace status/metadata/navigation); the sixth is the existing DOS-attributes assumption on this macOS filesystem. The separately enabled LSP measurement ran. The new core/interaction/transport/folder tests contribute 40 passing tests. Selection names, exact command, output and fixture cleanup are preserved in `raw/workbench-safe-complete.json.gz`.

Python benchmark/tooling tests passed **134**, with zero failures/errors/skips (`raw/workbench-tooling-final.json.gz` and log). Their intentional malformed-input/permission rejection cases emit usage errors without failing the suite. The unchanged reviewed runner/admission manifest remains in effect; tests were not enabled by bypassing the native gate. The complete unreviewed Maven/native suite was not run.

The 134-test tooling invocation uses the same disposable Python fixture, without a shell child:

```sh
python3 - <<'PYTHON'
import os, sys, unittest
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path('scripts').resolve()))
from native_fixture import Fixture
fixture, previous = Fixture(), dict(os.environ)
try:
    fixture.validate()
    os.environ.clear()
    os.environ.update(fixture.env)
    os.environ.update(SHED_PORTABLE_FIXTURE_ROOT=str(fixture.root),
                      PYTHONDONTWRITEBYTECODE='1', PYTHONNOUSERSITE='1')
    suite = unittest.defaultTestLoader.discover('scripts', pattern='test_*.py')
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        raise SystemExit(1)
finally:
    os.environ.clear()
    os.environ.update(previous)
    fixture.close()
PYTHON
```

## Failures and evidence discipline

Intermediate compile, fixture and rendering-test failures are retained under `raw/`; passing reruns do not erase them. Notable failures were the real JTextField Enter action needing its actual listener instead of Swing's focus-dependent NotifyAction, table-editor showing-state assumptions in a headless fixture, and desktop fixtures initially targeting legacy SettingsInspectorDialog rather than the actual SettingsEditorDialog. Completion assertions now explicitly select the intended existing result rather than assuming its rank. Background file-picker/save completion is awaited separately from accepted query text.

A document-switch test assumed the viewport began at zero, although Swing could already scroll to the initial caret. It now records the starting position and changes the document identity within the same EDT turn before the animation timer; it requires `CONTEXT_CHANGED` and an unchanged viewport. No production exception handling or stale-context check was weakened.

An artifact identity check also caught an older packaged JAR after the final terminal-root change. Those preliminary desktop/performance results remain evidence for that earlier artifact only. Final qualification is tied to the rebuilt artifact and class comparison recorded below.

Earlier intermittent owned-window context-copy timeouts were not conclusively attributed. The bounded ten-cycle context-only fixture and later complete tours passed; that does not establish that every native desktop/menu condition is resolved.

## Live visual acceptance

`make demo` exited successfully on the final rebuilt JAR. The source tour passed with read/typing/chunk/deletion modes, all three directional sweeps, create/modify/rename/delete, search/navigation/target/verification/conflict intents, source-integrity checks and compilation of its resulting Java fixture. Its 21 bounded typing-paint observations had a maximum of 10.555 ms; this small demonstration sample is not the principal typing percentile benchmark.

The workbench half passed **50 stages / 126 real authenticated external Java CLI calls**, including real Find/checkbox controls, vertical/horizontal scroll without source/caret/selection change, Explorer divider drag, project-owned file/buffer pickers, Markdown preview, splits, project search, source typing/project Save As, Undo/Redo, private clipboard, the actual source context menu and View menu, checked file/folder changes, completion selection/Tab acceptance with one-step Undo, density/focus, synthetic source-control actions, typed Settings search/dropdown interaction and handback cleanup.

While its scrolling CLI request ran, 100 queue-to-completed-root-paint observations gave p50 **3.491 ms**, p95 **6.748 ms**, p99 **9.825 ms**, maximum **18.525 ms**. This is event-queue/completed Swing paint latency, not source typing or compositor latency. Keep the maximum visible; p95 success is not a promise that every frame meets 16 ms.

The synthetic external sentinel SHA-256 was `b415f4d4f4b7bc55c644d3a6ba5bad7939706cd445794b47d83abf18d916902e` before and after; modification time was also unchanged. The fixture was removed and remaining native child processes were zero. No private history contents were read as evidence. Owned-dialog and pointer removal are asserted at handback.

Representative bounded component captures: [Find and agent pointer](captures/01-find.png), [scrolled source](captures/02-scroll.png), [project search](captures/03-project-search.png), [workbench](captures/04-workbench.png), [completion selection](captures/05-completion.png). Find capture was inspected for provider logo/name, outlined cursor and control positioning. These are owned component paints, not unrestricted desktop screenshots.

The final navigation-only fixture also passed: source selection/read succeeded while an attempted write was rejected with `PERMISSION_DENIED`, preserving source, dirty state and selection. The final ten-cycle real context-menu/private-copy fixture passed with no remaining child processes; its sentinel and cleanup checks passed. See `raw/workbench-readonly-complete--evidence.json.gz` and `raw/workbench-context-complete--evidence.json.gz`.

The command's source/workbench reports are archived separately as `raw/make-source-report.json.gz` and `raw/make-workbench-evidence.json.gz`. Packaged Mermaid/math/local-image smoke also passed for the final artifact. Real terminal creation/execution, real VCS mutations and real cloud-model behavior are not claimed from these fixed clients.

## Final matched performance

All 28 existing practical regression gates passed on the final JAR: `4f195d7def1cdb1a88bee8ab5d9c63ce3763c58a1ecf0ccdcbcd0dd4ed3caeff`. All **1,649 compiled main classes match packaged bytes** (`raw/workbench-artifact-complete.json.gz`). Baseline SHA-256 is `3582568e1d9754b12719f897d00be794e76ee08bc092b7c42c2333954eb9f981`; the unchanged probe SHA-256 is `53ad3697619769ce7a715afd3d3ba72bd5a60c9ba6cee8724c7dc36abecfce1c`.

| Metric; median of three per-process values | Baseline | Current |
| --- | ---: | ---: |
| Small-file typing completed-paint p95 | 2.437 ms | 2.605 ms |
| 100 KiB typing completed-paint p95 | 2.237 ms | 1.923 ms |
| 1 MiB typing completed-paint p95 | 1.173 ms | 1.492 ms |
| Scroll completed-paint p95 | 1.714 ms | 1.732 ms |
| Split-cycle p95 | 2.864 ms | 3.528 ms |
| JVM startup to completed source paint | 1,886 ms | 1,909 ms |
| Settled used heap | 31.130 MiB | 31.376 MiB |
| Java threads | 22 | 22 |
| Open descriptors | 11 | 11 |

Individual candidate typing-workload p95 values ranged from **0.715–2.703 ms**, below the existing 16 ms target. Full first-use, warmup, maximum, queue-delay, load and memory data remain in `raw/workbench-performance-complete--comparison.json.gz`. Split/startup/heap increases stay within the unchanged gates; this sample is not proof of statistical equivalence or universal perceived speedup.

These common measurements describe the ordinary control-disabled application with existing source-control test activity. Active agent scrolling has the separate queue-to-paint observations above. Disabled-state idle CPU, per-interaction allocations, retained active-session heap, compositor latency and whole-process native/RSS attribution were not separately measured here. No permanent worker pool or parked-pointer animation loop was introduced; existing idle-worker retirement was not changed. A stable descriptor/thread count is not by itself proof that every resource lifecycle is leak-free.

## Repository compliance

Entry HEAD `7838cca08e620e89a83725f45fed80023d354b36`, initially clean. External user commit `506370447` appeared during implementation and was preserved. Production implementation commits are intentionally separate; no reset, clean, revert, discard or push was performed. Existing historical evidence was left in place.

README SHA-256 before and after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

Existing default theme/font, source ownership, keymap behavior and ordinary human controls are preserved within tested boundaries. UI changes are the authorized agent presentation/consent and control integration. Personal preferences/configuration were not used as fixtures. Synthetic sentinel hashes and modification times are checked independently. Session cleanup closes its own dialogs/pointer/private clipboard, not pre-existing windows; repeated fixtures report descendants and cleanup explicitly.


Implementation commits: `6675eaffd` (semantic engine), `99fd2fd19` (bridge/permissions), `7a22a986c` (shared presence), `1f45c435b` (project routes/owned pickers), `6993fdc6d` (workflow/lifecycle completion), `50cb4b933` (live demos). Documentation/evidence is committed separately. `git diff --check` passes.

Compressed raw files retain their original JSON/log content; `FILES.sha256` records the preserved evidence bytes. `summary.json` provides the final artifact/test/measurement manifest. Earlier failed artifacts are explicitly distinct from the qualified final JAR.
