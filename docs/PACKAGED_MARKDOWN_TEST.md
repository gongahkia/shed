# Packaged Markdown rendering — Tranche 1A

This is a packaging correctness repair. It changes no editor, preview, renderer, document, UI, initialization policy, dependency or dependency version. The historical [Tranche 1 baseline](MARKDOWN_PERFORMANCE_BASELINE.md) remains unchanged.

## Root cause and correction

`mermaid-java:0.2.0-beta.2` brings GraalJS/Truffle 21.2.0 transitively. The dependency JARs supply two resources with the same name:

```text
META-INF/services/com.oracle.truffle.api.TruffleLanguage$Provider

js-21.2.0.jar:     com.oracle.truffle.js.lang.JavaScriptLanguageProvider
regex-21.2.0.jar:  com.oracle.truffle.regex.RegexLanguageProvider
```

Both provider classes were already in Shed's shaded JAR, but only the JavaScript provider survived in the service descriptor. Neither shade configuration merged service resources. GraalJS then failed when valid Mermaid required the regex language, logging `No language for id regex found. Supported languages are: [js]`; the renderer returned no image. The normal dependency classpath discovers both descriptors and succeeds. This was overwritten metadata, not a missing dependency or a requirement to replace the renderer.

The correction adds Maven Shade 3.5.3's existing `ServicesResourceTransformer` to both the ordinary execution and the `build-commit-metadata` configuration. It preserves and deduplicates dependency service registrations generally, including the two Truffle providers. No handcrafted service descriptor, relocation, filtering workaround, new plugin, runtime library or post-build patch is introduced.

## Opt-in packaged regression

Build using the existing Java 21/Maven infrastructure and run the independent probe:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -DskipTests package

PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-packaged-markdown.py \
  --jar target/shed-2.0.0.jar --output /tmp/shed-packaged-markdown-NEW.json
```

The Python runner compiles `src/benchmark/java/shed/PackagedMarkdownSmoke.java` against that JAR into a disposable directory. The child classpath contains **only the probe classes and the supplied JAR**, with an isolated home and working directory. Ambient classpath/JVM-option overrides are removed for this child. The probe verifies the application and Mermaid dependency code origins and requires exactly one merged Truffle descriptor. It never adds `target/classes`, `target/test-classes` or dependency JARs as a fallback.

A PASS requires all of the following in one process:

- JavaScript and regex provider registrations are present.
- Ordinary Markdown, tasks, tables and HTML sanitization work before Mermaid use.
- Math produces a decodable image.
- A valid simple flowchart and a multiple-node/branch diagram produce nonempty, decodable images.
- Consecutive changed diagrams and three identical cached renders work.
- Rich preview HTML contains working math/Mermaid/local-image URLs; relative image resolution and Swing HTML installation/layout work.
- Invalid DSL raises the existing error and preserves its source in the preview's error/fence fallback, without a successful Mermaid image placeholder.
- Both cached and changed valid diagrams work after invalid input.
- Another Markdown document/asset session renders correctly in the same application process.

The smoke test is explicitly opt-in and does not add heavy rendering work to normal Maven unit tests or normal app startup. Its child uses a 64–512 MiB heap and a 90 s timeout (configurable only within 10–180 s). No temporary user documents or settings are read or edited; only this invocation's scratch files are removed. A failed assertion, renderer failure, nonzero exit, missing completion marker or timeout fails the command. Error logs for deliberately invalid DSL are expected; `SMOKE_PASS` still requires the error-handling assertions and subsequent valid renders.

Use `--java /path/to/bundled/runtime/bin/java` to test the same JAR using a distribution's reduced runtime while compiling with the existing JDK. On Windows, pass `--jdk "$env:JAVA_HOME"` and an explicit `.exe` path when selecting a bundled runtime; this avoids relying on the POSIX resolver. The report path must be new. The test does not build or install native installers.

### Commit-metadata build and reproducibility

The native packaging scripts all build this same shaded JAR and copy it unchanged into their application images. They optionally enable the commit-metadata profile. Verify that build separately:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -DskipTests \
-Dmaven.jar.forceCreation=true -Dshed.build.commit="$(git rev-parse HEAD)" package

PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-packaged-markdown.py \
  --output /tmp/shed-packaged-markdown-commit-NEW.json
```

For a repeat build, use the first package command with `-Dmaven.jar.forceCreation=true`. That forces a fresh thin JAR before shading and avoids measuring a previously shaded file as the input. Compare hashes for identical POM/classes/profile/commit inputs; commit-profile and ordinary artifacts intentionally differ in manifest metadata. Preserve `project.build.outputTimestamp` and existing packaging conventions.

### Development classpath control

The normal `MarkdownPreviewRendererTest` remains unchanged and includes math, Mermaid and local-image rendering. Run it with the repository toolchain:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Dtest=MarkdownPreviewRendererTest -Djava.awt.headless=true test
```

For the complete same-probe classpath control, compile `PackagedMarkdownSmoke.java` into a temporary classes directory, obtain the existing Surefire `java.class.path` from its XML report (as documented in the Tranche 1 report), and invoke:

```sh
"$JDK/bin/java" -Xms64m -Xmx512m -Djava.awt.headless=true \
  -Duser.home="$SMOKE_WORK" -cp "$SMOKE_CLASSES:$TEST_CLASSPATH" \
  shed.PackagedMarkdownSmoke "$PWD/target/classes" "$SMOKE_WORK"
```

`JDK`, `SMOKE_CLASSES`, `TEST_CLASSPATH` and `SMOKE_WORK` refer to the JDK home, independently compiled probe, actual dependency classpath and a new disposable directory. This deliberately labeled control expects the application origin to be `target/classes` and allows the separate dependency descriptors; the packaged runner never uses it.

## Verification results — 13 November 2026

The final smoke passes on the development classpath, ordinary packaged JAR, commit-metadata packaged JAR and a reduced runtime built with the same `jdeps`/`jlink` module-selection commands as the native packagers. The preserved pre-fix JAR fails the same probe with `Missing regex provider`; its descriptor contains only the JavaScript provider. The historical Tranche 1 runs independently establish the resulting actual Mermaid render failure. Tests never rescue the old JAR with development dependencies or convert a missing image into success.

Provider descriptor after both builds:

```text
com.oracle.truffle.js.lang.JavaScriptLanguageProvider
com.oracle.truffle.regex.RegexLanguageProvider
```

The correction preserves all archive entry names and all class bytes, including all 1,290 application classes. Changed resources are the embedded project POM and six service descriptors (Truffle, four Batik/XMLGraphics registrations and SLF4J). The general transformer also merges/normalizes those existing service resources; no dependency or provider implementation is added. The ordinary JAR changes from **49,958,834 to 49,958,828 bytes**. Two independently repackaged ordinary JARs have the same SHA-256:

```text
3037b3a8de905931a38c8df30e03fc61d596090beabb191a42752ce771d39c90
```

The commit-metadata build preserves `Shed-Commit` and both providers; its SHA-256 is `16e68c91ffc36bd14b625fc33fb86ad2ba1711a8c8fd30f6d43ee9485078d650` for commit `2f5da152afe6f13844d3040abf857dd1a20fc0d7`. The unmodified baseline JAR SHA-256 is `d0e8a3016989fcad23e9fc409dd585474e02062ed0723a111773ea254528fb97`.

### Performance and memory characterization

These are exploratory single-process observations on the Tranche 1 host: Intel MacBookPro15,2, i5-8279U, 8 GiB RAM, macOS 15.7.7, Temurin 21.0.12.1+1. They are not stable percentile qualification or before/after improvement claims.

| Final packaged smoke | Observation |
| --- | ---: |
| First simple Mermaid, after ordinary Markdown/math/font use, n=1 | 5,281.59 ms |
| Identical diagram, n=3 | 0.004–0.005 ms each |
| Changed three-node diagram, n=1 | 1,494.90 ms |
| Multiple-node/branch diagram, n=1 | 2,340.15 ms |
| New valid diagram after invalid input, n=1 | 1,273.81 ms |
| Reduced-runtime first Mermaid, n=1 | 5,631.45 ms |

Identical calls retain the existing session cache. Changed diagrams still incur the existing renderer's context/setup work. That architecture and its EDT-blocking behavior are deferred to Tranche 1C; no optimization is claimed here.

The unchanged Tranche 1 `assets-ready` harness also passed against the corrected JAR with the actual workbench initialized: first Mermaid **6,564.64 ms**; three identical calls **0.016–0.018 ms**; two changed diagrams **2,429.33–3,263.31 ms**. First font-ready math was **240.30 ms**, repeated math **0.010–0.023 ms**. Each render is on EDT in that harness. Its combined log first reports GraalJS context creation only after workbench readiness and explicit asset calls, not during ordinary startup. The fix changes no application initialization code.

That workbench run's load average was 7.35 before / 8.41 after (one-minute), and full UI took **17.06 s**. This outlier is retained rather than presented as a normal startup result. A bounded four-launch startup control then alternated **old / fixed / fixed / old**, with no Mermaid use and lower load around 2.8. Observations:

| Artifact | Verified input, seconds | Full workbench, seconds |
| --- | --- | --- |
| Old JAR, n=2 | 1.403 / 1.360 | 5.076 / 5.088 |
| Fixed JAR, n=2 | 1.382 / 1.353 | 5.169 / 5.115 |

These samples do not demonstrate a meaningful startup regression or improvement, nor establish a cold-cache baseline. All application/dependency class bytes are unchanged. No eager Mermaid work was added.

In the workbench asset run, used/committed heap before assets was **23.09 / 64 MiB**; after first successful Mermaid **202.00 / 260 MiB**; identical calls left those occupancy readings unchanged. Sampled peak whole-process RSS across the complete case was **813.64 MiB**. This is the existing renderer's now-functional runtime cost, not an idle-memory figure or a measured incremental leak caused by packaging. Closing assets leaves garbage awaiting GC; retained memory was not measured. No new application worker, cache, document copy or runtime dependency was introduced. The package's six-byte size reduction is not a memory-optimization claim.

Reproduce the workbench measurement with the unchanged Tranche 1 harness:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --mode assets-ready --sizes 4096 --samples 6 --warmups 0 --timeout 60 \
  --output /tmp/shed-fixed-mermaid-assets-NEW
```

For the startup control, run its `--mode startup-empty --shapes plain --sizes 4096 --samples 1 --warmups 0 --timeout 30` options once per artifact, supplying `--jar` and distinct new `--output` directories. Do not run builds or test suites concurrently with timing comparisons.

### Distribution boundary and remaining work

All three installer scripts copy the same Maven shaded JAR into the image and optionally select the tested commit profile. Both build configurations contain the correct merged metadata. The native scripts' module-selection path produced this reduced runtime on the current host:

```text
java.base,java.desktop,java.net.http,java.scripting,java.security.jgss,java.sql,jdk.compiler,jdk.management,jdk.unsupported
```

The packaged smoke also passes using that runtime. A macOS ARM64 DMG cannot be built by the supported script on this Intel host, and Linux DEB/Windows MSI require their respective hosts/tools. Those native installers, signatures and launchers were not executed or newly qualified here. Reduced-runtime success on macOS x64 is not a claim of ARM64/Windows/Linux installer acceptance.

The repair does not change the known expensive first/changed Mermaid rendering, preview concurrency, parser work, source-view performance, semantic modeling or inline presentation. Tranche 1B may proceed separately after this packaging correction; this tranche starts none of that work.

## Regression and repository checks

Final complete-suite command:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m test

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_benchmark_markdown.py
git diff --check
shasum -a 256 README.md
```

The complete Maven run executed **1,309 tests: 1,304 passed, five skipped, zero failures/errors**. It includes the unchanged Mermaid/math/local-image rendering tests, Markdown folding/editing correctness, preview synchronization and all Swing integration tests, plus persistence/undo/large-file/recovery coverage. The existing five skips are the platform-dependent DOS attribute, unavailable Fish/CTest and opt-in AI benchmark/native tests. Four deterministic Tranche 1 tooling tests also pass. The separate smoke is not counted as an ordinary JUnit test or silently skipped by Maven; the commands above explicitly exercise packaged rendering.

[Raw verification evidence](performance/markdown-tranche1a/results.json) contains build/runtime commands, artifact hashes and provider descriptors, smoke results/events, startup controls, memory observations, the workbench asset measurement, per-suite counts and preservation checks. The preserved original JAR's expected failure is separate from regression failures. Binary JARs, reduced runtime and scratch logs remain outside the checkout in the temporary run directory; none is added to Git.

Files changed by this tranche:

- `pom.xml`: one service transformer entry in each of the two existing shade configurations.
- `src/benchmark/java/shed/PackagedMarkdownSmoke.java`: independently compiled functional smoke with image/error/cache/session checks.
- `scripts/test-packaged-markdown.py`: bounded isolated packaged-runtime runner and optional bundled-runtime selection.
- `docs/MARKDOWN_PREVIEW.md`: link to the packaging regression instructions.
- `docs/PACKAGED_MARKDOWN_TEST.md`: reproduction, diagnosis, results and platform limitations.
- `docs/performance/markdown-tranche1a/results.json`: raw verification evidence.

The working tree was clean at the start. Every pre-existing tracked file except the two intentionally edited files (`pom.xml` and `docs/MARKDOWN_PREVIEW.md`) remains byte-identical to its recorded baseline. In particular, all production source, ordinary tests and Tranche 1 performance infrastructure remain unchanged. The design system, layout, visual identity, components, navigation, keymaps, document ownership, persistence and LSP behavior were not modified. No dependency upgrades, commits or pushes were made.

`README.md` is unchanged. Before/after SHA-256:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

Tranche 1A meets its acceptance criteria for the portable packaged artifact and tested reduced runtime, with the native-installer limitation stated above. It is ready for a separately authorized Tranche 1B. No subsequent optimization work has begun.
