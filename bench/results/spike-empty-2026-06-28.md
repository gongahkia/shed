# Empty-app cold-start spike 2026-06-28

Command: `.build/release/PicoBench measure --app Pico.app --warmup-purge`
Runs: 20
Target: <100 ms
Result: fail

| Metric | Value |
|---|---:|
| Mean startup | 253.273 ms |
| Min startup | 228.513 ms |
| Max startup | 363.720 ms |
| Mean RSS | 84553 KB |

## Investigation

Direct binary probe: `/usr/bin/time -p Pico.app/Contents/MacOS/Pico --bench-exit-on-ready` averaged 0.068 s over 10 runs (min 0.050 s, max 0.100 s).

[Inference] The >150 ms result is dominated by the `NSWorkspace` app-bundle launch + first-window/AX-observer path, not the app-delegate ready path measured by `--bench-exit-on-ready`.

`DYLD_PRINT_STATISTICS_DETAILS=1` emitted no dyld breakdown in this environment; cannot verify dyld phase costs from this run.

| Run | Startup ms | RSS KB |
|---:|---:|---:|
| 1 | 273.340 | 86304 |
| 2 | 249.275 | 86192 |
| 3 | 278.375 | 83520 |
| 4 | 228.513 | 83104 |
| 5 | 232.031 | 86384 |
| 6 | 235.008 | 83168 |
| 7 | 262.637 | 86192 |
| 8 | 242.858 | 86240 |
| 9 | 250.194 | 83088 |
| 10 | 234.810 | 83280 |
| 11 | 228.835 | 83280 |
| 12 | 258.745 | 86560 |
| 13 | 231.097 | 86208 |
| 14 | 274.197 | 86128 |
| 15 | 235.929 | 83152 |
| 16 | 233.825 | 83168 |
| 17 | 258.742 | 82848 |
| 18 | 241.704 | 83136 |
| 19 | 251.627 | 86000 |
| 20 | 363.720 | 83104 |
