# Bench

## Benchmark protocol (frozen)
- **Hardware:** M2 or newer, 16 GB+ RAM, on AC, no other GUI apps open.
- **Tooling:** `hyperfine --warmup 0 --runs 20 --prepare 'sudo purge'` per cmd.
- **Cold-start measurement:** Each editor under test gets a `--bench-exit-on-ready` shim:
  - For `itsy`: native flag — print timestamp on `applicationDidFinishLaunching` + first paint, then `NSApp.terminate`.
  - For Zed/Sublime/VSCode/CodeEdit: external observer (Swift CLI using Accessibility API `AXObserver` to detect first window-visible event), records timestamp, then `kill -TERM`.
- **Corpus** (checked into `bench/corpus/`):
  - `small.ts` (1 kLOC)
  - `large.ts` (100 kLOC)
  - `huge.log` (1 GB synthetic, gitignored — generated via script)
  - `cold.empty` (no file)
- **Competitors:** Zed (latest stable), Sublime Text 4 (latest), VSCode (latest), CodeEdit (latest release), system `TextEdit` (control).
- **Outputs:** JSON via `--export-json`, rendered to `bench/results/YYYY-MM-DD.md` and committed.
- **Regression gate:** PR CI runs the harness against `itsy` only; fails if any KPI regresses >5% vs `main` baseline.

## CLI

```sh
itsybench measure --app <path> [--args <arg>] [--new-instance] [--warmup-purge]
itsybench rss --pid <pid>
itsybench latency --pid <pid> [--key-code <code>] [--display <id>] [--timeout-ms <ms>] [--dirty-rects <n>]
```

`latency` activates the target pid, observes routed keydown via `CGEventTap`, posts an ANSI key event, and reports the first `CGDisplayStream` dirty frame as keystroke-to-paint latency.

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
