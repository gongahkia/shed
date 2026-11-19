# Tranche 8A.1 negative gate evidence

Read the [qualification report](../../DECORATED_WINDOW_TRANSPARENCY.md) for the **NO-GO** verdict and explicit unavailable measurements.

| Evidence | Meaning |
|---|---|
| `report.json.gz` | Exact commands, source/artifact hashes, environment, all source paint/EDT samples, heap, timestamped RSS, six rejected requests and disposal records. Read using `gzip -dc`. |
| `gate.log` | Opaque child exit 0, attempted child exit 0 with NO_GO. The driver returned **3**, the expected failed feasibility gate. |
| `opaque-stdout.log`, `attempt-stdout.log` | Complete child protocol logs; no successful decorated transparency state exists. |
| `measurements.json` | Per-operation summaries and nearest timestamped RSS; both children are opaque. |
| `*-opaque-components.png` | Offscreen images of the disposable component tree, not desktop/title-bar screenshots or transparency proof. |
| `jdk-audit.json` | Installed source SHA-256 and method line locations for the Frame guards and AWT opacity path. No modified JDK classes. |
| `jna-awt.txt`, `jna-mac-window.txt` | Read-only disassembly of the existing packaged JNA implementation; it is not a new interop dependency. |
| `accessibility-read.json` | Read-only host preference observation. No system settings changed. |
| `java-version.txt` | Exact installed Java 21 runtime identity. |
| `maven.log.gz`, `tooling.log.gz`, `packaged-smoke.log.gz`, `verification.json` | Regression commands/counts/skips, packaged smoke and repository compliance. |
| `baseline.json.gz`, `git-status-after.txt` | Original 1,451-file hash inventory and Git status; prior user/tranche changes remain present. |
| `inventory.json`, `checksums.sha256` | All new 8A.1 files and hashes, with self-exclusions stated. |

No new native profile or extended lifecycle stress was run after the configuration failed. No native blur, private peer modification, new dependency, production setting, custom window chrome or production UI change was introduced. The 8A undecorated results are prior diagnostic evidence only, not a substitute for this failed decorated-window gate.
