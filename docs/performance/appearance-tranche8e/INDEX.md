# Tranche 8E evidence

Read [the implementation report](../../THEME_PREVIEW_AND_SYSTEM_APPEARANCE.md) for the delivered preview subset and incomplete automatic-theme requirements.

- `starting-state.json`: entry HEAD, existing working changes and frozen baseline JAR identity.
- `implementation.patch`: six modified production/test files compared with their actual starting bytes, independent of the external commit.
- `verification.json`, `maven-full-results.json`: exact commands, final counts and five unchanged skip reasons. The full Maven suite ran before the final focused rerun; the focused run overwrote those three Surefire XML files, so the full counts were retained beforehand.
- `full-tests.log.gz`, `focused-final.log.gz`, `tooling-final.log.gz`: original command output. Quiet successful focused output may be empty; class counts are recorded in verification.
- `config-tests.log.gz`, `gallery-tests.log.gz`: initial new-fixture failures caused by the nonexistent theme ID `gruvbox`, corrected to registered `gruvbox-dark`. No existing assertion was weakened.
- `packaged-smoke.json`: independent packaged-only Mermaid/math/image execution and candidate JAR SHA-256.
- `paired-report.json.gz`: final eight-process host records, fixture hashes/paths, artifact hashes, child commands/exits, all raw operation samples, heap records and sampled RSS. JDK version and final unchanged Java probe hashes were appended after the run as identity metadata; the measured child events were not rewritten.
- `summary.json`: nearest-rank p95, median, maximum and sample count for each measured operation; used/committed heap and lifecycle records remain separate.
- `benchmark-final.log.gz`: all eight final children completed successfully.
- `exploratory-report.json.gz`: prior run with unmatched baseline acceptance counts. Preserved, but excluded from the final paired comparison; it must not be substituted for the final report to make first-switch numbers look faster.
- `system-appearance-audit.json`: limited read-only JDK Java-source scan and its native-source exclusion. No global OS appearance changes or live automatic-notification qualification occurred.
- `preservation-check.json`: starting/final README hashes, unchanged existing files, external HEAD advancement and frozen candidate/current compiled-class comparisons.
- `inventory.json`, `checksums.sha256`: file inventory and integrity checks. Inventory/checksum self-hashes are excluded to avoid recursive identities.

Reproduction uses the existing toolchain environment and explicitly invoked `scripts/benchmark-theme-preview.py`. It requires a new output directory **outside the checkout**, a measured JAR and a frozen baseline JAR. The original artifacts remain in `/tmp/shed-tranche8e-run/`; these large JARs/classes are not checked into evidence. The baseline source corresponds to entry HEAD `b1aacc16fe3cc364e1d1fcc7bb76e41fd6d94fb9` (entry working changes were documentation only). There is no need to alter the user's worktree to reconstruct a baseline in a separate scratch build.

Each child uses a disposable home, `-Xms64m -Xmx512m`, existing default font, explicit Shed Light and Plain mode, bounded 75-second execution and finite operations. System GC requests appear only in this diagnostic harness. The four workloads are 4 KiB source, 100 KiB with splits/focus, 1 MiB source, and 4 KiB with the existing HTML preview open. The final baseline and candidate both perform acceptance/save and restoration operations.

Typing latency is the production input-to-completed-source-paint metric. Theme timing is synchronous EDT application/validation plus forced existing root/gallery painting; native display presentation, background convergence and total queued-EDT occupancy are not measured. RSS uses 250 ms samples and is neither a definitive peak nor settled idle memory. No new NMT/native attribution was performed. The probe's local source references survive its window-disposal point, so the post-disposal heap is not new buffer-reclamation evidence.

No performance claim resolves the known Unicode first-edit, HTML layout, native-memory, cross-platform or release-readiness gaps. No automatic OS observer, preferred light/dark settings, native bridge, polling worker, density feature or later appearance tranche was implemented.
