# PerfBench Results

Status: release-mode local snapshot. Generated on 2026-06-28T03:41:04Z with
`swift run -c release PerfBench --output metrics/perfbench/2026-06-28.json`.

Environment: local macOS SwiftPM release build, 50 iterations, 50 synthetic
windows, 5,000 soak events.

| Scenario | p50 ms | p95 ms | p99 ms | Max ms | Samples |
|---|---:|---:|---:|---:|---:|
| cold-start-proxy | 0.001584 | 0.003375 | 0.400458 | 0.400458 | 50 |
| hotkey-to-move-proxy | 0.008709 | 0.010166 | 0.124792 | 0.124792 | 50 |
| tag-switch-50-windows | 0.334708 | 0.348583 | 0.519500 | 0.519500 | 50 |
| wake-from-sleep-proxy | 0.327709 | 0.363584 | 0.412791 | 0.412791 | 50 |
| soak-5000-events | 59.530792 | 60.422792 | 60.931708 | 60.931708 | 50 |

Notes:

- These are synthetic proxy scenarios, not full AX-on-desktop measurements.
- Current `PerfBench` JSON also includes budget diagnostics; run with
  `--fail-on-budget` to enforce them locally or in CI.
- Source JSON: [`metrics/perfbench/2026-06-28.json`](../metrics/perfbench/2026-06-28.json).
- Budgets: [`docs/performance.md`](performance.md).
