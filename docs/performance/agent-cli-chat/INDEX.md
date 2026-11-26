# CLI chat verification evidence

See [the chat workflow](../../AGENT_CLI_CHAT.md). Starting HEAD was `ab8b899c3e89290123d325d5daa66b7b06b3bb8c`, with a clean tree. The production implementation is committed as `8d2942689`. No real model, language server, interactive shell, B0.1 native-shell harness or unrestricted desktop automation was run.

## Commands and boundaries

The existing source-admitted runner was used with these common arguments:

```text
python3 scripts/run-portable-tests.py
  --jdk /Users/gongahkia/.local/share/shed-tools/jdk-21
  --maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11
  --repository /private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository
  --output <new-json-file>
```

- Focused: `--tests AgentDelegationTest,AgentTaskLaunchTest,CliChatProtocolTest,AgentCliChatPanelTest,ArchitectureBoundaryTest` — final **27 passes**, no skips/failures/errors (`focused-04.json.gz`).
- Broad safe regressions: `--tests` with every manifest name except the already-retired `ProjectFileOpenPrerequisiteTest` — **554 passes, seven existing skips, zero failures/errors**, 561 total (`regression-release.json.gz`). The exact selected classes/command are in that report. Six opt-in measurements and the pre-existing data-loss fixture are skipped; no full native/GUI suite is claimed.
- Packaging: `--package-only` — success (`package-release.json.gz`). This mode skips tests solely for packaging, following the separate regression run.
- Tooling: `python3 -m unittest discover -s scripts -p 'test_*.py' -v` — **114 passes** (`tooling-final.json.gz`), run as a bounded subprocess under a disposable `native_fixture.Fixture` home/config/temp environment with `PYTHONNOUSERSITE=1` and `PYTHONDONTWRITEBYTECODE=1`.
- Final live demo: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 make demo-chat` — **PASS** (`make-demo-release-*`). The known missing JAVA_HOME is warned about and the existing resolver selects the app-owned JDK. `make demo-delegation` is the same target; the first Make alias run also passed before the final extra assertions/paint sample.
- Independent live driver: `python3 scripts/test-agent-delegation.py --jdk <same-JDK> --jar target/shed-2.0.0.jar --output <new-canonical-directory> --chat --demo` — passed (`live-03-*`), before the additional idle-bridge and post-chat-paint assertions in the final Make run.
- Packaged media: `python3 scripts/test-packaged-markdown.py --jdk <same-JDK> --jar <absolute-JAR> --output <new-json-file>` — **PASS** for Mermaid, math and local image (`media.json.gz`, `media-driver.json.gz`). The existing direct-Java driver ran inside a disposable fixture with a 100-second outer bound.
- `git diff --check` and README SHA-256 passed. README before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

The test manifest and its admission allowlist add only the reviewed protocol and in-process Swing chat tests. No unreviewed/native test override was added. Existing tests were not weakened. Earlier runs overlap; counts are not additive.

## Actual live assertions

The production packaged workbench opens a real macOS Swing window. The test replaces only the chat process runner with a fixed direct-Java CLI double, then uses the real chat buttons, approval dialog, streaming decoder, authenticated bridge, document loading, file watcher and editing presentation. No provider credentials or cloud access are needed.

The final Make run verifies:

1. First Send requires explicit approval; the real `Demo.txt` opens and shows the reading indicator.
2. Source editing is guarded while the composer remains editable and the glass pane allows chat interaction.
3. The first reply returns human source input. An authenticated bridge request between turns returns `AUTHORIZATION_REVOKED`.
4. The follow-up command contains the exact `resume fixture-chat` identity. Each prompt contains current visualization instructions; the visible transcript does not expose them.
5. Both changes are present exactly on disk and in the real editor, with no dirty-buffer divergence.
6. Stop on the third turn retains a draft, restores source input and retires the owned Java process/descendants and both IO workers. New chat clears the visible transcript.
7. The synthetic external sentinel's SHA-256 and modification time remain unchanged, and the disposable fixture is removed.

`01-chat-and-reading.png` and `02-follow-up-and-source.png` capture only the owned Swing root. They were visually inspected for chat/source coexistence. This is component-level capture, not OS-wide observation or keyboard-delivery qualification.

## Performance and resource limits

The final post-chat source-mutation plus `paintImmediately` probe used 40 character appends on the tiny disposable document: **first 2.084 ms, p95 0.708 ms**. It excludes time waiting to enter the EDT, physical keyboard delivery and compositor presentation. No warmup hides the first sample. This is a current-host bounded check against 16 ms, not a paired whole-IDE regression benchmark. Media smoke ran afterward, not during the samples.

`remaining_task_io_workers` is **zero**, tracked descendants have exited, and fixture cleanup passed. This does not establish whole-process RSS/native-memory equivalence. Startup, idle CPU/heap and real-provider resource attribution were not measured. Disabled chat adds no active timer/process; during a turn it uses the existing job service and a coalescing 60 ms UI timer, with explicit source/output/response limits described in the workflow report.

`environment.json` records host/JDK/artifact identity. `artifact-check.json` verifies all **1,572** compiled main classes match the final JAR; its SHA-256 is `1279f95cbeb4b5fdf0ab1640f6f187269ef017c1df70a5a2eef1cfa9fe6d2b63`.

## Preserved failures and limitations

`focused.json.gz` preserves the initial test compilation failure (an invalid compound `var` declaration); subsequent focused runs pass. `live-01-*` and `live-02-*` preserve `CONTEXT_CHANGED` failures during chat-driven source opening/reading. The old navigation focus policy only admitted the source/explorer; the correction admits the approved chat/Take Back surface while source ownership is held, retaining all context/revision validation. `live-03-*` and the final Make run pass after that correction. No retry or stale-context suppression was added.

Actual Codex/Claude/OpenCode native sessions, CLI-version compatibility, login/tool approvals, Windows/Linux desktops, detached-service confinement and complete process/native-memory qualification remain open. Provider event shapes are contract-tested, not independently verified by real provider runs here. The existing Unicode/glyph and preview stalls remain separate. B0.1 native-shell benchmarks remain NO-GO.

No source model, Undo engine, theme/font default, saved preferences or README was changed. Local atomic commits were requested and created; no push was performed. Prior reports and evidence remain intact.
