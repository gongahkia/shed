# Agent animation edge-case qualification

Added **17 deterministic regression tests** and an opt-in source-only desktop edge probe. Six reproduced lifecycle/display defects received local corrections. Component checks, the final desktop edge probe and the normal single-pane read-interruption flow pass. Broader GUI qualification remains **CONDITIONAL**: a checked open/read after multi-split layout changes returned `CONTEXT_CHANGED` and remains unresolved. This report does not classify that rejection as source corruption or attribute it to a specific production change without a paired reproduction.

Starting HEAD: `ecb07684d1c84aa3a20f50970ffae3f86e96b471`, with a clean working tree. Implementation/tests/admission: `8f9b66772`. Desktop tooling: `339f0b4c3`. Exact source/artifact/capture identities and host are in `identities.json`.

## Reproduced findings and corrections

| Finding | Evidence and local correction |
| --- | --- |
| Obsolete source tour clears a replacement tour's marker | Two tours share a pane; advancing the obsolete one removed `shed.agentTour` belonging to the newer one. A per-component owner token now limits cleanup to its owner. |
| Old viewport image survives theme changes | An installed light-theme image remained after the component changed background/foreground. Image handoffs now retire on those properties/UI replacement and reject changed font/colors/wrap geometry before installation. |
| Retired terminal command restarts its timer | Calling start after close restarted a timer whose callback immediately returned forever. Start now returns the finished result without restarting. No command is resubmitted. |
| Agent marker remains in shallow scrollback | A cursor near the top still fit geometrically after scrolling back one row. Agent cursor/badge bounds now reject every nonzero history origin. |
| Old terminal badge releases a newer owner's control state | Closing an older badge cleared the newer badge's agent-control property and could restore a closed badge later. Cleanup now checks ownership, skips closed predecessors and restores the previous control state. |
| Theme/highlighter replacement throws after a painted annotation | Swing deinstalls its old highlighter before delivering the property callback. Removing a painted layered tag then dereferenced the old highlighter's null component. Cleanup removes owned tags only while that highlighter remains attached to the same text component, then retires its references. No exception suppression or clearing of unrelated highlights. |

The initial focused run reproduced the first five cases: 22 run, one failure and four assertion-wrapped errors. The stronger native repeat exposed the sixth during actual theme changes and shutdown. A paint-first component test reproduced the same `DefaultHighlighter.removeHighlight` stack without a desktop. The JDK implementation confirms that deinstall clears its component before the callback. The first highlighter fixture did not paint the tag and used Swing's replaceable default caret; it is retained separately as a fixture-assumption failure. The corrected fixture uses Shed's BlockCaret and a painted/UIResource highlighter, preserving the selection assertion.

These changes add no UI feature, font, theme, dependency, worker, source model or editing command. They tighten ownership/retirement around existing presentations. The terminal remains a normal terminal with the existing explicit execution approval contract.

## Coverage added

- **Supersession:** old tour versus new tour/read; late frames after manual viewport movement, source edits, buffer replacement, pane context loss and pane disposal. Survivor source and human edits/Undo remain exact.
- **Geometry:** resize, horizontal scrolling, font and foreground/background changes, 1×/2× owned image painting, empty source, implied EOF, exact 64 Ki UTF-16 bounds, existing complex/wrapped limits and mixed-script fallback. Coordinate round trips remain document-relative.
- **Handoff images:** pending font/theme/wrap changes, viewport-size rejection, installed theme changes, finite expiry and explicit cleanup.
- **Lifetime:** 100 replay/interruption/image-expiry cycles assert restored input/document/viewport listener counts, stopped timers and cleared owner/replay/image properties. Another 100 terminal command retirement cycles and 100 terminal decoration/scaled-paint cycles check no restart, input duplication or leftover decoration state.
- **Terminal boundaries:** human cancellation after the final typed character but before Enter, closure while waiting/typing/running, cancellation before an already queued completion, and independence of the next command. Badge/cursor suppression covers selection, shallow scrollback, IME composition, hidden cursor and alternate screen; narrow/right-edge behavior and unchanged terminal text are checked.
- **Highlighter ownership:** replacement and UI reinstallation after painting; clear/close safety; source, selection and Undo preservation; unrelated owner highlights remain.

The five pre-existing manifest/allowlist discrepancies were reconciled after reviewing the tests: AgentTerminalCommandTest, AgentTerminalDecorationTest, AgentTerminalTransportTest, AgentViewportHandoffTest and EditorWorkspaceDocumentsTest. The new AgentAnimationEdgeTest is explicitly admitted too. Hash validation and native-test rejection remain intact.

## Final results

| Evidence | Result |
| --- | --- |
| `focused.json` | **57 passed**, zero failures/errors/skips. |
| `safe-suite.json` | **507 run: 500 passed, seven existing skips**, zero failures/errors. |
| `tooling.json` | **101 passed**, including admission/source hashes, unsafe-mode rejection and rejection of an uncaught desktop exception despite a PASS-shaped result. |
| `package.json` | Final JAR built successfully. |
| `desktop-qualified.json` | PASS, seven recorded checks: three density/layout combinations, 24 overlapping-tour retirements, theme-image retirement, passive external CLI read and human typing interrupting playback. Fixture removed. |
| `interrupt-qualified.json` | PASS, actual filesystem edit/replay followed by authenticated visible read, preserved Follow and exact source reply. Single source pane. Fixture removed. |
| `packaged-media.json` | PASS, 14 packaged Mermaid/math/image/runtime events on the same final JAR. Expected invalid-Mermaid diagnostics remain. |

The seven skips remain six opt-in measurements and unavailable DOS hidden attributes. The unrestricted full Maven/native-dependent suite was not run. Terminal driver/widget tests use in-memory fixtures without starting a PTY or shell; the native-shell benchmark remains **BLOCKED**. Windows/Linux desktop, physical HiDPI displays, long-session native-memory behavior and complete Unicode visual correctness remain unqualified.

The final source-only desktop probe uses the actual workbench with three shared source splits, Compact/Default/Comfortable density, light/dark themes, narrow/wide sizes and different editor fonts. It verifies source/revision/dirty/Undo ownership, retires stale callbacks without moving the replacement viewport, exercises theme-image retirement and makes a bounded passive read through a separate authenticated Java CLI. A passive read does not reopen the already-visible file. Owned root captures are [Compact](edges-layout-0.png), [dark/Default](edges-layout-1.png), [Comfortable](edges-layout-2.png) and [final](edges-final.png). The separate existing flow captures [writing before read](interrupt-before-read.png) and [the visible read takeover](single-interrupt.png).

## Failures retained and qualification boundary

- `missing-dependency.json`: the first offline run could not resolve the SwingX dependency introduced in the starting commit. No tests executed. A private disposable copy of the existing offline cache was supplemented with the already-declared SwingX painters/common artifacts and parent POM. Maven Central checksums were checked; URLs, sizes and SHA-256 identities are in `dependency-downloads.json`. No user Maven configuration/cache, production dependency or version was changed.
- `initial-failing-regressions.json`: the first five real regression failures before their corrections. `first-focused-pass.json` records their initial green run.
- `desktop-context-rejection.json`: the initial native probe passed its earlier layout/ownership stages, then the combined checked open/read returned `CONTEXT_CHANGED`. This remains a failed run and an open integration issue. The revised source-animation probe separates passive observation from navigation; it does not retry that rejected operation or weaken context/revision checks. The later single-pane visible-read interruption passing does not erase the multi-split rejection.
- `desktop-intermediate-pass.json`: the revised probe passed, before strengthening visible viewport/paint coverage.
- `desktop-highlighter-failure.json`: the strengthened repeat exposed the real highlighter exception. Closing the failed window hit the same exception; the exact owned probe supervisor was interrupted, its child terminated and fixture removed. Exit 130 is not a pass. `highlighter-reproduction.json` records the corrected deterministic red reproduction; `highlighter-fixture-assumption.json` preserves the preceding fixture mismatch.

The final desktop probe records uncaught exceptions and the launcher refuses acceptance if that marker appears, including asynchronous errors after an apparent PASS. No failed capture or earlier failure has been relabelled successful.

## Measurements and resource scope

Host: Apple M3, 16 GiB RAM, arm64 macOS; exact OS in `identities.json`. Existing JDK 21.0.13.1+2 and Maven 3.9.11. Final desktop/media workloads ran separately from Maven/tooling checks. System pressure, native RSS and OS input/scanout were not independently measured.

The final desktop edge run took **9.419 s**. Twenty-one synthetic source-edit-to-completed-component-paint samples in the three-split small ASCII fixture measured first **4.099417 ms**, warm p95 across the remaining twenty **2.465708 ms**, below the existing 16 ms target. Human input intentionally cancels playback. These figures are not a matched historical speedup claim, a large-file benchmark or a physical keyboard measurement.

Listener/timer/property cleanup is deterministic and bounded. This is not a GC-root census, hours-long soak or proof of equivalent whole-process/native memory. No new global registry, persistent worker or document-sized cache was added; existing image/source/range limits remain. Existing Unicode first-edit, missing-glyph and HTML-preview limitations are unchanged.

## Reproduction

Raw JSON reports preserve exact command arrays and fixture paths. Use an existing dependency cache containing the declared POM dependencies; the disposable supplemented cache used here is recorded in those arrays.

```sh
python3 scripts/run-portable-tests.py --jdk <existing-JDK-21> --maven <existing-Maven> \
  --repository <complete-offline-cache> \
  --tests AgentAnimationEdgeTest,AgentChangeTourTest,AgentEditPresentationTest,AgentViewportHandoffTest,AgentTerminalCommandTest,AgentTerminalDecorationTest,AgentTerminalTransportTest \
  --output <new-focused.json>
```

The full safe suite selects all hash-reviewed manifest classes except ProjectFileOpenPrerequisiteTest; use the exact list in `safe-suite.json`. Package with the same runner's `--package-only`. Python tooling uses `python3 -m unittest discover -s scripts -p 'test_*.py' -v`; packaged smoke uses `scripts/test-packaged-markdown.py --jdk <existing-JDK> --jar target/shed-2.0.0.jar --output <new.json>`. Both ran under a validated disposable Fixture, its reviewed environment, disabled user-site/bytecode, closed stdin and a 120-second bound.

```sh
python3 scripts/test-agent-desktop.py --jdk <existing-JDK-21> --jar target/shed-2.0.0.jar \
  --output <new-canonical-directory> --animation-demo --git-fixture --visual-only --edge-cases
python3 scripts/test-agent-desktop.py --jdk <existing-JDK-21> --jar target/shed-2.0.0.jar \
  --output <another-new-directory> --animation-demo --git-fixture --visual-only --animation interrupt
```

The edge mode rejects animation selection, looping, terminal handoff and incompatible fixture modes before creating output. It enables the existing source control attachment and does not start terminal execution. Normal `make demo` and individual demo targets remain available; no new visual feature or default demo scenario was introduced by this pass.

## Repository compliance

Changed production files: AgentChangeTour, AgentEditPresentation, AgentSourceHandoff, AgentTerminalCommand, AgentTerminalDecoration and AnimatedTerminalPanel. Tests: new AgentAnimationEdgeTest, extended AgentTerminalCommandTest/AgentTerminalDecorationTest, reviewed hash manifest and explicit admission list. Tooling: AgentAnimationDesktopProbe, test-agent-desktop.py and test_live_agent_tooling.py. Documentation: animation/terminal references and this evidence directory. Exact paths/hashes are in `identities.json`.

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Existing user commits, tracked files and earlier evidence were preserved. No new dependency or personal configuration change, no commit push, no reset/clean/revert. Local atomic commits only. `git diff --check` passes. Supported component behaviors qualify under these limits; general GUI/release readiness remains conditional on the open integration and previous platform/performance qualifications.
