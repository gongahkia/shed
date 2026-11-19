# Tranche 8D evidence

Read the [implementation report](../../FOCUS_MODE_CENTERED_WRITING.md) for the supported subset and explicit large-wrapped rejection policy.

| Evidence | Meaning |
|---|---|
| `paired-final.json.gz`, `paired-final-logs.json.gz` | Final 16-process baseline/current comparison, alternating artifact order. Commands, source/artifact hashes, environment, raw per-operation metrics and RSS. |
| `measurements.json` | Derived summaries, phase enabled flags, first/warm samples, heap, nearest RSS and source-integrity records. An unsupported phase with `enabled=false` is not a focused performance result. |
| `fixtures.json` | Exact fixture sizes, encoding, EOF and SHA-256. Scratch paths identify the run, not installed personal documents. |
| `verification.json` | Maven/tooling/packaged-smoke results, existing skips, context guard, original/final HEAD, README and changed-file safety checks. |
| `maven-final.log.gz`, `package-qualified.log.gz`, `tooling-qualified.log.gz`, `packaged-smoke-qualified.log.gz`, `final-performance.log.gz` | Final executed verification logs. |
| `reproduction.log.gz` | Pre-implementation restoration regression: pre-hidden header incorrectly became visible. |
| `focused-convergence.log.gz` | Passing 33-test focused run after fixing resize/layout convergence, before the final large-wrapped guard. Final full Maven includes the later guard test. |
| `focused*.log.gz`, `narrow-layout.log.gz`, `center-diagnostic.log.gz`, `initial-focused.log.gz` | Intermediate failures and corrected tests. These are not relabeled as passing qualification. |
| `CenterDiagnostic.java.txt`, `center-observation.log.gz` | Isolated stale-inset reproduction: after resize, an explicit later layout update corrected the inset. |
| `ContextGuardProbe.java.txt`, `context-guard.log` | Final-JAR automatic restoration when an eligible unwrapped source becomes unsupported wrapped source; two prior-centering states, source integrity checked. |
| `pre-bound-paired.json.gz` | Provisional working large-wrapped layout exposed expensive whole-root paints; rejected as broad support evidence. |
| `width-only-fallback.json.gz` | Width-only fallback still had long whole-root paints; superseded by no-change entry rejection. |
| `exploratory-missing-undo-marker.json.gz`, `exploratory-unpaired.json.gz`, `before.log.gz` | Earlier benchmark compile/paint-attribution issues and unpaired controls, including partial compilation overlap. Excluded from final matched results. Earlier probe hashes can differ from final source. |
| `maven.log.gz`, `maven-bounded.log.gz` | Passing full-suite checks for the provisional versions, kept separate from the final suite. |
| `minimum-state.md` | Design recorded before production edits. |
| `baseline.json.gz`, `git-status-after.txt` | Original repository content/status and final status; pre-existing work and external commits were preserved. |
| `inventory.json`, `checksums.sha256` | Every added/modified 8D file, byte identity and stated manifest self-exclusions. |

Use `gzip -dc` for compressed JSON/logs. Benchmarks are opt-in; routine tooling tests generate bounded fixtures without opening Swing windows. The final JAR is identified by hash and is not duplicated into repository evidence.

Memory measurements distinguish active post-cycle used heap, committed heap, short post-disposal observations and sampled process RSS. They do not establish full native attribution or universal closed-editor reclamation. Source typing paint, forced source scroll paint and forced whole-root toggle paint have different boundaries. Existing Unicode, wrapped-root, preview and general release limitations remain open.
