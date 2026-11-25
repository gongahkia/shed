# EOF append following investigation

Starting HEAD: `5ed1ad372e021e9a58ad2683c04609ac8d52fbba`, clean worktree. No production changes were needed or made. README SHA-256 before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

## Finding

The running visualization window was launched for the Shed repository; the supplied CLI capture showed edits in a separate temporary demo folder. These are different roots. Launch arguments establish the initial target, not whether a human subsequently changed the active project. A project-target mismatch is the leading explanation, not a reproduced scrolling defect. The existing Follow pause policy is another explicit troubleshooting check.

The deterministic unit case and the actual showing-workbench fixture both reveal an appended block through its final EOF. The native fixture writes five placeholder functions after 240 comment lines. It checks the complete authoritative source, clean state, unchanged history/revision during the tour, preserved caret, closed management dock and final EOF visibility. It checks subsequent EDT turns for 500 ms to detect a late viewport reset. Final viewport Y was 4,871; source length was 5,559 UTF-16 units. The owned-root capture is `appended-eof-visible.png`.

The first two probes failed a separate, overly broad selection assertion: the existing external reload changed selection `2..6` to `6..6` while retaining caret 6, exact source, revision and clean/Undo state. `RecoveryController.checkForExternalChanges` restores only the caret with `setCaretPosition`. Both failed reports are preserved. The final probe records that reload behavior and separately asserts that the tour preserves its installed caret/selection; the unit test retains the nonempty-selection preservation assertion. Selection preservation across external reload remains a separate limitation, not a fixed or hidden failure.

## Execution

All commands use the existing JDK 21.0.13.1+2 and Maven 3.9.11, with command arrays and hashes in the raw JSON. No tools were installed. The direct Java desktop driver uses disposable files, preferences and a fixed Git fixture. No real agent or language server was launched, no personal project was changed and no interactive shell/native-shell benchmark admission was used. The user's demo file was inspected read-only.

```sh
python3 scripts/run-portable-tests.py --jdk <existing-JDK> --maven <existing-Maven> \
  --repository <offline-cache> \
  --tests AgentEditPresentationTest,AgentActivityStateTest,ExternalChangeSnapshotTest,AgentChangeTourTest,SmoothScrollPaneUITest \
  --output <new.json>
python3 scripts/test-agent-desktop.py --jdk <existing-JDK> --jar target/shed-2.0.0.jar \
  --output <new-directory> --git-fixture --visual-only --qualification --motion
```

| Evidence | Result |
| --- | --- |
| `focused.json` | Five tour tests pass, zero failures/errors/skips. |
| `related-tests.json` | 38 related tests pass, zero failures/errors/skips; includes those five. |
| `initial-selection-failure.json`, `selection-diagnostic.json` | Append scrolled successfully, but failed the pre-reload nonempty-selection assertion described above. |
| `desktop-acceptance.json` | PASS, 14 workflow events, including append-to-EOF, late reload barrier, 5,000-file watch, themes/density/focus/splits, conflict preservation and lifecycle. |
| `identities.json` | Test, benchmark, manifest, unchanged production JAR and README hashes. |

Native watch typing: first completed-component-paint sample 2.80 ms, warm p95 1.07 ms from 20 warm samples; no claim of physical input/scanout measurement. Twelve stopped watchers retained zero watchers after bounded diagnostic GC. Heap was 80,694,464 bytes before cycles and 17,578,576 afterward; this does not establish RSS/native-memory equivalence. Sixty-four standard scroll panes were inspected by the existing matrix. Native Windows/Linux, full multilingual rendering and existing Unicode/HTML-preview latency qualifications remain open.

The unrestricted Maven suite, real-agent turn, packaged-media smoke and full Python tooling suite were not rerun: this change adds a narrow regression case, benchmark assertions and troubleshooting documentation, with no production/dependency change. Their previous evidence is preserved. `git diff --check` passes. Reading-state UI changes were explicitly excluded; the existing progress phase was inspected only.
