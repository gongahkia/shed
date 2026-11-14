## D. Measurements

All times are ms. Each variant is paired with stock/control in the same child JVM and fixture. Primary runs use frozen probe/runner hashes and the same packaged distribution. Order is fixed (stock, A, B, C), so cache/JIT/order effects remain a limitation; the repeat is not a complete successful convergence qualification. Profiled timings are excluded from latency tables.

| Fixture | Cold install+layout+paint task, n=1 | Same-HTML replacement median / max, n=3 | A retained task p95 / max | B text insertion p95 / max, n=20 |
| --- | ---: | ---: | ---: | ---: |
| 100 KiB prose | 246.88 | 103.39 / 121.88 | 5.47 / 8.41 | 5.79 / 6.36 |
| 1 MiB prose | 1516.78 | 922.54 / 1101.45 | 3.29 / 11.07 | 7.10 / 7.54 |
| 1 MiB table/code/prose | 2999.98 | 1640.61 / 1909.67 | 17.62 / 33.55 | 19.54 / 26.04 |
| 4 KiB rich | 86.47 | 26.08 / 35.49 | 6.64 / 8.50 | 5.06 / 5.21 |
| 1 MiB legacy padding | 1641.68 | 936.75 / 1085.46 | 3.20 / 6.93 | 6.59 / 6.91 |
| 1 MiB repeat (incomplete) | 1758.76 | 982.62 / 1097.62 | 3.79 / 7.50 | 7.74 / 14.50 |

A has 100 samples except rich (20). Full replacements include fresh view installation/layout/paint, with HTML read measured independently off EDT; A retains an existing model and is only applicable when content/style/resource validity is known. B is a rendered-text-only edit, not a general Markdown transformation. Comparing those operations does not establish a complete asynchronous implementation.

| C progressive construction | First-pass task p50 / p95 / p99 / max | Queued source insertion + immediate paint p50 / p95 / p99 / max (n) | Control insertion p95 |
| --- | ---: | ---: | ---: |
| 100 KiB prose | 4.67 / 7.24 / 8.01 / 8.08 | 13.60 / 16.69 / 18.06 / 20.05 (100) | 10.11 |
| 1 MiB prose | 4.77 / 6.55 / 9.94 / 32.90 | 13.91 / 17.66 / 22.35 / 23.19 (100) | 10.25 |
| 1 MiB composite, stopped | 700.56 / 700.56 / — / 700.56 | 706.86 / 706.86 / — / 706.86 (1) | 12.62 |
| 4 KiB rich | 3.06 / 4.59 / — / 6.60 | 9.06 / 10.20 / — / 13.56 (20) | 8.20 |
| 1 MiB legacy, stopped | 4.76 / 6.21 / 10.06 / 455.69 | 12.99 / 15.94 / 17.32 / 20.79 (100) | 9.05 |
| 1 MiB repeat, incomplete | 4.98 / 6.78 / 10.52 / 17.03 | 14.02 / 19.50 / 22.40 / 26.72 (100) | 8.15 |

The insertion metric includes actual EDT queue wait, document/listener/view work and synchronous paint of the showing Shed source widget. Native keyboard dispatch and compositor scanout are excluded. A source callback additionally cycles undo/redo and navigation for coverage; this aggregate callback is tagged `input.*` and must not be attributed to preview layout. Separate operation times remain raw. C insertion statistics include only the first mutating callbacks (100 or 20); later navigation-only callbacks have distinct operation names.

| Prose 1 MiB input, p50 / p95 / p99 / max | Preview-disabled control | While C staging |
| --- | ---: | ---: |
| Undo + paint | 5.45 / 7.45 / 9.12 / 9.39 | 5.69 / 7.92 / 10.44 / 10.76 |
| Redo + paint | 5.45 / 7.83 / 8.12 / 8.44 | 5.99 / 7.38 / 7.80 / 14.80 |
| Distant caret + paint | 2.13 / 2.88 / 3.30 / 3.32 | 7.27 / 10.65 / 13.74 / 37.66 |
| Source scroll + paint | 1.82 / 2.47 / 3.03 / 3.20 | 2.87 / 4.37 / 5.15 / 10.47 |

| C completion/resize | First pass ms | Current-settle pass ms | Atomic publish ms | Resize round-trip task ms | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 100 KiB prose | 4723.3 | 2700.5 | 1.68 | 116.23 | published current revision |
| 1 MiB prose | 31755.8 | 29123.5 | 2.45 | 1370.62 | published current revision |
| Composite | 786.0 | 0.0 | — | — | not published |
| Rich | 664.6 | 262.2 | 2.54 | 1.98 | published current revision |
| Legacy | 29782.2 | 0.0 | — | — | not published |
| Repeat | 24970.7 | 0.0 | — | — | ERROR; partial data only |

The first draft deliberately continues despite source revision changes to collect input samples, then rejects publication. The second pass captures the settled revision and uses navigation-only callbacks. Its time includes conservative pacing and source-test callbacks, not an optimized parser throughput estimate. First-pass completion is not first visible current content. `C.resize` narrows by 120 px **and restores** in one callback; its total is not a measurement of a single native resize event. The approximately 1.37 s aggregate nevertheless exposes unbounded global reflow. `stock.resize` is an offscreen control after reparenting and is not a valid paired resize baseline.

### Exact equivalence and resources

| Fixture | B text/size/links/positions/pixels | C text/size/links/positions/pixels | Retained old B views | Heap before / full / after candidates / after disposal, MiB | Peak RSS MiB |
| --- | --- | --- | ---: | ---: | ---: |
| 100 KiB | yes/yes/yes/yes/yes | yes/yes/yes/yes/yes | 3025 / 3046 | 15.7 / 18.1 / 20.2 / 17.5 | 570.2 |
| 1 MiB | yes/yes/yes/yes/yes | yes/yes/yes/yes/yes | 31115 / 31136 | 28.0 / 52.5 / 58.8 / 40.3 | 743.2 |
| Composite | yes/yes/yes/yes/yes | not completed | 50100 / 50120 | 28.4 / 67.4 / 76.8 / 58.2 | 649.2 |
| Rich | yes/yes/yes/yes/yes | yes/yes/yes/yes/yes | 254 / 274 | 47.6 / 50.3 / 58.7 / 50.5 | 774.7 |
| Legacy | yes/yes/yes/yes/yes | not completed | 29732 / 29752 | 27.9 / 52.1 / 58.2 / 46.6 | 739.6 |

Heap phases request GC outside timing and label those dispatches `memory_gc`; they are approximate occupancy, not a dominator/leak census or guaranteed completed collection. Main-method locals may remain live. RSS is sampled at ~250 ms, not a kernel high-water mark. C temporarily owns old+staging HTML documents and full View trees; it is not viewport-memory-bounded and retains state proportional to document size. The probe does not allocate full-height screenshots: exact RGB comparisons cover first/middle/last 600×400 crops plus selected model positions and complete anchor/link/image metadata.

### Explicit negative outcomes

- The original 1 MiB composite with a ~242,000-character (~236 KiB) / 2,000-line PRE exhausted the child’s 512 MiB heap in `HTMLReader.preContent → addContent → ElementSpec → Arrays.copyOf`, before layout. It is ERROR, not a performance success. The heap was not increased. The final runner guards that known workload and returns SKIPPED_RESOURCE_GUARD; no output is published.
- The final smaller composite remains exactly 1 MiB and contains the same large table plus 256 code lines and prose. C stops after its first ~700 ms table fragment. It does not publish a truncated preview.
- The legacy fixture’s long unbroken final padding word forces a new minimum width at the last fragment: ~456 ms despite a small p95. It is rejected rather than published. Prose fixtures replace only artificial p-only padding with equal-length p-space text; both variants and hashes are retained.
- One repeat exited 0 without HT_DONE during its second pass. Its cause is unresolved. The runner correctly returns ERROR and preserves partial data; no full convergence, disposal or equivalence claim uses it.
- A same-HTML retained local image becomes stale after its file changes; the rich control records pixel inequality at the image. Unconditional no-op suppression would break explicit refresh.
