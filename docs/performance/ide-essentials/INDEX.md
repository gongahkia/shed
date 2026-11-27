# IDE essentials qualification

Entry: clean `f6162f09bf63f8823fe82d33336dd537d9209e79`. Delivered: bounded local saved-file history with checked undoable restoration, persistent local-symbol Outline, and shortcut recording/collision review in the existing keymap inspector. [Usage, limits and settings](../../IDE_ESSENTIALS.md).

## Verdict and exact results

**PASS for the implemented scope and safely exercised workflows.** No blanket native/cross-platform release or latency claim follows.

| Check | Result | Raw evidence |
| --- | --- | --- |
| First focused selection, seven relevant classes | 30 passes, no failures/errors/skips | `focused1.json.gz` |
| Earlier broad safe run | 596 passes, seven existing skips | `regression.json.gz` |
| Final focused selection including LSP opt-in measurement | 31 passes, no failures/errors/skips | `release-focused.json.gz` |
| Final broad safe selection with `--measure-lsp` | **603 total; 597 passes, six existing skips; no failures/errors** | `maven.json.gz` |
| Python tooling | **116 passes**, zero failures | `tooling-media.json.gz` |
| Packaged Mermaid/math/local-image smoke | PASS | `media.json.gz` |
| Final owned-window app + fixed external Java Chat client | PASS; history, Outline, shortcuts and existing Chat/docks | `chat.json.gz`, PNGs |
| Final owned-window watcher + disposable fixed-system-Git workflow | PASS; reads, edits, moves/deletes, conflicts, Undo, EOF scrolling, splits/themes/fonts and cleanup | `watcher.json.gz` |

Selections overlap and are not additive. Final broad run enables the existing LSP measurement test, accounting for one fewer opt-in skip than the earlier broad run. Retired `ProjectFileOpenPrerequisiteTest` remains excluded. No native-shell fixture, real coding model, real language server or unrestricted native Maven/GUI suite was executed. The reviewed runner checks test/helper hashes, isolates native/JVM home/preferences/temp, copies the bounded offline dependency cache and runs Surefire in-process. Three new admitted classes are reviewed in-process tests; existing assertions/admission protections remain.

Local history tests cover exact UTF-16 persistence/reopen/deduplication/order, per-file/global entry retention, size exclusion, disabled capture without filesystem creation, hidden/sensitive paths, symlink escape, invalid identities, corrupted payload integrity, failed saves, synchronous/asynchronous saves, preserved disk line endings, history failure without failed source save, stale revision rejection and exact one-step Undo/Redo. Outline container tests cover independent Explorer/Outline ownership and focus hiding/restoration. Shortcut tests cover Mod/physical aliases, scopes, modifier-only/text/AltGraph rejection and function keys. Existing source, keymap, LSP, Markdown, appearance and agent tests remain part of the broad safe run.

## Live verification

The final Chat demo follows actual application controllers. It opens a Java file, enables Outline, waits for the existing symbol cache, reveals a method, rejects navigation from stale symbols, and saves through the real save command. It observes both loaded baseline and new history snapshots. The History dialog selects the old version and its real confirmation is approved inside the fixture. Source changes exactly, document revision advances, Undo restores the pre-restore draft, and disk bytes remain unchanged. A second History dialog opens the actual central comparison without source mutation. The shortcut dialog records a synthetic Mod+Shift+F11, saves `action:save` through existing validation/persistence, and checks the resulting configuration. The fixture explicitly saves its dirty buffer before agent delegation. Existing approvals/authentication, read/edit/follow-up, selection context, source input shielding, Stop/draft preservation, New Chat, independent docks and detached Search continue to pass.

`live-initial-failure.json.gz` is preserved: all new feature checks passed, but the test then timed out awaiting agent approval because it left the restored/undone buffer dirty. Production delegation correctly requires saved buffers. The fixture now saves before delegation; no production admission rule or assertion was weakened. All other compile, focused, broad, package and subsequent live runs passed. Earlier passing refinements remain labeled as precursors rather than substituted for final artifact evidence.

The final watcher workflow additionally checks 5,000 files and a 142-change burst, visible external CLI reading, exact reading ranges/expiry, human edits during delayed loads, same-file conflict handling and protected saves/Undo, light/dark/narrow/density/focus/three shared splits, animation expiry, safe config/file moves/deleted tree rows, same-status Git patch refresh and appended EOF visibility/selection preservation. Twelve stopped watcher instances retain zero objects after bounded diagnostic GC; final descendants zero. Chat task IO workers zero; synthetic external sentinel SHA-256 and modification time unchanged. All disposable fixtures are removed.

## Performance

Final source insertion to forced completed Swing paint after Chat: **40 samples, first 0.745 ms, p95 0.732 ms**. Previous same-host dock qualification reference was p95 0.689 ms on the older artifact. These are short, unmatched historical controls; no statistically established slowdown or speedup is claimed. Both meet the existing 16 ms target at this boundary.

Active 5,000-file watcher: first **7.257 ms**, 20 warm samples, p95 **0.905 ms**. Final in-process queued typing through completed offscreen source paint p95 **0.220–0.680 ms**, 50 warm samples per 4 KiB/100 KiB/1 MiB fixture with/without pending definition, with separate first samples preserved. The paired synchronous LSP reference still delays queued input **204.159–207.219 ms**; the production asynchronous request helper records **0.045–0.114 ms**, three samples each. Owned LSP workers retire after 24 cancellation/disposal cycles.

No heap, graphics, OS or timing definition was altered to improve results. Outline adds no parser/worker pool/document listener; it consumes the existing revision-validated local cache. Hidden Outline clears its own source reference and avoids list population. History adds bounded save-time IO through the existing normal save workers; synchronous save callers retain their existing synchronous boundary. There is no new startup scan or typing-time history capture. No new permanent services or source-sized glyph caches are introduced. Resource evidence establishes the covered disposal behavior, not whole-process native-memory equivalence. History capture/storage cost was exercised by saves and retention tests but not separately profiled as a general save-latency distribution.

Mac: macOS 26.5.2 arm64; Temurin 21.0.13.1+2; Maven 3.9.11. Load/memory-pressure snapshot and exact JVM arguments/fixture identities are retained. Final JAR SHA-256: `85a83ad649934a968023256f3dcecd516879816f364459af55621099b08c0087`; all **1,611** compiled main classes match packaged bytes (`artifact.json.gz`). Timing is synthetic event/component/forced-paint work, not physical keyboard/compositor latency. Startup, full RSS/native attribution, native-shell confinement, terminal execution, real-model performance, Windows/Linux GUI and OS-reserved/non-US key delivery remain unqualified. Existing Unicode first-use/glyph and preview EDT limitations remain open.

## Reproduction

```sh
python3 scripts/run-portable-tests.py \
 --jdk /Users/gongahkia/.local/share/shed-tools/jdk-21 \
 --maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11 \
 --repository /private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository \
 --tests <reviewed-manifest-names-except-ProjectFileOpenPrerequisiteTest> \
 --measure-lsp --output <new-absolute-json-path>
```

Exact expanded test selection and Maven/JVM argv are in `maven.json.gz`. Packaging uses the same runner with `--package-only`. Tooling is `python3 -m unittest discover -s scripts -p 'test_*.py' -v`, launched in the existing disposable `Fixture` environment; the packaged media driver runs there afterward. Exact commands and output are preserved in `tooling-media.json.gz`.

```sh
python3 scripts/test-agent-delegation.py --jdk <above-jdk> --jar target/shed-2.0.0.jar --output <new-absolute-directory> --chat
python3 scripts/test-agent-desktop.py --jdk <above-jdk> --jar target/shed-2.0.0.jar --output <new-absolute-directory> --qualification --visual-only --git-fixture --motion
```

`make demo-chat` uses the first workflow with presentation pauses, so it now demonstrates the new features too. The native-shell NO-GO is not bypassed. Direct Java and fixed local Git use existing separately reviewed disposable paths. No credentials, personal projects, real toolchains, SSH or network VCS operations are exercised.

## Product/maintenance limits

Saved source snapshots are bounded local history, not an exhaustive filesystem audit, byte-format backup, content-secret detector or simultaneous multi-process retention transaction. Explicit restore preserves current encoding/newline policy, shared document/Undo ownership and normal save reconciliation. Local symbol coverage remains exactly the existing collector's coverage, rather than semantic LSP coverage for every language. Shortcut overrides retain editor-only/OS/native-keymap limits. Existing Mercurial/Subversion `:scm` routes were inspected and documented, not reimplemented or executed; Git-sidebar parity is outside this pass.

## Repository compliance

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Existing evidence/user work preserved. No default font/theme, terminal, protocol, document ownership or unrelated production changes. No new dependency/platform port, no reset/clean/revert/discard, no pushes. `git diff --check` passes.

Atomic production commits: `91fecae6f` (history storage/save hook), `cac3eb7b8` (shortcut editor), `4d0d84613` (Outline), `dfe46c5e8` (history UI/integration). Tests/admission, enhanced existing demo and this guide/evidence are saved separately.
