# Agent buffer intentions: implementation and qualification

Implemented nine vendor-neutral source-buffer presentation kinds: search, navigation, edit target, move, symbol rename, formatting, revert, verification and conflict. See [the CLI, wire contract and harness instruction](../../AGENT_BUFFER_INTENTS.md). Core implementation: `208d5b083`; expanded Make demo: `7d500630c`. Starting HEAD: `ef6195de1c7e6b08e402c788e36b5a18ddf095b7`.

## Architecture and ownership

`AgentBufferIntent` defines the immutable bounded event and strict kind/status/range validation. `ShedControlCli` adds `ctl intent [path]` to the existing authenticated attachment. `EditorControl` verifies the existing instance/project/pane/document/revision/context grant, validates UTF-16 grapheme ranges on its background dispatch, then installs through `AgentWorkspaceController` on the EDT. `WorkspaceStatusProtocol` validates the echoed reply. No extra transport, dispatcher, agent SDK, vendor-log watcher, background pool or source model is introduced.

`AgentEditPresentation` reuses its existing finite 50 ms Swing timer, highlighter, theme roles and separate agent marker. It outlines matches/ranges, labels the focused one and scrolls the viewport over six ticks. Move endpoints distinguish amber source from green destination. Verification states distinguish checking, passed and failed; labels explicitly say the client **reports** a check. Conflicts mark a reported conflict, without becoming a file lock or invoking an editor mutation.

Each event replaces the old decoration. Human key/mouse/wheel/focus activity, document changes/replacement, hiding, disposal and attachment revocation retire it. Dynamic input listeners are removed on clear; no additional document listener is installed per event. Animated decorations expire in approximately six seconds. Reduced motion uses immediate reveal/static feedback without an animation timer, until replacement/cleanup. A source intent retires old change tours through the existing presentation-generation mechanism and does not disable Follow.

The endpoint is presentation-only. It never searches, moves code, renames symbols, formats source, invokes Undo, runs tests or resolves conflicts. The agent performs its normal authorized workflow and reports the intent/outcome separately. Existing filesystem changes still use diff replay; read-only navigation/status attachments cannot gain control by naming this operation. There is no automatic interception of Codex/Claude Code/OpenCode tools.

## Verification

| Evidence | Outcome |
| --- | --- |
| `focused.json` | 30 focused passes, zero failures/errors/skips, before the additional cross-file move case; final suite includes that case. |
| `safe-suite.json` | **449 run, 442 passes, seven existing skips**, zero failures/errors. |
| `tooling.json` | **95 passes**, including explicit test-admission manifest/source hashes. |
| `package.json` | Production package built successfully. |
| `packaged-media.json` | PASS, 14 packaged Mermaid/math/image/runtime events against the same production JAR as the desktop run. Expected invalid-Mermaid diagnostics remain in the log. |
| `desktop.json` | PASS through **`make demo`**, real macOS Swing workbench and separate authenticated Java CLI processes. All nine intention kinds, all three edit modes/directions, both move anchors and all check states observed. Disposable fixture removed; final generated source compiled. |
| `identities.json` | Starting HEAD, implementation/demo commits, source/test/tool/JAR identities, host and capture hashes. |

Nine new deterministic tests cover bounded event parsing, unknown/extra fields, UTF-16/grapheme/EOF rules, source-size rejection, all kinds, all check states, distinct fallback colors, invalid identities, stale source and human cursor changes, navigation-only permissions, disconnected/revoked clients, exact buffer/caret/dirty/Undo/disk preservation, invalid CLI options before opening, revision checks, cross-file move anchors and multiple client display names. Twenty-four presentation/expiry cycles assert restored listener counts, stopped timers and unchanged source/selection/Undo, followed by reduced-motion and document-replacement cleanup.

The seven skips remain the six opt-in measurement tests and unavailable DOS hidden attributes. The unrestricted full Maven/native-dependent suite was not run. Previous broad GUI file-open/context timing failures remain open in [the preceding qualification](../adaptive-agent-animation/INDEX.md); this is not a claim that those failures are repaired or that the IDE has general release qualification.

Two intermediate failures are preserved: `initial-compile-failure.json` records a test-only wrong identity-method name and ambiguous Timer import; `initial-tooling-admission-failure.json` records the missing new class in the explicit Python admission list. They were corrected without removing assertions or weakening the native gate. Subsequent final checks passed.

## Actual live flow and visual evidence

The fixed direct-Java demonstration first completes the existing reads, small typing, block deletion/rewrite, creation, modification, rename, deletion and Git workflows. It then opens Demo.java and pauses automatic Follow during the semantic presentation stages so unrelated observations do not replace the intended examples. All intent commands still use the real external authenticated CLI. Source edits for formatting/reversion are ordinary disposable filesystem writes; the intent itself is checked against unchanged source/revision/caret/selection/dirty/Undo before and after every display.

Client display names Codex, Claude Code, OpenCode, Any harness and Local agent exercise exactly the same implementation. No real coding model is called and no private provider authentication is accessed. The check stage uses the in-process Java compiler on valid and intentionally invalid disposable examples before reporting their actual outcomes. The conflict stage creates a synthetic human draft, verifies presentation preserves it, then uses real editor Undo to restore the exact prior source.

The probe asserts each focused range is visible after the scroll, checks the returned outcome and displayed kind/status, and captures only its own Swing root. PNG encoding is off the EDT. Selected owned-component captures:

- [Search matches](intent-search-active-1.png), [navigation](intent-navigate-active-0.png), [edit target](intent-target-active-0.png).
- [Move source](intent-move-active-0.png), [move destination](intent-move-active-1.png), [linked rename occurrences](intent-rename-active-0.png).
- [Formatting](intent-format-active-0.png), [reverting](intent-undo-active-0.png).
- [Checking](intent-verify-checking-0.png), [passed](intent-verify-passed-0.png), [failed](intent-verify-failed-0.png), [reported conflict with dirty human draft retained](intent-conflict-active-0.png).

Cross-file move source/destination requests are additionally verified through the authenticated CLI in the unit/integration fixture; the native demo's paired move anchors are within one file. These are intention visuals, not a claim to reconstruct semantic refactoring from arbitrary diffs.

## Performance and resources

Apple M3, 16 GiB RAM, arm64 macOS, existing JDK 21.0.13.1+2 and Maven 3.9.11, offline dependency cache. Exact OS and file identities are in `identities.json`. The live workload ran separately from Maven/media/tooling workloads; system load/pressure was not independently sampled.

The complete expanded demo took **124.624 s**. Twenty-one synthetic source-edit-to-completed-component-paint samples recorded first **1.099333 ms** and warm p95 over the remaining twenty **1.070166 ms**, below the existing 16 ms target for this small ASCII fixture. Human typing intentionally interrupts playback. This is not a physical keyboard/scanout or large/complex-document benchmark.

Twelve separate-process intention requests, including fresh client JVM startup and mutual authentication, took **104.684–441.920 ms**, median **142.965 ms**; the first was **104.684 ms**. With only twelve samples, no tail percentile is asserted. These are not EDT occupancy measurements or server-only latency. The labels are installed asynchronously from an off-EDT request; viewport motion uses the existing timer. No IPC/filesystem/compiler wait is introduced on the EDT by this feature.

State is bounded to 32 ranges, a 40-character display name, a known kind/status and at most 65,536 UTF-16 units of source validation, subject to existing complex/wrapped-layout limits. Each new event replaces one existing pane decoration. Explicit listener/timer cleanup is tested; no worker is owned by an intent. Disabled-state CPU/startup, retained heap, RSS, font/native resource attribution and cross-platform GUI performance were not newly measured. Passing listener-count tests or fixture removal is not a claim of whole-process memory equivalence.

## Reproduction

Raw reports preserve full executed command arrays. Use the same existing toolchain paths or equivalent valid installed JDK/Maven paths. No tool installation is required by the feature.

```sh
python3 scripts/run-portable-tests.py --jdk <existing-JDK-21> \
  --maven <existing-Maven> --repository <offline-cache> \
  --tests AgentBufferIntentTest,AgentEditPresentationTest,ShedControlCliTest,EditorControlTest \
  --output <new-focused.json>
```

For the full safely admitted suite, the exact class list is in `safe-suite.json`: all manifest entries except `ProjectFileOpenPrerequisiteTest`. Admission remains hash-bound to reviewed non-native tests. Package with the same runner and `--package-only`. Python checks use `python3 -m unittest discover -s scripts -p 'test_*.py' -v`; packaged smoke uses `scripts/test-packaged-markdown.py --jdk <existing-JDK> --jar target/shed-2.0.0.jar --output <new.json>`. Both were run under a validated `native_fixture.Fixture`, its reviewed environment, disabled Python user-site/bytecode, closed stdin and a 120-second bound.

```sh
JAVA=/private/tmp/shed-a222c1-tgamqlld/tools/jdk-21.0.13.1+2/Contents/Home/bin/java make demo
```

The recorded live invocation wrote `target/agent-demo-20261125-154614-84565/report.json`. Each new `make demo` invocation chooses its own fresh output directory and rebuilds the JAR when needed. Console/help wording was updated after acceptance without changing the command, flags or probe. `make -n demo`, `make help` and `git diff --check` pass. The fixed Git/disposable Java path remains distinct from the unqualified native-shell benchmark: that gate stays **BLOCKED**. No interactive shell, real language server or unrestricted desktop capture was launched.

## Repository and product compliance

No new dependency, theme, font, setting, panel, renderer, permission bypass, agent-specific parser or persistent service. Existing editor/document/Undo ownership, defaults and source-view infrastructure remain. Changed files are the new `AgentBufferIntent` and its tests; existing presentation/workspace/control/CLI/protocol classes; reviewed test manifest/admission list; the desktop probe/runner and Make help; usage/animation/intent docs and this evidence directory. Exact source paths/hashes are in `identities.json`.

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. The pre-existing untracked `Created.txt` was not inspected, edited or staged. Previous evidence remains intact. Atomic local commits only; no push. Windows/Linux GUI, missing-glyph coverage, Unicode first-edit latency, HTML-preview EDT stalls, native-shell confinement and native-memory qualifications remain open. These visuals do not resolve those limitations or make client-reported outcomes independently trustworthy.
