# Tranche 1C.2 — HTML reader memory

Read [the engineering report](../../HTML_READER_MEMORY.md). This tranche retains one preview-specific array-range correction. It does not complete 1C or change the 1C.1 table/reflow NO-GO findings.

| Artifact | Contents |
| --- | --- |
| `cases.json.gz` | All 40 child reports: exact commands, fixture parameters/hashes, JAR/probe/runner hashes, environment, every stage/sample, counters, RSS samples and completion states. |
| `measurements.json` | Readable raw reader/constructor rows, heap-pool upper bounds, requested-GC occupancy and queue callbacks by case. |
| `executions.json` | Sequential discovery and paired commands with exit codes and elapsed wall time. |
| `attribution.json` | Installed JDK source hashes, bounded JFR identities and weighted allocation-stack summaries, with streaming decoder source. |
| `counted-allocation-sites.txt`, `slice-allocation-sites.txt` | JDK `jfr view allocation-by-site` output; sampled traffic across whole children, not retained heap. |
| `diagnostic-logs.json.gz` | Original detached/production OOM stacks and pilot/focused-test diagnostics. |
| `verification.json` | Final Maven suite/case totals, commands, skips, packaged smoke, hashes and preservation checks. |
| `verification-logs.json.gz` | Verification command output, including the initial full-suite test-helper failure. |
| `packaged-smoke.json` | Actual shaded-JAR Mermaid/math/image/runtime-provider regression result. |
| `manifest.json` | Evidence hashes, excluding this manifest. |

## Versions and limitations

`discover-*` uses the preserved pre-fix artifact and isolated stock/counting/slice prototypes. The counted reader changes only diagnostics; the slice prototype is never registered in that artifact's application. `paired-*` compares stock reads from the preserved artifact with the actual production `MarkdownPreviewHtmlKit` loaded from the new JAR. `paired-production-after-{0,1,2}` each creates the real packaged preview once in a fresh process. The entire child classpath is the frozen diagnostic classes plus the supplied JAR; no Maven development dependencies are added.

`discover-original-stock` and `discover-original-production` are intentional bounded failures, ERROR/OOM. There is no completed reader timing or successful geometry claim for either. Their OOM stacks match. The first `pilot-slice-small` failed an incorrect terminal-newline extractor; `pilot-small-exact` corrects that extractor and requires exact CommonMark literal equality. Both are retained.

The final generator is `html-reader-v2`: prose uses 1C.1 p-space normalization, and the exact original fixture can be regenerated without external files. The earlier discovery `prose` fixture is the legacy plain generator, including its artificial long padding; do not pool it with paired ordinary prose. Frozen source hashes identify the exact probe version per run. Pairwise text and element-tree hashes match for 64/128 KiB, prose, table, special-character, single-line and EDT setText cases. The actual repaired preview also matches the original composite's independent code literal and repaired detached text hash.

Zero discarded warmups; three measured reads per child except original failures and actual preview constructors (one each). Report median/max and raw samples; n=3 cannot establish p95/p99 or meaningful host-wide confidence intervals. Class/font/JIT initialization and existing host load/swap are recorded, not controlled away. Whole-process render replays can initialize fonts before reader timing and affect RSS. No source-typing responsiveness claim follows from these reader timings.

Heap-pool peak sums are upper bounds over read **and validation**, not simultaneous peak heap. Requested-GC occupancy is not a retained-object/dominator census. The production-constructor queue callback additionally includes diagnostic text/tree checking and disposal. The EDT setText callback avoids that validation on EDT, but still excludes explicit layout/painting and physical input. No table/resize responsiveness improvement is established.

## Reproduce

Use the existing Java 21/Maven toolchain, Python 3 and a graphical desktop. Each output directory must be new and outside the repository. Heavy workloads are opt-in and are not discovered by normal Maven tests.

Build the actual distribution:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -DskipTests \
-Dmaven.jar.forceCreation=true package
```

Run a corrected bounded read:

```sh
python3 scripts/benchmark-html-reader.py \
  --jar target/shed-2.0.0.jar --output /tmp/new-reader-128 \
  --case code128 --mode repaired --samples 3 --timeout 45

python3 scripts/benchmark-html-reader.py \
  --jar target/shed-2.0.0.jar --output /tmp/new-reader-original \
  --case original --mode repaired --route production --samples 1 --timeout 45
```

`--case original` exactly regenerates SHA-256 `2bd8379d31ec16a14bd860a6550dd76fd774045354839709dd2bac5179c46825`. Optional `--reproducer PATH` copies an existing input only after validating its size. Additional cases: `small`, `code64`, `code128`, `code256`, `single`, `special`, `prose`, `table`, `raw-pre`. Use `--route raw` for `raw-pre`, `--route setText` for EDT installation, and default `detached` for an independently owned HTML document. `production` always uses the JAR's preview and accepts one constructor sample.

For a same-JAR reader comparison, `--mode stock` uses the stock HTMLEditorKit and `--mode repaired` uses the production class. `--mode counted` records incoming/range character counts without slicing; `--mode slice` is the isolated prototype. For a true production-before comparison supply the preserved pre-fix JAR recorded in case commands; selecting `stock` does not undo an application's factory registration.

**Known-risk cases:** original stock/old production exhausted 512 MiB twice, establishing the mechanism. They are not required for routine regression or an invitation to repeat exhaustion. The fixed script never raises the heap. Its exit status is nonzero for ERROR/TIMEOUT/OOM, and preserves logs for inspection.

Safety bounds: 1 MiB fixture input; 64–512 MiB JVM heap; 1–5 read samples; 5–60 second child watchdog; 64 MiB optional JFR. The Python runner removes ambient Java/classpath overrides from its runtime child, samples RSS and terminates only its own child on timeout. It writes only within the explicitly new output directory. No global stress or user-document changes occur. Full frozen inputs/classes/logs/JFR remain in the external run root named by the archived commands (about 122 MiB for this investigation), intentionally retained evidence rather than active workers.

Profile a moderate, successful workload:

```sh
python3 scripts/benchmark-html-reader.py \
  --jar target/shed-2.0.0.jar --output /tmp/new-reader-profile \
  --case code128 --mode counted --samples 3 --timeout 45 --profile

"$(scripts/resolve-toolchain.sh home)/bin/jfr" view \
  --width 200 --cell-height 5 allocation-by-site /tmp/new-reader-profile/profile.jfr
```

For the existing general profile decoder, compile `src/benchmark/java/shed/MarkdownJfrSummary.java` against the JAR into a disposable directory and invoke `shed.MarkdownJfrSummary` on that recording. The allocation-stack decoder used for attribution is preserved verbatim in `attribution.json`; it streams JFR rather than materializing all events.

Verification commands:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m \
-Dtest=MarkdownPreviewHtmlKitTest,MarkdownPreviewPaneTest test

JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m test

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-packaged-markdown.py \
  --jar target/shed-2.0.0.jar --output /tmp/new-reader-packaged.json
git diff --check
shasum -a 256 README.md
```

New JUnit geometry checks use complete text/structure, exact preferred sizes and selected model/hit-test positions, plus top RGB crops at two widths. They preload the same existing image data for both readers. Existing application integration tests separately cover scroll/caret synchronization and lifecycle. No assertion tolerance or production behavior was weakened to obtain matching output.
