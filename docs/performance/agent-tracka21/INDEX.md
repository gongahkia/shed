# Track A2.1 evidence index

Report: [Authenticated, bounded editor observation](../../AUTHENTICATED_EDITOR_OBSERVATION.md).

**CONDITIONAL GO for headless/component observation. Real-window attachment remains OPEN; native-shell qualification remains NO-GO.** This evidence does not describe navigation, source reading, editing, rendered observation or a desktop acceptance run.

## Final evidence

All compressed reports preserve exact expanded commands, output and disposable-fixture cleanup, rather than passing-line extracts.

| File | Meaning |
|---|---|
| `baseline.json.gz` | Entry HEAD/status and pre-existing repository input hashes, before implementation. |
| `environment.json` | Current Linux x64/JDK/Python identity, verified official JDK/Maven archive checksums and excluded native/RSS scope. |
| `final-safe-tests.json.gz` | Final production/test source: **276 cases, 271 passed, five opt-in skips, zero failures/errors**, 56 admitted classes. Includes the escaped-response-ID bound and ready Undo/Redo invariance checks. |
| `final-package.json.gz` | Offline production packaging, tests deliberately skipped because they were separately verified. |
| `final-packaged-smoke.json.gz`, `final-packaged-smoke-detail.json.gz` | Current packaged JAR passes image/math/Mermaid/provider/invalid-render recovery smoke in an isolated headless JVM. This is not GUI acceptance or a preview-latency improvement. |
| `final-tooling.json.gz` | **86 applicable tooling passes**, one explicitly excluded macOS-only profile-generation case on this Linux host. |
| `initial-tooling.json.gz` | Original unfiltered 87-case attempt: 86 passes and one platform error, preserved without changing the test or backend guard. |
| `final-entry-status-measurements.json.gz` | Unchanged A1 probe from an archived entry revision, same current host/JDK/cache, fresh JVM. |
| `final-status-measurements.json.gz` | Same A1 probe against the final source, fresh JVM. |
| `final-metadata-measurements.json.gz` | Final metadata round trips, EDT timing/allocation, 1 MiB source typing with metadata traffic, 24 disposal cycles, threads/descriptors and heap. One opt-in test passes. |
| `measurements-summary.json` | Structured final metrics; complete raw sample arrays remain in the measurement logs. No source contents or runtime authentication material are recorded. |
| `test-results.json` | Parsed staged/final counts and cleanup outcomes. Repeated/intermediate passes are not added to final counts. |
| `artifact.json` | Final JAR identity and exact match of all **1,441** compiled production classes; no metadata test fixture is shipped. |
| `source-hashes.json`, `source-versions/` | Exact final hashes/compressed sources for the 18 changed pre-existing inputs and four new implementation/test files. These are final versions, not claims about intermediate probe source identity. |
| `failure-notes.json` | Dependency bootstrap misses, unsupported initial typing fixture, Linux tooling exclusion, unavailable optional thread dump and final response-size correction. |
| `repository-verification.json` | Final HEAD/status, explicit changed/new file inventory, preserved-input checks, protected README identity and whitespace verification. |
| `checksums.sha256` | Complete integrity manifest for this directory and the report. |

The final three performance JVMs ran sequentially after other checks completed. The entry probe uses a source archive of the recorded entry commit, not reset/revert of the working checkout. Both entry and final source compile through the existing offline reviewed runner. Warm p50/p95 and first/max values are separate. Earlier raw measurements are retained, not pooled into final percentiles or silently discarded.

The final synchronized typing workload produced 51 `CONTEXT_CHANGED` responses: both captures executed, but the concurrent human edits invalidated their combination. Stable-context success is demonstrated by 101 preceding queries and the separate external CLI. This is a conservative refusal policy, not proof that an agent obtains a successful snapshot during every edit.

## Staged and intermediate evidence

| File | Scope |
|---|---|
| `compile-bootstrap.json.gz`, `package-bootstrap.json.gz` | Disposable direct-Java Maven setup/compilation with tests skipped; dependency download was needed because no JDK/Maven/cache existed on this host. No shell fixture or GUI was launched. |
| `stage-a-offline-provider-missing.json.gz`, `stage-a-offline-launcher-missing.json.gz` | Initial offline runs could not resolve cached Surefire provider/launcher artifacts. They ran no tests. Task-owned dependency seeding resolved the prerequisite; offline/admission requirements stayed enabled. |
| `stage-a.json.gz` | Initial 11 in-process editor contract/lifecycle passes. |
| `stage-b.json.gz` | Initial **43** metadata transport/A1 contract/A1 transport passes. |
| `history-verification.json.gz` | Fourteen focused final editor tests pass, including the same Undo manager with both Undo and Redo available across observation. |
| `intermediate-safe-tests.json.gz`, `intermediate-frozen-safe-tests.json.gz`, `intermediate-release-safe-tests.json.gz` | Earlier safe runs before the final escaped-ID regression. |
| `initial-entry-status-measurements.json.gz` | Earlier entry comparison, retained separately; only the final matched series is used for the report table. |
| `initial-measurements.json.gz` | Unsupported ordinary-typing fixture reused a Unicode document for ASCII; retained complex-layout state is the source-based inference recorded in the report. Its slow no-traffic control remains explicit. |
| `corrected-measurements.json.gz` | Fresh-ASCII fixture correction before final bounds/path/privacy review. |
| `intermediate-entry-status-measurements.json.gz`, `intermediate-status-measurements.json.gz`, `intermediate-metadata-measurements.json.gz` | Additional pre-final-bounds observations; not substituted for final results. |
| `intermediate-portable-tooling.json.gz`, `manifest-tooling.json.gz` | Earlier applicable tooling run and a narrow source-hash admission recheck. |

No full native/GUI/PTY suite, real language server or model provider, native desktop capture, Windows/macOS runtime, native Linux desktop or whole-process RSS profiler was run. The failed B0.1 native-shell harness was not retried or relaxed. Existing glyph, Unicode and HTML-preview limitations remain open.

## Reproduction

Use the current Java 21 toolchain and an offline cache containing the project's unchanged dependencies. The existing runner copies that cache into a disposable fixture, enforces the reviewed test/helper hashes, sets private home/temp/preferences, runs Maven's Java entry without a shell launcher, and uses offline in-process Surefire. It is trusted-code admission, not a hostile-code sandbox.

```sh
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --output <new-safe-report.json>
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --tests WorkspaceStatusMeasurementTest --measure-status \
  --output <new-status-measurements.json>
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --tests WorkspaceMetadataMeasurementTest --measure-status \
  --output <new-metadata-measurements.json>
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --package-only --output <new-package-report.json>
```

The metadata measurement deliberately reuses the existing `--measure-status` test opt-in, avoiding another execution mode or pool. Default safe verification skips both measurement classes, plus the three prior LSP/gutter/glyph opt-ins.

Tooling and packaged-media commands ran under fresh `native_fixture.Fixture` environments with Python user-site/bytecode output disabled; their exact argument arrays are in the final reports. The portable tooling selection excludes exactly `test_native_fixture.NativeFixtureTest.test_profile_no_fork_network_or_user_home_access` after asserting that the host is Linux and that precisely one matching case exists. The unfiltered failure remains recorded. No test assertion or production/native-fixture source is changed to accommodate Linux. Media uses `scripts/test-packaged-markdown.py --jdk <jdk-home> --jar <final-jar> --output <new-report>`.

The native, GUI and whole-process gates remain separate. Do not replace these commands with the blocked full-suite/native fixture to obtain a larger count.

## Changed-file ownership

The final source inventory in `source-hashes.json` is exhaustive for implementation, tests and tooling:

- Production: new `EditorMetadata`; changes to `AgentWorkspaceController`, `ApplicationStatus`, `EditorPane`, `EditorWorkspaceDocuments`, `HelpService`, `Texteditor`, `WorkspaceApiException`, `WorkspaceBridgeClient`, `WorkspaceBridgeServer`, `WorkspaceCli`, `WorkspaceRoots`, `WorkspaceRpc`, `WorkspaceStatusAttachment`, `WorkspaceStatusProtocol` under `src/main/java/shed/`.
- Tests: new `EditorMetadataTest`, `WorkspaceMetadataTransportTest`, `WorkspaceMetadataMeasurementTest`; fixed headless CLI helper `WorkspaceCliTestProcess` adds only the `--metadata` flag.
- Tooling/admission: `scripts/test_native_fixture.py`'s exact approved-class set and the two source-hash manifests under `src/test/resources/shed/`. The portable runner/native backend implementations remain unchanged.
- Documentation: `docs/AUTHENTICATED_EDITOR_OBSERVATION.md` and this evidence directory, fully enumerated by the checksum manifest.

The report's coordinate/privacy rules and lifecycle matrix explain each production boundary. Existing README, prior reports/evidence, dependencies, source/Undo ownership, themes/fonts, saved preferences and keyboard behavior are preserved. No commits or pushes occur, and no later tranche is started.
