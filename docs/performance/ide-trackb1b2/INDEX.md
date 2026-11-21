# Track B1B.2 evidence

Read [MISSING_GLYPH_RENDERING_QUALIFICATION.md](../../MISSING_GLYPH_RENDERING_QUALIFICATION.md). **PARTIAL investigation; the P1 default-font rendering finding remains open. No production change was accepted.** Passing JUnit checks source/observation contracts, not universal glyph correctness.

## Primary qualification

- `all-safe.json.gz`: final **192 passed**, zero failures/errors/skips, **43** explicitly reviewed classes; all three measurement opt-ins enabled. Includes glyph/font/GB9c/Unicode persistence tests plus prior safe document, gutter, split/Undo, source-view, appearance, Markdown and in-memory LSP checks. No real server/window/shell fixture.
- `all-safe-corpus.json`: exact font coverage, UTF-16/U+ positions, GlyphVector IDs, TextLayout bounds, font metric/caret geometry, reference-family availability, expected behavior and capture metadata for the 18-case matrix. GlyphVector LTR diagnostics are distinct from TextLayout/Swing bidi behavior.
- `corpus.json`: compact primary-font fixture definitions; all exact source strings, lengths, U+ records, hashes and expectations. Uncovered assigned scripts are not labeled unsupported merely because the primary font lacks them. Reference paths on this host are capable of the sampled assigned scalars; U+10FFFF is a separate unsupported noncharacter control.
- `rendering-status.json`: explicit missing-scalar register, rejected same-family lookup candidates, logical-font/ZWJ limitations and the raw EOF sentinel qualification. Missing glyphs are retained, not converted into passing font assertions.
- `fixtures/b0-mixed.md`: exact **600 UTF-8 bytes / 430 UTF-16 units** matching historical B0 SHA-256 `2481b5a8bd7b37aad5d78bc374658f14a26d177c15dd367741ae59e0e35f0545`.
- `all-safe-capture.json`: exact B0 source, simulated 1×/2× captures, pixel/geometry restoration, source/history invariants and raw hit-test records. At source EOF 430 the raw mapping is **431**, explicitly **not a source offset**. Only the known root-implied endpoint is allowed in the characterization; actual source selections remain bounded.
- `visual/`: 63 PNGs, including default tofu, same-family lookup failures, positive installed-font controls and exact B0 source views at 1×/2×. PNG encode/decode round-trip comparisons are exact. Representative CJK/Indic/ZWJ/mixed/ASCII captures were inspected; the full corpus is not a linguistic/shaping oracle. Logical Dialog restores scalars but does not fully join sampled ZWJ emoji, unlike the installed Apple Color Emoji control. Do not adopt reference fonts as a production default.

## Performance and resources

- `fresh-measurements.json.gz`, `fresh-measurements-measurements.json`: one opt-in measurement test passed in a fresh Maven/test JVM. One first + 50 warm paint/selection/scroll samples, one first + 10 warm font/palette round trips, 24 component disposal cycles. First font lookup **2,480.11 ms**, first ASCII paint **24.25 ms** and font-transition costs remain separate from warm paints. No GC during latency samples.
- `all-safe-measurements.json`: separate already-warmed suite observation; do not pool with the fresh process.
- `all-safe-typing.json`: existing B1A ordinary typing probe, including queueing and completed source-component paint. 1 MiB warm p95 **1.005 / 0.428 ms** without/with an in-memory definition pending, 50 warm samples per phase. This is not native display/input or Unicode first-edit requalification.
- Closed component diagnostic: zero of 24 pane weak references remain after post-timing diagnostic GC, listener count 1→1. Combined heap, descriptors and threads are observations, not a per-font retained-heap or native-resource census. No whole-process RSS/startup equivalence claim.
- `environment.json`: hardware/OS/JDK/arguments, simulated-scale limits, sample boundaries and load observations. New measurement harness was verified on macOS; Windows/Linux GUI and profiling remain unqualified.

## Intermediate results and failures

- `initial-glyph.json.gz`: initial matrix with one passing characterization test; reproduces primary-font tofu and failed registered-family/public-StyleContext controls before any production change.
- `focused-glyph.json.gz`: **9 passed / 1 failed**. The glyph measurement case passed; the new capture test incorrectly required every raw Swing hit-test output to be a valid source offset. This is not a font-remediation failure or source-mutation finding.
- `capture-geometry-trace.json.gz`: **1 passed / 1 failed**, retaining the same assertion and exact per-offset instrumentation. Raw source EOF 430 maps to 431 at the empty final paragraph; earlier sampled source positions, including ASCII 4 and Indic 28, map correctly. No runtime/source change occurred between these controls.
- `capture-source-contracts.json.gz`: the two checks pass after distinguishing the known root sentinel from valid source coordinates. Pixel, source, selection and history assertions remain strict. Raw invalid-source status at EOF stays visible; this is not a passing unchecked-coordinate contract or global JDK geometry correction. Previous regression assertions were not changed.

The failed runs are retained rather than relabeled as full successes. Component PNG comparisons did not fail; the new overly strict raw-range assumption did. Old full-root overprinting is still unattributed.

## Regression, production identity and preservation

- `tooling.json.gz`: **87 Python tests passed**, zero failures/skips, under a disposable parent environment. Exact manifest source hashes and the new package/measurement exclusion case pass; native admissions remain rejected.
- `packaged-smoke.json.gz`, `packaged-smoke-driver.json.gz`: unchanged application JAR PASS for Mermaid, math, local images, providers, invalid recovery and a second asset session. Compiler/runtime Java homes/temp/preferences were disposable. No new package build or production feature was needed.
- `artifact-verification.json`: all **407 production Java files** unchanged; all **1,429 compiled production classes** match the unchanged JAR `885dd51ae7ca684632f5eb19df04618a372e8bcf85769256c6acb277363315b5`; font-resource hash; no new test class shipped.
- `source-hashes.json`, `source-versions/`: exact final new test/tooling sources and admission manifest. Existing font/Indic/Unicode tests were admitted unchanged after source review.
- `original-evidence.json`: hashes/locations of retained B0/8H captures/logs, capture mechanism and exact source identity. Old source contains a native helper, but it was inspected only and not executed.
- `baseline.json.gz`, `repository-verification.json`: entry/final Git state, external commits, starting-file inventory/preservation and README identity. This agent made no commits/pushes.
- `checksums.sha256`: evidence integrity, excluding itself.

## Reproduction and safe boundary

Use an existing Java 21/Maven/offline dependency cache. The runner uses a bounded disposable dependency copy, empty Maven settings, isolated Java home/tmp/preferences and in-process Surefire. It is source-reviewed execution, not general process confinement. No native/full-suite override is introduced.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-portable-tests.py \
  --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home \
  --maven /tmp/shed-tranche2b-tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --measure-lsp --measure-glyph --measure-gutter \
  --gutter-baseline /private/tmp/shed-trackb1b1-run/baseline.jar \
  --output /private/tmp/shed-trackb1b2-run/all-safe.json
```

Choose a **new** output path for replay. Gutter comparison still requires its previously pinned B1A JAR. It instantiates only the old gutter, not the main application. If unavailable, omit the gutter opt-in rather than replace its control or bypass checks.

For just the new profile, use `--tests GlyphRenderingQualificationTest`; for fresh measurements `--tests GlyphRenderingMeasurementTest --measure-glyph`. New glyph measurement remains opt-in. Exact expanded argv, Java arguments, logs and cleanup results are archived. Python discovery and packaged smoke used the existing scripts under fresh Fixture environments; no normal personal shell configuration was used as a test fixture.

**Excluded:** full historical native/GUI Maven suite, PTYs, real language-server children, main-window/native capture replay, physical keyboard/IME/accessibility/monitor tests, native-memory profiling and Windows/Linux GUI. No unrestricted screenshot, native shell harness, font installation, new dependency or production setting was invoked. B0.1's failed confinement positive control remains NO-GO.
