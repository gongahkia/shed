# Live agent visualization evidence

Supported-scope verdict: **GO** for the qualified bounded, trusted-local macOS workflow. This is not a general Shed public-release or unrestricted native-process qualification.

## Reproducibility

Repository started clean at `67a7a7554eb72a6dfb015bf60c4a5a0e98839e93`. Code commits: `3928dea9e` (watch/launch/live diff), `527c45276` (PNG brands and icons), `f49f4318f` (bounded diff work), `7ea5fe647` (atomic launch readiness, latest-event follow and appearance-safe diff state), `007b18abc` (live latest-source acceptance). No push. README SHA-256 before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

macOS/aarch64, existing OpenJDK 21.0.13.1+2 and Maven 3.9.11. Exact commands, fixture locations and artifact hashes are retained in JSON. Toolchain archive provenance remains in `../agent-tracka222c1/environment.json`. Output directories must be new and canonical.

- Safe suite: `scripts/run-portable-tests.py --jdk <JDK> --maven <Maven> --repository <offline-cache> --tests <reviewed-class-list> --output <new.json>`. The class list is every source-reviewed manifest entry except the historical `ProjectFileOpenPrerequisiteTest` characterization. Native/unreviewed suites remain blocked.
- Live visualization: `scripts/test-agent-desktop.py --jdk <JDK> --jar target/shed-2.0.0.jar --output <new-directory> --replay-payload docs/performance/agent-editor-control/codex-generated-demo.java.txt --git-fixture --visual-only`.
- Launch-and-return: the same runner with `--launch-only` instead of replay/Git flags.
- Tooling: Python `unittest discover -s scripts -p 'test_*.py' -v` under the existing disposable `native_fixture.Fixture`, with no user site or bytecode writes.
- Media: existing `scripts/test-packaged-markdown.py` under the disposable fixture and supplied JDK/JAR.

`--git-fixture` permits only fixed `/usr/bin/git` through an owned fixture PATH. The repository, Git config and hooks directory are disposable; prompting, fsmonitor and signing are disabled. Only init/add/local fixture commit and Shed's existing direct Git inspection run. No shell or personal repository is involved. This independently bounded Git/Java path does not bypass or qualify B0.1's rejected interactive native-shell harness.

## Results

- `safe-tests.json`: **384 run, 378 passes, six opt-in measurement skips, zero failures/errors**. Tests cover external create/modify/delete/exact rename, exclusions/symlinks/size bounds, cancellation, literal source preservation, file-tree node retention/removal, insertion-only diff correctness, hunk navigation, authenticated project-only watch without an open document, wrong project/instance rejection, bundled PNGs, and existing safe editor/LSP/appearance/Undo regressions.
- `python-tests.json`: **91 passes**, zero failures; fixture removed.
- `package.json`, `artifact.json`: current package build and artifact identity. Resource decoding tests verify all 22 PNG variants are bundled. No dependency upgrades.
- `launcher.json`: a real independent Java process launched a real GUI child, returned matching owned PID/project readiness, then queried authenticated watch state without an open source file. One fresh-process sample took **2132.215 ms**; this is neither cold-cache performance nor a percentile. Only owned descendants were terminated by the fixture.
- `live-session.json`: real visible workbench and separate filesystem writer; exact source Apply/Undo/Redo/save regression retained. External creation, modification, deletion and rename produced four rows. A second isolated write had to appear in the active source and trigger the existing agent-activity presentation before acceptance proceeded; this checks latest-event following beyond alphabetical file selection. Manual pause and automatic dirty-buffer pause preserved the human draft and active document. Real isolated Git diff loaded after explicitly selecting the tracked file. Watcher/attachment cleanup succeeded; zero descendants remained and the fixture was removed.
- Warm ordinary input-to-completed-component-paint p95: **0.677583 ms** (20 warm samples; first input **0.663750 ms**). With watch active: **0.686667 ms** p95 over 20 samples; the first watch-active sample was **0.686667 ms**. Small ASCII Java fixture. These are synthetic editor actions through the actual visible Swing component, not physical-keyboard/scanout latency. The warm watch-active phase measures an active watcher, not a project-wide stress workload. No unmatched historical regression percentage is claimed.
- `packaged-media.json`: packaged Mermaid/math/image smoke passed, 14 events. The final live, launcher and media checks use the same packaged JAR SHA-256 recorded in `artifact.json`.

## Visual evidence

- `07-live-external-changes.png`: file status list, snapshot baseline and themed unified diff.
- `08-provider-logo-and-icons.png`: bundled OpenCode provider PNG and accessible outline action icons.
- `09-git-working-tree-diff.png`: actual Git patch with added/deleted lines and adjacent-word emphasis.

Captures paint only the application's owned root component at its real display scale. They are not unrestricted desktop screenshots. The existing physical-font multilingual limitation remains.

## Failed probes and qualifications

- `initial-diff-test-failure.json`: early rendering exposed that transaction hunks may be smaller than whole lines. The view now expands their display context without changing authoritative hunks/source; added tests distinguish insertion-only changes from falsely removed unchanged lines and verify next/previous hunk headers.
- `existing-preview-timeout.json`: the combined older scenario hit the existing `editor/preview/open` 200 ms deadline under concurrent GUI startup load. No timeout was enlarged or retry added. Current watch acceptance uses an explicit visual-only scope, excluding that unrelated HTML endpoint while retaining its original default test branch. Packaged media runs separately. The known HTML-preview EDT qualification remains open.
- `git-selection-probe-failure.json`: after a dirty-buffer pause, a preserved untracked file was selected and the probe incorrectly expected a tracked Git patch. [Inference] this selection assumption caused its wait to expire. The acceptance now explicitly selects Demo.java before requiring `diff --git`; real Git loading succeeds. Follow also checks human activity before changing selected rows.

Windows/Linux GUI, dark-theme desktop captures, narrow-window visual coverage, large projects, full native memory/RSS attribution and exhaustive listener/heap-root analysis were not newly qualified. Dark/light PNG decoding is tested; no broader native rendering claim follows from it. Existing Unicode first-edit, missing-glyph and HTML preview limits remain open. Explorer refresh handles at most 128 already loaded directories per update. Snapshot and CPU budgets fail with an explicit error, retaining the previous view. Renames with edited contents may appear as delete/add. Disk events cannot prove agent authorship or observe model tokens/cursor intent.

## Change inventory

- Launch/bridge: `ShedVisualize`, `Texteditor`, `AgentWorkspaceController`, `EditorControl`, `EditorMetadata`, `ShedControlCli`, `WorkspaceStatusProtocol`, `ToolWindowHost`.
- Presentation: `ExternalChangeSnapshot`, `LiveChangesPanel`, `ChangeDiffView`, `AgentWorkbenchPanel`, `AgentEditPresentation`, `FileTreeModel`, `FileTreePanel`.
- Branding/actions: `ProviderIcons`, `WorkbenchIcons`, `AgentSessionPanel`, `AgentConversationManager`, `pom.xml` resource inclusion and `assets/providers/` PNG/license/provenance files.
- Regressions: `ExternalChangeSnapshotTest`, `VisualWatchControlTest`, `ProviderIconsTest`, source-reviewed portable test manifest and `scripts/test_native_fixture.py`.
- Opt-in native acceptance: `scripts/test-agent-desktop.py`, `AgentWorkflowDesktopProbe`, `ShedVisualizeLaunchProbe`. The new probe requires the loaded source to include the second external block before accepting its presentation.
- Guides/evidence: `docs/LIVE_AGENT_VISUALIZATION.md`, appended guide link in `docs/USING_SHED_WITH_AGENTS.md`, this evidence directory.

No commits were pushed. The repository README was never edited. Existing theme/font defaults, source ownership and saved preference configuration are unchanged.
