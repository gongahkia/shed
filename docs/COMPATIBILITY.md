# `Shed` Compatibility

## Supported combinations

Shed supports the following Java 21 desktop combinations. Each was tested by the CI matrix in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

| OS family | CPU architecture | Verified Java distribution | Last CI environment |
| --- | --- | --- | --- |
| Ubuntu Linux | x64 | Eclipse Temurin JDK 21 | `ubuntu-latest` (`ubuntu-24.04`) |
| Windows | x64 | Eclipse Temurin JDK 21 | `windows-latest` (`windows-2025-vs2026`) |
| macOS | arm64 | Eclipse Temurin JDK 21 | `macos-latest` (`macos-26-arm64`) |

The current CI run verifies tests, packaging, manifest metadata, and the bundled font on each listed environment. Runner labels are rolling images, so the exact image is recorded here as verification evidence rather than a permanent OS-version guarantee.

## Java requirements

- Building Shed requires Maven and JDK 21. Maven rejects every other Java feature release before compilation.
- Running the packaged JAR requires a Java 21 JRE or JDK with the `java.desktop` module. The verified runtime is Eclipse Temurin JDK 21.
- Other Java 21 distributions may run Shed, but are not part of the compatibility verification matrix.

## Unsupported environments

- Java feature releases below or above 21.
- 32-bit runtimes.
- Headless runtimes or runtimes without `java.desktop`.
- CPU and OS combinations outside the supported table, including macOS x64, Linux arm64, and Windows arm64.

## Reproducible verification

From a clean checkout with JDK 21 active, run:

```console
$ java -version
$ mvn -version
$ mvn -B clean package
$ jar tf target/shed-2.0.0.jar
```

The first two commands must report Java 21. The Maven command runs the test suite and creates `target/shed-2.0.0.jar`; the final command must list `META-INF/MANIFEST.MF` and `assets/hackregfont.ttf`.

To compare against the hosted verification, inspect the latest `Shed CI/CD on push` run in the repository Actions tab. It exposes one result each for Ubuntu, Windows, and macOS.
