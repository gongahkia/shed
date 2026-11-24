# A2.2.2c.1 — internal asynchronous file opening: prerequisite result

> Historical prerequisite result. The later [portable implementation](PORTABLE_PROJECT_FILE_OPEN_INTEGRATION.md) supersedes this host blocker under an explicitly revised trusted-local acquisition contract. Original findings and raw evidence below are preserved.

**NO-GO on the tested host; c.1 remains unimplemented.** The committed acquisition contract requires `SecureDirectoryStream`. A real, isolated Java 21 probe on this Mac returned `sun.nio.fs.UnixDirectoryStream` and `secure=false`. The existing loader rejects this provider with `IOException: Provider cannot guarantee project-bound acquisition`. No production, test, admission or external-control behavior was changed to bypass that refusal.

## Checkout and implementation boundaries

The starting checkout was clean at `7c808e85231c061eb58ca17a7d951c03a4431e82`. The handoff's acquisition and installation code is already committed:

- `ProjectBoundFileAcquisition.pinAuthorizedRoot/acquire` binds acquired bytes to a pinned root through secure directory handles. Unsupported providers fail before reading the file.
- `LegacyDocumentStore.prepareProjectFile` produces a detached `PreparedProjectFile`; it does not register a canonical buffer or configure a project.
- `ProjectDocumentInstall.commit` provides the EDT commit/cancellation boundary and `APPLIED`, `REJECTED`, `CANCELLED`, `OUTCOME_UNKNOWN` outcomes.
- `PaneBufferController.installPreparedProjectFile` validates the target/context before calling the existing installation path, which reuses an already-open buffer for the same path.
- `AsyncJobService.submit` supplies background preparation and EDT completion. The controller's existing `DocumentRequests<EditorPane>` supplies pane generations; human loads, buffer switches and disposal already issue or invalidate requests.

There is still no asynchronous caller joining prepared acquisition to the typed controller installer. The legacy `openFileCheckedAsync` is unsuitable as that caller: it uses the ordinary pathname loader and can perform project configuration/trust work before its final validation. It was left unchanged. External `editor/file/open` remains unsupported; no grant, command, worker or endpoint was added.

## Actual verification

The host initially had no usable JDK or Maven. Temurin 21.0.13.1+2 and Maven 3.9.11 were downloaded to temporary scratch storage and checked against their published SHA-256/SHA-512 respectively. No Homebrew, shell configuration, production dependencies or saved preferences were changed. Toolchain identities, commands, raw output and fixture cleanup are in the [evidence index](performance/agent-tracka222c1/INDEX.md).

| Check | Actual result |
| --- | --- |
| Real Java filesystem-provider probe | `UnixDirectoryStream`; secure handles unavailable |
| Two committed prerequisite classes | 15 tests: **9 passed, 0 assertion failures, 1 error, 5 skips** |
| `ProjectBoundFileAcquisitionTest` | 1 passed; 5 existing secure-provider assumptions skipped |
| `ProjectDocumentInstallTest` | 8 passed; real `preparedProjectResourceUsesExistingCanonicalStore` acquisition errored |
| Python tooling | 87 tests: **86 passed, 1 failed, 0 skips** |

The historical Linux result of 15 passes is not reproduced on this host. The error is the unsupported acquisition provider, not a speculative asynchronous integration failure. Assertions and assumptions were not changed.

The tooling failure is also present without source edits: `test_reviewed_test_manifest_matches_sources` expects a class set that omits `ProjectBoundFileAcquisitionTest` and `ProjectDocumentInstallTest`, although both are already in `portable-fixture-tests.properties`. This mismatch needs a source-reviewed admission consistency correction; hashes must not be regenerated to hide it.

No native-shell fixture, real language server, desktop automation, full Maven suite, packaged-media smoke or performance measurement was run. The narrow prerequisite run was sufficient to establish this stopping condition. Compilation through the portable test runner succeeded; that does not qualify c.1, native desktop behavior or other platforms.

## Remaining acceptance and next step

Continue c.1 on a host/provider that actually supports the existing secure acquisition primitive, starting by rerunning the two committed classes. A maintained cross-platform acquisition alternative would be a separately scoped decision; an ordinary pathname fallback is not an acceptable substitution here.

After that prerequisite passes, the smallest internal integration remains: capture the intended pane/context on the EDT; issue a generation from the controller's existing `DocumentRequests`; prepare through `AsyncJobService` off EDT; cancel the installation ticket on cancellation; and validate the same pane, generation, context and authorization before entering the typed commit. Decline unsupported configuration/modal effects rather than routing through the legacy checked opener. Use the canonical store and existing controller attachment, including dirty-buffer reuse.

Real acquisition plus installation into an actual component pane, cancellation before commit, stale/superseded contexts, dirty-buffer/history preservation and repeated cleanup remain **OPEN**. Existing typed-gate unit tests are not evidence of this missing integration. No latency, resource, native desktop or cross-platform acceptance is claimed. A2.2.3 and later tranches were not started.

Only this report and its evidence directory were added. Production/UI, themes, fonts, preferences and prior tracked evidence remain unchanged. README SHA-256 before and after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No commits or pushes were created.
