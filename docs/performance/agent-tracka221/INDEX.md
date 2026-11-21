# Track A2.2.1 prerequisite evidence

Report: [Project-scoped file opening: prerequisite report](../../AUTHENTICATED_PROJECT_FILE_OPENING.md).

**NO-GO for production agent file opening.** The required acquisition-containment invariant failed. Existing status, metadata and navigation grants continue to reject file opening. This evidence qualifies defensive reproductions/refusal and unchanged safe controls; it does not qualify a new opening capability. No production code or authorization behavior changed.

After the user's explicit stopping instruction, work was limited to documentation and the already-running existing safe verification sequence. No further new filesystem security probe was executed, no safeguard was weakened, and no source/admission changes were made.

## Evidence inventory

Compressed reports preserve complete logs, expanded argument arrays and fixture cleanup. Staged/repeated passes are not summed into the final count.

| File | Meaning |
|---|---|
| `baseline.json.gz` | Clean entry `fe5f960c7439f5708d919d945367d7ccfb266404`; 2,338 original input hashes, including prior A2.2 failure/pass evidence. |
| `source-map.json` | Actual inspected sources/signatures/line anchors/hashes, required reports and applicable instructions. |
| `prerequisite-tests.json.gz` | Original four passing characterizations. `A221_BOUNDARY` records **failed safety containment**: restored identities/approved-looking path, bytes from a synthetic unapproved sibling, zero installed documents. Passing characterization is not a containment pass. |
| `safe-tests.json.gz` | Original full safe run: **309 cases, 302 passes, one LSP diagnostic failure, six skips**. Preserved unchanged. |
| `boundary-and-lsp-recheck.json.gz` | Four characterizations plus the unchanged LSP diagnostic: **five pass**. The extended characterization additionally exercises the existing bounded snapshot helper through the replaced ancestor. |
| `final-safe-tests.json.gz` | Final reviewed allowlist, 60 classes: **309 cases, 303 passes, six opt-in skips, zero failures/errors**. |
| `tooling.json.gz` | Original unfiltered tooling: **86 pass, one macOS-only Seatbelt profile error on Linux**, 87 attempted. |
| `final-tooling.json.gz` | **86 applicable passes**; precisely that Linux-inapplicable profile case excluded, with Linux/single-match assertions unchanged. |
| `package.json.gz` | Offline package; tests deliberately skipped because separately verified. |
| `final-media.json.gz`, `final-media-detail.json.gz` | Packaged image/math/Mermaid and invalid-render-recovery smoke; no desktop or native-shell acceptance. |
| `status-measurements.json.gz` | Existing unchanged A1 status control, one opt-in case passes. |
| `metadata-repeat-1.json.gz` through `metadata-repeat-3.json.gz` | Three sequential fresh-JVM runs of the **unchanged** metadata probe; all pass. New observed frequency 0/3 failures, not resolution of the historical anomaly. |
| `historical-a22-metadata-failure.json.gz` | Exact byte copy of preserved A2.2 `final-metadata.json.gz`: failed 4 ms EDT maximum assertion. |
| `historical-a22-metadata-repeat.json.gz` | Exact byte copy of preserved A2.2 `final-metadata-recheck.json.gz`: unchanged passing repeat. Neither original is modified. |
| `navigation-regression-measurements.json.gz` | Existing A2.2 control: applied-navigation typing p95 2.531 ms; 0/168 tracked retention after 24 cycles; one opt-in case passes. No new file-open traffic. |
| `verification-sequence.json.gz` | Exact driver arguments, order, UTC start/load, CPU/fault context and all existing verification exits. Driver was already running when the final stopping instruction arrived. |
| `measurements-summary.json` | Summaries of existing controls; raw arrays remain in full logs. No cross-run percentile pooling. |
| `test-results.json` | Parsed pass/fail/error/skip/cleanup counts, including original failures. |
| `source-hashes.json`, `source-versions/` | Final versions of the new characterization test and two test-admission inputs. No production source version changed. Initial raw reproduction predates the added bounded-helper check; final versions are not falsely claimed as every intermediate input. |
| `environment.json` | Linux x64/Python/reused Java 21/Maven/cache; no native, desktop or RSS qualification. |
| `artifact.json` | All **1,455** compiled production classes match the current JAR; new test fixture not shipped. Whole-JAR SHA differs from the historical A2.2 artifact and is not claimed identical. Cause unverified. |
| `failure-notes.json` | Containment/missing-file findings, original LSP failure and passing repeats, Linux error, timing anomaly, reporting corrections and blocked work. |
| `repository-verification.json` | README identity, all production/prior-input preservation, changed/new inventory, external commits and whitespace verification. |
| `checksums.sha256` | Integrity manifest for this directory and prerequisite report. |

The original LSP failure is the sampled-stack assertion at `LspDefinitionResponsivenessTest.java:41`, not an attributed new source or duration failure. The failed sampled stack was not emitted. The focused and final full-suite repeats pass without changing its code, assertions or timing. No LSP fix was attempted.

All three new metadata capture maxima occur on their first capture: 2.140, 3.844 and 2.700 ms. Warm capture p95 values are 0.138, 0.208 and 0.111 ms. First-capture allocation exceeds the warm median, consistent with first-use work, but cause attribution for the historical failure remains **unverified**. Host load and outer process measurements are context, not proof of JIT/scheduling attribution. Keep the cold-tail gate open.

## Execution and safety boundary

The existing portable runner admits exact reviewed test/helper source hashes, uses a disposable native/JVM home, temp and preferences, copies the existing offline cache, and launches Maven's Java entry with headless in-process Surefire. It is trusted-code admission, not an OS sandbox. The only new admission is the reviewed synthetic characterization class; native backend/guards and existing helper permissions are unchanged.

Exact expanded Java/Maven commands and selected classes are in each raw report's `command`/`tests` fields; toolchain paths are in `environment.json`. Commands already executed included:

```text
python3 scripts/run-portable-tests.py --jdk <recorded-jdk> --maven <recorded-maven> --repository <recorded-cache> --tests ProjectFileOpenPrerequisiteTest --output <new-report>
python3 scripts/run-portable-tests.py --jdk <recorded-jdk> --maven <recorded-maven> --repository <recorded-cache> --tests ProjectFileOpenPrerequisiteTest,LspDefinitionResponsivenessTest --output <new-report>
python3 scripts/run-portable-tests.py --jdk <recorded-jdk> --maven <recorded-maven> --repository <recorded-cache> --output <new-safe-report>
python3 scripts/run-portable-tests.py --jdk <recorded-jdk> --maven <recorded-maven> --repository <recorded-cache> --package-only --output <new-package-report>
python3 scripts/run-portable-tests.py --jdk <recorded-jdk> --maven <recorded-maven> --repository <recorded-cache> --tests WorkspaceStatusMeasurementTest --measure-status --output <new-report>
python3 scripts/run-portable-tests.py --jdk <recorded-jdk> --maven <recorded-maven> --repository <recorded-cache> --tests WorkspaceMetadataMeasurementTest --measure-status --output <new-report>
python3 scripts/run-portable-tests.py --jdk <recorded-jdk> --maven <recorded-maven> --repository <recorded-cache> --tests WorkspaceNavigationMeasurementTest --measure-status --output <new-report>
```

These are execution records, **not authorization to run further security probes after the stopping instruction**. No additional tests were started to enlarge the final count. The three metadata repetitions were predefined in the verification sequence; there was no retry-until-pass selection and no benchmark-definition change. Fresh JVM does not mean cold filesystem cache; ambient host activity is uncontrolled.

Tooling ran under fresh `native_fixture.Fixture` environments with Python user-site/bytecode output disabled. The final selector discovers `scripts/test_*.py`, asserts Linux/exactly one match and excludes only `test_native_fixture.NativeFixtureTest.test_profile_no_fork_network_or_user_home_access`. Its exact Python command is preserved. The original unfiltered error remains an error. Packaged smoke uses the existing `scripts/test-packaged-markdown.py` with recorded JDK/JAR/output arguments in the same reviewed disposable environment.

Six default skips are `GlyphRenderingMeasurementTest`, `GutterPerformanceTest`, `LspDefinitionPerformanceTest`, `WorkspaceStatusMeasurementTest`, `WorkspaceMetadataMeasurementTest`, `WorkspaceNavigationMeasurementTest`. Five separately passing opt-in controls are not added to 303 default passes.

## What is blocked or unverified

- No file-open grant, accepted request/success schema, typed opening completion, cancellation contract or authenticated successful opening exists.
- No further containment probes, security implementation, provider/handle experiment or controller restructuring followed the explicit stop.
- Authorized first/warm file-open latency, new-file EDT occupancy, source-type/encoding/mode behavior, file-open interference and retention are unmeasured.
- Case-insensitive/Windows normalization/reparse qualification, real window/controller integration, successful external file opening and RSS remain open.
- Native shell/PTY/full unreviewed Maven tests, real language servers/providers and desktop capture were not run. Existing guarded CLI regressions prove status/metadata/navigation only.
- No new source writes, saves, file-management RPC, ACP/MCP, diagnostics, preview, screenshots or A3 work was implemented.

The report defines the smallest prerequisite: one prepared-open contract spanning project-bound bounded acquisition through the existing store/buffer/codec and typed, cancellable installation through the existing controller/jobs. Its proposed mechanisms require independent proof before public file opening can be reconsidered.

## Repository compliance

Source changes are confined to the new test and two exact test-admission entries. Production, dependencies, fonts/themes, UI/keymaps/preferences and every pre-existing report/evidence file remain unchanged against entry hashes. README before/after SHA-256 is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

No commit, push, reset, clean, revert or discard command was issued by this agent. External capture commits were observed and preserved. The evidence uses entry hashes/HEAD as well as working status, so external capture does not hide this task's changes. Whitespace and integrity checks are recorded separately from runtime qualification.
