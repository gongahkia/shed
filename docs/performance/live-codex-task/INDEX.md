# Real Codex task and terminal-color verification

## Real model task

**The real coding task succeeded.** The user chose a local CLI agent using a cloud model. T3's configured Codex provider executed `gpt-6.1-sol` with low reasoning effort in a T3-created isolated checkout, based on `7e4b959d30446617be3785afa2b8be79fc7461b6`. This was not an offline model, scripted patch, or simulated agent conversation.

Task: correct valid bare TextMate `constant` and `storage` scopes in the existing Open VSX importer, retaining `constant.numeric` precedence. The agent independently inspected the code in a preparation turn, then added a synthetic ZIP regression, reproduced the failure, made the two predicate corrections and ran the requested safe tests. Its three-file diff was reviewed before integration:

- VsCodeThemeImporter.java: bare constant/storage mappings.
- OpenVsxThemeTest.java: one regression with three independent assertions, including numeric preservation.
- portable-fixture-tests.properties: only that reviewed test's SHA-256 changed.

The parent applied the reviewed patch to a clean main checkout, reran the same 35 focused tests and committed it as **`2d174116e`**. The original isolated checkout and its uncommitted agent diff remain available; nothing was reset, cleaned, pushed or discarded.

During implementation, a real Shed workbench watched the actual checkout's `src` subtree. The Codex task used the authenticated public CLI read/follow operations through a bounded Python launcher. Its read requests opened the source, test and manifest in the real editor. Ordinary filesystem edits produced the existing watcher-driven animations. No fixture edits were manufactured to make the demo look busy.

`live-ui.json` records ten distinct observed UI states: actual reading in the three files, chunk insertion in the test file, deletion/replacement replay for the manifest's changed hash line and typing replay in the production source. The directions include reading top-to-bottom, writing left-to-right and deletion right-to-left. This task did not create or delete whole files. Captures: [reading source](codex-reading-source.png), [test insertion](codex-test-insertion.png), [production edit](codex-source-typing.png).

The agent reported that an immediate post-write reread initially observed the previous editor revision; a later read observed revision 3. This is carried forward as the existing asynchronous filesystem-to-buffer observation boundary, not hidden as instantaneous synchronization. The parent independently reread the production range, verified both new predicates and compared the exact returned source against the edited file. `source-verification.json` records that successful revision-3 comparison and source hash. Captures and labels alone are not source truth or proof of which model acted.

The model's tests ran in its own safe Maven fixture, outside the terminal UI. No real shell command animation or agent-driven terminal execution was exercised. The existing `make demo` remains a scripted animation tour; this was a separate real-model task.

## Execution and cleanup boundary

The app used a disposable home, preferences, temporary files and a restricted executable search path containing only fixed Git. The authorized real source subtree was outside that disposable home, in the T3-prepared checkout; its path/base/clean state and the host JAR identity were checked before launch. This direct-Java path was assessed separately from native-shell benchmarking. It is not an OS sandbox against a same-user process.

Only the existing status/control bridge was started. The model used a helper restricted to follow/read/state/watch-state/intent/pause; reads were checked against the assigned source subtree. Credentials were kept in the owner-only disposable session directory, never embedded in a prompt or recorded as key contents. No real language server, interactive shell, personal shell startup/history, model installation or new transport was used by the observation harness.

The supervisor had a 420-second bound and only terminated its owned Java process if necessary. The task finished normally, the app/attachment closed and the private fixture was removed. `live-ui.json` records exit 0, PASS, reading/editing observed and `fixture_removed=true`. The preserved `run.py`, `LiveCodeTaskProbe.java` and `shed_ctl.py` are one-run evidence snapshots with fixed historical paths and identity checks, not a new supported launcher. They intentionally do not provide an unrestricted agent execution API.

## Terminal finding and correction

The terminal already supported ANSI, 256-color and RGB SGR and advertised `TERM=xterm-256color` / `COLORTERM=truecolor`. A real palette defect was found while answering the follow-up: fallback ANSI red/yellow/magenta came from editor replace/command/visual **backgrounds**. Shed Light and Shed Dark have identical mode backgrounds, so those three colors became identical gray after contrast adjustment.

The correction uses Shed's existing red/warning/navigation semantic hues for those fallback roles. Existing brightness/contrast behavior remains; Nib's dedicated ANSI palette and extended 256/RGB colors are preserved. No shell parser, shell plugin, startup configuration, input handling, new worker, dependency, font or source rendering was changed. Commit: **`ad108e83d`**.

Seven new in-process TerminalColorTest cases exercise the production emulator/settings: actual basic ANSI styles, distinct red/yellow/magenta, independent extended foreground/background, exact RGB, reset/default inheritance, 16-color foreground contrast in light/dark/Nib themes, official Nib backgrounds and font/theme refresh.

`terminal-fixture-assumptions.json` preserves the initial three failures: two new-fixture assumptions about Jediterm internals and the real gray-color defect. Jediterm resolves extended indexed SGR to RGB and represents reset by null overrides inheriting its unchanged defaults. The tests were corrected to assert those visible semantics and exact RGB/default values. `terminal-red.json` then reproduced only the genuine palette failure: three distinct expected hues versus one actual gray. The subsequent 26-test terminal-focused run passes.

`terminal-paint.json` contains same-host, same-font in-memory renders of the **production AnimatedTerminalPanel** before and after. The plain terminal screen contents are identical. No PTY or shell was started. Font: Monospaced 18, explicitly a diagnostic control rather than a change to the bundled application default.

| Theme | Before: red / yellow / magenta | After: red / yellow / magenta |
| --- | --- | --- |
| Shed Light | `#737374 / #737374 / #737374` | `#C64652 / #976C07 / #8B52C7` |
| Shed Dark | `#8D8F92 / #8D8F92 / #8D8F92` | `#D76E79 / #B28723 / #A67AD4` |

Captures: [Light before](terminal-before-light.png), [Light after](terminal-after-light.png), [Dark before](terminal-before-dark.png), [Dark after](terminal-after-dark.png). The comparison probe source is preserved as TerminalColorPaintProbe.java. These are component images, not native-shell or full-desktop qualification.

Shell command-line syntax highlighting remains provided by the user's selected shell and configuration, sourced by the existing terminal integration. Plain `cat` output does not acquire source-editor grammar coloring. No Zsh plugin or user dotfile was installed or changed. See [terminal usage and color commands](../../TERMINAL.md).

## Results

| Report | Actual outcome |
| --- | --- |
| model-red.json | 16 run, one intentionally failing test reproducing both missing bare scopes; numeric assertion passed. |
| model-green.json | 35 passed, zero failures/errors/skips. |
| parent-focused.json | 35 passed after integration into main, zero failures/errors/skips. |
| terminal-red.json | Seven run, one intentionally failing palette regression. |
| terminal-focused.json | 26 passed, zero failures/errors/skips. |
| safe-suite.json | **530 run: 523 passed, seven existing skips**, zero failures/errors. |
| tooling.json | **104 passed**. |
| package.json | Final JAR package succeeded. |
| terminal-paint.json | Before/after in-memory renders passed; identical screen text, distinct corrected colors, fixture removed. |
| packaged-media.json | PASS, **14 events** for packaged Mermaid/math/image smoke. |

The final suite selects all reviewed manifest classes except the existing ProjectFileOpenPrerequisiteTest exclusion. Seven skips remain six opt-in measurements and unavailable DOS hidden attributes. The unrestricted full Maven/native-dependent suite was not run. Windows/Linux desktop, actual shell/plugin behavior, physical input/scanout, whole-process native memory, Unicode glyph/layout and HTML-preview limits remain unqualified. No new typing-latency claim is made from this observation run.

## Reproduction and repository compliance

Host: arm64 macOS 26.5.2, Apple M3, 16 GiB; existing JDK 21.0.13.1+2 / Maven 3.9.11 and the existing bounded offline dependency cache. Raw test reports preserve exact command arrays and fixture paths. Representative safe tests:

```sh
python3 scripts/run-portable-tests.py --jdk <existing-JDK-21> --maven <existing-Maven> \
  --repository <offline-cache> --tests TerminalColorTest,TerminalControllerTest,AgentTerminalCommandTest,AgentTerminalDecorationTest,AgentTerminalTransportTest \
  --output <new-report.json>
```

The broader safe run uses the exact reviewed class list in safe-suite.json. Python tooling runs `python3 -B -m unittest discover -s scripts -p 'test_*.py' -v` with a validated disposable Fixture environment, disabled user-site imports, closed stdin and a timeout. Packaging uses the portable runner's `--package-only`; packaged smoke uses explicit JDK/JAR paths under the same safe environment. Native-shell confinement remains **NO-GO** and was not bypassed.

Starting main HEAD: `7e4b959d30446617be3785afa2b8be79fc7461b6`. Source/artifact/capture hashes and implementation commits are in identities.json. README before/after SHA-256 in both main and the task checkout: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Existing source ownership, Shed Light/JetBrains Mono defaults, preferences and prior evidence are preserved. No personal files were intentionally modified; no resets, cleans, reverts, pushes or dependency changes. Final `git diff --check` and clean main status are required before handoff.
