# Tranche 7B.2 evidence

Read the [implementation report](../../BUFFER_LIFECYCLE_REMEDIATION.md) for the A–M findings, ownership rules, limitations and next recommendation.

## Epochs and measurements

- **Before:** frozen packaged JAR from the clean starting checkout, with 7B.1 corrections and Light/JetBrains Mono defaults.
- **Intermediate (`after-*` raw reports):** the index/job cleanup patch, before the later watcher correction. The eight direct-copy buffers collected, but the initial normally opened buffer still had a file-watcher root. These reports remain evidence, not final success claims.
- **Final (`final-*`):** the complete confirmed cleanup, including watcher registration ownership and buffer replacement boundaries. Final JAR identity is in `verification.json`; every raw report keeps its own actual JAR/probe/runner hashes.

No prior evidence was overwritten. Existing eight-file, lifecycle, index, media and UI workloads remain independent controls. Additive cycle modes introduce watched/direct variants rather than changing an earlier result into a different workload. Versioned original/intermediate diagnostic source is preserved in the compressed log archive; final source is in the repository.

Final complete Maven: **1,561 run, 1,556 passed, five skips, zero failures/errors**. Focused ownership tests: **30 passed**. Final tooling: **19 passed**. Final packaged Mermaid/math/image smoke: **PASS**.

## Evidence files

| File | Contents |
|---|---|
| `baseline-files.json.gz` | Initial HEAD, clean status, file hashes and pre-change artifact identity |
| `inventory.json` | Every changed and added path relative to that initial inventory |
| `measurements.json` | Per-case state, explicit artifact epoch, complete timing summaries, heap/GC/resource observations and sampled RSS |
| `ownership-observations.json` | Actual retaining paths, weak buffer/snapshot/Undo observations, index/watcher ownership counts and index/media disposal observations |
| `before-*.json.gz` | Full frozen-artifact measurements, including repeated registration/cycle and existing controls |
| `after-*.json.gz` | Intermediate results, including the newly exposed watcher-retention path |
| `final-*.json.gz` | Full final packaged-artifact measurements |
| `diagnostics-and-logs.json.gz` | Failing-before tests, focused/full Maven and packaging logs, CLI/probe logs, NMT/heap/class/VM-map/thread diagnostics, measurement/archive scripts and prior probe-source versions |
| `commands.json`, `final-commands.json` | Serial command lists and exit codes, including intermediate and final checks |
| `regression-suites.json` | Final full-suite class totals and exact skip details |
| `packaged-smoke.json` | Final packaged-only Mermaid/math/image smoke and classpath/artifact identity |
| `recordings.json` | Paths, sizes and hashes of opt-in JFR recordings; binary recordings remain outside the checkout |
| `verification.json` | README hashes, protected source/default/previous-evidence checks, original/final JARs, HEAD, test totals and diff check |
| `manifest.json` | Evidence checksums |

All `.json.gz` files are UTF-8 JSON after gzip decompression. For example:

```sh
gzip -dc docs/performance/buffer-tranche7b2/final-watched-cycles.json.gz | python3 -m json.tool
```

## Reproduction

Use new paths outside the checkout, JDK 21 from the repository resolver, isolated homes and serial processes. Child heaps remain 512 MiB; no system-wide memory stress, JVM-option change or private-document modification is used.

```sh
python3 scripts/qualify-markdown.py --jar /path/to/frozen-before.jar --group retention --output /tmp/shed-retention-before-new
python3 scripts/qualify-markdown.py --group retention --output /tmp/shed-retention-final-new
python3 scripts/qualify-markdown.py --group retention-cycles --output /tmp/shed-retention-cycles-new
python3 scripts/qualify-markdown.py --group watched-cycles --output /tmp/shed-watched-cycles-new
python3 scripts/qualify-markdown.py --group lifecycle --output /tmp/shed-lifecycle-new
python3 scripts/qualify-markdown.py --group index --output /tmp/shed-index-new
python3 scripts/qualify-markdown.py --group rich --output /tmp/shed-rich-new
python3 scripts/benchmark-markdown.py --mode ui --shapes mixed --sizes 102400 1048576 --samples 100 --warmups 10 --output /tmp/shed-retention-ui-new
```

Cycle probes open/attach and close **three batches of eight** fixture copies, preserving one legitimate open buffer, without completion-query pruning or 200-job turnover between batches. Direct attachment intentionally does not register file watches; the watched variant calls the real recovery registration API. The Java control is a 1 MiB valid class plus comment-heavy filler. Those controls do not model all Java editing.

The original retention probe separately performs a completion query and 201 no-op jobs as a causal control. Final objects already collect **before** those actions. New cycle metadata observes weak buffers, captured source snapshots and Undo managers, index entry count, watch file count and a bounded strong path from the editor. Requested GC is diagnostic only; deterministic field/registration/race tests provide additional evidence.

Read/decode latency runs outside the EDT in the probe's main thread, not through the entire application opening pipeline. Attachment/close timings measure existing EDT lifecycle operations and allocations; they are not automatically open-to-real-paint timings. The independent UI workload measures attributed completed Swing paint. Source-only typing passed 16 ms p95 at 100 KiB and 1 MiB; startup samples are too few/noisy to constitute final release qualification, and the baseline includes a visible-window target miss.

Index controls use the existing **1,002-file low-density diagnostic**, distinct from the historical 1,001-file performance fixture. Rich controls keep service and legacy renderer workloads separately labeled. Both retain high native/process RSS; that is not hidden or equated to closed-buffer heap retention.

## Failure and limitation visibility

The failing-before index/job tests and the two later watcher regressions are retained. The first intermediate full suite passed, but its lifecycle probe still retained the watched source; the final suite and final lifecycle probe were required afterward. Every final measured case completed successfully. This is not proof of universal absence of leaks.

FileWatcher directory registrations keep their existing service lifetime. Native-memory attribution, Unicode conformance/layout, preview responsiveness and cross-platform/native input qualification remain open. No unrelated remediation or appearance work began. README remains byte-for-byte unchanged; no commits/pushes were created by this agent.
