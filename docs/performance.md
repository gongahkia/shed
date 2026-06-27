# Performance Budgets

Status: v0.1 hard budgets. Source: `NORTHSTAR.md` section 12a.

Performance is a release gate. PRs that touch AX, layout, dispatch, IPC, hotkeys, display
handling, or app startup must either attach benchmark evidence or explain why the path is not
runtime-sensitive.

## Budgets

| Metric | Budget | Measurement |
|---|---|---|
| Cold start to menubar visible | <= 400 ms on M-series, <= 800 ms on Intel | wall clock from `main` to status item visible |
| Hotkey press to action begin | <= 5 ms p99 | Carbon callback in, dispatcher enter |
| Hotkey press to window moved | <= 50 ms p95, <= 120 ms p99 | end-to-end with one moved window |
| Tag switch with 50 windows and 2 displays | <= 80 ms p95 | last AX move call settles |
| Layout-engine recompute | <= 4 ms p95, <= 16 ms p99 | `arrange()` entry to return |
| Wake-from-sleep recovery | <= 500 ms | wake notification to focused-window restore |
| Steady-state CPU, idle, 50 windows | <= 0.1% | `top -pid` sampled for 10 s |
| Steady-state RSS, 50 windows | <= 60 MB | `ps -o rss` |
| RSS growth over 7 days | <= 5% from day-1 baseline | longevity soak |
| AX call rate, idle | 0/sec | signpost counter |
| AX call rate, active drag | <= 120/sec coalesced | signpost counter |

## Engineering Rules

- Coalesce AX writes per display per frame.
- Skip no-op window writes when target and current frames differ by less than 1 px.
- Keep `LayoutEngine.arrange()` pure and synchronous.
- Keep UI/layout reads on cached snapshots, not live synchronous AX calls.
- Use AsyncStream backpressure for AX observer deltas.
- Avoid timers in production paths; use AX, display, workspace, and IPC events.
- Add signposts for hot paths so regressions can be tied to functions.
- Measure release builds for performance decisions.

## PR Evidence

Attach one of:

- `PerfBench` JSON once the benchmark target exists.
- Instruments or `xctrace` evidence for targeted hot-path changes.
- A written explanation that the PR only changes docs, tests, fixtures, or non-runtime metadata.

Until `PerfBench` lands, use focused unit tests plus release-build smoke timing where possible.
