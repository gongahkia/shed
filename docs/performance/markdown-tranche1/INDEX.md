# Tranche 1 evidence

Read the [baseline report](../../MARKDOWN_PERFORMANCE_BASELINE.md) for methodology, environment, exclusions, results and follow-up work. These files contain measurements, not performance optimizations. Large corpora, JFR binaries, GC logs and full timeout dumps remain in the temporary run root named in that report.

| File | Contents |
| --- | --- |
| [runs.json](runs.json) | Exact fixture byte sizes/SHA-256/generation parameters, runner/probe/JAR hashes, JVM version and per-run arguments. |
| [cases.json](cases.json) | Per-case commands, environment before/after, sampled RSS, memory/GC phases, operation summaries and explicit completion/error/fallback states. |
| [samples.jsonl](samples.jsonl) | Raw operation samples; `run_group` + `case` join to case metadata. Millisecond time fields are named explicitly. Allocation values are bytes; heap/RSS values are MiB. |
| [profiles.json](profiles.json) | Four JFR attribution summaries: full top stacks, overlapping sample groups, weighted allocation traffic and GC pause events. No CPU-percentage or retained-size inference. |
| [native-memory.json](native-memory.json) | Original early explicit NMT summaries; not peak RSS and not all graphics/font allocations. |
| [packaging.json](packaging.json) | Packaged Truffle provider registration and byte comparison of application classes against the classpath control. |
| [diagnostic-excerpts.json](diagnostic-excerpts.json) | Packaged Mermaid errors and the long-line timeout EDT stack. |
| [executions.json](executions.json) | Actual invocation records, including superseded/excluded investigative runs. The report identifies which groups are primary; do not pool all investigations into one baseline. |
| [legacy-desktop.json](legacy-desktop.json) | Unchanged desktop reference harness raw results and original gates, including the full-startup budget miss. |
| [legacy-model.txt](legacy-model.txt) | Existing model responsiveness harness result; three samples only. |
| [regression-suites.json](regression-suites.json) | Full Maven per-suite counts and reasons for skipped tests. |
| [verification.json](verification.json) | Final integrity/test checks and the original protected-file hashes. |
| [manifest.json](manifest.json) | Evidence-file SHA-256 inventory, excluding this manifest itself. |

Primary groups use `current-model`, `clean-*` and `final-*` names. `assets-*` are standalone first-use controls; `font-ready-assets-*` measure after ordinary editor/font readiness. The classpath controls are not the packaged distribution. `adversarial-model` and the read-only `constrained-model` cases predate the completion sentinel; their terminal phase, after independent byte verification, establishes completion and is explicitly annotated. The superseded 128 MiB editable model OOM is excluded from primary cases.

A preview case may complete while its HTML contains a Mermaid error fallback; that flag is preserved and is not a successful Mermaid render. A timed-out long-line case retains completed **partial** edit samples. Three renderer/startup samples do not justify p99. Normal 100-sample edit categories report nearest-rank p50/p95/p99; other raw summaries use null p99 when n < 100.

The fixture generator is deterministic at generation version 1. Exact generated source and local-image hashes are in run metadata where available; old image assets are not evidence of local-image rendering. Only the final valid-PNG preview group establishes image availability. Do not commit or copy giant generated Markdown files into this directory.
