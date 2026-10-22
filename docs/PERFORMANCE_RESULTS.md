# Recorded performance

The measurements establish a reproducible baseline and expose missed targets. **They do not establish an overall editor speed improvement.** The isolated, alternating ranker comparison does show lower CPU and wall time for the allocation reduction; desktop startup and latency percentiles still miss their targets on this host.

## Method and artifacts

Measured on macOS 15.7.7, x86_64, using Java 21.0.12.1+1-LTS, with 8 reported logical CPUs. Each desktop measurement used a fresh JVM, a fresh temporary home, default configuration except Plain keymap/session restore disabled, a 64–512 MiB Java heap, and a deterministic 1,380,000-byte fixture (20,000 newline-terminated records / 20,001 document lines). All runs observed zero language servers; no user extensions were loaded.

Five startup/memory samples and 125 warmed edit/picker samples are retained for each build. The OS cache was not purged, and other host activity was not controlled. Builds/tests were kept separate from timed runs. These are local fresh-process measurements, not controlled cold-cache measurements or cross-editor comparisons. The visible source area changes with the added document strip, so edit/paint differences also include the changed default layout. Heap was sampled without forcing GC; RSS includes native JVM/font allocations.

- [Baseline `fbec1929`](performance/baseline-fbec1929.json)
- [Intermediate UI build `b2605f6b`](performance/pre-staging-fix-b2605f6b.json)
- [Measured production code `ea9ac59b`](performance/current-ea9ac59b.json)
- [Alternating ranker comparison](performance/picker-comparison.json)

The desktop JSON includes JAR/probe SHA-256 hashes, Java/OS details, all samples, thresholds and internal `:perf` output. All desktop artifacts use the same probe hash. Use the commands in [EDITING_PERFORMANCE.md](EDITING_PERFORMANCE.md) to repeat the experiment; the benchmark intentionally returns exit status 1 for missed budgets.

## Desktop results

Values are **p50 / p95**; startup and memory p95 is the maximum of only five samples.

| Measurement | Baseline | Current | p95 target | Current result |
| --- | ---: | ---: | ---: | --- |
| Visible window (ms) | 2466.7 / 3758.3 | 4174.2 / 6858.5 | 2000 | Miss |
| Source installed and painted (ms) | 3216.2 / 4989.3 | 5499.1 / 8677.5 | 3000 | Miss |
| Full UI painted (ms) | 12418.2 / 20415.3 | 29088.4 / 31600.5 | 10000 | Miss |
| Document edit → synchronous paint (ms) | 4.8 / 6.2 | 6.0 / 23.1 | 16 | Miss |
| 50,000-path ranking in desktop process (ms) | 57.1 / 126.8 | 76.8 / 238.0 | 50 | Miss |
| Idle used heap (MiB) | 38.7 / 41.9 | 41.7 / 42.7 | 384 | Pass |
| Idle RSS (MiB) | 476.9 / 481.6 | 386.3 / 393.7 | 768 | Pass |

The first baseline/full-UI sample was 20.42 s; later baseline samples were 12.42, 8.63, 9.78, 13.75 s. The current first sample was 30.05 s; later samples were 29.09, 27.01, 31.60, 25.38 s. Startup remains dominated by a long full-UI/font-readiness interval. Desktop edit and picker timings also varied substantially; the raw samples should be kept when investigating those misses. The final run is slower than the original baseline on these wall-clock latency percentiles, so no general speedup is claimed.

## Regression caught during implementation

The intermediate document strip requested renderer metrics before the temporary startup font was installed. Its median window/source-ready times were 13.74 / 16.24 s. Applying the staged physical font to the strip reduced those medians to 4.17 / 5.50 s in the subsequent run. Internal constructor-to-visible metrics and the source path supported the diagnosis. This removes the severe early-font-loading regression; it does not meet the startup targets or establish a whole-app gain over the original baseline.

## Isolated ranking comparison

One JVM loaded both JARs through separate classloaders and alternated their order, with 30 warmups and 50 samples per implementation. Every ordered result list matched. This separates the pure matcher from startup/font discovery and editor background jobs.

| Measurement | Baseline p50 / p95 | Current p50 / p95 |
| --- | ---: | ---: |
| Wall time (ms) | 28.82 / 50.54 | 22.69 / 43.83 |
| Current-thread CPU time (ms) | 28.01 / 33.26 | 21.57 / 27.17 |

The optimization lowercases each candidate once, scores basenames without allocating substrings, and avoids allocating ranked results that cannot enter the bounded result set. The isolated experiment supports this narrow improvement. It does not replace the slower, more variable desktop-process picker results above.

Next performance work should profile the full-UI/font interval and event-thread/background scheduling under a quiet, repeatable host load. Installed competitors were not measured; these figures cannot rank Shed against Lite XL, Sublime Text, VS Code or Atom.

## Large editable-file model

The existing 500,000-line `EditorResponsivenessBenchmark` also ran against the measured build, with 25 samples and the same 64–512 MiB heap bounds. [Raw output](performance/large-editing-ea9ac59b.txt): edit p95 **1.368 ms**, caret p95 **6.623 ms**, deferred convergence p95 **562.607 ms**. Edit and caret passed their 16 ms targets; convergence missed its 250 ms target, with completion indexing the slowest measured independent task. This model/background benchmark does not measure Swing painting. It returned exit status 1 for that budget miss.
