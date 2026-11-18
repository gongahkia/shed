# Tranche 7B.1 evidence

See [the implementation report](../../UNICODE_CORRECTNESS_REMEDIATION.md). This tranche corrects verified Indic GB9c boundaries and the shared forward-character deletion Undo behavior. It does not qualify all Unicode input or fix 7A's Unicode layout stalls.

## Inventory

| File | Contents |
|---|---|
| `baseline-files.json.gz` | Initial status, HEAD, JAR identity and file hashes; includes pre-existing 7A work |
| `inventory.json` | Complete changed/added path inventory relative to that initial state, independent of later external commits |
| `unicode-provenance.json` | Pinned Unicode 15.1 URLs, original full-property checksum, vendored extract and verbatim official test-corpus checksums, license identity |
| `conformance-before-final.json`, `conformance-after.json` | Every official corpus mismatch, exact expected/actual UTF-16 boundaries, GB9c counts, JDK alternative checks and helper timing/allocation data |
| `conformance-comparison.json` | All seven GB9c cases corrected, no new failure records, 51 unchanged legacy mismatch records; full conformance remains false |
| `before-ui.json.gz`, `after-ui.json.gz` | Unchanged packaged-JAR source editor benchmark, complete commands/environment/fixture/probe hashes and real-paint samples |
| `measurements.json` | Full timing summaries, heap/GC metadata and sampled peak RSS from paired UI workloads |
| `regression-suites.json` | Final Maven class totals and skip details |
| `verification-logs.json.gz` | Deterministic failing-before test, Undo reproduction, intermediate test-development failures, final focused/full Maven, packaging and tooling logs, serial measurement/archive scripts |
| `commands.json` | Exact post-change benchmark, packaged smoke and tooling invocations with return codes; JVM commands are also in raw benchmark reports |
| `packaged-smoke.json` | Packaged-only Mermaid/math/image execution, PASS |
| `verification.json` | README, artifacts, initial/final HEAD, source/default preservation and final test totals |
| `manifest.json` | Evidence checksums |

The benchmark corpus is official Unicode **15.1.0**. The property fixture is only the InCB section of `DerivedCoreProperties.txt`; comments identify the extraction. Lookup tests compare every Unicode code point against that extract. Runtime lookup tables are compiled literals, with no runtime data parsing or new dependency. Unicode notices are in `docs/UNICODE_LICENSE.txt` and the packaged license directory.

## Results and limitations

- Maven: **1,548 run; 1,543 passed; five existing skips; zero failures/errors**.
- Combined focused regression: **22 passed**. The final extended route/recovery test also passed, followed by the full suite.
- Tooling discovery: **19 passed**.
- Packaged media smoke: **PASS**.
- Entire official corpus: **1,129/1,187 before; 1,136/1,187 after**. All **seven GB9c cases pass** after correction.
- **51 mismatches remain.** Full conformance is false; these are report-only diagnostic results, not silently skipped JUnit failures or a passing conformance certificate. All remaining failure records are identical to baseline. The focused JUnit cases assert supported behavior strictly.
- One-shot 1 MiB source typing p95: **1.224 ms before / 1.228 ms after**, 100 samples, 10 warmups, same Light/JetBrains Mono defaults.
- Helper timing shows zero measured per-call thread allocation. Existing linear backward traversal at a Unicode EOF still costs about 20 ms for a 1 MiB prefix; no claim is made that all boundary operations meet the ordinary typing budget.
- RSS peaks and no-GC heap samples are not retained-memory equivalence. Prior native-memory, buffer-retention and preview/Unicode-layout issues remain open.
- No new native IME, external LSP or Windows/Linux desktop qualification. Plain native single-caret actions were not replaced or universally certified.

The original failed regression expected boundary 3 but observed 2. The subsequent Emacs integration reproduction showed two Undo records because character deletion called `setText`; the final range deletion uses one edit. Logs also retain a wrong test API name and an isolated test snapshot requiring explicit reconciliation. Final production assertions were not disabled.

## Reproduction

Use fresh output paths outside the checkout and the repository's JDK 21 resolver. Source benchmarks are headful, serial, isolated-home workloads; the new artifact defaults to light mode.

```sh
python3 scripts/benchmark-markdown.py --jar /path/to/before.jar --mode ui --shapes mixed --sizes 102400 1048576 --samples 100 --warmups 10 --output /tmp/shed-unicode-before-new
python3 scripts/benchmark-markdown.py --mode ui --shapes mixed --sizes 102400 1048576 --samples 100 --warmups 10 --output /tmp/shed-unicode-after-new
```

The conformance probe is read-only and report-only: **inspect its `failed` and `gb9c_failed` fields**, rather than treating process exit as conformance success. It reports all mismatch records and separate micro-timings. It does not mutate documents, run as part of startup, or run heavy workloads through Maven.

```sh
qualification_jdk=$(scripts/resolve-toolchain.sh home)
mkdir -p /tmp/shed-unicode-probe-new
"$qualification_jdk/bin/javac" -cp target/shed-2.0.0.jar -d /tmp/shed-unicode-probe-new src/benchmark/java/shed/GraphemeQualificationProbe.java
"$qualification_jdk/bin/java" -Xms64m -Xmx256m -cp /tmp/shed-unicode-probe-new:target/shed-2.0.0.jar shed.GraphemeQualificationProbe src/test/resources/unicode/15.1.0/GraphemeBreakTest.txt
```

Before/after use exactly the same final diagnostic source. An initial development timing run included the timing-array allocation in its counter; final paired measurements allocate that array before the counter and do not attribute harness storage to the boundary helper. The earlier development output remains outside the checkout; the final JSONs are the reported pair.

README SHA-256 before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Prior 7A evidence and default-appearance sources remain unchanged. External commits were preserved; this agent created no commits or pushes. No other 7B, final qualification or appearance work began.
