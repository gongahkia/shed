# Cold-start audit 2026-06-28

## Inputs

- `bench/results/spike-empty-2026-06-28.md`: 20 warmup-purge runs, mean 253.273 ms, min 228.513 ms, max 363.720 ms.
- Local trace: `bench/traces/pico-app-launch-2026-06-28.trace` (143 MB, not committed).
- Command: `xcrun xctrace record --quiet --no-prompt --template 'App Launch' --time-limit 5s --output bench/traces/pico-app-launch-2026-06-28.trace --launch -- Pico.app`
- `DYLD_PRINT_STATISTICS=1 DYLD_PRINT_STATISTICS_DETAILS=1 .build/release/PicoApp --bench-exit-on-ready` emitted no dyld statistics in this environment; dyld data below comes from xctrace export.

## Findings

- App Launch lifecycle, instrumented: process creation 334.77 ms; system interface init 10.08 ms total; static runtime init 1.45 ms; `applicationDidFinishLaunching()` starts at 415.56 ms; foreground starts at 552.74 ms.
- Direct binary probe: `/usr/bin/time -p Pico.app/Contents/MacOS/Pico --bench-exit-on-ready` averaged 0.068 s over 10 runs.
- [Inference] The failing 253.273 ms benchmark is dominated by app-bundle/process/window launch path, not app-delegate-ready work.
- xctrace dyld activity totals: `dlopen` 14.905 ms, ObjC map 5.926 ms, launch executable 5.335 ms, static initializers 2.548 ms, fixups 1.056 ms, image callbacks 0.946 ms.
- Top dyld rows: WritingToolsUI `dlopen` 5.750 ms, ViewBridge `dlopen` 1.568 ms, AppIntents `dlopen` 1.422 ms, TextInputUI `dlopen` 0.639 ms.
- Release binary is 11 MB. `size -m` reports `__TEXT,__const` at 9.410 MB. [Inference] Most of that is statically linked grammar tables.
- `@_cdecl` was not found under `Sources/`.

## Remediation candidates

- Add an internal first-paint signpost/output so `picobench measure` can separate process creation, `applicationDidFinishLaunching`, first window, and first draw.
- Try release flags `-dead_strip` and `-Osize`, then re-run id031 before taking larger code changes.
- Keep id105 lazy grammar loading high priority; [Inference] it is the most plausible path to reducing binary constant data.
- Review the 12 app/library `import Foundation` uses after measurement is split; no single import is proven hot by this trace.
