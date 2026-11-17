# Markdown Tranche 3B — evidence

The complete [A–O implementation report](../../MARKDOWN_ASYNC_MEDIA.md) covers ownership, API, file inventory, security, limits and deferred presentation. This evidence does not declare the stock HTML preview responsive or resolve whole-process native-memory attribution.

## Files

- `cases.json.gz`: all raw benchmark reports, samples, source/runner/probe/JAR hashes, commands, host pressure/load and RSS series. `control-before`, `control-final`, `service-final-tested`, `startup-before` and `startup-final` are the final comparison. Pilots/earlier successful runs remain separately named.
- `measurements.json`: final latency, allocation, heap/RSS and lifecycle summaries.
- `regression-suites.json`: full suite counts and exact skip reasons.
- `packaged-smoke.json`: final packaged provider/Mermaid/math/image smoke.
- `verification-logs.json.gz`: complete task logs, including corrected development/setup failures.
- `renderer-inspection.json.gz`: local dependency bytecode inspection, including per-call Graal Context/helper-executor cleanup.
- `baseline-files.json.gz`: initial HEAD/status and 1,143 file hashes.
- `verification.json`: preservation, dependency/production hashes, exact artifact/toolchain identity and final status.
- `manifest.json`: hashes/byte counts of these evidence files.

## Reproduce

Use JDK 21.0.13 and Maven 3.9.11. The actual toolchain paths and exact child invocations are recorded in JSON. Run builds and benchmark processes serially, with fresh output directories outside the checkout. Existing earlier-tranche benchmarks/evidence are unchanged.

```sh
export PYTHONDONTWRITEBYTECODE=1
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar

# repeat control for preserved before.jar and final distribution
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar \
  --mode media-control --shapes plain --sizes 4096 102400 1048576 \
  --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-media-control-after
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar \
  --mode media-service --shapes plain --sizes 4096 102400 1048576 \
  --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-media-service-after
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar \
  --mode startup-empty --shapes plain --sizes 4096 --samples 1 --warmups 0 \
  --runs 3 --timeout 30 --output /tmp/shed-media-startup-after
```

## Method and limits

Same Apple M3, eight CPUs, 16 GiB macOS host; Java 21; 64–512 MiB child heap. Host load, memory pressure, existing swap and warm filesystem/font caches are uncontrolled and recorded. This is one process per size/artifact/workload, with three empty-startup observations, not a cross-machine confidence study.

The new service uses a small independently captured Markdown media revision while typing in Shed's real 4 KiB/100 KiB/1 MiB source buffer. This represents another open document/split rendering; source edits do not keep that independent document alive or cancel its work. One semantic parse prepares its requests outside the EDT. Each service/control case has 100 normal typing/undo samples during first Mermaid work, with **100 observed overlaps**, and checks exact source restoration. No synchronous source parsing, media decoding or renderer execution occurs on the EDT through this service. Forced completed Swing paint excludes native key delivery/compositor latency.

The legacy control executes existing `MarkdownPreviewAssets` operations on a diagnostic single worker so allocation/RSS and source interference can be measured. This is not a production preview migration or a claim that old preview rendering was asynchronous. Before/after control probes and runner hashes match. Sources/diagrams/formulas are matched with the service workload: a cold diagram, ten changed diagrams, a dark-themed diagram, eight formulas, one local image and repeated cache lookups. Legacy output includes its existing PNG encoding/files; new output uses bounded owned rasters. Do not equate those operations or compare a cache hit with cold initialization as a rendering speedup.

There are 100 cache-hit/typing observations per process, 20 math/image API observations, 11 warm Mermaid observations (including theme), eight queued math waits and one cold/image-render observation. For small samples, p95 is often the maximum, not a stable tail estimate; no p99 is inferred below 100 samples. Renderer allocation is **current worker thread only** and excludes Graal's own helper thread, JIT/native allocations and the final service-owned raster copy. Retention is measured separately after three requested GCs and short settling intervals. Whole-process RSS is sampled approximately every 250 ms, not a kernel high-water mark.

## Latency and queue

| Measurement (ms; p50 / p95) | 4 KiB | 100 KiB | 1 MiB |
| --- | ---: | ---: | ---: |
| Before legacy worker typing to paint (n=100) | 0.611 / 1.261 | 0.965 / 1.739 | 1.068 / 1.379 |
| After legacy worker typing to paint (n=100) | 0.715 / 1.622 | 1.051 / 1.543 | 1.090 / 1.814 |
| New service typing to paint (n=100) | 0.613 / 1.201 | 0.984 / 1.337 | 0.939 / 1.546 |
| New service inactive typing to paint (n=100) | 0.612 / 0.919 | 0.965 / 1.247 | 0.943 / 1.193 |
| Cached Mermaid dispatch EDT (n=100) | 0.017 / 0.032 | 0.016 / 0.033 | 0.015 / 0.035 |
| Cached Mermaid request/result (n=100) | 0.131 / 0.211 | 0.126 / 0.175 | 0.105 / 0.208 |
| Cold Mermaid renderer (n=1) | 1845.085 / 1845.085 | 1863.311 / 1863.311 | 1898.755 / 1898.755 |
| Warm/changed Mermaid renderer (n=11) | 315.468 / 566.639 | 310.602 / 554.279 | 322.144 / 562.914 |
| Changed Mermaid request/result (n=10) | 316.579 / 567.782 | 311.731 / 555.559 | 323.401 / 564.114 |
| Queued math wait behind Mermaid (n=8) | 1911.193 / 1911.411 | 1926.803 / 1927.101 | 1958.693 / 1958.953 |
| Math renderer, includes first use (n=8) | 0.077 / 65.441 | 0.087 / 62.441 | 0.079 / 58.790 |
| Cached math request/result (n=20) | 0.099 / 0.134 | 0.059 / 0.092 | 0.062 / 0.110 |
| Local image decode (n=1) | 0.373 / 0.373 | 0.487 / 0.487 | 0.377 / 0.377 |
| Image capture/validation (n=20) | 0.205 / 0.420 | 0.196 / 0.362 | 0.224 / 0.442 |
| Cached image request/result (n=19) | 0.336 / 0.548 | 0.295 / 0.564 | 0.317 / 0.598 |

Queue waits deliberately include head-of-line blocking behind first-use Mermaid. They are not source-EDT stalls. Each service case reaches exactly eight queue slots, rejects eight additional requests, coalesces one duplicate and records one stale request. All accepted jobs finish or are explicitly superseded; the worker remains occupied until actual completion even when a handle is cancelled. Unit latches separately verify noninterruptible cancellation and physical concurrency one.

## Memory and allocation

| 1 MiB source phase | Before legacy control heap used / committed (MiB) | After legacy control heap used / committed (MiB) | New service heap used / committed (MiB) |
| --- | ---: | ---: | ---: |
| Before media | 22.10 / 66 | 22.01 / 66 | 22.07 / 66 |
| Retained | 49.50 / 174 | 49.48 / 170 | 66.39 / 230 |
| Disposed | 49.09 / 174 | 49.07 / 170 | 47.96 / 167 |

| 1 MiB source RSS (MiB) | Before legacy control | After legacy control | New service |
| --- | ---: | ---: | ---: |
| Before media | 668.75 | 676.75 | 672.44 |
| Retained | 914.77 | 906.75 | 957.84 |
| Disposed | 914.69 | 906.75 | 893.48 |
| Sampled peak | 1221.70 | 1234.75 | 1226.30 |

The retained raster payload is 16,087,236 bytes (15.34 MiB), within the 16 MiB cache cap. All resident pixels/bytes, handles, sessions and queue entries return to zero on service close. All three disposed service weak references are collected and all three report **zero live media-worker threads**. Cached assets retain no document/snapshot/AST. Allocation and native libraries are not counted as cache retention.

Both before/after legacy controls and the new service exceed 1.2 GiB peak RSS in this repeated-render workload. This exceeds the historical 912.69 MiB index observation on the **before artifact too**, so the differing workloads cannot establish a new media-service regression. The final tables preserve the actual paired deltas rather than claiming universal equivalence or attributing all RSS to assets. Used heap, committed heap and RSS remain above startup after disposal in every renderer workload. Graal/font/JIT/native state remains a qualification; no general JVM/cache/font tuning was performed. These are observations, not a dominator census or native allocation attribution.

Current-worker allocation examples and full distributions are in `measurements.json`; the cold Mermaid worker allocates much more than the retained image, and its helper-thread allocations are additional. Do not describe a small cache or collected service as proof that the whole app is low-memory.

## Startup and regression budgets

Three empty-startup controls per artifact retain approximately 23 MiB used / 64 MiB committed heap and 19 threads, with no media job/initialization. Existing startup job lists and unchanged composition code show that no service is constructed/requested automatically. Exact full-UI times and RSS ranges are retained in the raw reports; the small sample supports a startup control, not a universal no-regression guarantee.

Fixture gates: source typing-to-Swing-paint p95 <=16 ms during media work; cached dispatch EDT p95 <5 ms; cached Mermaid/math request-result p95 <10 ms; queue <=8, physical service render concurrency <=1, consumer handles/leases <=64, cached raster bytes <=16 MiB and all resident raster bytes <=32 MiB / eight Mi pixels. All three source sizes meet those measured gates. Local image hits include bounded filesystem validation and have a separate 20 ms fixture budget, not a promise for arbitrary storage. Cold/warm rendering is reported separately; small-sample timings are not hard deadlines. Check host load before attributing differences. Do not increase semantic limits or JVM heap to obtain a passing fixture.

## Verification and preservation

Final Maven: **1,471 total; 1,466 passing; five existing skips; zero failures/errors**. All 31 new media-service tests pass. Python benchmark tooling: 12 passing tests. Final packaging, packaged smoke, nine comparison cases and six startup controls pass; fixtures are unchanged. Final-source hashes match the evidence and current/start-HEAD whitespace checks pass.

Development logs preserve a test using a nonexistent FileBuffer constructor, JLaTeXMath accepting an unbalanced expression (now explicitly rejected by the adapter), and a final-probe attempt with an unset required Java 21 toolchain (rerun with the recorded environment and a new output directory). No failed attempt is reported as passing and no assertions were weakened. Benchmark-only diagnostic changes are separately hashed in earlier/final reports.

The default preview remains synchronous and can wait on shared renderer serialization if a future consumer runs concurrently. No claim of simultaneous-preview or HTML-layout responsiveness follows from the source-only service measurements. A second OS/filesystem and native installers were not exercised. Previous performance evidence, README, document/editing/highlighting/layout code and dependency manifests are hash-checked against the starting inventory.
