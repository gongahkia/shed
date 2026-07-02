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

`ItsyBench` now falls back to polling `AXWindows` when `kAXWindowCreatedNotification` registration is unavailable, so staged measurement does not fail on AX notification error `-25204`.

## 2026-06-29 staged refresh

After the AppKit metadata cleanup commits, a 5-run staged sample against `Itsy.app` produced one `window creation timed out` result and four successful runs:

| Run | process_start ms | delegate_init ms | app_did_finish_launching ms | initial_document_opened ms | first_window_visible ms | first_draw ms | RSS KB |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | timeout | timeout | timeout | timeout | timeout | timeout | timeout |
| 2 | 68.831 | 123.582 | 172.848 | 269.319 | 289.834 | 295.153 | 86736 |
| 3 | 63.595 | 93.303 | 142.130 | 235.310 | 266.055 | 269.540 | 86704 |
| 4 | 69.676 | 107.558 | 152.573 | 239.647 | 264.129 | 274.171 | 86768 |
| 5 | 69.824 | 124.651 | 178.736 | 267.083 | 303.291 | 300.172 | 86752 |

Successful-run mean first-window-visible: `280.827 ms`. Mean `applicationDidFinishLaunching`: `161.572 ms`. Mean first draw: `284.759 ms`. Mean RSS: `86740 KB`.

Status remains fail for the `<150 ms` cold-start target and `<30 MB` idle-RAM target.

`ItsyBench measure` now accepts `--timeout-ms <ms>` for staged diagnostics. With `--timeout-ms 10000`, a 5-run staged sample completed without dropping the slow first-launch outlier:

| Run | process_start ms | delegate_init ms | app_did_finish_launching ms | initial_document_opened ms | first_window_visible ms | first_draw ms | RSS KB |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 546.332 | 588.802 | 635.246 | 734.166 | 765.095 | 770.868 | 86896 |
| 2 | 50.977 | 85.097 | 121.118 | 221.909 | 251.295 | 255.989 | 86848 |
| 3 | 46.477 | 77.552 | 107.415 | 192.098 | 221.362 | 225.933 | 86704 |
| 4 | 46.504 | 74.804 | 107.523 | 194.245 | 217.508 | 227.546 | 86672 |
| 5 | 48.993 | 81.225 | 119.485 | 214.146 | 237.057 | 241.158 | 87040 |

Successful-run mean first-window-visible including the first-launch outlier: `338.463 ms`. Warm-run mean excluding run 1: `231.805 ms`.

## 2026-07-01 lazy-link audit

References:

- https://developer.apple.com/library/archive/documentation/DeveloperTools/Conceptual/DynamicLibraries/100-Articles/UsingDynamicLibraries.html
- https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man3/dlopen.3.html

Release binary audit:

```sh
otool -L .build/release/ItsyApp | rg 'WritingToolsUI|AppIntents|ViewBridge|TextInputUI|QuickLook|SwiftUI|UniformTypeIdentifiers'
```

Output:

```text
/usr/lib/swift/libswiftUniformTypeIdentifiers.dylib (compatibility version 1.0.0, current version 877.5.1, weak)
```

Runtime load audit:

```sh
DYLD_PRINT_APIS=1 .build/release/ItsyApp --bench-exit-after-initial-document
```

Observed target framework loads before `READY`:

```text
dlopen("/System/Library/Frameworks/AppIntents.framework/AppIntents", 0x00000101)
dlopen("/System/Library/PrivateFrameworks/ViewBridge.framework/Versions/A/ViewBridge", 0x00000100)
dlopen("/System/Library/PrivateFrameworks/WritingToolsUI.framework/WritingToolsUI", 0x00000101)
dlopen("/System/Library/Frameworks/SwiftUI.framework/SwiftUI", 0x00000101)
```

`TextInputUI` was not observed in the current runtime trace.

Source audit:

```sh
rg -n 'WritingToolsUI|AppIntents|ViewBridge|TextInputUI|import SwiftUI'
```

No app-source import matched these frameworks.

Applied deferrals/opt-outs:

- `FindBarController` is now created on first find action instead of during initial window construction.
- Menus set `automaticallyInsertsWritingToolsItems = false` on macOS 15.2+.
- `MetalTextView` exposes `writingToolsBehavior = .none` on macOS 15+.

[Inference] The remaining `WritingToolsUI`, `AppIntents`, `ViewBridge`, and `SwiftUI` loads are AppKit/HIToolbox/text-system runtime loads, not direct Itsy link edges. There is no remaining direct app import of those frameworks to replace with `dlopen`/`dlsym`.

## 2026-07-01 post-lazy-link cold-start bench

Command:

```sh
bench/scripts/make_app.sh
.build/release/ItsyBench measure --staged --app Itsy.app --new-instance --timeout-ms 10000
```

Five-run sample:

| Run | process_start ms | delegate_init ms | app_did_finish_launching ms | main_menu_installed ms | initial_document_opened ms | first_window_visible ms | first_draw ms | RSS KB |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 612.980 | 681.954 | 2460.739 | 2468.996 | 6934.869 | 7011.747 | 7008.510 | 467552 |
| 2 | 58.760 | 119.235 | 182.829 | 188.531 | 2373.717 | 2453.865 | 2450.120 | 468560 |
| 3 | 51.237 | 95.911 | 164.029 | 171.562 | 2590.410 | 2676.152 | 2668.753 | 464768 |
| 4 | 63.140 | 109.215 | 164.877 | 169.445 | 2305.917 | 2370.338 | 2374.371 | 467424 |
| 5 | 59.414 | 103.490 | 158.255 | 163.173 | 2079.503 | 2156.309 | 2148.670 | 468688 |

Summary:

- Mean first-window-visible: `3333.682 ms`
- Warm-run mean excluding run 1: `2414.166 ms`
- Previous warm-run mean in this note: `231.805 ms`
- Delta vs previous warm-run mean: `+2182.361 ms`
- Target: `>=40 ms` improvement
- Status: fail

Direct binary cross-check:

```sh
ITSY_BENCH_STAGES_PATH=/tmp/itsy-direct-stages.log .build/release/ItsyApp --bench-exit-after-initial-document
```

Observed stage deltas:

```text
process_start 0.000
delegate_init 58.274
app_did_finish_launching 101.662
main_menu_installed 107.175
initial_document_opened 2120.836
```

[Inference] The post-lazy-link changes did not produce the requested cold-start improvement. The current miss is dominated by the initial document/window construction path after main-menu installation, not direct `otool -L` dependencies.

## 2026-07-02 post-dylib checkpoint

Command:

```sh
bench/scripts/make_app.sh
size -m .build/release/ItsyApp
hyperfine -N --warmup 0 --runs 20 -- 'Itsy.app/Contents/MacOS/Itsy --bench-exit-on-ready'
```

Binary section result:

- `__TEXT,__const`: `97,371 bytes` (`0.093 MiB`)
- Previous recorded `__TEXT,__const`: `9.410 MiB`
- Delta: `-9.317 MiB`
- Target: `<2 MiB`
- Status: pass

Direct-ready cold-start result:

- 20-run mean: `4.187 ms`
- Min/max: `3.703 ms` / `5.205 ms`
- Median: `4.069 ms`
- Previous direct-binary probe in this note: `68 ms`
- Delta: `-63.813 ms`
- Target: `>=25 ms` improvement
- Status: pass

Direct initial-document stages:

| Run | delegate_init ms | app_did_finish_launching ms | main_menu_installed ms | initial_document_opened ms |
|---:|---:|---:|---:|---:|
| 1 | 35.827 | 64.607 | 67.452 | 1072.510 |
| 2 | 31.016 | 58.867 | 62.102 | 1055.192 |
| 3 | 29.366 | 58.595 | 61.368 | 1060.687 |
| 4 | 34.171 | 61.225 | 64.251 | 1054.811 |
| 5 | 36.551 | 65.261 | 68.033 | 1060.629 |

Mean `initial_document_opened`: `1060.766 ms`.

`ItsyBench measure --staged --app Itsy.app --new-instance` timed out waiting for AX window creation at both `--timeout-ms 10000` and `--timeout-ms 30000` in this local session, so first-window-visible could not be reverified here. Direct stage output reached `READY`.
