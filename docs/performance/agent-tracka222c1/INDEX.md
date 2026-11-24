# A2.2.2c.1 prerequisite evidence

The subsequent [implementation evidence](IMPLEMENTATION.md) records the Mac-compatible integration under the revised trusted-local contract; original failures here are historical and preserved.

This is a blocked macOS prerequisite check, not a completed asynchronous integration or native desktop benchmark.

| File | Contents |
| --- | --- |
| `environment.json` | Starting HEAD, OS/architecture, temporary toolchain identities and published archive checksums, README baseline |
| `provider-probe.json` | Real Java 21 directory-stream capability probe; no mocked provider |
| `prerequisite-tests.json.gz` | Exact isolated/offline Maven invocation, full log, nonzero exit and fixture removal |
| `tooling.json.gz` | Exact isolated Python invocation, full 87-test log, manifest mismatch and fixture removal |

The Maven command was:

```text
python3 scripts/run-portable-tests.py \
  --jdk <temporary-tools>/jdk-21.0.13.1+2/Contents/Home \
  --maven <temporary-tools>/apache-maven-3.9.11 \
  --repository <existing-offline-maven-cache> \
  --tests ProjectBoundFileAcquisitionTest,ProjectDocumentInstallTest \
  --output <new-scratch-file>/prerequisite-tests.json
```

The existing runner validates reviewed source hashes, copies the bounded offline dependency cache, runs Java directly with headless Swing and disposable home/preferences/temp paths, and removes its fixture in `finally`. There was no shell fixture or admission override. It compiled current production/test sources and ran 15 tests: 9 passed, 1 error, 5 skips.

Tooling command: `python3 -m unittest discover -s scripts -p 'test_*.py' -v`, run directly under the existing `native_fixture.Fixture` controlled environment, with `PYTHONNOUSERSITE=1` and `PYTHONDONTWRITEBYTECODE=1`, a 30-second bound and `close()` in `finally`. It produced 86 passes and 1 failure. An earlier direct invocation produced the same failure; these runs are overlapping checks, not 174 distinct tests.

The provider probe was a direct, bounded Java source launch under the same disposable environment. Its only operation was `Files.newDirectoryStream` on its own fixture directory, inspecting the implementation class and `instanceof SecureDirectoryStream`, then closing the stream. No shell, arbitrary project or personal configuration was accessed. Its result is specific to the recorded JDK/provider/host.

Archives were obtained over HTTPS from Adoptium's `temurin21-binaries` GitHub release `jdk-21.0.13.1+2` and Apache Maven's Maven Central distribution. Their hashes are recorded in `environment.json`. They were extracted only into new temporary scratch storage. No production toolchain setting changed.

See [the report](../../INTERNAL_PROJECT_FILE_OPEN_INTEGRATION.md) for the stopping condition and unexecuted acceptance gates. Historical Linux evidence and all prior tracked performance files were preserved; none was replaced with this result.
