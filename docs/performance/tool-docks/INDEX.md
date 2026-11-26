# Independent Git/Chat docks and tool-report qualification

Starting HEAD: `76a3d878f49e08416fd2e335e1607002a06570d4`, clean working tree. The user authorized the Git/Chat and report-placement recommendations and explicitly retained existing tabbed terminals. No terminal relocation, new agent endpoint, document/Undo model, native dependency, font/theme default, preference migration, commit push or native-shell benchmark is part of this change.

## Delivered boundary

`WorkbenchDocks` gives the existing editor/bottom-tool split and the right Git/Chat region independent visibility and sizes. Hide/reopen, focus-mode restoration, automatic bottom growth/manual sizing, old single-dock placement hints and new two-region session state are supported. `ToolWindowHost` continues owning one instance of each existing tool. Bottom detach/reattach remains; Git/Chat are right-pinned. The existing agent input shield exempts Chat's actual panel, rather than granting interaction with the other docks during an approved turn.

Git status/changes/history normally use the right sidebar, with compact vertical files/patch presentation and a central read-only comparison action. History and commit requests use existing bounded process/job services. Workspace generations reject stale actions/results, including an away-and-back project switch. Commit comparisons additionally check pane/file/revision/input context before replacing the center. Unavailable, blank-root or stale history cannot fall back to the application's working directory. Remote actions, graph, worktree/stash and conflict dialogs remain explicit advanced views with existing confirmations.

`ToolReportPanel` provides bounded, read-only Language/Debug/Tasks/Tests/Git reports and an explicit editable text export. One bounded startup request survives deferred tool-host creation; newer tool navigation/hiding supersedes it. `QuickfixToolPanel` navigates the existing service from Results, with a 10,000-row snapshot cap and entry-identity validation. `ReadOnlyComparisonPanel` reuses `ChangeDiffView` in the center without a source buffer, source revision or Undo entry. Rename application remains explicit through the existing checked edit path. See [user guide and limits](../../INDEPENDENT_TOOL_DOCKS.md).

## Commands and actual results

The existing source-admitted, disposable Maven runner used:

```text
python3 scripts/run-portable-tests.py
 --jdk /Users/gongahkia/.local/share/shed-tools/jdk-21
 --maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11
 --repository /private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository
 --output <new-json-file>
```

| Check | Actual result | Evidence |
| --- | --- | --- |
| Broad selection: every admitted name except retired `ProjectFileOpenPrerequisiteTest` | **587 passes, seven existing skips, 594 total; zero failures/errors** | `regression-final.json.gz` |
| Final `--tests GitSidebarContextTest,WorkbenchDocksTest,ToolReportPanelTest,ReadOnlyComparisonPanelTest,AgentCliChatPanelTest,DocumentWorkbenchTest,LspDefinitionRequestsTest,FileExplorerSidebarTest` | **39 passes, zero failures/errors/skips** | `preferences-focused.json.gz` |
| `--package-only` after source/test checks | Passed | `package-preferences.json.gz` |
| `python3 -m unittest discover -s scripts -p 'test_*.py' -v`, disposable fixture environment | **116 passes**, zero failures | `tooling-final.json.gz` |
| `JAVA=/Users/gongahkia/.local/share/shed-tools/jdk-21/bin/java make demo-chat` | **PASS**, actual macOS Swing app with a bounded fixed Java CLI double | `live-release-*` and `release-*.png` |
| Existing packaged Mermaid/math/local-image driver, in its disposable environment | **PASS**, run after UI latency samples | `media-release.json.gz`, `media-driver-release.json.gz` |

Selections overlap and must not be added. The broad run precedes the final bounded-startup refinement and the new one-test history-admission guard. The final 39-test selection and qualified native run cover the final implementation; no final full/native Maven total is asserted. The seven broad skips are existing opt-in measurement/data-loss cases. The complete unrestricted GUI/native suite remains excluded by the B0.1 safety boundary. Updated `TexteditorSwingIntegrationTest` assertions compile and now inspect the correct report/right-dock surfaces, but that unrestricted suite was not run. Existing body-content assertions and default/manual dock-size expectations remain.

Source-hash admission adds only four reviewed in-process tests. They cover independent region visibility/content ownership; exact size/focus/session snapshot restoration; bounded untrusted dimensions; automatic growth versus dragged sizes; read-only report/export behavior and grapheme-safe truncation; comparison formatting/back/export/font updates; and history action admission with null, unavailable, blank-root and stale/away-and-back identities. Existing safe source, Undo, split, keymap, Unicode, appearance, LSP and agent regressions remain. No assertion/admission guard is weakened.

`git-compile.json.gz` preserves the initial compile failure: the existing `WrappingFlowLayout` accepts a `FlowLayout`, rather than three scalar arguments. Its existing constructor was reused; no new layout dependency was added. All subsequent targeted/broad/package runs passed. Earlier passing native refinements are retained in `live-01-*` through `live-03-*`; the earlier qualified run is preserved in `live-qualified-*`. The final `live-release-*` run also includes saved-setting guards and uses the release artifact.

## Native workflow and source safety

The existing driver compiles a reviewed probe against the packaged JAR, using disposable project/home/preferences/temp, a fixed direct Java CLI double, bounded timeouts and only owned-component captures. It never launches a native shell, real coding CLI/model, language server, debugger or Git backend. Source guards are not bypassed. The final run also checks that history-disabled and diff-disabled settings reject the new backend requests before process execution. Git UI uses clearly labeled synthetic snapshot/patch data with backend dispatch suppressed in the fixture; this does not qualify staging/commit/history/remote execution against a real repository.

The qualified run verifies:

- A startup report survives deferred initialization.
- Persistent Explorer, its toggle/width/source ownership, workspace switching, theme/density and file-opening action remain valid.
- Actual LSP status and Debug status, a **real controller task dry run that launches no process**, Results and a central comparison leave source identity/text/revision/dirty state/Undo/caret/document count unchanged.
- Synthetic Git changes render in the right sidebar, Open Diff uses a central read-only surface, and a project switch invalidates the old repository-action context.
- Chat and Search remain visible together; right toggles leave the bottom alone; focus mode restores both region sizes; detached Search remains independently active and reattaches.
- Existing consent/authentication/read/edit/follow-up/selection-context/idle-authorization/cancellation/draft-preservation checks pass with the external Java double.
- Synthetic external sentinel content hash and modification time are unchanged; the fixture is removed; remaining task IO workers and child processes are zero.

The live probe uses actual component/controller actions and synthetic key events where applicable; it does not establish OS keyboard delivery or compositor timing. Terminal native execution and a real provider were not attempted. The longstanding native-shell NO-GO remains.

## Performance, artifact and limitations

Qualified post-chat source insertion plus `paintImmediately`: 40 samples, **first 3.250 ms, p95 0.689 ms**. This is a bounded supported component check against the existing 16 ms p95 target, including the first sample and excluding OS input delivery, EDT admission and compositor display. No matched prior-artifact control or contemporaneous host-load sample was captured, so no comparative speedup is claimed.

The host is macOS 26.5.2 arm64 with Temurin JDK 21.0.13.1+2 and Maven 3.9.11. Exact commands/JVM flags/fixture paths are in compressed JSON (`gzip -dc <file>`). `artifact-release.json` records JAR SHA-256 `ac3a549ffda73476ec0ce27a9c95ba0f488f43a92212d64e46163ac101681a21`; all 1,598 compiled main classes match their packaged bytes. No new permanent worker pool or source-sized cache is introduced. One bounded report per tool and one bounded central comparison are retained. Whole-process RSS/native-memory equivalence is not measured.

Windows/Linux GUI behavior, full native Git operations, terminal runtime behavior, real provider latency, native-shell confinement and full desktop input-to-screen performance remain unqualified. Existing synchronous Git actions, Unicode layout/glyph limits and preview EDT stalls are not remediated or downgraded. Advanced Git dialogs and deliberately exported scratch text remain explicit. Report history is window-local, not a durable archive.

## Repository compliance

Production changes are limited to shared docking/comparison/report presentation, Git sidebar history/context checks and their existing controllers/menus. Tests/admission, the controlled demo and documentation accompany them. `TerminalController`/terminal implementation, README, source ownership, default fonts/themes and external commits are unchanged. Starting user work was clean; all existing evidence is retained. README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. `git diff --check` passes. Atomic local commits are authorized; no push is performed.

## Atomic implementation commits

- `5e742e925`: independent right/bottom regions, Git sidebar, source-preserving reports/comparisons, tests and controlled demo.
- `f0511e97f`: respect existing Git history/presentation/diff-disable settings before new backend requests, with controlled live checks.
- Documentation/evidence is saved separately. No commits were pushed.
