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

Auto-detection only inspects local files. It never downloads an executable or dependency. A missing runtime is reported in the selected test job output. Node adapters prefer a local `node_modules/.bin` executable; Maven and Gradle prefer executable wrappers.

## `.shedtests`

Place this UTF-8 TOML file in a workspace root to replace auto-detection completely:

```toml
schema_version = 1

[[adapter]]
id = "pytest"
command = ["python", "-m", "pytest"]

[[adapter]]
id = "vitest"
command = ["./node_modules/.bin/vitest"]
```

`schema_version` must be `1`. Each `[[adapter]]` requires one unique built-in id. `command` is optional; when supplied it is a non-empty direct argv array, never a shell string. Supported ids are `maven`, `gradle`, `pytest`, `jest`, `vitest`, and `go`. Invalid files block testing for that root and report exact diagnostics; Shed does not fall back to auto-detection.

Results are session-only. Pytest/Jest/Vitest report files go under the configured Shed data directory's `test-reports/` cache (default `~/.shed/test-reports/`); source project files are not changed by Shed. Maven/Gradle use the XML reports their normal test tasks produce. Failed tests with a source location appear in Problems as `test:<adapter>`; no test failure replaces the quickfix list.
