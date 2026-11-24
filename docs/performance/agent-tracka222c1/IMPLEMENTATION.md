# Portable internal file-open implementation evidence

See [the current implementation report](../../PORTABLE_PROJECT_FILE_OPEN_INTEGRATION.md). The original [prerequisite evidence](INDEX.md), including its failure, remains intact.

| File | Purpose |
| --- | --- |
| `implementation-focused-1.json.gz` | First 28 focused passes, exact runner invocation and cleanup |
| `implementation-regression-1.json.gz` | Final 62-class safe selection; 336 total, 330 passes and 6 opt-in skips |
| `implementation-tooling.json.gz` | All 87 tooling passes under disposable environment |
| `implementation-navigation-measurement.json.gz` | Existing opt-in 1 MiB offscreen typing/navigation/lifecycle probe, complete samples and first-operation costs |
| `implementation-package.json.gz` | Offline packaged JAR build |
| `implementation-media.json.gz` | Packaged Mermaid/math/image smoke, artifact/probe identity and results |
| `implementation-media-driver.json.gz` | Exact direct Python smoke invocation/output under the controlled fixture environment |

Toolchain and OS details are in `environment.json`; the temporary JDK/Maven installations were reused. The Java runner validates reviewed source hashes, copies the bounded offline dependency cache and supplies isolated home/preferences/temp paths. Fixture removal is recorded in the runner outputs.

Reproduce using `python3 scripts/run-portable-tests.py --jdk <JDK21-home> --maven <Maven-home> --repository <offline-cache> --output <new-json>` with these selections:

- Focused: `--tests ProjectBoundFileAcquisitionTest,ProjectDocumentInstallTest,ProjectFileOpenTest`.
- Regression: all names in `portable-fixture-tests.properties` except `ProjectFileOpenPrerequisiteTest`, supplied as the comma-separated `--tests` argument. The exact list is in the raw JSON. The old race characterization was intentionally not repeated.
- Measurement: `--measure-status --tests WorkspaceNavigationMeasurementTest`.
- Build: `--package-only` without test/measurement options.

Tooling used the existing `native_fixture.Fixture` controlled environment, `PYTHONNOUSERSITE=1`, `PYTHONDONTWRITEBYTECODE=1`, a direct `python3 -m unittest discover -s scripts -p 'test_*.py' -v`, a 30-second bound and cleanup in `finally`. Media smoke similarly ran the existing `scripts/test-packaged-markdown.py --jar <fresh-JAR> --jdk <JDK21-home> --output <new-json>` directly under a controlled fixture environment. These paths do not start interactive/native-shell benchmarks.

There is no new externally reachable operation and no native desktop acceptance claim. The raw logs distinguish successes, measurement skips and the preserved original prerequisite failures.
