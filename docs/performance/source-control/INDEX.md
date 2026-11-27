# Graphical Mercurial/Subversion qualification

Entry: clean `5a01e783435112343201eca915a2a811b9eb46be`. [Usage and capability matrix](../../SOURCE_CONTROL.md).

## Scope and acceptance

The right-side **Source Control** surface now presents Git, Mercurial and Subversion. The Git controller remains intact. New provider pages use structured status/history, compact/central diffs, native working-copy actions, confirmed mutations and the existing bounded process/job lifecycle. Internal dock IDs remain compatible with saved sessions. There is no new document model, source listener, worker pool, renderer framework or dependency.

**Implemented and qualified at the parser/command and controlled graphical boundaries. Real Mercurial/Subversion client acceptance remains OPEN.** Neither `hg`, `svn` nor `svnadmin` is installed on this machine. No installation, personal repository mutation, network VCS request or unsafe native-shell execution was attempted. Local parser/argv tests and in-process client doubles cannot certify actual client versions, repository hooks/extensions, server behavior or credentials. Windows/Linux native GUI checks remain open.

## Exact final results

| Check | Result | Evidence |
| --- | --- | --- |
| New parser/service/renderer selection | 14 passes, zero failures/errors/skips | `focused-renderer.json.gz` |
| Final broad safe Maven with LSP measurement | **617 total; 611 passes, six existing skips; zero failures/errors** | `maven.json.gz` |
| Python tooling | **116 passes**, zero failures | `tooling-media-final.json.gz` |
| Packaged Mermaid/math/local-image smoke | PASS | `tooling-media-final.json.gz`, `media-*.json.gz` |
| Final real Swing + fixed Java Chat/SCM client | PASS; both provider pages, source/context guards and existing Git/Chat | `scm-chat-final-evidence.json.gz`, `scm-chat-final-result.json.gz`, PNGs |
| Final disposable fixed-Git/watcher desktop workflow | PASS, including EOF follow | `scm-watch-final-report.json.gz` |
| Earlier same-artifact watcher failure / unchanged rerun | FAIL at late EOF viewport stability / PASS | `scm-watcher-release-report.json.gz`, `scm-watcher-release2-report.json.gz` |
| Packaged classes | All **1,628** compiled main classes match JAR bytes | `artifact-final.json.gz` |

Selections overlap and are not additive. Earlier `focused2`/`focused3` selections passed 17 tests before the full action matrix/renderer test was added. Existing skips are `GlyphRenderingMeasurementTest`, one `DataLossRegressionTest`, `WorkspaceNavigationMeasurementTest`, `WorkspaceMetadataMeasurementTest`, `WorkspaceStatusMeasurementTest`, and `GutterPerformanceTest`. Retired `ProjectFileOpenPrerequisiteTest` and native/unreviewed classes are unexecuted, rather than counted as passes/skips. Full unrestricted Maven/native qualification was not attempted.

Final JAR SHA-256: `a176ba1258dce576dd0c89def40c8306e4f1a2e4588f5c0735293b4ba2bd0c3b`. macOS 26.5.2 arm64, Temurin 21.0.13.1+2, Maven 3.9.11. Source/artifact identities, JVM argv, fixture paths and post-check host load are retained. There is no matched before/after whole-process memory-pressure control for this change.

Final Chat/source-control insertion to forced completed paint: **40 samples; first 0.896 ms, p95 0.460 ms**. Active 5,000-file watcher: **first 2.861 ms, 20 warm samples, p95 0.568 ms**. Existing in-process LSP source-paint controls: **50 warm samples per 4 KiB/100 KiB/1 MiB row, warm p95 0.236–1.037 ms**, with first use retained separately. All covered typing boundaries meet the existing 16 ms target. Paired delayed definition still shows synchronous queued-input blocking **206.624–207.880 ms**, versus **0.024–0.158 ms** with the existing asynchronous request helper (three samples each). This checks preservation of the prior remediation; it is not a new VCS-native benchmark.

Final Chat task IO workers: zero. Final watcher descendants: zero; retained stopped watchers after 12 diagnostic disposal cycles: zero. The synthetic external sentinel has equal before/after SHA-256 and unchanged modification time. All disposable fixtures were removed. These covered object/process checks do not establish whole-process native-memory equivalence or prove all new SCM native-client lifecycles.

## Coverage

New safe tests cover NUL-delimited Mercurial paths (spaces/newlines/international text), conflicts, structured SVN text/property/tree states and root properties, valid/invalid history identities, all exposed provider-specific argv contracts, literal arguments and empty peg revisions, malformed/truncated/oversized/XXE output, URL/credential validation, symlink/traversal/nested-working-copy rejection, rename no-clobber, metadata detection and cancellation through the existing job service. The graphical renderer test verifies that the first render of a repository-controlled HTML-like branch or filename remains literal text, without a Swing HTML view.

The enhanced existing Chat desktop fixture opens the real application in disposable native/JVM homes/preferences and replaces only the new SCM client's command runner with fixed in-process responses. It checks automatic Hg/SVN detection, file selection, inline and central comparison, unchanged active source/revision/Undo, real Add/Commit confirmation, literal arguments, history/revision comparison, explicit SVN server consent, cancelled Revert, project switching during a Revert confirmation, pending-work cancellation and subsequent existing Git/Chat flows. The fixed runner does not execute native VCS mutations. Owned-component captures preserve both provider pages. `make demo-chat` uses this existing fixture with presentation pauses.

The separate existing watcher fixture uses a fixed system Git executable in its disposable repository, empty owned config/hooks and disabled prompts/signing. It checks the preserved Git patch-refresh path, 5,000-file burst, visible reads, conflicts, protected saves/Undo, agent tours, EOF follow, fonts/themes/density/focus/splits, watcher disposal and descendants. All captures are bounded owned Swing components, not unrestricted desktop screenshots.

## Development failures and unresolved observation

Evidence retains the initial compile ambiguity (`Action` versus Swing `Action`), one incorrect octal escape in a synthetic history string, an unregistered-project live fixture, and disabled history-comparison buttons after provider rebuilding. The compile/fixture defects were corrected; the UI retains its history controls in the enable/disable lifecycle. No assertion was weakened. Context checks additionally reject a project/provider change across modal confirmation and before mutation execution; timed refresh pauses during prompts.

One packaged-build watcher attempt failed **“Late callback moved viewport away from appended EOF”** in unchanged `AgentWorkflowDesktopProbe.qualifyEofAppend`. Its preceding editing/source-integrity, conflict, appearance, Git-refresh, disposal and latency checks passed. A same-artifact, unchanged-command rerun passed. The viewport assertion remains unchanged. **The intermittent failure is unresolved; its relation to this feature is unverified.** A subsequent successful run does not erase the failure or establish a cause. Original failure and rerun evidence are retained separately. This is not reported as an all-green native release qualification.

## Reproduce safely

```sh
python3 scripts/run-portable-tests.py \
 --jdk /Users/gongahkia/.local/share/shed-tools/jdk-21 \
 --maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11 \
 --repository /private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository \
 --tests ScmWorkbenchModelTest,ScmWorkbenchServiceTest,ScmChangesPanelTest \
 --output <new-absolute-json>
```

Broad safe selection: all reviewed manifest names except retired `ProjectFileOpenPrerequisiteTest`, with `--measure-lsp`; exact expanded argv lives in the Maven evidence. Packaging uses the same runner with `--package-only`. There is no full-suite/native override. Native-shell confinement remains NO-GO; no shell/real model/language server is launched by these checks.

```sh
python3 scripts/test-agent-delegation.py --jdk <above-jdk> --jar target/shed-2.0.0.jar --output <new-absolute-directory> --chat
python3 scripts/test-agent-desktop.py --jdk <above-jdk> --jar target/shed-2.0.0.jar --output <new-absolute-directory> --qualification --visual-only --git-fixture --motion
```

Tooling is `python3 -m unittest discover -s scripts -p 'test_*.py' -v` inside the existing disposable `Fixture`, followed by the packaged Markdown smoke driver in that environment. No personal configuration/history was read to prove isolation. The live Chat fixture checks its synthetic external sentinel's content hash and modification time, and removes its owned fixture. Native process cleanup remains the existing qualified direct-Java/fixed-Git path, not general OS confinement.

## Performance boundaries

Timings retain separate first samples and raw samples. Headless LSP controls use 4 KiB/100 KiB/1 MiB documents and an in-process delayed server double. Native typing probes measure application/component insertion to forced completed Swing paint, rather than physical keyboard/compositor latency. No heap, graphics, OS or timing definition is changed. Historical timings are unmatched earlier runs, so no statistically established speedup/slowdown is claimed.

New provider status/discovery and process work run on the existing shared bounded executor. UI snapshots/results install on the EDT. Pre-execution validation can wait on a bounded EDT callback from the worker; the EDT never waits for the VCS process. Source Control retains at most 10,000 change rows, 100 history rows, 1,000 branch heads and a 2 MiB response/patch budget. Hidden new-provider surfaces do not poll; failures stop timed retries. Close stops the owned timer and cancels its pending job. These are implementation bounds plus covered cancellation tests, not a measured zero-retention proof for every native VCS client.

Startup/RSS/native-memory equivalence, active real-Hg/SVN interference, repeated native-client disposal, all client/server versions, credentials/hooks, every legal unusual filename and Windows/Linux GUI behavior are unmeasured. Existing Unicode/glyph/preview limitations remain open. Real-client qualification is the next acceptance step once those tools are available in an appropriately isolated fixture.

## Command references

Mercurial's [scripting guidance](https://www.mercurial-scm.org/help/topics/scripting), [status](https://www.mercurial-scm.org/help/commands/status) and [resolve](https://www.mercurial-scm.org/help/commands/resolve) document the explicit record/template approach. SVN operand handling was inspected against Apache's [move implementation](https://svn.apache.org/repos/asf/subversion/trunk/subversion/svn/move-cmd.c): source peg handling and raw destination handling differ. These references support command construction, not runtime compatibility claims.

## Repository compliance and files

New production files: `ScmWorkbenchModel.java` (structured data/argv/path contracts), `ScmWorkbenchService.java` (bounded client facade), `ScmChangesPanel.java` (Hg/SVN UI) and `SourceControlPanel.java` (shared selector/detection around preserved Git). Integration touches only `ToolWindowHost`, `ShedMenuBar`, `PaletteController`, `ScmController` and explicit Git selection in `TreeGitController`. New tests: `ScmWorkbenchModelTest`, `ScmWorkbenchServiceTest`, `ScmChangesPanelTest`; reviewed hashes and the tooling admission expectation are updated. The existing Chat probe gains the new UI workflow. Usage guides link the current shared sidebar and preserve earlier qualification evidence.

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Entry working tree was clean; previous implementation/evidence and external commits remain. Existing theme/font defaults, saved preferences/layout identifiers, source/Undo ownership, keymaps, terminals, agent protocols, Markdown and appearance behavior are preserved within the tested boundaries. No dependencies, platform port, reset/clean/revert/discard, installation or push. `git diff --check` passes. Local commits separate backend/tests, graphical integration/tests and demo/documentation/evidence.
