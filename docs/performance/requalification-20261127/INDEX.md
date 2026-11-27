# Safe E2E and latency requalification — 27 November 2026

Verdict: **PASS for the exercised workflows and latency budgets; broader whole-IDE performance acceptance remains partial.** No production code changed. Starting clean HEAD: `f4c34c81e144d1c749a5c1a2f2019d12052835b9`. Same packaged application as the previous dock qualification: SHA-256 `ac3a549ffda73476ec0ce27a9c95ba0f488f43a92212d64e46163ac101681a21`.

## Results

| Check | Actual result |
| --- | --- |
| Reviewed portable Maven regressions | 595 total, **588 passed**, seven existing skips, zero failures/errors |
| Opt-in LSP in-process performance | One measurement test passed; 24 cancellation/disposal cycles retired owned workers |
| Python tooling | **116 passed**; repeated after final probe change |
| Packaged Mermaid/math/local-image smoke | PASS |
| Existing owned-window Chat/external fixed Java client | Four PASS runs; no remaining task IO workers, sentinel content/mtime unchanged and fixtures removed |
| Watcher/visualization workflow, actual Swing window and disposable Git | Final PASS, 37.84 seconds, zero descendants, fixture removed; earlier failed assertions preserved below |

The broad selection includes every reviewed manifest name except retired `ProjectFileOpenPrerequisiteTest`. Counts overlap: do not sum suites. All seven broad skips are existing measurement/data-loss opt-ins. Tests using real language servers, interactive native shells or unrestricted GUI suites remain excluded. Neither `make test` nor the blocked whole-IDE harness was substituted for source-reviewed admission.

## Performance boundaries

Previous dock Chat p95 was 0.689 ms. Fresh sequential Chat runs 2–4: **0.849 / 0.551 / 0.699 ms**, 40 samples each, first samples **2.296 / 2.928 / 2.994 ms**. Run 1 overlapped portable regression work: p95 1.500 ms, first 2.161 ms; it is preserved separately, not pooled into the sequential runs. All remain below the existing 16 ms target. No comparative speedup or statistically established slowdown is claimed from these short runs of the same binary.

LSP paired test on the current classes: synchronous reference queued input **209.704–214.836 ms**; asynchronous path **0.058–0.142 ms**, three samples each. First asynchronous command occupies EDT 4.910 ms. Queued synthetic typing through completed offscreen paint p95 **0.258–0.740 ms** across 4 KiB, 100 KiB and 1 MiB fixtures, with/without pending definition; 50 warm samples each and separate first inputs. The 4 KiB first control input was 11.524 ms. Paired synchronous reference intentionally demonstrates blocking; it is not the current production dispatch path.

Active 5,000-file watcher: typing first **4.286 ms**, warm p95 **0.600 ms**, 20 warm samples. Atomic burst (142 changes) converged in **2,576.24 ms**, baseline scan 165.37 ms. This filesystem-to-observed-state boundary is distinct from input latency; no new target or improvement is inferred. Twelve start/stop cycles retain zero watcher objects after diagnostic GC. Post-GC used heap 21,428,256 bytes is a fixture diagnostic, not whole-process RSS or proof of all-native resource equivalence. LSP used/committed heap rises with setup/JIT; owned worker retirement passes, native child count zero. No retained-heap regression is established from unmatched heap snapshots.

Chat measures source insertion plus forced completed Swing paint. LSP measures enqueued synthetic typing through completed offscreen component paint. Neither establishes physical keyboard delivery/compositor latency. No JVM heap/graphics/OS setting or benchmark definition was adjusted to obtain a passing latency result. Existing scripts record exact JVM arguments. Host: macOS 26.5.2 arm64, Temurin 21.0.13.1+2, Maven 3.9.11. Initial load averages 7.79/5.05/4.07; memory-pressure query reported 77% system-wide free. Per-run Chat load is retained under `target/requalification-20261127/load-chat-*.json`; system activity was not suppressed.

Startup, picker, full OS input-to-screen, settled/peak RSS, native memory, actual terminal/toolchains/provider quality, Unicode first-use stalls, preview EDT stalls and Windows/Linux desktop qualification **were not remeasured**. Historical whole-IDE scripts remain blocked by B0.1 native-shell restrictions. Passing these controls does not imply universal Lightspeed qualification.

## E2E coverage and corrected test assumptions

The Chat workflow checks independent docks, source-preserving reports/comparison, explicit approvals/authentication, external fixed Java client reads/edits/follow-up turns, selection context, cancellation, draft preservation and cleanup. No real model, language server, native shell or real Git backend is used in Chat.

The watcher workflow uses fixed direct Java clients and fixed system Git in a disposable repository with empty owned config/hooks, no signing/prompting. It checks two-file visible reading with exact source/caret/ranges, decoration expiry, 5,000-file burst and typing, dirty-buffer conflict/view-both/save protection/Undo/Redo, delayed human edits versus Follow, light/dark/narrow/density/focus/three splits, 24 animation cycles, font hit testing, file creation/modification/moves/deleted-tree rows, same-status Git patch refresh, observation/reload ordering, EOF append autoscroll with unchanged selection, save and zero-descendant cleanup. No real cloud/local coding model, personal repository, native-shell benchmark or unrestricted desktop automation is involved. Captures are only the fixture's own Swing root and remain in the target directory.

Preserved failures:

1. `watcher.json.gz`: obsolete assertion checked bottom host visibility after explicit agent changes. Current behavior opens the right sidebar. Positive checks now require selected visible AGENT page; quiet-path checks require both regions hidden.
2. `watcher-current-docks.json.gz`: obsolete prerequisite expected Explorer to be an editor pane. It now requires visible persistent Explorer alongside the source, with no tree document.
3. `watcher-sidebar.json.gz` and `watcher-range-diagnostic.json.gz`: global highlighter-count assertion failed. Diagnostic showed expected agent range **5610:5710**, plus independent zero-length Swing selection marker **0:0**. The assertion now identifies the agent painter, requires exactly one correct agent range and later no remaining agent decoration. Exact source, dirty, Undo, caret and viewport checks remain unchanged. No production rendering was changed or failed ranges hidden.

Only `src/benchmark/java/shed/AgentWorkflowDesktopProbe.java` changed outside evidence. Its compiler/runtime path and final workflow pass; no admission/security checks were disabled.

## Reproduce

Existing safe runner prefix:

```sh
python3 scripts/run-portable-tests.py \
 --jdk /Users/gongahkia/.local/share/shed-tools/jdk-21 \
 --maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11 \
 --repository /private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository \
 --output <new-absolute-json-path>
```

Add `--tests <all-reviewed-names-except-ProjectFileOpenPrerequisiteTest>` for broad selection; exact expanded selection is in `regression.json.gz`. Add `--tests LspDefinitionPerformanceTest --measure-lsp` for LSP. Exact scoped environments, driver argv, timeouts and fixture removal are retained in compressed raw JSON (`gzip -dc <file>`).

```sh
python3 scripts/test-agent-delegation.py --jdk <above-jdk> --jar target/shed-2.0.0.jar --output <new-absolute-directory> --chat
python3 scripts/test-agent-desktop.py --jdk <above-jdk> --jar target/shed-2.0.0.jar --output <new-absolute-directory> --qualification --visual-only --git-fixture --motion
```

Tooling command `python3 -m unittest discover -s scripts -p 'test_*.py' -v` and `scripts/test-packaged-markdown.py` ran inside the existing disposable `Fixture` environment. Wrapper commands/output are in `tooling-media.json.gz`. No native shell or real language server was launched.

## Follow-up product judgment

Prioritize reliability/qualification before another subsystem. Small useful product additions: bounded per-file saved-version history (distinct from existing recovery and Git commit history); a persistent all-language Outline using existing symbols/LSP and their documented fallback limits; keyboard shortcut discovery/editing and clearer unavailable-tool setup. These are proposals only. Existing palettes, recent files, format-on-save, test/debug/task clients and optional inline completion should be reused rather than duplicated. Native-shell confinement and Unicode/preview latency remain separate reliability work.

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No production, preferences, UI/defaults or personal files changed. Previous evidence preserved. Local atomic commit authorized; no push. `git diff --check` passes.
