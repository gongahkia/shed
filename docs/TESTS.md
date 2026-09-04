# Testing

Shed's Test Explorer is explicit-refresh only. Opening the Tests panel performs no detection, filesystem walk, process launch, watcher registration, indexing, or network I/O. Click **Refresh** or run `:test refresh` to discover tests; runs start only through an explicit Run action or `:test run` command.

## Built-in adapters

| Id | Auto-detection | Discovery | Run/report result |
| :--- | :--- | :--- | :--- |
| `maven` | `pom.xml` | Java annotations under `src/test/java` | direct `mvn`/`mvnw test`, Surefire/Failsafe JUnit XML |
| `gradle` | Gradle build/settings file | Java annotations under `src/test/java` | direct `gradle`/`gradlew test`, Gradle JUnit XML |
| `pytest` | pytest config, dependency, or `tests/` | `pytest --collect-only -q` | JUnit XML in Shed's local report cache |
| `jest` | local `package.json` dependency | `jest --listTests` | Jest JSON in Shed's local report cache |
| `vitest` | local `package.json` dependency | `vitest --listTests` | Vitest JSON in Shed's local report cache |
| `go` | `go.mod` | `go test -list . ./...` | `go test -json ./...` |
| `dotnet` | root `.sln` or `.csproj` | `dotnet test --list-tests` | `dotnet test --logger trx`; TRX results in Shed's local report cache |
| `cargo` | `Cargo.toml` | `cargo test -- --list` | `cargo test`; selected/rerun-failed tests run individually with libtest's `--exact` |

Auto-detection only inspects local files. It never downloads an executable or dependency. A missing runtime is reported in the selected test job output. Node adapters prefer a local `node_modules/.bin` executable; Maven and Gradle prefer executable wrappers.

## `.shedtests`

Place this UTF-8 TOML file in a workspace root to replace auto-detection completely:

```toml
schema_version = 1

[[adapter]]
id = "pytest"
command = ["python", "-m", "pytest"]
debug_configuration = "pytest-test"

[[adapter]]
id = "vitest"
command = ["./node_modules/.bin/vitest"]
```

`schema_version` must be `1`. Each `[[adapter]]` requires one unique built-in id. `command` is optional; when supplied it is a non-empty direct argv array, never a shell string. `debug_configuration` is optional and names one global `debug.configuration.<name>` mapping. It enables only explicit **Debug Selection** and `:test debug <test-id>`; Shed does not infer or automatically launch a test debugger. DAP launch args may use `${testId}` and `${testFile}` for that explicit mapping; unknown placeholders and files outside the selected workspace are rejected. Supported ids are `maven`, `gradle`, `pytest`, `jest`, `vitest`, `go`, `dotnet`, and `cargo`. Invalid files block testing for that root and report exact diagnostics; Shed does not fall back to auto-detection.

Results are session-only. Pytest/Jest/Vitest report files go under the configured Shed data directory's `test-reports/` cache (default `~/.shed/test-reports/`); ordinary local tests do not make app-owned changes to source projects. Maven/Gradle use the XML reports their normal test tasks produce. Failed tests with a source location appear in Problems as `test:<adapter>`; no test failure replaces the quickfix list.

## Local Dev Containers

For a selected root with `.devcontainer/devcontainer.json`, explicit dynamic discovery, Run All, Run Selection, and rerun-failed run through the user-installed `devcontainer exec` CLI in the already-running container. Auto-detection and Maven/Gradle's static Java source scan stay on the local mounted workspace. **Debug Selection** remains an explicit configured stdio DAP bridge; it does not infer a test debugger.

For report-based adapters, Shed creates one generated `.shed-devcontainer-test-reports/run-…` directory under the selected project so that it is visible through the mounted workspace. It maps only exact workspace-path argv prefixes to the container's probed POSIX workspace root, parses the resulting reports locally, rejects cache report trees containing symbolic links, non-regular files, more than 10,000 files, a file over 8 MiB, or more than 32 MiB total, and then removes only that generated run directory. Cleanup failures are shown in test output and can leave that generated directory behind. Maven and Gradle keep using their normal workspace-relative report locations.

This route does not start or rebuild a container, install a dependency, perform a full workspace synchronization, or create a remote extension host. An explicit refresh or run is what invokes the CLI; a stopped or misconfigured container is reported in that job.

## Connected remote roots

Selecting a Tests root inside an explicitly connected SSH, Docker, or WSL workspace makes `:test refresh`, run-all, Run Selection, and rerun-failed execute in that workspace's remote environment. A connected Git URI is already a local clone, so its tests remain local to the clone. Auto-detection and Java's static source discovery use the current local mirror; dynamic discovery and test commands run remotely only after the user explicitly refreshes or runs tests.

Shed rewrites only known local workspace and private report-cache paths to the selected remote root, then retrieves each declared report path directly into a fresh private `test-reports/` cache directory. Maven/Gradle reports are copied from their normal workspace-relative output paths; pytest, Jest, Vitest, .NET, and extension reports use a generated `.shed-remote-test-reports/<id>` directory in the selected remote project. Shed creates that directory immediately before the explicit run and removes that exact generated relative path after report retrieval. A failed cleanup is reported and may leave that generated directory behind.

Artifact retrieval accepts only workspace-relative regular files or directories. Built-in providers reject traversal and symbolic links; the Tests controller rejects symbolic links, files over 8 MiB, totals over 32 MiB, and report trees over 10,000 files before parsing. Remote command output is capped at 128 KiB and visibly marked when truncated, so Go/Cargo or extension providers that derive results from stdout can report incomplete results in that case. No full pull, push, source upload, automatic synchronization, or remote dependency installation occurs. Remote test cancellation cannot terminate a provider command already running on the remote host; it takes effect when that request returns. **Debug Selection** stays explicit: it resolves global and permitted trusted root-local DAP declarations against the selected Tests root, can use a configured already-installed remote stdio adapter for SSH, Docker, or WSL, and never infers, downloads, or uses remote TCP adapters.

## Java extension providers

An installed Java extension can register `TestContribution` through the versioned extension API. Shed asks a provider whether it supports the selected root, executes the provider's direct-argv discovery/run commands, and translates returned `ExtensionTestCase` values into the Tests view and Problems. Provider code owns its framework parser and report format. Discovery and execution remain explicit (`:test refresh` / `:test run`); registering a provider does not grant background scanning or automatic downloads.

## Coverage import

Use **Import Coverage…** in Tests or `:coverage import <report>` to parse a local JaCoCo XML, Cobertura XML, LCOV, or Go `-coverprofile` report. Shed does not run, generate, upload, or watch coverage reports. Imported values are session-local, merge by workspace file/line, ignore paths outside the selected root, and show covered/uncovered active-file lines in the gutter. Use `:coverage clear` or **Clear Coverage** to remove them.
