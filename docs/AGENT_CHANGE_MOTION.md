# Config, moves and live agent-change motion

The requested presentation extends the existing explicitly started Live changes watcher. It does not add agent editing permissions, intercept model tokens, synthesize source edits or move the human caret. Binary comparisons and a Git/session-operation timeline remain deferred.

## Supported behavior

- Known safe project dotfiles, including `.gitignore`, `.editorconfig` and formatter/linter config, plus `.github` and `.changeset`, are observed and visible in Explorer. Other hidden paths, environment/credential patterns, generated/dependency paths, symlinks and nested independently configured roots remain excluded. This is a whitelist, not a secret scanner.
- Unique physical file identity plus creation time can associate a move even after editing. Unique identical contents provide the existing alternative. Bounded reciprocal text similarity produces an explicitly advisory `R?`; ambiguity or a completely rewritten file remains delete/add. Folder summaries group at least two observed file moves. Empty directories and an atomic directory operation are not inferred.
- Explorer shows A/M/D/R/R? badges and aggregate folder counts. Deleted/moved-away rows are struck through; opening one selects its recorded diff without recreating a source file. Recent rows pulse in Explorer, Live changes and the visible Git summary. Git uses its actual statuses and existing actions; the selected patch refreshes when contents change without a status change.
- Follow smoothly scrolls through up to 32 observed blocks, with the distinct labeled agent marker moving through each block and a pulsing highlight. The diff retains its chosen baseline; the source tour uses the newest incremental observation. It presents completed writes, not a model's actual private cursor or token stream.
- Typing, source clicks, manual scrolling and Explorer interaction pause Follow; interaction cancels the active tour. The human caret/selection, source revision and Undo history are independent. Follow does not replace an active Explorer or preview surface. Enabling Follow explicitly can select the existing source pane when Explorer was active.
- Reduced motion reveals the first block statically and disables pulses. Source layout safeguards remain authoritative. Follow does not automatically open binary or greater-than-64-Ki-UTF-16 sources.

Use `shed visualize . --agent Codex`, or start a watch in the existing Agents → Live changes surface. Leave Follow enabled to see tours; resume with Follow or `shed ctl follow` after manual interaction. See [the usage guide](LIVE_AGENT_VISUALIZATION.md).

## Ownership and limits

`ExternalChangeSnapshot` owns bounded observations, not editable documents. `FileChangeMarks` contains immutable paths/status only. `LiveChangesPanel` retains the existing baseline/latest snapshots and at most 32 small incremental before/after pairs for tours. Similarity comparison is capped at 4,096 pairs, 64 Ki UTF-16 per candidate and 4 MiB total comparison input.

`ChangePulse` is a finite EDT timer, ending after 2.4 seconds or on hiding. `AgentChangeTour` borrows the existing pane, viewport and `AgentEditPresentation`; it removes temporary input/document/focus listeners on completion/cancellation. Each block has a 300 ms scroll and 700 ms dwell. New observations can supersede an earlier tour. There is no worker or per-keystroke layout calculation for animation.

The existing async checked-open path revalidates pane, revision, input and caret/selection before installing source. A loaded source must match the observed text before any marker is installed. No forced reload, synthetic Undo entry or background source mutation was added. When the watch observation arrives before the ordinary external reload, one bounded pending presentation waits for the existing recovery controller's reload-completion notification. It uses weak pane/buffer references, checks current project/pane/input/selection again, and requires exact observed source before starting. Pausing, stopping or a newer open discards it. This is event-driven; there is no retry timer. Explorer refreshes preserve loaded surviving nodes, expansion and selection where valid. The mounting order now rerenders after assigning the existing Explorer component; without this, the component could remain absent from the rendered split.

## Verification

See [raw commands, results and captures](performance/agent-change-motion/INDEX.md). The final reviewed safe suite ran 415 tests: 408 passes, seven skips, zero failures/errors. All intermediate failures remain recorded. Focused snapshot/presentation/tour tests passed 23/23. Python tooling passed 95/95. The real-model desktop, a second independent folder-move/reload desktop run, original proposal Apply/Undo/Redo/save flow, launcher and packaged-media smoke all passed against the same final JAR. The unrestricted native-shell and full native-dependent Maven suites were not run.

The visible macOS workbench fixture checks safe config inclusion/private-path exclusion, edited folder moves, deleted-row diff navigation, same-status Git patch refresh, real Codex three-block edits, viewport movement and user-scroll cancellation. It also retains the existing 5,000-file burst, dirty-buffer conflict, delayed Follow, split/theme/density/focus, custom-font, Undo and watcher disposal matrix. Exact disk/source assertions accompany captures; screenshots alone are not a source-integrity oracle.

A repeated folder-move scenario exposed a stalled JDK 21 polling watcher. Captured stacks show `PollingWatchKey.disable` waiting during registration while the provider holds that key and waits in `cancel` for its registration map. The map still held `before-folder` after that directory had moved. The watcher now retires missing/invalid registrations once before registering a newly observed subtree; initialization retains its original path and avoids repeated directory scans. Closing cancels owned keys before provider close. The deterministic regression checks that an existing valid key survives and a moved-away key is cancelled/removed. The desktop check additionally edits a file at its new path and requires that edit to appear without manual refresh.

Physical input/scanout, Windows/Linux desktop, full native-memory attribution, long-soak resource equivalence and complete multilingual glyph coverage remain unqualified. Unicode first-edit and stock HTML-preview latency limitations remain open. The filesystem watcher can coalesce writes and the OS can delay events; this is not a complete filesystem audit or authorship tracker. Record any timed-out desktop attempts alongside successful runs rather than treating retries as reliability proof.

README's SHA-256 before/after is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Existing defaults, fonts, colors, source ownership and native-shell admission policy are unchanged. Local atomic commits only; no push.

## Recorded measurements

The accepted visible-workbench run captured the 5,000-file watch baseline in 170.04 ms; its atomic/multi-file burst converged in 2,384.12 ms, including OS event delivery. Typing-to-completed-component-paint used 21 samples: first 3.28 ms and warm p95 1.05 ms. The real model tour reached vertical offsets 0 → 2,075 → 4,268 while preserving source, revision, dirty/Undo state and the human caret/selection. The controlled reload barrier proved that presentation starts after authoritative source reload even when observation arrives first.

Twelve watcher start/stop cycles retained zero stopped watchers after bounded diagnostic GC. Used Java heap was 79,329,800 bytes before those cycles and 16,912,160 bytes after diagnostic GC. This is not an RSS comparison or proof that all native resources return to baseline. Timers/listeners are also checked deterministically for removal/expiry. No paired whole-process startup/native-memory claim is made.

## Changed files

Observation and integration: `ExternalChangeSnapshot.java`, `FileChangeMarks.java`, `FileTreePanel.java`, `TreeGitController.java`, `TestWatchService.java`, `LiveChangesPanel.java`, `ToolWindowHost.java`, `AgentWorkbenchPanel.java`, `RecoveryController.java`.

Presentation: new `ChangePulse.java` and `AgentChangeTour.java`; extended `AgentEditPresentation.java`, `EditorUiController.java`, `GitChangesToolPanel.java`, `ChangeDiffView.java`.

Verification: `ExternalChangeSnapshotTest.java`, `TestWatchServiceTest.java`, new `AgentChangeTourTest.java`, the reviewed test hash manifest, `AgentWorkflowDesktopProbe.java`, `scripts/test-agent-desktop.py`, `scripts/test_live_agent_tooling.py`, `scripts/test_native_fixture.py`.

Documentation: this report, the live usage guide, a superseding note in the earlier qualification report, and the new raw evidence/capture directory. No dependency or packaging configuration changed.
