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

Results are session-only. Pytest/Jest/Vitest report files go under the configured Shed data directory's `test-reports/` cache (default `~/.shed/test-reports/`); source project files are not changed by Shed. Maven/Gradle use the XML reports their normal test tasks produce. Failed tests with a source location appear in Problems as `test:<adapter>`; no test failure replaces the quickfix list.

## Java extension providers

An installed Java extension can register `TestContribution` through the versioned extension API. Shed asks a provider whether it supports the selected root, executes the provider's direct-argv discovery/run commands, and translates returned `ExtensionTestCase` values into the Tests view and Problems. Provider code owns its framework parser and report format. Discovery and execution remain explicit (`:test refresh` / `:test run`); registering a provider does not grant background scanning or automatic downloads.

## Coverage import

Use **Import Coverage…** in Tests or `:coverage import <report>` to parse a local JaCoCo XML, Cobertura XML, LCOV, or Go `-coverprofile` report. Shed does not run, generate, upload, or watch coverage reports. Imported values are session-local, merge by workspace file/line, ignore paths outside the selected root, and show covered/uncovered active-file lines in the gutter. Use `:coverage clear` or **Clear Coverage** to remove them.
