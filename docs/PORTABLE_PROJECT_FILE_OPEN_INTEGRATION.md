# A2.2.2c.1 — portable internal asynchronous file opening

> Internal-tranche scope record. The subsequent [authenticated editor-control implementation](AGENT_EDITOR_CONTROL.md) now enables explicitly authorized external opening and reviewed proposals under this trusted-local contract. The original verification below is preserved.

**GO for the tested internal headless/component scope.** Project file acquisition now works with the tested macOS Java 21 provider. It is connected to the existing background job service, pane request generations and typed EDT installation gate. External agent file opening remains disabled. Native workbench and other-platform acceptance remain open.

## Contract and implementation

The acquisition contract now targets **trusted local development projects**. Providers with `SecureDirectoryStream` retain directory-handle traversal. Other providers use canonical project paths, no-follow component metadata checks, a no-follow file channel and before/after resource identity checks. Both paths retain bounded bytes, cancellation, regular-file restrictions and the existing buffer codec. Missing files do not become empty buffers.

The portable path rejects observed symlinks, root replacement, traversal, oversized files and observed content/identity changes. It does **not** establish a sandbox against a hostile process replacing and restoring an ancestor during a read. Metadata checks also do not establish a stable snapshot against all concurrent in-place writes. This is an explicit revision of the previous stronger acquisition contract, not a claim of equivalent race resistance.

`PaneBufferController.openProjectFileAsync` connects `ProjectFileOpen` to the actual workbench. Preparation runs through `AsyncJobService` using `LegacyDocumentStore.prepareProjectFile`; it never calls the legacy pathname loader or applies project configuration. Completion runs on the EDT. The same `DocumentRequests` used by human pane loads rejects superseded requests; target identity, current navigation context, authorization, disposal and source-surface eligibility are checked before commit. Notebook/alternate-surface targets are declined by this initial path.

The typed ticket supports cancellation through the pre-commit boundary, including after preparation has completed but before its EDT callback runs. Once commit begins, completion is `APPLIED` or `OUTCOME_UNKNOWN`, rather than a false assertion of cancellation. Preparation failures are `REJECTED` with an internal failure cause. Finished request handles release their job-service reference. There is no new executor, polling worker, source cache, document model, command or permission grant.

Installation uses the existing pane-loading path and normal post-commit recent-file/watch/LSP/UI lifecycle. Its canonical-registration step was extracted without changing its selection rules: reuse an already-open buffer, replace only the single clean empty untitled buffer, otherwise register once. Dirty buffers and their Undo manager are reused. No trust dialog or project-configuration application was added to the internal path. Rejected operations never enter the installer.

## Verification and evidence

Host/toolchain identities and exact commands are retained in the [implementation evidence](performance/agent-tracka222c1/IMPLEMENTATION.md). All Java tests ran through the existing reviewed, disposable, offline/headless runner. No unqualified native-shell fixture or real language server was launched.

| Check | Result |
| --- | --- |
| Initial focused acquisition/install/async tests | 28 passed; zero failures/errors/skips |
| Final safe regression selection, 62 classes | **330 passed, zero failures/errors, 6 existing measurement opt-in skips** |
| Acquisition/install/async cases within that final run | **31 passed**: 9 acquisition, 9 gate, 13 asynchronous integration |
| Python tooling | **87 passed**, zero failures/skips |
| Additional existing navigation/paint measurement | 1 passed, zero failures/skips |
| Offline packaged JAR build | Passed |
| Packaged Mermaid/math/local-image smoke | Passed |

The focused and regression counts overlap. The additional measurement executes one case skipped in the default regression run; it is not an additional newly written test. The tooling manifest inconsistency found during the prerequisite check was corrected by explicitly listing the already-admitted acquisition/install classes and the new reviewed integration test. Only reviewed test-source hashes were updated; no guard or assertion was disabled.

The real integration fixture uses acquired bytes, `FileBuffer`, `LegacyDocumentStore`, the production canonical-registration helper, `EditorPane`/`JTextArea`, production context capture/matching and the typed commit gate. It proves successful acquisition and binding, stale selection/source/authorization rejection, pane switching/disposal, supersession, queued and post-preparation cancellation, shutdown, dirty-buffer/Undo/Redo preservation, missing/invalid targets, accurate post-commit failure and 24 repeated opens without duplicate canonical buffers or retained job-service references in finished handles. A queued EDT task also executes while preparation is held on a worker. This is actual component integration, not a JFrame workbench qualification; its fixture does not start watches, LSP or Git workers.

## Performance and remaining gates

The existing 1 MiB navigation/paint probe measured warm typing-to-offscreen-paint p95 of **0.49 ms** for its control, **0.40 ms** with navigation traffic and **0.42 ms** queued at applied navigation (50 warm samples each). The control's first sample was **30.51 ms**, reported separately. These are headless component results, not native desktop input latency or an async file-open benchmark. No matched pre-change performance control was collected, so no speedup claim is made.

That existing navigation lifecycle workload retained 0 of 168 tracked objects after 24 cycles; settled heap was 27,271,600 to 27,287,000 bytes, committed heap 98,566,144 bytes, with 23 threads and 179 file descriptors before/after. These measurements concern the established navigation workload. The new open workload separately checks canonical counts and handle retirement; it is not a whole-process retained-heap/RSS qualification. RSS, first/warm file-open latency and native focus/completed paint remain unmeasured. No new work was added to the ordinary typing path.

The full native Maven suite, native workbench startup/configuration/LSP integration, Windows/Linux GUI/filesystem qualification, all editing modes in a visible workbench and large-file projections remain unqualified. The historical path-race characterization class was deliberately excluded from the regression selection; its known limitation is documented rather than rerun. Native-shell confinement, Unicode glyph/layout and HTML-preview latency limitations remain open.

## Files and repository compliance

Production: `ProjectBoundFileAcquisition.java`, `ProjectFileOpen.java` (new), `ProjectDocumentInstall.java`, `PaneBufferController.java`. Tests/tooling: `ProjectBoundFileAcquisitionTest.java`, `ProjectFileOpenTest.java` (new), `portable-fixture-tests.properties`, `scripts/test_native_fixture.py`. Documentation: this report, historical-result cross-links and additional evidence under `docs/performance/agent-tracka222c1/`.

The starting HEAD remains `7c808e85231c061eb58ca17a7d951c03a4431e82`. The prior prerequisite report/evidence was preserved. No dependency, UI design, default theme/font, saved preference, public CLI/RPC method or authoritative document format changed. README SHA-256 before and after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No commits or pushes were created.

The next separately scoped step is workbench qualification of this internal controller entry point, including approved post-commit watcher/LSP/configuration interactions, before considering external file-open enablement. No later tranche was started.
