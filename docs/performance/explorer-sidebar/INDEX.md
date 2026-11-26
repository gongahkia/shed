# Persistent Explorer qualification

Starting HEAD: `b34ba9bf15bdfb03c3ae2d53e85afd7f3a5c7a37`, clean working tree. README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Existing evidence and user work remain intact. No native-shell harness, real language server, coding CLI/model, personal project or unrestricted desktop automation is used.

## Implementation and audit scope

`FileExplorerSidebar` hosts the existing `FileTreePanel` independently of editor panes and documents. The old tree scratch-buffer/split creation path is removed. The View checkbox and `:tree toggle` retain the same component, expansion/selection and width across hide/show; focus mode temporarily hides it. Document tabs live beside Explorer rather than above it. Workspace-root changes replace the explorer surface; theme, density and external-change badges/pulses explicitly refresh it. File opening targets the active content pane. The sidebar adds no permanent worker, model or dependency.

The other tool-placement findings are static inventory and recommendations only; see [sidebar audit](../../WORKBENCH_SIDEBAR_AUDIT.md). Git/Terminal/Chat ownership and command behavior are not otherwise changed. Old manually restored scratch documents are preserved. No new cross-restart sidebar preference or saved-session migration is claimed.

## Commands and results

Source-reviewed Maven admission uses:

```text
python3 scripts/run-portable-tests.py
 --jdk /Users/gongahkia/.local/share/shed-tools/jdk-21
 --maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11
 --repository /private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository
 --output <new-json-file>
```

| Run | Result | Evidence |
| --- | --- | --- |
| `--tests FileExplorerSidebarTest,DocumentWorkbenchTest,UiDensityTest,ThemePreviewTest,EditorSurfaceTest` | **18 passes**, no failures/errors/skips | `focused-02.json.gz` |
| Every source-admitted manifest name except previously retired `ProjectFileOpenPrerequisiteTest` | **580 passes, seven existing skips; 587 total**, no failures/errors | `regression.json.gz` |
| `--package-only` after the safe tests | Passed | `package.json.gz` |
| `python3 -m unittest discover -s scripts -p 'test_*.py' -v` inside the disposable fixture environment | **116 passes**, no failures | `tooling.json.gz` |
| `JAVA=/Users/gongahkia/.local/share/shed-tools/jdk-21/bin/java make demo-chat` | Final **PASS** in the actual Swing app, fixed external Java CLI double | `live-final-*`, PNGs |

These selections overlap and are not additive. Source-hash admission adds only the inspected pure-container `FileExplorerSidebarTest`. The unrestricted `TexteditorSwingIntegrationTest` assertions were updated and compile with Maven; that suite was **not run** because it includes unqualified native fixtures. Three new headless cases cover toggle/width/content ownership, focus-mode restoration including an explicitly hidden sidebar, and project-surface replacement/removal. Existing safe regressions retain editing/Undo, agent, layout, appearance and Unicode coverage. No assertion is weakened.

The first targeted compile failed because a revised GUI assertion lacked its `assertNull` import; `focused.json.gz` preserves that failure. The import was added and compilation/tests passed. The first live run passed the new sidebar checks, then failed an older inactive-tab test setup: the tree was no longer an extra document. The probe now creates an actual empty source document before testing inactive-tab closure; the close-target assertions remain. `live-01-*` preserves the failure; `live-02-*` and the expanded final run pass.

## Live checks and limits

The existing direct-Java fixture creates an isolated project/home/preferences/temp, verifies an external synthetic sentinel by content hash **and** modification time, bounds process execution and captures only its own Swing root. Final sentinel integrity, fixture deletion and zero remaining task IO workers/child processes passed. No B0.1 admission bypass occurred.

The final run checks startup with one source pane and no tree documents; actual View-menu toggles; exact width and source-context retention; source-split creation/closure while Explorer remains; focus-mode hide/restore; workspace switching; theme/density updates without changing source ownership; and opening a real file through the explorer's Enter action. It retains the existing right-chat, narrow-window, bottom-tool, detached Search, selected-source context, authenticated read/write/follow-up, inactive-document close, Stop and draft-preservation checks. File-change badges remain visible in the final capture.

Final 40-sample post-chat source insertion plus `paintImmediately`: **first 2.690 ms, p95 0.780 ms**, below the established 16 ms component target. This excludes OS input delivery, EDT admission and compositor presentation. Host is macOS 26.5.2 arm64, Temurin JDK 21.0.13.1+2. Exact JVM arguments and raw samples are retained. No contemporaneous load or matched prior artifact control was recorded, so no comparative speedup is claimed. Native-memory equivalence, full retained-object qualification, Windows/Linux GUI behavior and native-shell safety remain open.

`artifact.json` records the packaged JAR identity and matching main-class bytes. No source/document/Undo model, default font/theme, saved preference, agent permission or new dependency changed. `git diff --check` and README identity pass. Local atomic commits are authorized; no push is performed.

Qualified implementation commit: `96a23fc91`. Documentation and evidence are recorded separately for review.
