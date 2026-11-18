# Tranche 4A evidence

**CONDITIONAL GO for limited geometry research; NO-GO for production activation of this prototype.** The Unicode open-view heap increase and multi-second first edit, plus document-wide initial geometry preparation, remain blockers. See the complete [A–O engineering report](../../MARKDOWN_LIVE_PRESENTATION_FEASIBILITY.md). This evidence includes unsuccessful feasibility experiments; process completion does not mean performance acceptance.

## Scope and provenance

Recorded on 18 November 2026, Apple M3 / 16 GiB / macOS 26.5.2, Temurin 21.0.13, Maven 3.9.11. Full environment, load, pressure, swap, fixture hashes, source/probe hashes, raw samples, JVM commands and process RSS series are in compressed reports. Children have a 512 MiB heap and 90-second watchdog. Cases ran serially; there is one process per case. Host load was variable. Primary distributions have 100 measured samples; shape cases have 30; first-edit comparisons have only three and are reported as maxima. Five explicit preparation samples do not establish robust p95 estimates.

The control and prototype share the production buffer and listeners in a disposable workbench process. Diagnostic surfaces temporarily replace its content pane; production defaults are untouched. Forced Swing paint completion is measured, not compositor/display latency. Ordinary repeated typing is **raw-source fallback** after the first edit, not continuously styled editing. No automatic full parse is added to an ordinary edit.

## Artifact inventory

- `control`, `prototype`, `wrap-control`, `wrap-prototype`: paired 4 KiB, 100 KiB, 1 MiB mixed-source measurements.
- `shapes`: 100 KiB headings, Unicode and fence-generator fallback. The 100 KiB fence fixture contains zero complete fence blocks.
- `unicode-control`: matched Unicode source control; important for separating existing complex-text stalls from prototype overhead.
- `large-fence`: four complete large fenced blocks plus overlong padding; semantic line-length rejection, not evidence for supported large-fence layout.
- `long-prose`: 32 KiB single-line geometry rejection.
- `unsupported`: above-cap 2 MiB plain and long-line source fallback.
- `styled`: bounded 4/100 KiB separate styled-document experiment. It cannot attach the canonical PlainDocument and is rejected on ownership. Its layout replay timing excludes install/validation; it is not a full editor comparison.
- `pilot`: earlier small diagnostic, retained separately from final controls. Its source/probe hashes can differ.
- Each named `.json.gz` is a raw report; `measurements.json` is a compact, unfiltered phase/summary view. The 22 final cases and one pilot completed; rejected presentation states are explicit.
- `probe-logs.json.gz`: child stdout/GC logs. `verification-logs.json.gz`: tests, packaging, probes and unsuccessful development attempts.
- `baseline-files.json.gz`: initial HEAD/status and 1,160 source/evidence hashes. `verification.json`: protected-file and packaged-class comparison.
- `regression-suites.json`: all suite counts and five skip reasons. `packaged-smoke.json`: real packaged Mermaid, math and image verification.
- `images.json` records PNG provenance. `unwrapped-initial.png`, `wrapped-initial.png`, `wrapped-selection.png` are isolated prototype captures, visually inspected for typography, source markers and source selection. No production UI is shown as a new feature.
- `manifest.json`: SHA-256 and byte size for every archived artifact (excluding itself), plus the report and implementation files.

## Verification

Maven: **1,491 total, 1,486 passed, five existing skips, zero failures/errors**. Twenty new tests. Python tooling: **13 passed**. Packaging and packaged smoke passed. Early failed new-test attempts remain in the logs; no existing assertions were weakened or tests disabled. Native IME, accessibility and additional platforms were not exercised.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 scripts/test_benchmark_markdown.py
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --output /tmp/shed-tranche4a-run/packaged-smoke.json
git diff --check
```

The environment file selects the recorded JDK/Maven. To reproduce elsewhere, supply compatible JDK 21 and Maven 3.9.11. Use fresh output directories; preserve historical results.

## Benchmark commands

These are opt-in. Do not run them concurrently with Maven or other heavy probes. All original parsed arguments and child commands are also retained in each report.

```sh
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-control --shapes mixed --sizes 4096 102400 1048576 --samples 100 --warmups 5 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-control
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-prototype --shapes mixed --sizes 4096 102400 1048576 --samples 100 --warmups 5 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-prototype
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-control --shapes mixed --sizes 4096 102400 1048576 --samples 100 --warmups 5 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-wrap-control --wrap
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-prototype --shapes mixed --sizes 4096 102400 1048576 --samples 100 --warmups 5 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-wrap-prototype --wrap
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-prototype --shapes fence headings unicode --sizes 102400 --samples 30 --warmups 3 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-shapes --wrap
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-prototype --shapes long-line plain --sizes 2097152 --samples 3 --warmups 0 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-unsupported
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-styled --shapes mixed --sizes 4096 102400 --samples 3 --warmups 0 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-styled
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-control --shapes unicode --sizes 102400 --samples 30 --warmups 3 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-unicode-control --wrap
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-prototype --shapes fence --sizes 1048576 --samples 10 --warmups 2 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-large-fence --wrap
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-prototype --shapes long-line --sizes 32768 --samples 3 --warmups 0 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-long-prose
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode presentation-prototype --shapes mixed --sizes 4096 --samples 10 --warmups 2 --runs 1 --heap 512 --timeout 90 --output /tmp/shed-tranche4a-pilot
```

## Gate results and bounded retention

ASCII source-fallback typing remains short, but does not prove live editing. Initial 1 MiB geometry medians are 3.82/5.45 seconds. Scroll action p95 regresses from 0.82 to 2.55 ms unwrapped. Unicode first-edit maxima are 2.48 seconds prototype / 1.96 seconds control. Retained heap while Unicode presentation is open is 189.24 MiB / 53.64 MiB control; after disposal both return near 33.5 MiB. This is a failed production gate with unresolved attribution, not a fixed regression.

Frames cap rows/spans at 32,768 and per-line geometry at 4,096 UTF-16 units; shaped rows cap at 128 / 16,384 units. These bounds do not establish that the complete Swing hierarchy is sufficiently bounded. Small cases repeat three construction/disposal cycles; all observed prototype/frame weak references clear. Larger repeated cycles, actual native IME and cross-platform graphics resource attribution remain unverified. No media renderer initializes in these probes.

All 401 original production files, 242 previous performance artifacts and all 1,406 packaged Shed classes are unchanged. README before/after SHA-256 is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No production activation, startup registration, new dependency, commit or push was made by this task. The report records external HEAD advancement. Tranche 1C remains incomplete and 4B has not begun.
