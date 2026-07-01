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
