# TODO

Format: [todo.txt](https://github.com/todotxt/todo.txt). One task per line. Priority `(A)`–`(D)`. Tags: `+Phase` `@area` `id:NN` `est:Xh` `dep:NN` `ref:URL`. Completed: prefix `x YYYY-MM-DD`.

## How to use this file
- Read NORTHSTAR.md first. It defines scope, KPIs, architecture, principles.
- Pick the highest-priority unblocked task (no `dep:` pointing to incomplete `id:`).
- Each line is intended to be implementable in isolation. Refs and acceptance criteria are inline.
- Mark complete by replacing the leading `(P)` with `x 2026-MM-DD`.
- Add new tasks at end of section; do not renumber `id:`.
- KPIs live in NORTHSTAR.md "KPI table". Anything you do should not regress them.
- After each Phase 21+ subsection labelled `Checkpoint`, run `swift build && swift test && swift build -c release && bench/scripts/regression.sh` and commit if all pass. Never merge a phase that regresses any KPI >5%.

## Conventions baked in
- **Swift 5.9+**, macOS 13.0 deployment target. No iOS code paths.
- **No external Swift deps** except vendored C: tree-sitter + grammars + (new) libgit2. No CocoaPods, no Carthage, no SPM remote pulls in release builds.
- **One .app bundle, one binary.** No helper processes through v1.0.
- **No `@MainActor` annotation on hot paths** unless required; explicit dispatch.
- **No SwiftUI on launch path.** AppKit only for window + menus + first view.
- **Errors:** use `throws`. No optional-eating, no force-unwraps outside tests.
- **Tests:** Swift Testing (`import Testing`, `@Test`). XCTest tolerated only where Swift Testing lacks parity (e.g., performance baselines via `XCTMetric`).
- **Comments:** in-line only, lowercase, sparing. WHY only, not WHAT.
- **Style:** tabs in source. SwiftFormat config committed in `.swiftformat`.

---

## Current state (2026-07-01)

Working, verified in tree:

- 22 kLOC Swift across 11 SwiftPM targets. Editor core (rope, motions, undo), Metal text view with CoreText + glyph atlas, tree-sitter with 14 grammars statically linked, 3 keymap profiles (plain 22 / vim 128 / emacs 37 bindings), LSP session/framing + 6 bundled server configs + full apply-layer (completion, hover, refs, goto def, rename, code actions, formatting, signature help), Git UI shelling to `/usr/bin/git` (status, diff, stage, hunk/line stage, commit, branch, stash, fetch/pull/push, gutter hunks, conflicts), tasks panel, problems panel, workspace file/symbol index (regex-based), file tree, tabs, split panes, command palette (@ workspace symbols, # file symbols), project find, outline panel, hover tooltip, signature help popover, completion popup, quick look, autosave/versions, handoff, recent docs, terminal (PTY forkpty w/ zsh -il), settings.toml, keys.toml, themes (2), lsp.json, bench harness (measure/rss/latency/display/rope).

Blocked / off-target:

- Cold start `<150 ms`: committed release-candidate mean remains 272.661 ms; latest Phase25 post-lazy-link sample regressed to 2414.166 ms warm mean. See `bench/notes/coldstart-audit.md`.
- Idle memory footprint `<100 MB`: committed clean audit is 98611 KB physical footprint; no-purge local samples are noisier. See `bench/notes/rss-realism.md`.
- 1 GB file open `<500 ms`: unreachable on current `Document.read` path (slurps entire file to `String`, then builds rope from string).
- Distribution: Developer ID cert, notarization, Sparkle, Homebrew cask, final name all pending in Phase 16.

Known structural issues (targeted by Phase 21+):

- `Sources/ItsyApp/App/AppDelegate.swift` = 26 LOC, `Sources/ItsyApp/App/AppCoordinator.swift` = 438 LOC, `Document.swift` = 2318 LOC, `MetalTextView.swift` = 2752 LOC. Feature state is split across coordinators; `EditorWindowController` remains concentrated.
- `Editor.text` full flatten; `Document.data(ofType:)` full flatten on every save.
- Rope insert rebuilds the affected leaf as a full `String` then re-splits; each ancestor branch reallocates children. Repeated-ASCII fast path masks this in current benchmarks.
- `UndoStack.record` snapshots full `textBefore: String` every 32 edits.
- Grapheme cluster correctness across selections + multi-cursor is unproven.
- Highlight color refresh no longer clears shaped-line cache; remaining risk is full highlight-span filtering per visible line.
- Terminal emulator silently drops SGR params, has no mouse, no OSC titles/clipboard/hyperlinks, no 24-bit color.
- Workspace index symbol extraction is regex-based; no incremental FSEvents watch.
- Git ops shell out to `/usr/bin/git`; hunk staging round-trips via temp patches. No blame, no line-history, no file-history browser. Remote `Process` has no cancel-on-close.
- DAP is protocol + framing only. No transport, no session, no UI.
- Extension manifests contribute tasks only. No trust model, no marketplace.

---

## Phase 0 — Bootstrap


---

## Phase 1 — Bench harness + baselines


---

## Phase 2 — Empty-app cold-start spike


---

## Phase 3 — Metal text renderer

---

## Phase 4 — Rope buffer


---

## Phase 5 — Editor core


---

## Phase 6 — Tree-sitter syntax


---

## Phase 7 — File system + tabs + tree

---

## Phase 8 — Command palette

---

## Phase 9 — Find/replace


---

## Phase 10 — Keymap engine


---

## Phase 11 — Vim profile


---

## Phase 12 — Emacs profile


---

## Phase 13 — Multi-cursor + column select


---

## Phase 14 — Split panes


---

## Phase 15 — Hardening + regression bench


---

## Phase 16 — Packaging + release

(A) 2026-06-28 +Phase16-Release @release id:301 est:2h dep:300 Code signing: Developer ID Application cert. Build: `codesign --sign "Developer ID Application: <name>" --options runtime --timestamp Itsy.app`. Document in `bench/notes/codesign.md`.
(A) 2026-06-28 +Phase16-Release @release id:302 est:2h dep:301 Notarization: `xcrun notarytool submit Itsy.dmg --apple-id ... --wait` → `xcrun stapler staple Itsy.dmg`. Script in `scripts/notarize.sh`.
(B) 2026-06-28 +Phase16-Release @release id:306 est:3h dep:305 Sparkle integration: vendor Sparkle XPC service, point at `https://<host>/appcast.xml`. Defer publishing infra to v0.2 if no host yet.
(B) 2026-06-28 +Phase16-Release @release id:307 est:3h dep:304 Submit Homebrew cask: open PR against `homebrew/homebrew-cask` per their docs.
(C) 2026-06-28 +Phase16-Release @release id:308 est:2h dep:304 Pick a final name (NORTHSTAR.md "codename itsy"). Decide via short list, register a domain if available. Rebrand bundle id, repo name, README.

---

## Phase 17 — Native macOS integration


---

## Phase 18 — Workspace symbol navigation UI


---

## Phase 19 — LSP end-to-end UX


---

## Phase 20 — Git diff viewer + commit UI + branch ops (optional)


---

## Phase 21 — Codebase atomization and organization

Goal: no source file over ~600 LOC, no type over ~300 LOC, feature-per-directory layout. A new contributor should be able to locate any feature by directory name in under a minute. Every task in this phase must pass `swift test` before merge; no behavior change is permitted.

Directory target layout (create empty dirs first in id:800, then move files):

```
Sources/ItsyApp/
  App/                  AppDelegate + top-level entry (main.swift shrinks to ~150 LOC)
  Documents/            ItsyDocument, ItsyDocumentController, tab coord
  Windows/              EditorWindowController, EditorPane, EditorPaneCoordinator
  Palette/              CommandPalette panel + registry
  ProjectFind/          project-find panel
  Git/                  git panel + composer + branch popover + stash + conflict + gutter
  Tasks/                task panel
  Problems/             problems panel
  Outline/              outline panel + collapse store
  References/           references panel
  Hover/                hover tooltip
  Signature/            signature help popover
  Completion/           completion popup
  FileTree/             sidebar
  Terminal/             terminal panel/view/session/emulator
  Settings/             settings window + preferences
  Menu/                 menu builder
  Bench/                recordBenchStage helpers
```

(C) 2026-07-01 +Phase21-Refactor @refactor id:818 est:2h dep:807 Move `EditorPreferences.swift`, `HoverTooltip.swift`, `SignatureHelpPopover.swift`, `TabBarView.swift`, `FileTreeSidebar.swift`, `GitHunkGutter.swift`, `ProblemGutter.swift`, `ReferencesPanel.swift`, `CompletionPopup.swift`, `FindBarView.swift`, `Localization.swift`, `AppKeymap.swift`, `CommandPalette.swift`, `TerminalEmulator.swift`, `TerminalSession.swift`, `TerminalView.swift` into their new feature directories. Update `Package.swift` if any target needs new paths (SwiftPM auto-discovers; no change likely needed).
(C) 2026-07-01 +Phase21-Refactor @refactor id:819 est:1h dep:818 Add `Sources/ItsyApp/README.md` with a directory map. Add a top-of-file `// @file <purpose>` doc comment on each new file (single line).
(A) 2026-07-01 +Phase21-Refactor @refactor id:820 est:2h dep:819 Checkpoint: run `swift build -c release && swift test && bench/scripts/regression.sh`. Attach output to `bench/notes/refactor-phase21.md`. Cold start must not regress >5% vs current 272 ms baseline; RSS must not regress >5% vs current 90 MB.

---

## Phase 22 — Large-file text stack (piece-tree + mmap + streaming)

Goal: hit the `<500 ms` 1 GB open KPI and stop the O(N) full flatten on save. Piece table with mmap-backed original buffer is the industry standard (VSCode PieceTree uses 64 KB chunks; Zed's SumTree keeps summaries in each B+ tree node for O(log n) traversal). Ref: `https://code.visualstudio.com/blogs/2018/03/23/text-buffer-reimplementation`, `https://zed.dev/blog/zed-decoded-rope-sumtree`, `https://dev.to/_darrenburns/the-piece-table---the-unsung-hero-of-your-text-editor-al8`.

Design decision to record in `docs/design/textstack.md` before implementation (id:900):

- Original buffer: `Data` backed by `mmap(fd, len, PROT_READ, MAP_PRIVATE|MAP_NOCACHE, ..., 0)` for files > 1 MB; in-memory `Data` for smaller.
- Add buffer: `ContiguousArray<UInt8>` appended to, never truncated.
- Piece tree: red-black tree of pieces keyed by cumulative UTF-8 byte offset; each node caches per-subtree byte + line + grapheme counts (summary metric, like SumTree).
- Public surface identical to today's `Rope` (`insert`, `remove`, `slice`, `chunk`, `copyUTF8Chunk`, `line(forOffset:)`, `offset(forLine:)`, `lineRange`, `length`, `lineCount`, `graphemeCount`). Old `Rope` type kept, delegates to piece tree behind a feature flag until id:924.


---

## Phase 23 — Undo/history without periodic snapshots

Goal: remove the O(N) `textBefore` snapshot every 32 edits. Xi's model: keep only edit deltas + tombstones; snapshots exist as pointers into the persistent structure (Ref: `https://xi-editor.io/docs/rope_science_09.html`, `https://xi-editor.io/docs/crdt-details.html`).

Approach for Itsy (simpler than full CRDT — piece-tree makes this cheap):

- Every `UndoEntry` stores forward `Edit` (byte range + inserted bytes) + reverse `Edit` (byte range + removed bytes as a `Data` chunk). No full-buffer snapshot.
- Redo stack mirrors the same shape.
- `popUndo` / `popRedo` apply reverse edits directly to `PieceTree`; O(log n) each.
- Group semantics (`beginGroup`/`endGroup`) unchanged.


---

## Phase 24 — Selection + grapheme correctness

Goal: prove multi-cursor + emoji + regional-indicator + ZWJ sequences are handled correctly. Adopt the Unicode UAX #29 grapheme break test suite as ground truth. Ref: `https://www.unicode.org/Public/UCD/latest/ucd/auxiliary/GraphemeBreakTest.txt`.

(A) 2026-07-01 +Phase24-Grapheme @editor id:961 est:3h dep:960 Add `Tests/ItsyEditorTests/GraphemeBreakConformanceTests.swift`: parses each `÷ / ×` divider from `GraphemeBreakTest.txt` (~2000 cases) and asserts `UAX29GraphemeIterator` reports identical boundaries. Failing lines print the row number + the sequence as U+XXXX codepoints.
(A) 2026-07-01 +Phase24-Grapheme @editor id:962 est:3h dep:940 Rewrite `Editor.previousCharacterRange(before:)` / `nextCharacterRange(after:)` to use grapheme boundaries directly from the piece-tree grapheme summary rather than iterating a materialized `String`. Property test: no allocation of `String` inside these two functions (verify via `os_signpost`).
(A) 2026-07-01 +Phase24-Grapheme @editor id:963 est:3h dep:962 Selection invariants: add `SelectionSet.validate(_:against:PieceTree)` that asserts (a) every anchor/head lands on a grapheme boundary, (b) selections are pairwise disjoint after `merge(_:)`, (c) all offsets `<= tree.length`. Call from `Editor.setSelection` in DEBUG only.
(A) 2026-07-01 +Phase24-Grapheme @editor id:964 est:3h dep:962 Multi-cursor emoji regression suite: `Tests/ItsyEditorTests/MultiCursorGraphemeTests.swift`. Cases: family emoji 👨‍👩‍👧‍👦 (7 codepoints, 1 grapheme), Indic ka+virama+ka, national flag 🇸🇬🇯🇵, keycap 1️⃣, skin-tone modifier 👋🏽, ZWJ Woman-Firefighter. For each: place N cursors at random offsets, insert '#' at each, then delete-backward at each — expect exactly N grapheme-cluster deletions.
(B) 2026-07-01 +Phase24-Grapheme @render id:965 est:2h dep:964 Add manual QA doc `docs/qa/grapheme-checklist.md` listing keystroke sequences to run interactively (paste-family-emoji, arrow-through-flag, backspace-through-ZWJ). Each step includes the observable and the failure mode.
(A) 2026-07-01 +Phase24-Grapheme @refactor id:966 est:1h dep:965 Checkpoint: `swift test` all grapheme suites green. Any regression in previous suites (rope tests migrated to piece-tree in id:922) blocks merge.

---

## Phase 25 — Renderer perf + memory realism


---

## Phase 26 — Syntax breadth: grammars, captures, themes

Goal: parity with Zed/nvim/Helix on capture set, at least 10 built-in themes, +8 grammars.

(B) 2026-07-01 +Phase26-Syntax @syntax id:1105 est:4h dep:1104 Add 8 more bundled themes matching the capture set: `bundled:solarized-light`, `bundled:solarized-dark`, `bundled:gruvbox-light`, `bundled:gruvbox-dark`, `bundled:nord`, `bundled:catppuccin-mocha`, `bundled:catppuccin-latte`, `bundled:tokyo-night`. Each is a `.toml` in `Sources/ItsySyntax/Resources/themes/`. Attribution + license in a top-of-file comment.
(A) 2026-07-01 +Phase26-Syntax @syntax id:1106 est:2h dep:1105 Settings window: theme popup populated from bundled + user themes (glob `~/.config/itsy/themes/*.toml`). Persist selection to `settings.toml`.
(B) 2026-07-01 +Phase26-Syntax @syntax id:1107 est:2h dep:1102 Update `Sources/ItsyBench/main.swift` `smoke` cmd to open a `.swift` / `.zig` / `.bash` / `.sql` sample and assert non-empty highlight-span output. Prevents grammar-drop regressions.
(B) 2026-07-01 +Phase26-Syntax @syntax id:1108 est:2h dep:1101 Grammars in separate dylibs by default (finish the deferred lazy-load work). Extend `bench/scripts/build_grammar_dylibs.sh` to be called from `bench/scripts/make_app.sh`; drop grammar `.c` sources from `CTSGrammars` static target for release builds. Wire dlopen in `GrammarLoader.language(for:)` (already exists).
(B) 2026-07-01 +Phase26-Syntax @syntax id:1109 est:2h dep:1108 Cold-start bench post-dylib. Target: `__TEXT,__const` drops from 9.4 MB to <2 MB; cold start improves ≥25 ms. Update `bench/notes/coldstart-audit.md`.

---

## Phase 27 — Keymap parity (vim + emacs + plain)

(B) 2026-07-01 +Phase27-Keymaps @keymap id:1120 est:3h Expand `keys.vim.toml`. Add: text objects `iw aw is as ip ap i" a" i' a' i( a( i[ a[ i{ a{ it at`, jumps `Ctrl-O Ctrl-I gd gD gf gt gT`, splits `Ctrl-w s Ctrl-w v Ctrl-w h/j/k/l/w Ctrl-w q Ctrl-w o`, folding `zc zo za zC zO zA zM zR`, marks `m<letter> `<letter> '<letter>`, replace `r R`, case `~ gu gU`, indent `>> << = == gq`, search history `q/ q?`, cmdline history `q:`, ex `:w :q :wq :x :bd :bn :bp`, `%s/pattern/replacement/g` command.
(B) 2026-07-01 +Phase27-Keymaps @keymap id:1121 est:3h Expand `keys.emacs.toml`. Add: mark ring `Ctrl-Space Ctrl-x Ctrl-x`, kill/yank `Ctrl-w Ctrl-y M-w M-y`, transpose `Ctrl-t M-t`, case `M-u M-l M-c`, sexp `Ctrl-M-f Ctrl-M-b Ctrl-M-k Ctrl-M-Space`, isearch `Ctrl-s Ctrl-r`, undo `Ctrl-/ Ctrl-_`, incremental undo/redo, prefix `Ctrl-x Ctrl-f` (open file), `Ctrl-x Ctrl-s` (save), `Ctrl-x Ctrl-c` (quit), `Ctrl-x k` (close buffer), `Ctrl-x b` (switch buffer), `Ctrl-x 0/1/2/3/o` (window mgmt), `M-x` (command palette), macro `Ctrl-x ( Ctrl-x ) Ctrl-x e`, rectangle `Ctrl-x r k/y/t`, `M-g g` goto line, `M-%` query-replace.
(B) 2026-07-01 +Phase27-Keymaps @keymap id:1122 est:2h Expand `keys.plain.toml`. Add macOS-standard bindings that Cocoa users expect: `Cmd-N` new doc, `Cmd-O` open, `Cmd-Shift-N` new window, `Cmd-,` settings, `Cmd-P` command palette (alt), `Cmd-K Cmd-S` keyboard shortcuts, `Cmd-B` toggle sidebar, `Cmd-J` toggle terminal panel, `Cmd-Shift-P` command palette, `Cmd-Shift-.` toggle hidden files, `Ctrl-Tab / Ctrl-Shift-Tab` tab cycling, `Cmd-1..9` tab N.
(B) 2026-07-01 +Phase27-Keymaps @keymap id:1123 est:1h dep:1120,1121,1122 Regenerate `docs/keymap-reference.md` via `scripts/gen_keymap_docs.swift`.
(C) 2026-07-01 +Phase27-Keymaps @keymap id:1124 est:2h Add a keymap validator: `scripts/validate_keymaps.swift` runs at CI-time and asserts every command id referenced in a `.toml` exists in `CommandRegistry`. Failing commands print the file:line.

---

## Phase 28 — Vim semantics isolation

Goal: pull vim state out of `MetalTextView` into a testable module with no AppKit deps. This is the follow-up to id:814.


---

## Phase 29 — LSP UX polish


---

## Phase 30 — Debugger (full DAP)

Goal: working debugger for at least one language end-to-end (LLDB DAP → Swift/C/C++), then extend. Ref: `https://microsoft.github.io/debug-adapter-protocol/specification.html`, `https://microsoft.github.io/debug-adapter-protocol/overview`.

Architecture:

- `ItsyDAP` (existing) gets a session actor + process transport analogous to `ItsyLSP`.
- `ItsyDebugger` (new SwiftPM target) hosts orchestration + UI-adjacent state (breakpoints, stack, threads, variables, watches, console).
- `ItsyApp/Debugger/` hosts the UI (breakpoints gutter, callstack panel, variables tree, watches, debug console, launch config chooser).

(B) 2026-07-01 +Phase30-DAP @dap id:1215 est:3h dep:1204 Exception filters via `setExceptionBreakpoints`. UI in launch-config panel.
(B) 2026-07-01 +Phase30-DAP @dap id:1216 est:2h dep:1205 Reverse-debug support (LLDB): `stepBack`, `reverseContinue`. UI-gated behind `session.capabilities.supportsStepBack`.

---

## Phase 31 — Git (libgit2 + blame + history)

Goal: eliminate temp-patch round-trips for hunk staging; add blame, file history, line history; safe cancel for streaming remote ops.

Decision recorded in `docs/design/git.md`: vendor libgit2 as a C target (`Sources/CLibgit2`) — same pattern as `CTreeSitter`. Do NOT depend on SwiftGit2 (last active update Nov 2025, no XCFramework, iOS-conflict via libpcre — irrelevant here but a maintainability signal). Ref: `https://libgit2.org/`, `https://github.com/SwiftGit2/SwiftGit2`.

(A) 2026-07-01 +Phase31-Git @git id:1300 est:3h Vendor libgit2 as a git submodule under `Sources/CLibgit2/upstream` pinned to v1.9.x. Add `Sources/CLibgit2/module.modulemap` + `Package.swift` C target. Build with `-DUSE_HTTPS=SecureTransport -DUSE_SHA1=CommonCrypto` on macOS. Ensure `swift build -c release` produces a `.o` that links.
(A) 2026-07-01 +Phase31-Git @git id:1301 est:2h dep:1300 `Sources/ItsyEditor/GitRepository+Libgit2.swift`: thin Swift facade: `Repository.open(at:)`, `Repository.status(pathspec:)`, `Repository.diff(cached: Bool)`, `Repository.blob(at:)`. Wrap `git_repository`, `git_status_list`, `git_diff`, `git_blob` handles as classes with `deinit` cleanup.
(A) 2026-07-01 +Phase31-Git @git id:1302 est:3h dep:1301 Reimplement `GitRepository.status()` on libgit2. Compare output to current porcelain-v2 parser on a fixture repo — must produce identical `GitStatus`.
(A) 2026-07-01 +Phase31-Git @git id:1303 est:4h dep:1301 Reimplement hunk stage/unstage on libgit2 via `git_apply_to_tree` + `git_index_write_tree` + `git_index_add_frombuffer`. No temp files, no stdin round-trip. Test: on a 1k-hunk fixture, staging all hunks must be <500 ms.
(A) 2026-07-01 +Phase31-Git @git id:1304 est:3h dep:1301 Reimplement diff (`GitRepository.diffFiles(...)`, `diffFilesAgainstHead(...)`) on `git_diff_index_to_workdir` / `git_diff_tree_to_index`. Verify identical output to current shell-out on fixture repo.
(A) 2026-07-01 +Phase31-Git @git id:1305 est:3h dep:1304 Reimplement commit composer: `git_index_write` → `git_commit_create_v` with signoff & amend. Keep the shell fallback behind an env flag `ITSY_GIT_BACKEND=shell` for regression comparison until id:1315.
(B) 2026-07-01 +Phase31-Git @git id:1306 est:3h dep:1301 Blame. `git_blame_file` for the current file. Return `[BlameHunk = { finalStartLine, lineCount, origCommit, origCommitSummary, origAuthor, origAuthorTime, origPath }]`. Cache per (fileURL, HEAD-oid).
(A) 2026-07-01 +Phase31-Git @gitui id:1307 est:3h dep:1306 Blame UI: inline gutter annotations showing short-oid + author-initials + relative time on hover. Click opens a popover with full author/committer/summary + `Copy SHA` + `Show file@commit`.
(B) 2026-07-01 +Phase31-Git @gitui id:1308 est:3h dep:1306 File history panel. `git_revwalk` on the file path yields commits; panel lists them with author + summary + relative date. Selection opens a side-by-side diff of that commit against its parent for the file. Ref: `git log --follow -- <file>` semantics.
(B) 2026-07-01 +Phase31-Git @gitui id:1309 est:3h dep:1308 Line history (blame → prior). From a blame line, `Show previous change` follows `git_blame` on the parent commit at the origPath's corresponding line. Repeatable.
(A) 2026-07-01 +Phase31-Git @git id:1310 est:3h dep:1305 Remote-op cancellation. `git_transport` operations wrapped in a `Task` with `CheckedContinuation`; user-triggered close → `git_indexer_progress_cb`/`git_transfer_progress_cb` returns non-zero → libgit2 aborts. Ensure `Process` fallback also gets killed via `Process.terminate()` on window close.
(A) 2026-07-01 +Phase31-Git @git id:1311 est:2h dep:1310 UI: streaming remote-op panel gets a Cancel button. Wired to a `Task.cancel()`. On close, force-cancels in progress.
(A) 2026-07-01 +Phase31-Git @git id:1312 est:3h dep:1310 Fetch/pull/push move to libgit2 with credential callback: SSH agent, macOS Keychain via `SecItemCopyMatching`. Fall back to `askpass` prompt via NSAlert if no credentials cached.
(B) 2026-07-01 +Phase31-Git @git id:1313 est:2h dep:1312 Add `Sign commits with GPG/SSH` toggle in Git settings. Delegate to `gpg --detach-sign` / `ssh-keygen -Y sign` since libgit2 doesn't do this itself.
(A) 2026-07-01 +Phase31-Git @git id:1314 est:3h dep:1305 Property tests: for a scripted sequence of 50 git ops (add/modify/delete/rename/stage-hunk/commit/branch/checkout/merge/rebase/stash/apply/pop), libgit2 backend produces byte-identical repo state to the shell backend. Fixture in `Tests/ItsyEditorTests/Fixtures/git-parity/`.
(A) 2026-07-01 +Phase31-Git @git id:1315 est:1h dep:1314 Retire shell backend as default; keep behind `ITSY_GIT_BACKEND=shell` env for triage. Update NORTHSTAR "conventions" and CLAUDE.md if referenced.
(A) 2026-07-01 +Phase31-Git @refactor id:1316 est:1h dep:1315 Checkpoint. `bench/notes/git-phase31.md`.

---

## Phase 32 — Terminal completeness (SGR, mouse, OSC, allowlist)

Ref: xterm control sequences at `https://invisible-island.net/xterm/ctlseqs/ctlseqs.html`; xterm.js VT support list at `https://xtermjs.org/docs/api/vtfeatures/`.

(A) 2026-07-01 +Phase32-Term @terminal id:1360 est:4h SGR implementation. In `ItsyTerminalEmulator.applyCSI` case `"m"`: parse params (semicolon-separated ints, colon-separated sub-params in mode 38/48). Support: 0 reset, 1 bold, 2 dim, 3 italic, 4 underline, 7 reverse, 8 conceal, 9 strikethrough, 22/23/24/27/28/29 off variants, 30–37 fg indexed 0–7, 39 default fg, 40–47 bg indexed 0–7, 49 default bg, 90–97 bright fg, 100–107 bright bg, `38;5;N` and `48;5;N` for 256-color, `38;2;R;G;B` and `48;2;R;G;B` for 24-bit true color. Store attributes per cell: `TerminalCell = { char: Character, fg: TerminalColor, bg: TerminalColor, style: TerminalStyle }`. `TerminalColor = default | indexed(UInt8) | rgb(UInt8, UInt8, UInt8)`.
(A) 2026-07-01 +Phase32-Term @terminal id:1361 est:3h dep:1360 Rewrite `TerminalSnapshot` to carry attributed cells, not `[String]`. `TerminalView` renders via `NSTextView`-alt or a small CoreText path.
(A) 2026-07-01 +Phase32-Term @terminal id:1362 est:3h dep:1360 xterm 256-color palette table. Vendor the standard 6×6×6 cube + 24 grey ramp resolution to sRGB. Themable via `[terminal.palette]` in `settings.toml` (16 named colors).
(A) 2026-07-01 +Phase32-Term @terminal id:1363 est:3h OSC sequence handling. Currently OSC is entered but not parsed. Parse `OSC Ps ; Pt ST` where `Ps` = param, `Pt` = text, `ST` = `BEL` or `ESC \`. Support: `OSC 0/1/2` set title (propagate to `NSWindow.title` when this is the focused terminal), `OSC 4` set palette color, `OSC 7` set current working directory (used to seed new terminals), `OSC 8` hyperlink (store per-cell URL for hover-to-open), `OSC 10/11` fg/bg default, `OSC 52` clipboard read/write (base64), `OSC 133` semantic prompt marks (start/end/output — enables prompt navigation later).
(A) 2026-07-01 +Phase32-Term @terminal id:1364 est:3h dep:1363 Clipboard integration (OSC 52). Write path: base64-decode `Pt` payload, write to `NSPasteboard.general`. Read path (rare, most terminals disable): base64-encode current clipboard, respond with `OSC 52 ; c ; <base64> ST`. Guard behind `settings.toml` `[terminal] osc52 = "write" | "readwrite" | "off"` default `write`. Reason: OSC 52 read is a security-sensitive channel.
(A) 2026-07-01 +Phase32-Term @terminal id:1365 est:3h Mouse tracking. Handle CSI `?1000h` (X10 button), `?1002h` (button-event), `?1003h` (any-event), `?1006h` (SGR extended). Encode outbound mouse events. Preferred encoding: SGR 1006 `CSI < B ; X ; Y M` (press) / `m` (release). Support 1016 (pixel-based) if `?1016h` was requested. Ref: `https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h4-Mouse-Tracking`.
(B) 2026-07-01 +Phase32-Term @terminal id:1366 est:2h dep:1365 `TerminalView` translates `NSEvent.mouseDown/mouseUp/mouseMoved/scrollWheel` to the currently-enabled mouse mode and writes to PTY.
(A) 2026-07-01 +Phase32-Term @terminal id:1367 est:2h Environment allowlist. In `ItsyTerminalSession.start` do NOT forward `ProcessInfo.processInfo.environment` verbatim. Curated allowlist: `HOME`, `USER`, `LOGNAME`, `SHELL`, `PATH`, `TMPDIR`, `LANG`, `LC_ALL`, `LC_CTYPE`, `TERM_PROGRAM_VERSION`, `SSH_AUTH_SOCK`. Add Itsy-injected: `TERM=xterm-256color`, `COLORTERM=truecolor`, `TERM_PROGRAM=Itsy`, `INSIDE_ITSY_TERMINAL=1`. Extra keys can be opted in via `settings.toml` `[terminal.env] allow = ["FOO", "BAR"]`.
(A) 2026-07-01 +Phase32-Term @terminal id:1368 est:2h dep:1360 Bracketed paste (already parsed via mode 2004) wired to `NSPasteboard`: paste wraps content in `ESC [ 200 ~ ... ESC [ 201 ~` when bracketed paste is enabled by the child.
(B) 2026-07-01 +Phase32-Term @terminal id:1369 est:3h Smoke test terminal QA. `bench/scripts/terminal_smoke.sh` runs a series of TUIs against Itsy's PTY: `htop -n 1`, `btop --preset 0 --terminal 80x24`, `vim -c ':q'`, `less README.md`. Screenshots stored under `bench/results/terminal-smoke/`. Manual verification checklist in `docs/qa/terminal-checklist.md`.
(A) 2026-07-01 +Phase32-Term @refactor id:1370 est:1h dep:1369 Checkpoint. `bench/notes/terminal-phase32.md`.

---

## Phase 33 — Workspace symbol extractor + LSP-backed symbols

(A) 2026-07-01 +Phase33-Nav @nav id:1400 est:4h Replace regex-based symbol extraction with tree-sitter query-based extraction. Add per-language `tags.scm` queries under `Sources/ItsySyntax/Resources/queries/<lang>/tags.scm`. Adopt the tree-sitter tags convention: captures `@definition.function`, `@definition.class`, `@definition.method`, `@definition.type`, `@definition.constant`, `@definition.enum`, `@definition.interface`, `@definition.variable`, `@definition.constructor`, `@name`. Ref: `https://tree-sitter.github.io/tree-sitter/4-code-navigation.html`.
(A) 2026-07-01 +Phase33-Nav @nav id:1401 est:3h dep:1400 Extend `WorkspaceIndexer` to run tags query for each indexed file. Fall back to regex only for languages with no `tags.scm`. Every language added in id:1102 gets a `tags.scm`.
(A) 2026-07-01 +Phase33-Nav @nav id:1402 est:2h dep:1401 Enrich `WorkspaceSymbol`: add `signature: String?`, `containerName: String?`, `documentation: String?`. Populated from surrounding node text when possible.
(B) 2026-07-01 +Phase33-Nav @nav id:1403 est:3h Persistence. Serialize the workspace index to `~/.config/itsy/index/<workspace-hash>.json` on quit; reload on open. Invalidate stale entries via `Content-Modified` timestamps.
(A) 2026-07-01 +Phase33-Nav @nav id:1404 est:2h dep:1403 Load-on-open uses persisted index immediately; kicks off a background re-index via FSEvents (Phase 34).
(A) 2026-07-01 +Phase33-Nav @lsp id:1405 est:3h dep:1164 LSP-backed workspace symbols hierarchy: (1) LSP `workspace/symbol` results, (2) tree-sitter tags-scm results, (3) persisted-index results. Merge, dedup by `(uri, range, kind)`, sort by fuzzy-match score then kind priority (function > class > variable).
(A) 2026-07-01 +Phase33-Nav @lsp id:1406 est:3h dep:1165 LSP-backed document symbols mirror. Prefer `textDocument/documentSymbol` when the session for the current document is running.
(A) 2026-07-01 +Phase33-Nav @refactor id:1407 est:1h dep:1406 Checkpoint. `bench/notes/nav-phase33.md`.

---

## Phase 34 — FSEvents incremental workspace watcher

Ref: `https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html`.

(A) 2026-07-01 +Phase34-FS @nav id:1440 est:4h Add `Sources/ItsyEditor/Workspace/FSEventStream.swift`. Wraps `FSEventStreamCreate` with `kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagNoDefer | kFSEventStreamCreateFlagUseCFTypes | kFSEventStreamCreateFlagUseExtendedData`. Scheduled on a dedicated dispatch queue (not main). Callback delivers `[FSEvent(url: URL, flags: FSEventFlags, eventID: UInt64)]`.
(A) 2026-07-01 +Phase34-FS @nav id:1441 est:2h dep:1440 Persist last-seen `eventID` per workspace to `~/.config/itsy/index/<hash>.fsevents`. On start, resume from that ID so events during a previous close aren't lost.
(A) 2026-07-01 +Phase34-FS @nav id:1442 est:3h dep:1441 Coalesce & debounce. A single `FSEventCoalescer` buffers events for 100 ms then emits a deduplicated set. Adjacent events on the same path collapse.
(A) 2026-07-01 +Phase34-FS @nav id:1443 est:3h dep:1442 Wire into `WorkspaceIndexer`: on create/remove/rename → touch/drop index rows; on modify → re-run tags query for that file; on directory-created → walk one level, respecting gitignore.
(A) 2026-07-01 +Phase34-FS @nav id:1444 est:2h dep:1443 Bench: 10k-file monorepo cold-open with persisted index → first symbol query response <100 ms. Measure via `ItsyBench index --workspace <path>`.
(B) 2026-07-01 +Phase34-FS @nav id:1445 est:2h Gitignore-aware re-index. Events under paths matching `.gitignore` are dropped early.
(A) 2026-07-01 +Phase34-FS @refactor id:1446 est:1h dep:1444 Checkpoint. `bench/notes/fsevents-phase34.md`.

---

## Phase 35 — Extension system + Vouch trust

Adopt Mitchell Hashimoto's Vouch for the trust layer (Ref: `https://github.com/mitchellh/vouch`, `https://simonwillison.net/2026/Feb/7/vouch/`). Format: `VOUCHED.td` (Trustdown) — one handle per line, `platform:username`, `-` prefix for denounce, `#` comments. Vouch decides "can this author's extension load"; Itsy decides how strictly to apply that gate.

Architecture:

- Ship the current `.itsy/extensions/*.json` manifest work-as-is (tasks contribution).
- Add contribution kinds: `commands`, `menus`, `snippets`, `themes`, `languages`, `problem-matchers`, `keybindings`. NO in-process executable host through v1.0 — that stays in NORTHSTAR OUT list. Executable behavior can only reach the editor via LSP/DAP/PTY process-boundary channels.
- Trust store: per-installation `~/.config/itsy/trust/VOUCHED.td` (user's personal vouch list) + optional shared `<project>/VOUCHED.td`. `vouch check` CLI shell-out at install time.
- Marketplace: a static index published to `https://itsy.dev/extensions/index.json` (post-rename) listing `{ id, name, author, repo, sha256, minItsyVersion }`. Itsy fetches via `URLSession`, verifies sha256, prompts trust decision.

(A) 2026-07-01 +Phase35-Ext @ext id:1500 est:3h Design doc `docs/design/extensions.md`: contribution kinds allowed in v1, trust model (vouch), install/uninstall flow, storage layout, ABI stability guarantee, non-goals (executable plugin host, WASI runtime).
(A) 2026-07-01 +Phase35-Ext @ext id:1501 est:3h Extend `ExtensionManifest` schemaVersion to 2. New fields: `contributes.commands: [{ id, title, when? }]`, `contributes.keybindings: [{ command, key, when? }]`, `contributes.snippets: [{ language, scope?, path }]`, `contributes.themes: [{ id, name, path, uiTheme: "dark"|"light" }]`, `contributes.languages: [{ id, extensions, grammar?, tagsScm?, highlightsScm? }]`, `contributes.problemMatchers: [{ id, regex, file, line, column, severity, message }]`. Backwards-compatible: v1 manifests still load; new fields ignored.
(A) 2026-07-01 +Phase35-Ext @ext id:1502 est:3h dep:1501 Wire command contributions into `CommandRegistry`. Extension-contributed commands prefixed with `ext:<extid>:`. Palette shows them.
(A) 2026-07-01 +Phase35-Ext @ext id:1503 est:3h dep:1502 Wire keybinding contributions into keymap loader. Precedence: user keys > project keys > extension keys > bundled keys.
(A) 2026-07-01 +Phase35-Ext @ext id:1504 est:3h dep:1501 Wire theme contributions into `SyntaxTheme.loadUserOrDefault`. Extension themes appear in settings picker with an `ext:` prefix.
(B) 2026-07-01 +Phase35-Ext @ext id:1505 est:3h dep:1501 Wire snippet contributions into completion popup. Snippet activation via prefix match + `Tab` trigger. Existing snippet placeholder parser (`LSPCompletionApply.parsePlaceholder`) is reused.
(A) 2026-07-01 +Phase35-Ext @ext id:1506 est:3h dep:1501 Wire grammar contributions. When an extension declares a language with a `grammar` field pointing at a `.dylib` in its bundle, `GrammarLoader.language(for:)` looks in extension dirs after the app's Frameworks path. Contribution requires the extension to ship the compiled dylib for the current arch — otherwise disabled with a clear error.
(B) 2026-07-01 +Phase35-Ext @ext id:1507 est:3h dep:1501 Wire problem-matcher contributions. `WorkspaceProblemParser` accepts registered matchers with named-capture regex.
(A) 2026-07-01 +Phase35-Ext @ext id:1508 est:3h Trust module. `Sources/ItsyEditor/Trust/VouchStore.swift`: parses `VOUCHED.td`. Public API: `func check(handle: VouchHandle) -> VouchState = .vouched | .denounced(reason: String?) | .unknown`. `VouchHandle` = `platform: String, username: String` (default platform: "github").
(A) 2026-07-01 +Phase35-Ext @ext id:1509 est:2h dep:1508 Bundled default `VOUCHED.td` lives at `Sources/ItsyEditor/Resources/VOUCHED.default.td` and includes only the Itsy maintainers. User store at `~/.config/itsy/trust/VOUCHED.td`; project store at `<workspace>/VOUCHED.td` if present.
(A) 2026-07-01 +Phase35-Ext @ext id:1510 est:3h dep:1509 Trust policy: on install, resolve extension's `author` field (must include `platform:handle`) → `VouchStore.check`. If `.vouched`, proceed silently. If `.unknown`, present a modal describing what the extension contributes + "Trust once", "Trust always (add to VOUCHED.td)", "Cancel". If `.denounced`, refuse with the reason string.
(A) 2026-07-01 +Phase35-Ext @ext id:1511 est:2h dep:1510 CLI passthrough. If `vouch` binary is on PATH, defer to it via `Process` for authoritative checks (supports the web-of-trust sync). Else use local store.
(A) 2026-07-01 +Phase35-Ext @ext id:1512 est:3h Marketplace client. `Sources/ItsyEditor/Marketplace/MarketplaceClient.swift`: fetches `https://itsy.dev/extensions/index.json` (URL configurable via env `ITSY_MARKETPLACE_URL`). Cache under `~/.config/itsy/marketplace/index.json` with an `ETag`. Model: `MarketplaceEntry = { id, name, author, description, repo, versions: [{ version, sha256, minItsyVersion, downloadURL }] }`.
(A) 2026-07-01 +Phase35-Ext @ext id:1513 est:3h dep:1512 Install flow. `install(_ id: String, version: String?)` downloads `downloadURL` (zip), verifies sha256, extracts to `~/.config/itsy/extensions/<id>/`, runs manifest validation, resolves trust via id:1510, on approval registers contributions.
(A) 2026-07-01 +Phase35-Ext @extui id:1514 est:4h dep:1513 Extensions panel. New coordinator `ExtensionsCoordinator`. Panel tabs: `Installed`, `Marketplace`. Marketplace tab is a search field + list; Install button per row. Installed tab: enable/disable/uninstall per row.
(A) 2026-07-01 +Phase35-Ext @extui id:1515 est:2h dep:1514 Command-palette entries: `Extensions: Install…`, `Extensions: Show Installed`, `Extensions: Reload`, `Extensions: Open VOUCHED.td`.
(B) 2026-07-01 +Phase35-Ext @ext id:1516 est:3h Marketplace publish flow (out of app). `scripts/publish_extension.sh` takes a manifest + built dylibs, produces a signed zip, uploads via GitHub Release + updates the `index.json`. Documented in `docs/publishing-extensions.md`. Non-blocking for v1; enables ecosystem after v1 ships.
(A) 2026-07-01 +Phase35-Ext @ext id:1517 est:3h Property tests. `Tests/ItsyEditorTests/VouchStoreTests.swift`: parse round-trip of `VOUCHED.td` handling of comments, denouncements, empty lines, whitespace. Verify examples from the mitchellh/vouch README match.
(A) 2026-07-01 +Phase35-Ext @ext id:1518 est:2h Extension manifest schema-v2 tests. Round-trip encode/decode, back-compat with schema-v1, validation errors for empty fields, unresolvable grammar paths, dup command ids.
(A) 2026-07-01 +Phase35-Ext @refactor id:1519 est:1h dep:1517,1518 Checkpoint. `bench/notes/extensions-phase35.md`. Close `bench/notes/extension-gap.md`.

---

## Cross-cutting


---

## References (consolidated)

- Apple — [Reducing your app's launch time](https://developer.apple.com/documentation/xcode/reducing-your-app-s-launch-time)
- Apple — [Core Text Programming Guide](https://developer.apple.com/library/archive/documentation/StringsTextFonts/Conceptual/CoreText_Programming/Overview/Overview.html)
- Apple — [Using the File System Events API](https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html)
- Metal by Example — [Rendering 3D Text with Core Text](https://metalbyexample.com/text-3d/)
- Metal by Example — [Rendering Text with SDF](https://metalbyexample.com/rendering-text-in-metal-with-signed-distance-fields/)
- Xi editor — [Rope science part 1](https://github.com/xi-editor/xi-editor/blob/master/docs/docs/rope_science_01.md), [part 3 grapheme clusters](https://xi-editor.io/docs/rope_science_03.html), [part 9 CRDT approach to async plugins and undo](https://xi-editor.io/docs/rope_science_09.html), [CRDT details](https://xi-editor.io/docs/crdt-details.html), [retrospective](https://raphlinus.github.io/xi/2020/06/27/xi-retrospective.html)
- Zed — [Rope & SumTree](https://zed.dev/blog/zed-decoded-rope-sumtree), [syntax-aware editing](https://zed.dev/blog/syntax-aware-editing)
- VSCode — [Text Buffer Reimplementation](https://code.visualstudio.com/blogs/2018/03/23/text-buffer-reimplementation)
- Text data structures — [Gap Buffers vs Ropes](https://coredumped.dev/2023/08/09/text-showdown-gap-buffers-vs-ropes/), [Piece Table — Darren Burns](https://dev.to/_darrenburns/the-piece-table---the-unsung-hero-of-your-text-editor-al8)
- Tree-sitter — [official](https://github.com/tree-sitter/tree-sitter), [syntax highlighting](https://tree-sitter.github.io/tree-sitter/3-syntax-highlighting.html), [code navigation](https://tree-sitter.github.io/tree-sitter/4-code-navigation.html)
- Tree-sitter capture standardization — [nvim treesitter groups](https://neovim.io/doc/user/treesitter.html#treesitter-highlight-groups), [Helix theme scopes](https://docs.helix-editor.com/themes.html#scopes), [Zed discussion #23371](https://github.com/zed-industries/zed/discussions/23371)
- Unicode — [UAX #29 Text Segmentation](https://www.unicode.org/reports/tr29/), [GraphemeBreakTest.txt](https://www.unicode.org/Public/UCD/latest/ucd/auxiliary/GraphemeBreakTest.txt)
- LSP — [3.17 specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/)
- DAP — [specification](https://microsoft.github.io/debug-adapter-protocol/specification.html), [overview](https://microsoft.github.io/debug-adapter-protocol/overview)
- libgit2 — [homepage](https://libgit2.org/), [repo](https://github.com/libgit2/libgit2)
- xterm — [Control Sequences (`ctlseqs.html`)](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html), [xterm.js VT support](https://xtermjs.org/docs/api/vtfeatures/)
- Vouch — [mitchellh/vouch](https://github.com/mitchellh/vouch), [announcement](https://itsfoss.com/news/mitchell-hashimoto-vouch/), [Simon Willison notes](https://simonwillison.net/2026/Feb/7/vouch/)
- VimR — [Neovim GUI in Swift](https://github.com/qvacua/vimr) (reference for AppKit+Metal+rope-ish architecture, not for embedding nvim)
- CodeEdit — [source](https://github.com/CodeEditApp/CodeEdit)
- CotEditor — [source](https://github.com/coteditor/CotEditor)
- Hyperfine — [github](https://github.com/sharkdp/hyperfine)
- FZF algo — [src/algo/algo.go](https://github.com/junegunn/fzf/blob/master/src/algo/algo.go)
- Emerge Tools — [Swift Reference Types and Startup](https://www.emergetools.com/blog/posts/SwiftReferenceTypes)
- todo.txt format — [spec](https://github.com/todotxt/todo.txt)
