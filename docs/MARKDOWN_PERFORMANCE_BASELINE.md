# Markdown performance baseline — Tranche 1

This is an opt-in measurement tranche. It changes no production editor behavior, UI, document ownership, parser, renderer, undo policy, or default performance threshold. The 0A/0B/0B.1 correctness work remains in place. `README.md` is protected.

## Existing infrastructure and measurement definitions

- `scripts/benchmark-editor.py` and `src/benchmark/java/shed/EditorWorkflowProbe.java` remain the desktop workflow reference. Its fresh-process launches do not purge filesystem/font caches. Its edit loop measures document mutation plus a **forced synchronous paint**, not native keyboard-to-screen latency. Its historical 2 s visible / 3 s source / 10 s full UI, 16 ms edit/paint, 50 ms picker, 384 MiB used heap and 768 MiB RSS targets are unchanged diagnostic gates.
- `EditorResponsivenessBenchmark` measures immutable snapshot operations and separately executed background computations. Its reported convergence is the maximum of independent measured stages, not observed concurrent UI convergence. The existing 16 ms edit/caret and 250 ms convergence thresholds remain unchanged. It does not measure Swing painting or Markdown editing.
- `LargeFileBenchmark` is a legacy model/filesystem harness. `coldOpen` means the first open after copying, **not cold OS caches**. `edit` calls whole-content `setContent`, not ordinary typing. `save` of an unmodified read-only large file is a no-op. Do not use these labels to claim local editing or streamed-save performance.
- Existing `PerfService` paint-completion metrics, source snapshot ownership, `SyntaxUiController` worker submissions, `SearchPattern`, `LargeFileProjection`, `AtomicFileWriter`, and `MarkdownPreviewAssets` are reused directly. The new probe is compiled independently against the measured shaded JAR, following the existing desktop probe pattern; it is outside ordinary Maven source/test roots.

## Reproduction

Use Java 21 and the repository's existing Maven resolver. No installation or dependency download is required:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -DskipTests package

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_benchmark_markdown.py

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --output /tmp/shed-md-model-NEW --mode model --samples 100 --warmups 10

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --output /tmp/shed-md-ui-NEW --mode ui --sizes 102400 1048576 10485760 \
  --samples 100 --warmups 10 --timeout 90
```

Every output directory must be new and outside the checkout. Large files are generated there, not in Git. Each process edits only its own disposable input copy and isolated configuration. The corpus generator records exact bytes, SHA-256, shape, complete blocks and final padding. The main ASCII/UTF-8 corpus includes headings, prose, lists, tasks, quotes, links/references, escaped-pipe tables, code, local images, Mermaid and math. ASCII is intentional for exact streamed document-byte validation; Unicode correctness is covered separately by the regression suites.

Additional runs use the same command with these options and distinct output directories:

| Scenario | Options |
| --- | --- |
| Wrapped source | `--mode ui --wrap --sizes 102400 1048576 10485760 --samples 100 --warmups 10 --timeout 90` |
| Adversarial model | `--mode model --shapes long-line nested table fence headings markers --sizes 1048576 --samples 30 --warmups 3 --timeout 45` |
| Adversarial UI | `--mode ui --shapes long-line headings table --sizes 1048576 --samples 30 --warmups 3 --timeout 60` |
| Whole preview pipeline | `--mode preview --shapes plain --sizes 102400 1048576 --samples 1 --warmups 0 --timeout 30` |
| Rich preview | `--mode preview --sizes 4096 --samples 1 --warmups 0 --timeout 35` |
| Math/Mermaid assets | `--mode assets --sizes 4096 --samples 6 --warmups 0 --timeout 35` |
| Font-ready math/Mermaid | `--mode assets-ready --sizes 4096 --samples 6 --warmups 0 --timeout 45` |
| Production syntax scheduling | `--mode syntax --sizes 102400 1048576 10485760 --samples 3 --warmups 0 --timeout 60` |
| Production find bar / buffer switching | `--mode search --shapes plain --sizes 102400 1048576 --samples 3 --warmups 0 --timeout 45` |
| Editing with preview | `--mode ui --preview --shapes plain --sizes 102400 --samples 30 --warmups 3 --timeout 60` |
| Empty startup | `--mode startup-empty --shapes plain --sizes 4096 --samples 1 --warmups 0 --runs 3 --timeout 30` |
| Markdown startup | `--mode startup --shapes plain --sizes 102400 1048576 --samples 1 --warmups 0 --runs 3 --timeout 30` |
| Configured-font startup | `--mode startup --shapes plain --sizes 102400 --font Dialog --samples 1 --warmups 0 --runs 3 --timeout 30` |
| Bounded heap, model | `--mode model --sizes 10485760 52428800 104857600 --heap 128 --samples 30 --warmups 3 --timeout 45` |
| Bounded heap, UI | `--mode ui --sizes 102400 --heap 128 --samples 30 --warmups 3 --timeout 60` |
| UI attribution | `--mode ui --sizes 10485760 --samples 30 --warmups 3 --timeout 60 --profile` |
| Preview attribution | `--mode preview --shapes plain --sizes 1048576 --samples 1 --warmups 0 --timeout 30 --profile` |
| Startup/native attribution | `--mode startup-empty --shapes plain --sizes 4096 --samples 1 --warmups 0 --timeout 30 --profile` |
| Extreme-line attribution | `--mode ui --shapes long-line --sizes 1048576 --samples 30 --warmups 3 --timeout 60 --profile` |

Default child heaps are 64 MiB initial / 512 MiB maximum; allowed maxima are bounded at 768 MiB. Individual child lifetimes are bounded, and the watchdog terminates only its own child. No system-wide stress, cache eviction, swap exhaustion, unrelated process termination, or system configuration change is performed. `--profile` separately enables JFR (profile settings, 64 MiB cap), NMT summary and GC/safepoint logs. Profiled times are attribution runs, not interchangeable with unprofiled latency measurements. RSS is sampled with `ps` about every 250 ms; its maximum is a **sampled peak**, not the kernel high-water mark. NMT does not account for every external graphics/font allocation.

`assets-ready` first starts the ordinary editor and awaits font/workbench readiness, then measures a new asset session. It opens no preview. `assets` measures first use without that editor/font preparation. For the packaged-versus-classpath control, first run the Maven tests below, extract their existing classpath, then repeat either asset command with `--classpath-file /tmp/shed-md-classpath.txt`:

```sh
python3 - <<'PY'
import xml.etree.ElementTree as ET
from pathlib import Path
root = ET.parse('target/surefire-reports/TEST-shed.MarkdownServiceTest.xml').getroot()
classpath = next(p.attrib['value'] for p in root.find('properties')
                 if p.attrib['name'] == 'java.class.path')
Path('/tmp/shed-md-classpath.txt').write_text(classpath)
PY
```

This is an explicitly labeled dependency-JAR classpath control, not the distributed artifact. Its application class bytes must match the packaged JAR before comparing results. The evidence includes that check. The optional control does not install or fetch dependencies.

Inspect a recording without installing tools:

```sh
JDK="$(scripts/resolve-toolchain.sh home)"
"$JDK/bin/javac" -cp target/shed-2.0.0.jar -d /tmp/shed-jfr-summary \
  src/benchmark/java/shed/MarkdownJfrSummary.java
"$JDK/bin/java" -Xmx256m -cp /tmp/shed-jfr-summary:target/shed-2.0.0.jar \
  shed.MarkdownJfrSummary /path/to/profile.jfr > /tmp/shed-jfr-summary.json
```

JFR stack-presence groups overlap. Execution/native sample counts are not wall-clock percentages. Weighted allocation samples are estimates of allocation traffic, not retained heap. Full recordings and timeout thread dumps remain outside the repository.

## Interpretation and safeguards

- `wall_ms` on UI text operations includes controller/action dispatch, document mutation, document/view listeners and synchronous EDT work. Snapshot-only replacement is separately measured in model mode; it is not subtracted from UI wall time to fabricate an isolated storage cost.
- `input_to_real_paint_ms` comes from Shed's existing `edit.input-to-paint` metric, ending after the source component's actual normal Swing paint. The origin is a programmatically delivered controller/Swing action, not physical keyboard hardware, IME, native event delivery, monitor scanout, or a queued repaint. Queue time is recorded separately. Samples wait for their paint to avoid coalescing away observations; this measures sequential interactive edits, not burst throughput. Background syntax work is not drained between edits.
- Caret/selection/scroll/split operations report EDT action time and separately named forced visible paint time. They are **not** physical event-to-paint or compositor frame-rate measurements.
- Warmups are excluded. Each ordinary UI edit category has 100 measured operations pooled across beginning/middle/end, with separate per-position samples. Report p99 only at n >= 100. Longest-line, search, stage and first-use measurements have smaller n and no meaningful p99. The longest logical line is selected by UTF-16 length; ties need not select Swing's internally cached maximum-width line.
- The production syntax probe requests real `SyntaxUiController` jobs and records status/generation plus `scanChars`/total source from `PerfService`. Cancelled or superseded requests are not successful near-zero-latency measurements. Isolated lexer samples are clearly separate.
- Preview opening/refresh includes real asset preparation, parsing, sanitization and `JEditorPane.setText`. Layout/forced paint is separate. Warm stage replays call the same production methods but are **not additive partitions of the first-use total**. Offscreen installation/layout replays are not substitutes for the actual preview surface.
- The existing notification row is held stable during source UI measurements, and viewport/font/DPI transform are recorded. This avoids confusing the 0B.1 footer-geometry clamp with scroll synchronization work. Preview rendering may independently change HTML view geometry; no pixel tolerance or behavior is changed.
- Search/replace model operations measure the matching/computation engine, not complete find-bar interaction or replacement installation. Projection moves are bounded reader-window operations, not full source-view scrolling. Read-only editing is explicitly unsupported. Clean large-file save and a genuine independently verified streamed atomic copy are separate operations. Dirty editable saves are verified against the authoritative document hash.
- Heap snapshots are taken without forced GC. Used heap after close includes garbage awaiting collection; neither allocation counters nor these snapshots establish retained memory or leaks. Source/probe/fixture/JAR hashes distinguish runs. OS cache and background host load remain uncontrolled; fresh JVM does not mean cold cache.

## Environment and qualification limits — 13 November 2026

The measured machine is a MacBookPro15,2 with an Intel Core i5-8279U at 2.40 GHz, 8 logical CPUs, 8 GiB physical RAM, Intel Iris Plus 655, and a 2560 × 1600 Retina display (Java transform 2×). OS: macOS 15.7.7, build 24G720, x86_64. JVM: Eclipse Temurin 21.0.12.1+1. Default child arguments are `-Xms64m -Xmx512m -Djava.awt.headless=false`, isolated `user.home`, and GC/safepoint logging; attribution runs additionally enable JFR and NMT. Configured-font controls use `Dialog`; this tests the existing override path, not a newly installed font.

At the start, load averages were 4.85 / 5.92 / 8.02, swap usage was 3,509.25 MiB of 4,096 MiB, and `memory_pressure -Q` reported 52% free. This is a loaded, already swapping host. These measurements are **exploratory engineering baselines**, not stable hardware qualification, a controlled system-memory-pressure experiment, or comparisons with other editors. Per-case environment snapshots retain subsequent load, pressure, swap, VM counters and display information. No other process was killed and no system settings were changed.

The Markdown UI workloads retain normal IDE background activity, including Git jobs. Automatic Markdown LSP startup attempts failed in this environment; no connected language server was recorded. Those attempts/jobs remain visible in the raw performance reports. The UI results are not isolated document-engine timings, and this host does not qualify editing with an active Markdown language server.

The packaged application SHA-256 is `d0e8a3016989fcad23e9fc409dd585474e02062ed0723a111773ea254528fb97`. The classpath control compared all 1,290 `shed` class files against this JAR: zero byte differences. Runner/probe hashes and actual child arguments identify instrumentation revisions independently of the application artifact. The application classes remained unchanged throughout measurement.

The run root was `/var/folders/pq/lllswlg16q396qgx147zv_mm0000gn/T/shed-markdown-tranche1-d_uxpp_4`; full recordings, logs and disposable corpora remain there. Durable results below retain raw samples, fixture hashes, commands, environment and attribution summaries without committing generated documents or binary recordings.

### Excluded or superseded measurements

1. An early tiny PNG had invalid chunk CRCs. Those preview-media pilot results are excluded; the final local image is generated with checked PNG CRCs and its actual preview loading is observed.
2. An early 128 MiB model save failed while the **benchmark** retained a full-document replacement in its blackhole. Clearing that unrelated result before the save corrected the control. That failure is not evidence that a normal Shed save necessarily fails at this heap limit.
3. Early probes could exit through `bd!` before recording post-close memory. Current probes retain an ordinary spare scratch buffer and require `MD_DONE`; exit status zero alone is insufficient to call a run complete.
4. Final stack inspection found `RecoveryWorkspaceDialog` in early UI samples. The startup input/undo probe restored bytes but left its disposable buffer dirty; a journal could be written before staged startup checked recovery. The harness now restores the fixture's original dirty flag after undo, outside input timing, awaits the recovery read, and rejects visible modal dialogs. **All primary UI/startup/syntax/preview/search measurements were rerun with this control.** Earlier UI numbers are superseded, including otherwise plausible editing samples. No real home or recovery journal was used. The recovery-startup ordering itself is a follow-up correctness hypothesis; this tranche does not change production recovery behavior.
5. A timed-out case retains its completed samples as partial diagnostic evidence; it is not a successful complete workload. Stale/cancelled syntax requests, clean save no-ops, and unsupported projection editing are never counted as successful low-latency equivalents.

## Executive assessment

Shed has a viable low-latency path for ordinary small Markdown edits, but it is not yet an exceptionally lightweight, consistently responsive Markdown editor. Three measured gaps dominate: **source-view undo/layout at 10 MiB and on extreme lines; EDT-bound preview/layout and deep-prefix Markdown highlighting; and fixed native footprint/font startup**. The shipped Mermaid packaging failure is an additional correctness blocker, independent of optimization.

## Recorded results

All latency values below are milliseconds unless labeled seconds. These are unprofiled runs unless stated otherwise. Raw operation samples, case states, commands, fixture manifests and environment are in [the evidence index](performance/markdown-tranche1/INDEX.md). A single observation is n=1, not a latency percentile. All UI baseline cases explicitly checked for no visible modal dialogs after recovery-read completion.

### Startup and fixed footprint

Each row uses three fresh JVMs. Entries are median (min–max), in **seconds**, measured from process launch. Source readiness is an explicit source paint; input acceptance includes a controller/Swing typing action, its normal paint, undo and fixture reset, and therefore bounds rather than pinpoints earliest possible input acceptance. Full readiness paints the workbench. These are warm-cache-uncontrolled launches, not cold-cache results.

| Configuration | Visible | Painted source | Input verified | Full workbench |
| --- | --- | --- | --- | --- |
| Empty, default fonts | 1.271 (1.231–1.415) | 1.360 (1.326–1.516) | 1.392 (1.358–1.551) | 5.311 (5.141–5.873) |
| 100 KiB, default fonts | 1.284 (1.257–1.410) | 1.494 (1.466–1.637) | 1.543 (1.535–1.691) | 5.387 (5.283–5.859) |
| 1 MiB, default fonts | 1.297 (1.262–1.330) | 1.687 (1.570–1.748) | 1.741 (1.613–1.791) | 5.457 (5.353–5.598) |
| 100 KiB, Dialog override | 5.836 (4.908–5.939) | 6.050 (5.088–6.174) | 6.122 (5.146–6.264) | 6.146 (5.165–6.292) |

The unchanged reference desktop harness (20,000-line plain text; three processes, 75 pooled edit/picker samples) reported full-UI median 7.555 s and p95/max 11.969 s, exceeding its existing 10 s gate (exit 1). Visible/source p95 were 1.820/2.494 s; edit/forced-paint p95 4.17 ms and picker p95 22.27 ms passed. This is an observed budget miss on a loaded host, not a newly introduced failing correctness test. No threshold was relaxed.

### Source editing, undo and wrapping

Real normal source paint completion follows a synthetic controller/action origin. Undo/redo invoke the production undo manager with the same attributed origin. It excludes physical keyboard, native input delivery and compositor scanout. Each row pools 100 measured operations over beginning/middle/end after 10 warmups, with syntax enabled and preview closed; per-position counts are 33/34 and remain in raw data.

| Unwrapped document | Operation | p50 | p95 | p99 |
| --- | --- | ---: | ---: | ---: |
| 100 KiB | Insert character | 2.53 | 3.61 | 3.75 |
| 100 KiB | Delete character | 2.13 | 3.79 | 4.08 |
| 100 KiB | Insert newline | 2.95 | 4.10 | 4.76 |
| 100 KiB | Undo inserted character | 2.12 | 3.60 | 3.77 |
| 100 KiB | Redo character | 2.07 | 3.29 | 3.64 |
| 100 KiB | Undo newline | 2.47 | 3.18 | 3.65 |
| 1 MiB | Insert character | 3.47 | 10.77 | 12.40 |
| 1 MiB | Delete character | 6.99 | 10.02 | 15.13 |
| 1 MiB | Insert newline | 4.05 | 10.77 | 15.00 |
| 1 MiB | Undo inserted character | 6.99 | 12.17 | 14.59 |
| 1 MiB | Redo character | 2.78 | 4.85 | 11.57 |
| 1 MiB | Undo newline | 2.74 | 5.21 | 9.58 |
| 10 MiB | Insert character | 5.05 | 7.95 | 9.11 |
| 10 MiB | Delete character | 37.97 | 43.07 | 51.36 |
| 10 MiB | Insert newline | 3.11 | 42.49 | 44.86 |
| 10 MiB | Undo inserted character | 38.00 | 43.96 | 49.78 |
| 10 MiB | Redo character | 2.21 | 3.41 | 4.26 |
| 10 MiB | Undo newline | 2.18 | 3.38 | 5.96 |

| Wrapped document | Insert p95 | Undo p95 | Delete p95 | Newline p95 | Undo newline p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 100 KiB | 5.56 | 4.62 | 4.69 | 5.46 | 5.38 |
| 1 MiB | 5.38 | 4.90 | 4.78 | 7.37 | 5.98 |
| 10 MiB | 11.87 | 7.54 | 7.37 | 20.59 | 18.02 |

Wrapping trades width rescanning against paragraph reflow; this does not justify a default/UI change. Longest-logical-line delete/undo use only 10 samples, and equal-length prose lines do not necessarily select Swing's cached widest line. See raw results for those operations rather than generalizing them to all undo behavior.

| Unwrapped document | Distant caret EDT / paint p95 | Selection EDT / paint p95 | Scroll EDT / paint p95 | Split switch EDT / paint p95 | Heading navigation n=1 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 100 KiB | 11.83 / 2.58 | 2.11 / 9.91 | 7.67 / 1.59 | 2.00 / 10.32 | 2.66 |
| 1 MiB | 3.16 / 1.16 | 1.88 / 1.47 | 2.22 / 1.03 | 2.18 / 1.40 | 3.61 |
| 10 MiB | 5.52 / 1.31 | 4.48 / 2.70 | 3.53 / 2.11 | 3.64 / 2.18 | 73.89 |

Caret/selection/scroll n=100 each; split switches n=20 after creating a second view of the same buffer. EDT action and forced paint are different sequential measurements; their separate percentiles must not be added to fabricate an end-to-end percentile. Most scrolling samples set widely separated scrollbar positions; this is not continuous trackpad frame pacing.

- Adversarial 1 MiB `long-line`: n=30 completed insert/undo samples, p95 94.75/100.29 ms; whole-case state **TIMEOUT**. The 60 s watchdog expired in later view/split work, after edit samples completed. Partial samples do not establish successful workload completion.
- Adversarial 1 MiB `headings`: n=30 completed insert/undo samples, p95 6.85/12.31 ms; whole-case state **OK**. No p99 claimed.
- Adversarial 1 MiB `table`: n=30 completed insert/undo samples, p95 5.98/10.09 ms; whole-case state **OK**. No p99 claimed.
- Preview-open source editing, plain 100 KiB: n=30, insert/undo p95 10.78/11.96 ms. This is a separate plain corpus, not a controlled percentage comparison with the mixed corpus.

### Production Markdown syntax jobs

Three real scheduled jobs at each viewport; worker ranges and end-to-end ranges include all observations. Convergence includes the production debounce, scheduling, application and 10 ms polling resolution. Values are not isolated lexer timings.

| Size / viewport | Scan characters | Installed foreground spans | Worker range | Debounce + convergence range |
| --- | ---: | ---: | ---: | ---: |
| 100 KiB / beginning | 102,400 | 1,007 | 3.47–6.19 | 138.85–141.71 |
| 100 KiB / middle | 102,400 | 1,007 | 4.23–5.98 | 135.48–142.54 |
| 100 KiB / end | 102,400 | 1,007 | 3.46–9.24 | 133.40–147.70 |
| 1 MiB / beginning | 9,325 | 84 | 1.42–1.78 | 130.40–136.57 |
| 1 MiB / middle | 530,833 | 5,296 | 22.57–50.72 | 152.75–182.39 |
| 1 MiB / end | 1,048,576 | 10,469 | 26.70–36.28 | 151.74–170.92 |
| 10 MiB / beginning | 9,325 | 84 | 1.73–3.05 | 128.50–133.80 |
| 10 MiB / middle | 5,250,273 | 52,416 | 107.12–232.25 | 234.22–369.11 |
| 10 MiB / end | 10,485,760 | 104,690 | 211.13–277.54 | 343.98–407.76 |

Runtime and emitted spans scale with the scanned prefix, not just the viewport; isolated lexer allocation samples likewise grow with prefix size. Code confirms a new grammar service and prefix string per job, and Markdown's viewport fallback emits prefix tokens. Repeated requests still perform that work; reduced later timings reflect warmup/JIT/host effects, not a verified checkpoint cache. Stale direct-job attempts from UI runs remain explicitly classified in raw data. Per-thread isolated lexer allocations are recorded separately; they are not claimed as complete worker allocations.

In the separate isolated 10 MiB lexer replay (five samples per viewport), median calling-thread allocation was **356,656 bytes** for an 8,192-character prefix, **231,764,392 bytes** for a 5,246,976-character prefix, and **446,275,624 bytes** for the complete 10,485,760 characters. Token counts were 77 / 52,384 / 104,690. This verifies substantial prefix-dependent allocation in that callable path; it is not a retained-memory estimate or a direct allocation census of the concurrent production syntax worker.

### Preview, assets and media

| Plain source | Open on EDT n=1 | Refresh on EDT n=1 | 10-edit burst to latest HTML n=1 | Explicit layout/paint n=1 |
| --- | ---: | ---: | ---: | ---: |
| 100 KiB | 546.35 | 41.40 | 170.19 | 3.82 |
| 1 MiB | 877.33 | 151.51 | 260.11 | 3.07 |

Burst convergence ends when the latest unique marker is installed in HTML after 10 consecutive document insertions; undo verifies the original source hash. It includes debounce and any synchronous renderer/installation work, but not compositor completion. Separate explicit paints can run after Swing has already performed expensive validation; their small duration does not erase earlier layout cost.

| Warm production-method replay, n=1 each | 100 KiB | 1 MiB |
| --- | ---: | ---: |
| source_capture | 0.14 | 1.43 |
| preprocessing | 4.43 | 35.28 |
| parse | 4.91 | 19.19 |
| html | 1.25 | 4.97 |
| sanitize | 2.23 | 11.54 |
| asset_resolution | 1.03 | 2.54 |
| setText_offscreen | 8.70 | 46.88 |
| preferred_size_layout_offscreen | 74.03 | 635.30 |

Actual 4 KiB mixed preview (math + local relative PNG + Mermaid error fallback): open 1569.02 ms; refresh 82.69 ms; image-readiness observation 5.57 ms for 6 actual ImageViews. PNG loading requires non-null images with `ALLBITS`; the mixed HTML contains a Mermaid failure fallback and is **not** successful Mermaid rendering. The offscreen replay uses a generic HTML pane at 600 × 600 without the complete production CSS shell; it illustrates installation/layout cost and is not identical preview geometry. Rich-preview and offscreen replay stages must not be added to derive a first-use breakdown.

| Renderer control | First math n=1 | Identical math n=3 range | Changed math n=2 range | First Mermaid n=1 | Changed Mermaid n=2 range |
| --- | ---: | ---: | ---: | ---: | ---: |
| Packaged, fonts not prewarmed | 17997.66 | 0.031–0.117 | 7.235–11.271 | ERROR: no image | ERROR: no image |
| Classpath, fonts not prewarmed | 15298.16 | 0.017–0.060 | 2.493–3.308 | 10679.76 | 1817.647–2694.517 |
| Packaged, editor/fonts ready | 192.80 | 0.009–0.029 | 1.973–2.157 | ERROR: no image | ERROR: no image |
| Classpath, editor/fonts ready | 236.35 | 0.011–0.037 | 1.777–1.803 | 5853.37 | 1284.987–1603.591 |

The standalone first-math figure includes native font/class initialization and is not representative of font-ready editor math latency. The classpath Mermaid first-use figure is after math/font preparation, with a fresh JS engine, not fully cold font startup. Identical Mermaid cache hits are <0.1 ms in both successful classpath controls (n=3), while changed content still executes the renderer. Invalid math/DSL observations and actual errors are retained in raw samples; error durations are never successful render percentiles. This sample set is too small for p99 or a stable first-use budget.

Packaging diagnosis: `META-INF/services/com.oracle.truffle.api.TruffleLanguage$Provider` in the shaded JAR registers only the JavaScript provider. The regex provider class exists but is not registered. Valid Mermaid logs `No language for id regex found. Supported languages are: [js]`, then produces no image. The classpath control, with byte-identical application classes and separate dependency service resources, succeeds. This is strong evidence for a service-resource packaging defect, not a Markdown DSL parsing or Swing-layout explanation.

### Search, navigation, load and actual writes

| Actual find bar, plain source | Literal median / max, n=3 | Regex median / max, n=3 | Replace All install n=1 | Buffer switch p95, n=20 |
| --- | ---: | ---: | ---: | ---: |
| 100 KiB | 101.56 / 103.76 | 111.95 / 113.32 | 29.76 | 7.16 |
| 1 MiB | 113.85 / 128.85 | 130.89 / 140.02 | 87.86 | 8.35 |

Find-bar timings include its approximately 90 ms debounce and async result installation. Replace All is installed and undone with source hash verification. Model literal/regex-many-match/Replace All computation and cooperative cancellation are separately recorded, n≤5; they are not complete UI interactions.

| Mixed file | Worker/model open n=1 | Actual write n=1 | Projection move p95, n=100 | Mode |
| --- | ---: | ---: | ---: | --- |
| 100 KiB | 614.04 | 94.40 | Unsupported | Editable; dirty save |
| 1 MiB | 620.36 | 177.62 | Unsupported | Editable; dirty save |
| 10 MiB | 1068.85 | 416.35 | Unsupported | Editable; dirty save |
| 50 MiB | 749.62 | 464.53 | 1.19 | Read-only projection; streamed copy |
| 100 MiB | 1165.34 | 860.50 | 1.30 | Read-only projection; streamed copy |

Model opens include first-use class initialization in each child and uncontrolled warm OS caches; they are not UI readiness. Dirty saves really write and are independently SHA-256 verified. Large-file streaming calls the same `AtomicFileWriter`/store writer on a separate target and verifies `Files.mismatch == -1`; it does not represent editable large-file save support. The separate clean read-only save is a no-op and excluded from write-throughput conclusions. Adversarial model opens and writes for all six shapes are retained with their hashes in raw evidence.

### Heap, RSS, allocations and bounded heaps

Whole-process RSS is distinct from heap. Startup empty default-font full-ready used heap is approximately 32.12 MiB, with 64 MiB committed; no forced GC. The unchanged reference harness, after its one-second settling phase, reported heap-used median 26.94 MiB and RSS median 502.36 MiB. Native/font/graphics/JIT resources contribute beyond heap; exact per-subsystem retained RSS attribution remains unverified.

| Actual mixed UI | Full-ready used / committed MiB | After edits used / committed MiB | After document close used MiB | Sampled peak RSS MiB | Cumulative GC count / ms after edits |
| --- | ---: | ---: | ---: | ---: | ---: |
| 100 KiB | 15.8 / 64 | 39.4 / 64 | 19.6 | 450.6 | 11 / 35 |
| 1 MiB | 47.9 / 64 | 55.3 / 68 | 74.2 | 504.4 | 18 / 46 |
| 10 MiB | 199.3 / 292 | 300.3 / 469 | 478.3 | 853.2 | 29 / 129 |

These are live occupancy readings, not retained sizes or leak findings. Close snapshots may contain garbage and undo/view objects awaiting collection; no forced GC or stable post-close plateau was measured. Opening rich preview, repeated file switches and renderer-close phase heap/GC readings are retained in `cases.json`; their phase RSS may lack a sufficiently close live process sample. Sampled peak RSS spans the whole case, not idle memory.

With a 128 MiB heap, the corrected 10 MiB model run completed and independently verified its dirty save in 378.59 ms, allocating 54,559,272 bytes on the save thread. After the full model workload (including lexer, regex, replacement and snapshot probes), heap occupancy was 115.83/128 MiB with 427 collections and 1391 ms cumulative GC. Those totals are not save-only pauses or ordinary-typing GC. The constrained 100 KiB UI and 50/100 MiB read-only model controls completed; no system-wide pressure was induced.

Snapshot-only replacements, plain-document-plus-snapshot-listener timings, UI mutation wall/CPU/allocation, actual paint duration, load/save allocations, GC logs and JFR weighted allocation samples remain separate. Allocated bytes measure traffic; none is equated with retained memory. Native-memory snapshots are early explicit samples, not peak or complete external allocation censuses.


## Profiler attribution: confirmed paths and hypotheses

The four final attribution runs are separate from unprofiled latency tables. JFR records execution/native samples across startup and the whole workload; stack groups overlap and include sleeping native threads. **Counts are not CPU percentages.** No production sampling code was added.

| Run | Total execution/native samples | Relevant overlapping stack-presence counts | What this establishes |
| --- | ---: | --- | --- |
| 10 MiB source UI | 616 | EDT `PlainView` 223; font metrics 164; undo 84; gap content 6; versioned snapshot 11 | The expensive local undo/removal path traverses `DefaultDocumentEvent.undo` / `AbstractDocument.remove` → `PlainView.removeUpdate` → `updateDamage` → `calculateLongestLine` → `getLineWidth`/font measurement. It is synchronous view invalidation on EDT, not just asynchronous parsing. |
| 1 MiB plain preview | 770 | EDT paragraph view 216; flow view 176; font metrics 166; renderer 24; HTML editor kit 10 | Actual UI validation/preferred-size work uses Swing HTML paragraph/flow layout. Moving parsing to a worker alone cannot remove HTML installation/layout blocking. Counts also include explicitly labeled offscreen replay. |
| Empty startup | 205 | Top worker stacks: native font loading 85; native font creation 78 | System font enumeration is a substantial startup stage. This does not attribute a particular number of retained native bytes to fonts. |
| 1 MiB extreme-line UI | 3,325 | EDT `PlainView` 1,845; font metrics 560; gap content 38; snapshot 3; top Metal glyph-rendering worker stack 854 | Long lines stress actual glyph painting as well as width/model-to-view measurement. This profiled case completed in 52.97 s; the unprofiled complete workload hit the 60 s watchdog. A universal deadlock or one exclusive hot method is not established. |

The unwrapped 10 MiB undo action itself has wall p95 **43.41 ms** and current-thread CPU p95 **42.53 ms**, versus source-component paint duration p95 **0.23 ms** (100 samples each). Its input-action-to-completed-paint p95 is **43.96 ms**. View width work occurs in document notifications before paint; a small final paint does not mean a cheap edit. Snapshot-only replacement p95 at beginning/middle/end is **0.090 / 0.108 / 0.127 ms** (34/33/33 samples). Plain document + snapshot-listener insert-and-remove p95 is **5.52 / 2.24 / 2.49 ms**. These are different workloads; their subtraction would not isolate a trustworthy storage cost.

The final long-line timeout thread dump caught EDT waiting for a Metal glyph flush, beneath Swing text drawing. JFR also samples `EditorUiController.paintForegroundSpans` through model-to-view/font measurement. The source/graphics rendering bottleneck is verified; exact asymptotic complexity, an independent overlay contribution and Metal-specific versus cross-platform cost remain hypotheses requiring separate controls. Do not prescribe a new document store based on this evidence.

JFR weighted allocation estimates across the source UI workload include approximately **350 MB byte arrays / 114 MB char arrays**; the preview workload includes **1.15 GB char arrays**, and the extreme-line workload **4.42 GB char arrays / 2.62 GB byte arrays**. These are sampled traffic estimates over many operations, including initialization and probes; they are neither live heap nor a single edit's allocation. GC pause-event totals for those runs were **143 / 190 / 731 ms** respectively. A 10 MiB model open independently recorded **267,042,936 bytes** allocated on its calling thread, including initialization/decoding/document construction; more detailed call-site allocation attribution is still needed.

Early NMT samples show source UI total committed **365,758 KiB**, of which Java heap committed **151,552 KiB**; startup total committed **256,252 KiB**, heap **65,536 KiB**. Reserved address space is not resident memory. NMT excludes some font/graphics/external allocations and these samples precede workload peaks, so neither is a complete explanation of RSS. [Profile summaries](performance/markdown-tranche1/profiles.json) retain full top stacks and allocation groups; [NMT output](performance/markdown-tranche1/native-memory.json) retains the original explicit snapshots.

## Ranked follow-up engineering work

No item in this section is implemented by Tranche 1. Effort estimates are engineering estimates, not measured delivery times. Acceptance targets are proposed unless explicitly listed as existing gates below.

| Rank / scope | Evidence and user impact | Smallest proposed change | Effort / dependencies / risks | Required checks and acceptance |
| --- | --- | --- | --- | --- |
| P0, Markdown packaging | Valid Mermaid fails in the shipped JAR; identical application classes work with dependency JARs on the classpath. The regex provider class is present but absent from the merged Truffle language-provider service file. | Merge service-provider resources in the existing shaded packaging configuration and add an opt-in packaged-JAR Mermaid smoke test. Investigate both shade configurations in `pom.xml:98` and `:277`. Do not change renderer architecture to fix registration. | 0.5–1 day; independent of source/view work. Risk: other shaded service registrations, native packaging profiles and distributions. | A fresh packaged process renders a small valid diagram to a decodable image, reports invalid DSL safely, closes resources, and passes existing math/preview tests. Compare every packaged distribution. No “fast failure” render metric counts as success. |
| P1, entire IDE source view | Undo/removal spends EDT time in `PlainView.calculateLongestLine` via `updateDamage`; long-line diagnostics additionally implicate font measurement/model-to-view in source overlays. The document store is not the dominant sampled cost. | First isolate per-view width invalidation with a narrow, source-preserving Swing view prototype; separately bound repeated prefix-to-pixel measurement in visible syntax overlays. Keep the authoritative `Document`, undo and split ownership. Decide on a production change only after prototype profiling. | 3–7 days feasibility, implementation estimate afterward. Independent of parser changes. Risks: tabs, proportional fonts, Unicode shaping, horizontal extent, wrapping and split font differences. Do not merely enable wrapping by default. | Same 10 MiB fixture: 100 local undo/removal samples p95 ≤16 ms, p99 ≤64 ms; no document-wide width scan for an ordinary local edit after initialization. 256 KiB-line workload completes within its watchdog; p95 ≤16 ms is a separate stretch target requiring a demonstrated algorithm. Preserve pixel positioning, scrolling, undo and 0A/0B tests. |
| P1, Markdown highlighting | Actual viewport requests flatten and tokenize the document prefix; final viewport emits tokens for preceding text. Repeated jobs recreate the grammar service. | Design reusable lexical state/checkpoints and visible-range output with revision-safe invalidation. Start with bounded token output and allocation measurement; align parser-state ownership with Tranche 2 rather than building a competing semantic engine. Locations: `SyntaxUiController.java:539–603`, `GrammarHighlightService.java:89–93`. | 3–5 days for bounded output/cancellation investigation; stateful checkpoints depend on semantic-model contracts. Risks: fences, nested Markdown, delimiter state, stale revisions, accidental rescans. | Beginning/middle/end source must produce equivalent visible tokens. Repeat/distant-scroll/one-character-edit jobs converge ≤250 ms at 10 MiB on qualified hardware; emitted spans bounded by viewport/context; report prefix scan bytes and allocations. No full-document work on each key. |
| P1, Markdown preview | Real refresh performs renderer work plus HTML installation on EDT; independent layout replay and JFR identify substantial Swing paragraph/flow layout. Rich renderer initialization has additional cost. | Capture immutable source/revision, prepare HTML/assets on a cancellable worker with lazy initialization and bounded ownership; install only current results on EDT. Measure HTML installation/layout independently before deciding how to bound that second stage. Locations: `MarkdownPreviewPane.java:147–162`, `MarkdownPreviewRenderer.java:34–64`, `MarkdownPreviewAssets.java:91–146`. | 3–5 days initial worker boundary; layout mitigation requires a separate measured spike. Independent of source-width work, shares revision contracts with Tranche 2. Risks: wrong-document previews, stale asset URLs, lifetime/cancellation, EDT-only Swing access and scroll synchronization. | Rapid edits and document switches cannot install old HTML. Close cancels/disposes safely. Warm 100 KiB update ≤250 ms; 1 MiB ≤1 s end-to-end, with no >16 ms preparation task on EDT. A separate gate measures remaining HTML install/layout rather than hiding it behind worker timing. Retain B.1 geometry-sensitive scroll tests. |
| P1, entire IDE startup/native footprint | Startup profiles sample native font enumeration; configured font overrides delay initial source readiness. RSS greatly exceeds used Java heap. | Profile and narrow `StagedStartupFont.loadSystemFonts` and configured-font initialization (`Texteditor.java:535`, `StagedStartupFont.java:18–64`), preserving existing fonts and staged UI. Determine unavoidable font cost versus duplicate discovery before changing anything. | 2–4 days investigation; can proceed independently. Risks: fallback font metrics, custom font behavior, cross-platform font availability and eventual menu/tool initialization. | At least five fresh-process runs each for default/override/empty/1 MiB. Retain existing 2/3/10 s gates; no font/layout changes. Separate native font attribution from JVM committed heap; compare equivalent settled RSS phases. |
| P2, entire IDE allocation and persistence | 10 MiB editable operations/load produce much more heap traffic than the source bytes; a 128 MiB model run completes but with heavy GC. This does not establish a leak or save-specific pause attribution. | Profile exact codec/snapshot/undo allocations before proposing stream-oriented encoding or fewer transient copies. Locations: `FileBuffer.java:391`, `VersionedTextSnapshot`, `TextFileCodec`, `AtomicFileWriter`. Preserve codec metadata and atomic save guarantees. | 2–3 days attribution; changes separately estimated. Independent of Markdown parser. Risks: BOM/mixed-newline preservation, snapshot consistency, recovery and durability. | Independently verify dirty saved bytes and all codec/data-loss tests; stable 10 MiB load/edit/save under a bounded heap with recorded GC and no retained benchmark blackholes. No whole-document ownership rewrite. |
| P2, startup recovery ordering | Early editing during staged startup can create a journal before the initial recovery read, producing a recovery prompt for the current process's work. Observed only in deliberately early benchmark input; no user-data loss reproduced. | Reproduce in a narrow integration test using an isolated home, then distinguish previous-session journal discovery from current-session capture if confirmed. `Texteditor.java:544`, `RecoveryController.java:318`, `:417`. | 1–2 days investigation; independent correctness follow-up, outside measurement scope. Risks: suppressing legitimate recovery. | Deterministic early-input/staged-startup test must never suppress an existing prior-session journal and must not prompt to recover the current session's own new snapshot. No timing sleeps as a fix. |
| P2, Markdown navigation/search | Search has real matching/debounce/installation costs; heading navigation still reads source outside a shared Markdown model. | Use Tranche 2 ranges for outline/heading commands when that model exists; retain existing search cancellation and match bounds. No new index/database now. | Depends on semantic model; avoid preemptive parallel infrastructure. Risks: source offsets and stale results. | Correct navigation after edits; cancellation and source-preserving Replace All/undo; ≤250 ms normal 1 MiB find-bar convergence as an initial target, with debounce reported separately. |

### Budgets and qualification policy

| Metric | Existing gate (unchanged) | Proposed next target / status |
| --- | --- | --- |
| Fresh JVM visible / painted source / full workbench | 2 / 3 / 10 s in desktop harness | Keep these first. Stretch source usability ≤1 s requires a quieter-host qualification, not a new claim. Configured fonts must be tested explicitly. |
| Used heap / whole-process RSS | 384 / 768 MiB in desktop harness | Preserve gate definitions. Stretch settled empty/small-file RSS ≤384 MiB; not yet qualified. Peak workload RSS is a separate metric. |
| Model edit / caret / maximum independent stage | 16 / 16 / 250 ms | Preserve; these do not certify user-visible input or convergence. |
| Source input-action to completed Swing paint | No new gate added | Proposed p95 ≤16 ms / p99 ≤64 ms through 10 MiB for local typing/delete/undo/redo, n≥100 per category and both wrap modes. |
| Caret/selection/scroll | No compositor gate | Proposed EDT action plus completed visible paint ≤16 ms p95; sustained trackpad/compositor qualification remains separate. |
| Markdown highlighting convergence | Existing 250 ms model gate is not an observed-UI gate | Proposed actual latest-generation UI convergence ≤250 ms at deep 10 MiB viewports, including stated debounce; stable visible-span/scan/allocation bounds. |
| Warm preview | No established full-pipeline gate | Proposed 100 KiB ≤250 ms, 1 MiB ≤1 s and preparation EDT tasks ≤16 ms. Installation/layout must retain a separate gate. |
| Mermaid | No established gate; packaged success currently broken | Aspirational first engine use ≤2 s and changed simple diagram ≤250 ms; identical cached ≤100 ms. Qualify successful renders only, after packaging correctness. No invented predicted speedup. |
| Math / images | No established gate | Proposed font-ready first small math render ≤250 ms, cached ≤100 ms; local tiny image availability ≤250 ms after HTML installation. Full cold font startup measured separately. |
| File load | No new gate | Proposed worker open ≤500 ms at 1 MiB / ≤2 s at 10 MiB and 50–100 MiB projection; at least five opens with controlled cache labels. Current n=1 values do not qualify a p95. |
| Genuine streamed copy / large reader movement | No new gate | Proposed 100 MiB ≤2 s streamed write, independently verified, and 40-line projection movement ≤16 ms p95. This does not certify editing or rendered scrolling of 100 MiB. |

A target requires correctness checks, identical artifact/fixture/JVM/hardware settings, and sufficient samples. No noisy exploratory result changes a CI gate. Before calling these product guarantees, rerun on a quiet constrained Mac and a second cross-platform host, retain raw data, and compare native RSS plus GC. Do not force system memory pressure; a later opt-in OS-pressure experiment needs explicit limits and a stop policy.

### Readiness for Tranche 2

The correctness baseline supports defining source-range/revision contracts for the shared semantic model. It does **not** support claiming an exceptionally lightweight Markdown product today. Begin contract/test design independently if desired; first production follow-ups should be the small packaged-Mermaid correctness fix and the source-view/preview feasibility work above. Do not add live decorations, rich inline rendering or more parse consumers before setting and measuring their EDT/allocation bounds. Markdown lexical-state ownership should be designed together with the semantic model, rather than optimized through a second unrelated parser.

No language/framework replacement is justified by this evidence. Read-only projection measurements show useful bounded file handling; they do not demonstrate that the existing editable Swing view can scale to 50–100 MiB. That remains a separate engineering milestone.

## Coverage gaps and next qualification procedure

Unavailable measurements are not successful zeroes:

- Physical keyboard/IME-to-compositor latency, display scanout, sustained trackpad frame pacing and true cold filesystem/font caches were not measured. The probe's normal Swing paint completion is the closest current reproducible UI timing; forced paints are separately labeled.
- UI latency was measured in Plain mode. Existing Maven tests preserve Vim/Emacs correctness; comparative keymap performance and native IME performance need a separate desktop qualification.
- No 50–100 MiB editability claim is made. Those sizes use production read-only projections. Reader-window movement and streaming are measured without the full workbench; full large-file rendered scrolling, selection and split-view memory remain unqualified.
- First and repeated native renderer samples are small. The packaged Mermaid failure is a functional blocker, so successful renderer performance comes from a clearly separate classpath control. Real UI font readiness must be separated from a standalone renderer's font initialization.
- End-to-end rapid-edit preview convergence is measured through latest HTML installation; compositor completion and stale-preview installation across active-document switches are not timed. Existing preview/document/split tests remain the correctness evidence for those flows. A future benchmark should carry an explicit render generation rather than infer one from a queued repaint.
- Syntax jobs are exercised through production scheduling at multiple viewports and after source edit workloads. Their standalone worker allocation totals are not isolated from concurrent workers; isolated lexer per-thread allocations and JFR allocation traffic are separate evidence. No synthetic percentage is assigned to snapshot/listener costs by subtracting unrelated timings.
- Heap readings are without forced GC. No post-quiescence retained-object census or document-close leak conclusion is justified. The full Swing edit workload records split creation and switching, but does not isolate incremental typing overhead of one versus two panes. RSS samples immediately around shutdown can be unavailable; zero `ps` readings from early runner versions are not memory-release evidence.
- Search cancellation measures cooperative cancellation between matching steps, not interruption inside a pathological regex engine call. Bounded ordinary regexes were used; no adversarial unbounded regex workload was run.
- The 128 MiB runs bound one JVM, not macOS. There was no deliberate host-pressure injection, swap exhaustion, process eviction test or low-memory survival certification. Linux/Windows and a quiet second Mac remain unmeasured.

For future qualification: use the same application, runner and fixture hashes; record host pressure before/after; run each startup configuration at least five times and each ordinary edit category at least 100 times; compare both wrap modes; retain JFR separately from unprofiled times. Repeat on quiet hardware before selecting regression thresholds. Use explicit state/generation waits, not longer sleeps, for preview, highlighting and file installation. A system-wide memory-pressure experiment remains separately opt-in.

## Files and runtime isolation

Tranche 1 adds or extends only these tooling/documentation paths:

- `scripts/benchmark-markdown.py`: deterministic corpus, isolated bounded child JVMs, raw results, environment/RSS capture, optional JFR/NMT, meaningful completion/error/unsupported states. Reuses the established desktop percentile definition.
- `scripts/test_benchmark_markdown.py`: four fast deterministic checks for fixture identity/syntax, bounds, PNG CRCs and percentile/status handling. No UI or heavy performance workload enters Maven's ordinary tests.
- `src/benchmark/java/shed/MarkdownPerformanceProbe.java`: separately compiled probe invoking the existing editor, document, worker, search, preview, asset and file APIs. Reflection accesses existing measurement/state seams only. Synthetic edits use disposable copies and restore/verify their source. No production instrumentation, retained monitoring service or default startup work is added.
- `src/benchmark/java/shed/MarkdownJfrSummary.java`: streaming JFR summarizer; avoids loading whole recordings into heap. Does not run in normal application execution.
- `docs/LARGE_FILE_BENCHMARK.md` and `docs/EDITING_PERFORMANCE.md`: correct legacy measurement descriptions while retaining established report keys, commands and gates.
- `docs/MARKDOWN_PERFORMANCE_BASELINE.md`: this report, reproduction, measurements, limits, budgets and next engineering work.
- `docs/performance/markdown-tranche1/`: compact raw operation/case metadata, fixture manifests, profiler summaries, reference benchmark results and regression evidence. Large documents, JFR binaries and temporary working copies stay outside the checkout.

The existing `scripts/benchmark-editor.py`, `EditorWorkflowProbe`, legacy Java benchmarks, application classes, renderer, codec, document ownership and production UI remain unchanged by this tranche. The extra fixture cleanup of dirty state is in the probe alone. Each benchmark uses an isolated home and disposable file; source snapshots are not retained by ordinary application execution. No dependency or eager initialization is introduced.

## Regression verification and repository compliance

Commands executed with the existing Java 21 toolchain, offline dependencies and a live desktop:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -Djava.awt.headless=false \
-DargLine=-Xmx512m test

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_benchmark_markdown.py

python3 scripts/benchmark-editor.py --jar target/shed-2.0.0.jar \
  --runs 3 --label markdown-tranche1-reference --output /tmp/shed-desktop-NEW.json

"$(scripts/resolve-toolchain.sh home)/bin/java" -Xms64m -Xmx512m \
  -cp target/shed-2.0.0.jar shed.EditorResponsivenessBenchmark --samples=3

git diff --check
shasum -a 256 README.md
```

The complete Maven suite passed **twice**: each run executed 1,309 tests, with **1,304 passing, five skipped, zero failures and zero errors**. The final run completed in 3:19. It includes `MarkdownFoldingTest`, `MarkdownEditTest`, `MarkdownServiceTest`, `TexteditorSwingIntegrationTest` (including preview synchronization), FileBuffer/codec/undo, large-file store/projection and recovery suites. No tests were weakened or suppressed. Per-suite evidence lists all counts. The five existing skips are the unsupported DOS hidden-attribute check, unavailable Fish integration, unavailable CTest-preset integration, and the two explicitly opt-in AI performance/native acceptance suites.

The four Python tooling tests passed, including a final rerun (0.010 s). Every benchmark mode used in this report was compiled against the unchanged application JAR and exercised. The reference model harness exited zero: edit p95 0.133 ms, caret 3.693 ms, maximum independent computation 88.618 ms, n=3. These model numbers do not certify Swing responsiveness.

Benchmark qualification is **not all green**: the unchanged desktop startup gate failed in one of three launches; two packaged-asset cases report ERROR because valid Mermaid produced no image; one unprofiled extreme-line case timed out. The profiled extreme-line control completed in 52.97 s. The final mixed preview completed its pipeline with an explicitly recorded Mermaid error fallback. The report retains those outcomes instead of treating fast failures or partial samples as successes.

`git diff --check` passes. No production source or ordinary JUnit test file was modified by Tranche 1; the ten original protected/pre-existing file hashes, including 0A/0B/0B.1 source/tests and README, match their initial values. The design system, visual identity, layout, typography, themes, navigation, keyboard interactions, split/document ownership, LSP and persistence architecture remain unchanged. No dependencies, runtime instrumentation, persistent workers or eager startup work were added to normal execution.

`README.md` SHA-256 before **and** after:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

No commits or pushes were created by this work. Repository HEAD advanced independently during interruptions (the pre-existing changes and initial benchmark work became committed); those commits and every baseline source/test byte were preserved. The final working patch contains only benchmark tooling, documentation and raw measurement evidence.

The smallest next performance implementation tranche is a **source-view width-invalidation feasibility change**, bounded to existing Swing view behavior and validated by this 10 MiB/long-line probe. Stop that follow-up if it requires a document ownership rewrite; return evidence before widening scope. The packaged service-registration repair is a smaller independent correctness task and should precede shipping Mermaid. Preview work and semantic-model contract design can proceed independently, with explicit revision/cancellation and EDT budgets, but no such work has begun here.
