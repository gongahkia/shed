# Cold-start audit 2026-06-28

## Inputs

- `bench/results/spike-empty-2026-06-28.md`: 20 warmup-purge runs, mean 253.273 ms, min 228.513 ms, max 363.720 ms.
- Local trace: `bench/traces/itsy-app-launch-2026-06-28.trace` (143 MB, not committed).
- Command: `xcrun xctrace record --quiet --no-prompt --template 'App Launch' --time-limit 5s --output bench/traces/itsy-app-launch-2026-06-28.trace --launch -- Itsy.app`
- `DYLD_PRINT_STATISTICS=1 DYLD_PRINT_STATISTICS_DETAILS=1 .build/release/ItsyApp --bench-exit-on-ready` emitted no dyld statistics in this environment; dyld data below comes from xctrace export.

## Findings

- App Launch lifecycle, instrumented: process creation 334.77 ms; system interface init 10.08 ms total; static runtime init 1.45 ms; `applicationDidFinishLaunching()` starts at 415.56 ms; foreground starts at 552.74 ms.
- Direct binary probe: `/usr/bin/time -p Itsy.app/Contents/MacOS/Itsy --bench-exit-on-ready` averaged 0.068 s over 10 runs.
- [Inference] The failing 253.273 ms benchmark is dominated by app-bundle/process/window launch path, not app-delegate-ready work.
- xctrace dyld activity totals: `dlopen` 14.905 ms, ObjC map 5.926 ms, launch executable 5.335 ms, static initializers 2.548 ms, fixups 1.056 ms, image callbacks 0.946 ms.
- Top dyld rows: WritingToolsUI `dlopen` 5.750 ms, ViewBridge `dlopen` 1.568 ms, AppIntents `dlopen` 1.422 ms, TextInputUI `dlopen` 0.639 ms.
- Release binary is 11 MB. `size -m` reports `__TEXT,__const` at 9.410 MB. [Inference] Most of that is statically linked grammar tables.
- `@_cdecl` was not found under `Sources/`.

## Remediation candidates

- Add an internal first-paint signpost/output so `itsybench measure` can separate process creation, `applicationDidFinishLaunching`, first window, and first draw.
- Try release flags `-dead_strip` and `-Osize`, then re-run id031 before taking larger code changes.
- Keep id105 lazy grammar loading high priority; [Inference] it is the most plausible path to reducing binary constant data.
- Review the 12 app/library `import Foundation` uses after measurement is split; no single import is proven hot by this trace.

## 2026-06-29 update

- `ItsyBench measure --staged --app Itsy.app` now preserves the first-window-visible KPI and adds internal stage deltas from `ITSY_BENCH_STAGES_PATH`.
- Stages currently emitted: `process_start`, `delegate_init`, `app_did_finish_launching`, `main_menu_installed`, `initial_document_opened`, `app_activated`, `first_draw`.
- [Inference] This separates process/window-manager launch cost from app delegate and first Metal draw work without changing competitor measurement semantics.

Sequential staged sample after grammar dylib, static library, native integration, memory, and release-pipeline changes:

| Run | process_start ms | delegate_init ms | app_did_finish_launching ms | initial_document_opened ms | first_window_visible ms | first_draw ms | RSS KB |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 51.304 | 89.207 | 123.730 | 231.040 | 256.715 | 263.226 | 77424 |
| 2 | 58.441 | 93.734 | 144.915 | 249.946 | 280.628 | 270.442 | 77344 |
| 3 | 51.060 | 85.664 | 120.934 | 224.183 | 250.726 | 256.871 | 77504 |

Mean first-window-visible: `262.690 ms`. Mean `applicationDidFinishLaunching`: `129.858 ms`. Mean first draw: `263.513 ms`.

[Inference] The current `<150 ms` miss is after `applicationDidFinishLaunching`, mostly between initial document/window activation and AX-visible window detection. The staged data does not prove the app meets the cold-start KPI.
