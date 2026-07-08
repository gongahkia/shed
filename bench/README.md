# Bench

## Benchmark protocol (frozen)
- **Hardware:** M2 or newer, 16 GB+ RAM, on AC, no other GUI apps open.
- **Tooling:** `hyperfine --warmup 0 --runs 20 --prepare 'sudo purge'` per cmd.
- **Cold-start measurement:** Each editor under test gets a startup probe:
  - For `itsy`: `itsybench measure --staged` sets `ITSY_BENCH_STAGES_PATH` and records internal stage deltas for process/delegate/menu stages plus document open, window-controller init/show, first display-link tick, render begin, and `first_draw` alongside the external first-window-visible KPI.
  - For Zed/Sublime/VSCode/CodeEdit: external observer (Swift CLI using Accessibility API `AXObserver` to detect first window-visible event), records timestamp, then `kill -TERM`.
- **Corpus** (checked into `bench/corpus/`):
  - `small.ts` (1 kLOC)
  - `large.ts` (100 kLOC)
  - `huge.log` (1 GB synthetic, gitignored — generated via script)
  - `huge-text.log` (1 GiB pseudo-random ASCII, newline every 80 bytes, gitignored — generated via script)
  - `cold.empty` (no file)
- **Competitors:** Zed (latest stable), Sublime Text 4 (latest), VSCode (latest), CodeEdit (latest release), system `TextEdit` (control).
- **Outputs:** JSON via `--export-json`, rendered to `bench/results/YYYY-MM-DD.md` and committed.
- **Regression gate:** PR CI runs the harness against `itsy` only; fails if any KPI regresses >5% vs `main` baseline or `first_window_visible_ms` exceeds `150 ms`.

## CLI

```sh
itsybench display [--display <id>]
itsybench measure --app <path> [--args <arg>] [--new-instance] [--staged] [--timeout-ms <ms>] [--warmup-purge]
itsybench open --file <path> [--app <path>] [--timeout-ms <ms>] [--warmup-purge]
itsybench rss --pid <pid>
itsybench latency --pid <pid> [--key-code <code>] [--display <id>] [--timeout-ms <ms>] [--dirty-rects <n>]
```

`display` reports CGDisplay mode dimensions plus CVDisplayLink actual/nominal refresh Hz for ProMotion verification.

`latency` activates the target pid, observes routed keydown via `CGEventTap`, posts an ANSI key event, and reports the first `CGDisplayStream` dirty frame as keystroke-to-paint latency.

Memory audit:

```sh
bench/scripts/memory_audit.sh
```

This launches the release binary, waits for `first_draw`, samples `ItsyBench rss`, runs `vmmap -summary`, and writes `bench/results/memory-YYYY-MM-DD.{json,md}`.

Coverage:

```sh
bench/scripts/coverage.sh
```

This runs `swift test --enable-code-coverage`, exports LCOV with `xcrun llvm-cov`, writes `bench/results/coverage-YYYY-MM-DD.{lcov,json,md}`, and fails when `ITSY_COVERAGE_GATE=1` and line coverage drops by more than `ITSY_COVERAGE_DROP_LIMIT` percentage points from the latest committed coverage JSON.

## 2026-06-28 baseline

Installed Homebrew casks:
- Zed 1.8.2
- Visual Studio Code 1.126.0
- Sublime Text 4200
- CodeEdit 0.3.6

Baseline command:

```sh
ITSY_BASELINE_PURGE=0 bench/scripts/run_baseline.sh
```

`sudo purge` was unavailable non-interactively (`sudo: a password is required`), so this baseline records no-purge cold starts.

Nightly competitor command:

```sh
RUNS=20 BASELINE_PREFIX=nightly-competitors ITSY_BASELINE_INCLUDE_ITSY=1 ITSY_BASELINE_INCLUDE="Itsy,Zed,Sublime Text,CodeEdit" ITSY_BASELINE_PURGE=0 bench/scripts/run_baseline.sh
bench/scripts/competitor_gate.sh bench/results/nightly-competitors-YYYY-MM-DD.json
```

The competitor gate fails if Itsy is more than 25% slower than Sublime Text on mean startup, or more than 15% larger RSS than Zed.
