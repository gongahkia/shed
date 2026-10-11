# `Shed` Compatibility

## Supported combinations

Shed supports the following Java 21 desktop combinations. The [CI matrix](../.github/workflows/ci.yml) builds and tests the portable JAR on each OS.

| OS family | CPU architecture | CI Java distribution | CI runner |
| --- | --- | --- | --- |
| Ubuntu Linux | x64 | Eclipse Temurin JDK 21 | `ubuntu-latest` |
| Windows | x64 | Eclipse Temurin JDK 21 | `windows-latest` |
| macOS | arm64 | Eclipse Temurin JDK 21 | `macos-latest` |

The CI job runs Maven tests and packages the portable JAR on each listed environment. Native DMG, MSI, and DEB installers use the separate platform packaging scripts. Runner labels are rolling images.

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

The first two commands must report Java 21. The Maven command runs the test suite and creates `target/shed-2.0.0.jar`; the final command must list `META-INF/MANIFEST.MF` and `assets/logo/shed.png`.

To compare against hosted verification after the workflow runs, inspect the latest `Shed CI` run in the repository Actions tab. It exposes one result each for Ubuntu, Windows, and macOS.
