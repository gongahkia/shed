# Bench

## Benchmark protocol (frozen)
- **Hardware:** M2 or newer, 16 GB+ RAM, on AC, no other GUI apps open.
- **Tooling:** `hyperfine --warmup 0 --runs 20 --prepare 'sudo purge'` per cmd.
- **Cold-start measurement:** Each editor under test gets a startup probe:
  - For `itsy`: `itsybench measure --staged` sets `ITSY_BENCH_STAGES_PATH` and records internal stage deltas for process/delegate/menu stages plus document open, window-controller init/show, first display-link tick, render begin, and `first_draw`. In staged mode, `first_window_visible_ms` is the app-owned `applicationDidFinishLaunching` to first AX-visible window delta; `external_first_window_visible_ms` preserves the full `NSWorkspace.openApplication` wall-clock delta.
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
- **Large-text gate:** PR CI generates a 1 GiB UTF-8 fixture, then verifies mmap-backed open, streamed search, edit/revert, atomic save, and a 1.5 GiB peak-RSS delta budget without flattening it.

## CLI

```sh
itsybench display [--display <id>]
itsybench measure --app <path> [--args <arg>] [--new-instance] [--runs <count>] [--staged] [--timeout-ms <ms>] [--warmup-purge]
itsybench open --file <path> [--app <path>] [--timeout-ms <ms>] [--warmup-purge]
itsybench piecetree [--ops <count>] [--slice-length <bytes>] [--file <path>] [--mmap-contract] [--mmap-rss-budget-kb <kb>]
itsybench rss --pid <pid>
itsybench latency --pid <pid> [--key-code <code>] [--display <id>] [--timeout-ms <ms>] [--dirty-rects <n>]
itsybench workflow --file <path> [--repeats <count>] [--pane-transitions <count>]
```

`display` reports CGDisplay mode dimensions plus CVDisplayLink actual/nominal refresh Hz for ProMotion verification.

`latency` activates the target pid, observes routed keydown via `CGEventTap`, posts an ANSI key event, and reports the first `CGDisplayStream` dirty frame as keystroke-to-paint latency.

Representative workflow gate:

```sh
bench/scripts/editor_workflows.sh
```

The fixed `small.ts`, `large.ts`, and `Sources/ItsyBench/main.swift` corpora cover app-owned open, edit, search, save, persisted pane-state transitions, and SourceKit-LSP diagnostics. Git status refresh and task output are labeled `environment` because their timing includes an external executable. Existing launch, first-window, RSS, and input-latency gates remain separately app-staged in `regression.sh`.

Record a baseline only on a controlled Mac:

```sh
bench/scripts/editor_workflows.sh --record-baseline
```

Each metric stores five samples, mean, variance, ownership, and a lower-is-better policy: a 10% relative tolerance floor or three combined standard deviations, whichever is larger.

Memory audit:

```sh
bench/scripts/memory_audit.sh
```

Large-text contract:

```sh
bench/scripts/large_text_gate.sh
```

Use `ITSY_LARGE_TEXT_BYTES` and `ITSY_LARGE_TEXT_RSS_BUDGET_KB` for a local reduced-size rehearsal.

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
