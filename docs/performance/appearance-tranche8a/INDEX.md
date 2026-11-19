# Tranche 8A evidence

The [engineering report](../../APPEARANCE_FEASIBILITY.md) defines verdicts and measurement limits. No production appearance implementation is included.

| File | Content |
|---|---|
| `matrix-report.json.gz` | Twenty fresh disposable-window children; paired alpha/opaque raw timings, RSS samples, environment, commands and source hashes. Nineteen fully passed; the first opaque child failed optional image capture after its timings. |
| `matrix-logs.json.gz` | Every matrix stdout/stderr log, including the failure. |
| `native-report.json.gz`, `native-logs.json.gz` | Two NMT/vmmap-instrumented alpha/opaque children and raw diagnostics. **Not native blur.** |
| `capture-control-report.json.gz`, `capture-control-logs.json.gz` | Two passing children after optional capture failure handling was narrowed. |
| `pilot-report.json.gz`, `pilot-logs.json.gz` | Earlier exploratory pair; not pooled with final measurements. Its rectangle images were removed and are not included. |
| `measurements.json` | Derived per-child summaries, nearest timestamped RSS, heap and source/listener/window disposal assertions. |
| `alpha-*-component-argb.png` | Offscreen component rendering, alpha background and full-alpha foreground marker. No photographed desktop or blur claim. |
| `alpha-*-owned-window-surface.png` | Only the experiment's OS window surface. May retain alpha; excludes other windows and the desktop backdrop. |
| `capture-correction.txt` | Record of the discarded rectangle-capture approach. |
| `accessibility-read.json` | Read-only host reduced-transparency / increased-contrast observation; no system settings changed. |
| `platform-audit.json` | Installed-JDK source identities and relevant implementation locations, SDK header location and primary documentation links. |
| `AppearanceTransparencyProbe-matrix.java.gz` | Exact probe revision for matrix and NMT measurements, before optional capture handling. Final probe hash is in the capture-control report. |
| `AppearanceTransparencyProbe-pilot.java.gz`, `benchmark-appearance-pilot.py.gz` | Earlier pilot source identities, before owned-window capture. |
| `baseline.json.gz` | Starting HEAD/status, all 1,422 pre-existing file hashes, production artifact identity. |
| `verification.json`, `maven.log.gz`, `tooling.log.gz`, `packaged-smoke.log.gz` | Full-suite counts/skips, packaged smoke output, safety checks and explicit unavailable qualification. |
| `git-status-after.txt` | Preserved pre-existing changes plus new 8A files; this is not a clean-checkout claim. |
| `inventory.json`, `checksums.sha256` | Every 8A file and byte identity, with self-exclusion documented. |

Read compressed reports with `gzip -dc`. Raw scratch paths identify the exact run and are not expected to exist on another machine. Reproduction uses the opt-in driver with a **new directory outside the checkout** and Java 21. Routine tests do not launch this GUI experiment.

The alpha experiment does not establish decorated-window compatibility, real workbench contrast, native blur, physical-input latency, all-pane paint completion, long-term native-resource reclamation or Windows/Linux behavior. Those remain gates. The existing Unicode and preview production blockers remain open.
