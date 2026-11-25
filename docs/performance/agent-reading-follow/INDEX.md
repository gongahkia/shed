# Reading presentation and fresh-window following evidence

Starting HEAD: `968b099add5909b0d9e542b6dfb4f866f98dbcb6`. The only pre-existing worktree item was untracked `Created.txt`; it was not edited, staged or committed. Implementation: `9d7171cc9` (idle Follow correction) and `00b8cab4f` (checked first-file opening and reading presentation). See [the implementation and usage contract](../../AGENT_READING_AND_FOLLOW.md).

## Scope and reproduction

An authenticated, read-only query of the user's reported attachment showed `watching=true`, `ready=true`, `following=false`, one changed file and no watch error. `observed-user-watch-state.json` preserves those fields without credentials or private instance identifiers. This contradicted treating the later correct-project screenshot as only a root mismatch.

The new desktop prelude starts `Texteditor` with a project directory, not an already-open source file. It clicks the idle blank source pane before any external edit. The starting JAR fails that assertion in `before-follow-fix.json`. With the correction, a subsequent external create opens the file in the actual source pane, with management controls hidden. Manual input during an active tour still pauses it. The matrix retains the explicit dirty-buffer and late-reload checks.

Reading uses a separate, bounded direct Java CLI process with a disposable home/project and the actual authenticated transport. It starts with Explorer active and an untitled content pane, reads one range near line 221, then opens a second file. Assertions cover exact source, source identity/revision/clean/Undo state, human caret preservation, precise highlight offsets, visibility of both ends of the short chunk, hidden agent dock and real expiry. Root-pane images show the amber reading pulse/underline/label; the existing animated edit tour remains green. No shell, real language server, desktop input automation or live model is used.

## Reproducible commands

Use the existing JDK 21.0.13.1+2, Maven 3.9.11 and offline dependency cache recorded in the raw command arrays. No environment installation was performed. The test class list is every entry in `src/test/resources/shed/portable-fixture-tests.properties` except `ProjectFileOpenPrerequisiteTest`; hashes admit only reviewed non-native cases.

```sh
python3 scripts/run-portable-tests.py --jdk <existing-JDK> --maven <existing-Maven> \
  --repository <offline-cache> --tests <reviewed-class-list> --output <new.json>
python3 scripts/run-portable-tests.py --jdk <existing-JDK> --maven <existing-Maven> \
  --repository <offline-cache> --package-only --output <new.json>
python3 scripts/test-agent-desktop.py --jdk <existing-JDK> --jar target/shed-2.0.0.jar \
  --output <new-directory> --git-fixture --visual-only --qualification --motion
python3 scripts/test-agent-desktop.py --jdk <existing-JDK> --jar target/shed-2.0.0.jar \
  --output <new-directory> --launch-only
python3 scripts/test-agent-desktop.py --jdk <existing-JDK> --jar target/shed-2.0.0.jar \
  --output <new-directory> --git-fixture --visual-only \
  --replay-payload docs/performance/agent-editor-control/codex-generated-demo.java.txt
```

Python tooling used `python3 -m unittest discover -s scripts -p 'test_*.py' -v` under a validated disposable `native_fixture.Fixture`, with user site/bytecode disabled. Packaged-media smoke used `scripts/test-packaged-markdown.py --jdk <existing-JDK> --jar target/shed-2.0.0.jar --output <new.json>` under the same boundary. No unsafe native-shell admission was overridden. Heavy native desktop measurements were run separately from Maven/media workloads.

## Final verification

| Evidence | Result |
| --- | --- |
| `safe-suite.json` | 437 tests run: **430 passes**, seven existing skips, zero failures/errors. |
| `focused.json` | 29 focused passes, zero failures/errors/skips; the final suite additionally covers the final opening/geometry changes. |
| `tooling.json` | **95 passes**. |
| `desktop-first-pass.json`, `desktop-repeat.json` | Two PASS runs with 15 integrated events each; first and repeated fresh-window flows. |
| `launcher.json` | Actual `visualize` child, authenticated watch and first-file reading PASS; oversized text baseline still fails promptly. |
| `review-regression.json` | PASS, ten events including opening, reading, proposal review, explicit Apply, Undo/Redo, save and cleanup. Uses a preserved model-generated payload, not a new model call. |
| `packaged-media.json` | PASS, 14 packaged Mermaid/math/image events. |
| `package.json`, `identities.json` | Final production JAR, relevant source/test-tool identities and host. |

The safe skips remain six opt-in measurements and unavailable DOS hidden attributes. The unrestricted full Maven/native-dependent suite was not run. The proof is in-process correctness plus actual macOS Swing windows, real authenticated transport, distinct direct Java clients and the real launcher; it is not Windows/Linux GUI or arbitrary-agent integration qualification.

## Measurements

The two whole-workbench runs measured 21 synthetic input-to-completed-component-paint actions each while watching 5,000 files. First samples were 3.67 ms and 2.92 ms; warm p95 was 1.33 ms and 1.36 ms, below the existing 16 ms target. These are not physical input/scanout measurements or a matched historical speedup claim.

The first direct reading clients took 238.80 ms and 170.94 ms; second clients took 216.39 ms and 137.68 ms. Those include a fresh client JVM, attachment authentication, checked opening and bounded reading; they do not measure EDT occupancy or warm in-process read latency. The actual launcher reached authenticated watch readiness in 2,114 ms; its first-file read took 39.68 ms in the already-running parent probe/client environment. Its known oversized baseline error took 3,109 ms. These are fresh-process, not cold-cache, measurements.

Each run inspected 65 standard scroll panes and verified three source splits plus independently owned Explorer. Each completed 24 presentation cycles and 12 watch start/stop cycles with zero stopped watchers retained after bounded diagnostic GC. Used heap went from 123,767,960 to 18,549,136 bytes in one run and 118,554,552 to 18,847,160 in the other. This is not a whole-process RSS/native-memory equivalence or reading-specific heap-retention proof. Reading reuses the existing per-pane presentation/highlighter/timer; it adds no per-read listener, persistent worker or disabled-state polling loop. Both final desktop runs ended with zero owned descendants.

EOF-append tours reached Y=4,851 and Y=4,871 and retained the final additions after completion. The disk-reload selection collapse `2..6` → `6..6` is explicitly recorded and remains unchanged; the tour and reading decoration preserve their installed human caret/selection state.

## Intermediate failures retained

- `initial-unit-failures.json`: incomplete implementation had an indistinguishable fallback color and lacked client validation for the new read-reply operation. Both were corrected before acceptance.
- `initial-no-editor-failure.json`: a tree/untitled window could not obtain the old file-only navigation context. This required the checked project-targeted first-file opening shape.
- `intermediate-context-failure.json`, `context-diagnostic.json`, `focus-context-failure.json`: native tests exposed geometry context changes after opening and an asynchronous focus request invalidating installation. The client now takes one fresh context after open with stable file/pane/revision/caret checks; preparation no longer requests keyboard focus. No retry loop or weaker source-revision check was added.
- `fixture-dirty-state-failure.json`: the synthetic human-edit fixture inserted text without the production editor's dirty listener. It now explicitly supplies that dirty transition; source/history assertions remain intact.
- `split-fixture-assumption-failure.json`: after moving the fixture from file startup to project startup, the old assertion wrongly required Explorer to own the source document. It now asserts Explorer retains its own buffer/surface and all three source splits retain the exact shared source buffer.

The source-view glyph, Unicode first-edit, stock HTML-preview, cross-platform and native-memory qualifications remain open. Direct filesystem reads remain invisible unless the agent explicitly reports them; no universal read watcher or vendor log parser is claimed.

## Repository compliance

No UI redesign, new setting, dependency, font, theme, source-document model, source-edit automation or persistent worker was introduced. Existing preferences and previous evidence were retained. README SHA-256 before/after is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. `git diff --check` passes. Only local atomic commits were created; no push. The original untracked `Created.txt` remains outside these commits.
