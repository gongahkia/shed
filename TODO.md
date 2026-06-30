# TODO

Format: [todo.txt](https://github.com/todotxt/todo.txt). One task per line. Priority `(A)`–`(D)`. Tags: `+Phase` `@area` `id:NN` `est:Xh` `dep:NN` `ref:URL`. Completed: prefix `x YYYY-MM-DD`.

## How to use this file
- Read NORTHSTAR.md first. It defines scope, KPIs, architecture, principles.
- Pick the highest-priority unblocked task (no `dep:` pointing to incomplete `id:`).
- Each line is intended to be implementable in isolation. Refs and acceptance criteria are inline.
- Mark complete by replacing the leading `(P)` with `x 2026-MM-DD`.
- Add new tasks at end of section; do not renumber `id:`.
- KPIs live in NORTHSTAR.md "KPI table". Anything you do should not regress them.

## Conventions baked in
- **Swift 5.9+**, macOS 13.0 deployment target. No iOS code paths.
- **No external Swift deps** except vendored C: tree-sitter + grammars. No CocoaPods, no Carthage, no SPM remote pulls in release builds.
- **One .app bundle, one binary.** No helper processes through v1.0.
- **No `@MainActor` annotation on hot paths** unless required; explicit dispatch.
- **No SwiftUI on launch path.** AppKit only for window + menus + first view.
- **Errors:** use `throws`. No optional-eating, no force-unwraps outside tests.
- **Tests:** Swift Testing (`import Testing`, `@Test`). XCTest tolerated only where Swift Testing lacks parity (e.g., performance baselines via `XCTMetric`).
- **Comments:** in-line only, lowercase, sparing. WHY only, not WHAT.
- **Style:** tabs in source. SwiftFormat config committed in `.swiftformat`.

---

## Phase 0 — Bootstrap


---

## Phase 1 — Bench harness + baselines


---

## Phase 2 — Empty-app cold-start spike

x 2026-06-28 +Phase2-ColdStart @bench id:031 est:1h dep:020,030 Run `itsybench measure --app Itsy.app --warmup-purge` 20×, target <100 ms. Record in `bench/results/spike-empty-$(date +%F).md`. If >150 ms, investigate.
x 2026-06-28 +Phase2-ColdStart @perf id:032 est:3h dep:031 If id:031 exceeds 150 ms: set `DYLD_PRINT_STATISTICS_DETAILS=1`, capture launch trace via `xcrun xctrace record --template 'App Launch' --launch Itsy.app`. Identify worst offenders. Document findings in `bench/notes/coldstart-audit.md`. Remediation candidates: link `-dead_strip`, set `OTHER_SWIFT_FLAGS=-Osize`, kill any `@_cdecl` init, remove unused `import Foundation` chains.
x 2026-06-28 +Phase2-ColdStart @perf id:033 est:2h dep:032 Add `bench/scripts/dyld_audit.sh`: runs binary with `DYLD_PRINT_STATISTICS=1`+`DYLD_PRINT_STATISTICS_DETAILS=1`, parses output, asserts rebase fixup count <2000. Wire into CI as a warning (not failure) gate.

---

## Phase 3 — Metal text renderer

(B) 2026-06-28 +Phase3-Renderer @metal id:049 est:2h dep:046 ProMotion 120Hz support: ensure `CAMetalLayer.maximumDrawableCount = 3`, `wantsExtendedDynamicRangeContent = false`, `CVDisplayLink` runs at refresh rate. Verify on 120Hz display.
x 2026-06-28 +Phase3-Renderer @metal id:050 est:3h dep:046 Subpixel-aa for non-Retina displays: detect via `backingScaleFactor < 2.0`, switch atlas to RGB (3-channel) and use Apple's recommended subpixel positioning.

---

## Phase 4 — Rope buffer

x 2026-06-28 +Phase4-Buffer @buffer id:063 est:3h dep:060 Benchmarks via `XCTMetric` (or signposts in a CLI): insert 1M chars sequentially, insert 1M chars at random positions, slice 1M ranges. Target: random insert <100 ns/op amortized. Document `bench/notes/rope-bench.md`.
x 2026-06-28 +Phase4-Buffer @buffer id:064 est:4h dep:063 Bring rope random insert below the <100 ns/op target. Current 1M release run: 331,601.466 ns/op. Replace per-edit `String` leaf copy/path allocation with a faster edit representation, then rerun `bench/notes/rope-bench.md`.

---

## Phase 5 — Editor core


---

## Phase 6 — Tree-sitter syntax

x 2026-06-28 +Phase6-Syntax @treesitter id:102 est:4h dep:100,101 `Sources/ItsySyntax/Parser.swift`: Swift wrapper for `TSParser`, `TSTree`, `TSNode`. API: `Parser(language: Language)`, `parse(_ rope: Rope, oldTree: Tree?) -> Tree`, uses `TSInput` callback streaming from rope chunks (avoid full-string materialization). Test: parse `large.ts` (100k lines) in <300 ms initial, incremental edit reparse <5 ms.
x 2026-06-28 +Phase6-Syntax @treesitter id:103 est:3h dep:102 Highlight queries: load `.scm` files from each grammar's `queries/highlights.scm`. Walk tree via `TSQuery` + `TSQueryCursor`. Output `[(range: Range<Int>, capture: String)]`. Test: TS keywords/strings/comments highlighted correctly.
x 2026-06-28 +Phase6-Syntax @treesitter id:104 est:3h dep:103 Theme: `~/.config/itsy/theme.toml` maps capture names → color (hex). Bundle default `default-dark.toml` (Solarized-ish) and `default-light.toml`. Apply to glyph rendering via per-glyph color in `ShapedGlyph`.
x 2026-06-28 +Phase6-Syntax @treesitter id:105 est:3h dep:104 Lazy grammar loading: do NOT init parsers at launch. On `open(file:)`, detect language via file extension, then `dlopen` is N/A (we statically linked); but defer `TSParser` allocation + grammar bind until first parse. Verify cold-start unaffected with all grammars linked.
x 2026-06-28 +Phase6-Syntax @treesitter id:106 est:3h dep:082,103 Incremental reparse on edit: editor commands emit `TSInputEdit` → `Parser.edit(tree, edit)` → `Parser.parse(rope, oldTree)`. Highlight diff applied to dirty line range. Acceptance: typing in middle of 100k-line file maintains <16 ms frame time.

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

x 2026-06-28 +Phase11-Vim @vim id:206 est:2h dep:201,082 Wire `u` to undo, `Ctrl-R` to redo. Mind vim's edit-grouping semantics: an insert-mode session = one undo unit.
x 2026-06-29 +Phase11-Vim @vim id:207 est:3h dep:200 Search: `/`, `?`, `n`, `N`. Reuse find infra (id:161).
x 2026-06-29 +Phase11-Vim @vim id:208 est:2h dep:200 Marks: `'` jump-back; defer named marks (a-z) to v0.2.
x 2026-06-29 +Phase11-Vim @vim id:209 est:5h dep:201 Macros: `q<reg>` record, `@<reg>` replay. Recursion-safe.

---

## Phase 12 — Emacs profile

x 2026-06-28 +Phase12-Emacs @emacs id:220 est:4h dep:183 Standard motions: C-f, C-b, C-n, C-p, C-a, C-e, M-f, M-b, M-<, M->. Defined in `keys.emacs.toml`.
x 2026-06-28 +Phase12-Emacs @emacs id:221 est:3h dep:220 Kill ring: `KillRing` class, ring of 60 entries. M-w copy, C-w cut, C-y paste, M-y rotate. Sync C-w/M-w to system clipboard.
x 2026-06-28 +Phase12-Emacs @emacs id:222 est:3h dep:220 Prefix keys: C-x map (C-x C-s save, C-x C-f open, C-x b switch buffer, C-x k kill buffer, C-x 0/1/2/3 window ops mapped to split-pane equivalents).
x 2026-06-28 +Phase12-Emacs @emacs id:223 est:3h dep:220 Incremental search: C-s forward, C-r backward. Reuse find infra.
x 2026-06-28 +Phase12-Emacs @emacs id:224 est:2h dep:222 Universal arg: C-u <n>. Pass to next command as count.

---

## Phase 13 — Multi-cursor + column select

x 2026-06-28 +Phase13-MultiCursor @editor id:240 est:3h dep:081 Cmd-D: add next match of current word/selection as additional cursor. Reuses find next.
x 2026-06-28 +Phase13-MultiCursor @editor id:241 est:2h dep:081 Cmd-Click on text: add cursor at clicked offset. Cmd-Click on existing cursor: remove it.
x 2026-06-28 +Phase13-MultiCursor @editor id:242 est:3h dep:081 Column select via Opt-drag: builds a vertical block of cursors, one per affected line at the same visual column.
x 2026-06-28 +Phase13-MultiCursor @editor id:243 est:2h dep:240 Cmd-Ctrl-G: select all matches (already in id:162; verify integrated here).

---

## Phase 14 — Split panes

x 2026-06-28 +Phase14-Splits @appkit id:260 est:4h dep:121 Replace single editor view with nestable `NSSplitViewController`. Each leaf = `EditorViewController` wrapping a `MetalTextView`. Same buffer can be shown in multiple panes (shared rope, independent viewport/selection).
x 2026-06-28 +Phase14-Splits @appkit id:261 est:2h dep:260 Bindings: Cmd-\ horizontal split, Cmd-Opt-\ vertical, Cmd-W close pane (falls back to close tab if last pane), Cmd-Opt-Arrow focus pane in direction.
x 2026-06-28 +Phase14-Splits @appkit id:262 est:2h dep:260 Save/restore pane layout per window via NSCoder. Honored on relaunch.

---

## Phase 15 — Hardening + regression bench

x 2026-06-29 +Phase15-Hardening @ci id:280 est:3h dep:021 Add `bench/scripts/regression.sh`: runs full bench against current `itsy` build, compares to `bench/results/baseline-itsy-current.json`, fails if any KPI worse by >5%. Wire into CI on PRs.
x 2026-06-29 +Phase15-Hardening @perf id:281 est:4h dep:047 Memory leak audit: `xcrun leaks --atExit -- ./itsy --bench-exit-on-ready`. Fix all. Document in `bench/notes/leak-audit.md`.
x 2026-06-29 +Phase15-Hardening @perf id:282 est:3h dep:281 Instruments Allocations trace: open `large.ts`, scroll, edit; identify alloc hotspots. Target zero per-frame allocs in render path.
x 2026-06-29 +Phase15-Hardening @perf id:283 est:2h dep:032 Final dyld audit: re-run `dyld_audit.sh` after all phases, expect <2000 rebases. If grown, identify cause (Swift reference types are #1 suspect — see [Emerge tools post](https://www.emergetools.com/blog/posts/SwiftReferenceTypes)).
x 2026-06-29 +Phase15-Hardening @bench id:284 est:2h dep:280 Re-run full baseline bench. Commit `bench/results/release-candidate.md`. Verify itsy beats Zed on cold-start KPI on M-series. If not, do not ship.
x 2026-06-29 +Phase15-Hardening @qa id:285 est:4h dep:047 Soak test: open repo as workspace, open 50 files across tabs, edit each, keep running for 1 h. No crashes, RSS growth <10%.

---

## Phase 16 — Packaging + release

x 2026-06-29 +Phase16-Release @release id:300 est:3h dep:030 Final `Info.plist`: bundle id `dev.itsy.editor` (or final-name), version 0.1.0, `LSApplicationCategoryType=public.app-category.developer-tools`, `NSAppleEventsUsageDescription` (none, but document), high-resolution capable.
(A) 2026-06-28 +Phase16-Release @release id:301 est:2h dep:300 Code signing: Developer ID Application cert. Build: `codesign --sign "Developer ID Application: <name>" --options runtime --timestamp Itsy.app`. Document in `bench/notes/codesign.md`.
(A) 2026-06-28 +Phase16-Release @release id:302 est:2h dep:301 Notarization: `xcrun notarytool submit Itsy.dmg --apple-id ... --wait` → `xcrun stapler staple Itsy.dmg`. Script in `scripts/notarize.sh`.
x 2026-06-29 +Phase16-Release @release id:303 est:2h dep:301 Build DMG via `create-dmg` (brew). Background image optional. Script in `scripts/make_dmg.sh`.
x 2026-06-29 +Phase16-Release @release id:304 est:3h dep:284 Update `README.md`: bench table with itsy vs Zed/Sublime/VSCode (use latest `bench/results/`), screenshots, install via DMG, install via `brew install --cask <name>`. Link NORTHSTAR.md.
x 2026-06-29 +Phase16-Release @release id:305 est:3h dep:303 GitHub Release workflow: `.github/workflows/release.yml` triggered on tag `v*.*.*`. Builds release, signs, notarizes, staples, uploads DMG + SHA256.
(B) 2026-06-28 +Phase16-Release @release id:306 est:3h dep:305 Sparkle integration: vendor Sparkle XPC service, point at `https://<host>/appcast.xml`. Defer publishing infra to v0.2 if no host yet.
(B) 2026-06-28 +Phase16-Release @release id:307 est:3h dep:304 Submit Homebrew cask: open PR against `homebrew/homebrew-cask` per their docs.
(C) 2026-06-28 +Phase16-Release @release id:308 est:2h dep:304 Pick a final name (NORTHSTAR.md "codename itsy"). Decide via short list, register a domain if available. Rebrand bundle id, repo name, README.

---

## Phase 17 — Native macOS integration

x 2026-06-29 +Phase17-Native @appkit id:500 est:2h Services menu registration: `NSRegisterServicesProvider`, text-selection service, file service, `NSServices` app bundle metadata.
x 2026-06-29 +Phase17-Native @quicklook id:501 est:2h Quick Look preview from file tree with Space via `QLPreviewPanel`.
x 2026-06-29 +Phase17-Native @documents id:502 est:1h Versions/AutoSave: `autosavesInPlace` and `preservesVersions`.
x 2026-06-29 +Phase17-Native @handoff id:503 est:2h Handoff `NSUserActivity` per open file with cursor offset restore.

---

## Phase 18 — Workspace symbol navigation UI


---

## Phase 19 — LSP end-to-end UX

x 2026-06-30 +Phase19-LSP @perf id:636 est:2h dep:622 LSP cold-start budget verification: instrument `bench/scripts/regression.sh` to assert that opening a Swift file does not regress Itsy cold-start KPI (<150 ms) by spawning sourcekit-lsp during launch. Spawn must be strictly lazy on first matching `didOpen`. Add a separate KPI: "Time from didOpen to first publishDiagnostics" with target <5 s for sourcekit-lsp on `large.ts` equivalent.
(C) +Phase19-LSP @qa id:637 est:3h dep:622,623,624,626,629 LSP smoke test: open `bench/corpus/large.ts` (or a known Swift package), verify each: (a) server starts and reports `initialized`, (b) `didOpen` sent, (c) `publishDiagnostics` arrives within 5 s, (d) completion at known position returns >0 items, (e) `textDocument/definition` at known position jumps correctly. Add to CI as `swift test --filter ItsyLSPSmokeTests`. Mark suite skipped if `typescript-language-server` / `sourcekit-lsp` not on PATH.

---

## Phase 20 — Git diff viewer + commit UI + branch ops (optional)

(B) +Phase20-GitUI @git id:670 est:2h Unified-diff parser (`Sources/ItsyEditor/UnifiedDiff.swift`): parse `git diff --no-color` output into `[DiffFile { oldPath, newPath, hunks: [DiffHunk { oldStart, oldCount, newStart, newCount, lines: [DiffLine.context | .add | .remove] }] }]`. Handle file-mode/rename headers (`diff --git`, `rename from`, `new file mode`). Reference Swift unified-diff libraries (e.g., `guillermomuntaner/GitDiff`) — implement in-house ~250 LOC to honor NORTHSTAR principle "no external Swift deps". ref:https://git-scm.com/docs/diff-format
(B) +Phase20-GitUI @ui id:671 est:4h dep:670 Diff viewer pane: shown when a file is selected in Git Changes panel. Two modes (toggle in pane header): unified (single column, `+`/`-` prefix, syntax-highlighted via existing tree-sitter on language inferred from extension) and side-by-side (`NSSplitView` 50/50, aligned hunks with context, blank-line padding to align). Use Metal text view for parity with main editor on large diffs (10k+ line diffs must scroll at 60 fps).
(B) +Phase20-GitUI @ui id:672 est:3h dep:671 Hunk-level stage/unstage: per-hunk "Stage Hunk" / "Unstage Hunk" button in pane gutter. Implementation: synthesize a minimal patch containing only the selected hunk (with the surrounding `diff --git` and `index` headers from the parent file), pipe to `git apply --cached -` (stage) or `git apply --cached --reverse -` (unstage). Validate first with `--check`. Refresh `GitRepository` status after. ref:https://git-scm.com/book/en/v2/Git-Tools-Interactive-Staging
(C) +Phase20-GitUI @ui id:673 est:5h dep:672 Line-level stage/unstage: select a contiguous run of `+`/`-` lines within a hunk; synthesize a sub-hunk by trimming surrounding context (keeping ≥1 line context above/below if available) and recomputing `@@` header line counts. Apply via same `git apply --cached` path with `--check` first.
(A) +Phase20-GitUI @ui id:674 est:3h Commit composer: panel above Git Changes panel with multi-line message field (separate "summary" and "body" with 50-char and 72-char ruler hints). Toggles: `--signoff`, `--amend`. Cmd-Enter commits. Show staged file count and disable button if staged=0 or summary empty. Shell out to `/usr/bin/git commit` with `-m <summary> -m <body>` passed as separate `Process` args (no shell interpolation).
(B) +Phase20-GitUI @git id:675 est:2h dep:674 Recent commit messages: Up-arrow on empty summary field cycles through last 10 commits' messages via `git log -10 --format=%B%x00` (NUL-separated). Down-arrow returns to draft. Drafts persist per workspace in `~/.config/itsy/commit-drafts.json`.
(B) +Phase20-GitUI @git id:676 est:3h Branch panel: list local + tracking + remote branches via `git for-each-ref --format='%(refname:short)\t%(upstream:short)\t%(HEAD)\t%(committerdate:relative)' refs/heads refs/remotes` (tab-separated, NUL-delimited rows safer). Show current branch in toolbar; click → branch list popover. Actions per branch row: switch, create-from, delete (`-d`; `-D` with confirm if `-d` fails).
(B) +Phase20-GitUI @git id:677 est:2h dep:676 Branch create/checkout: `git switch -c <name>` (create) or `git switch <name>` (existing). Refresh status after. If working tree dirty: block, but offer "Stash and switch" → `git stash push -u -m "itsy-autostash-<branch>"` then switch then `git stash pop` if user opted in.
(B) +Phase20-GitUI @git id:678 est:3h Remote ops: Fetch (background `git fetch --all --prune`, progress in status bar via stderr line-streaming), Pull (`git pull --ff-only` default; rebase variant via menu), Push (`git push`; first push of a new branch uses `--set-upstream origin <branch>`). Stream stderr to status bar; surface non-zero exits in a transient banner with full log accessible.
(C) +Phase20-GitUI @ui id:679 est:5h Conflict viewer: when status shows any `UU`/`AA`/`DU`/`UD` file, double-clicking opens a three-pane view (ours `git show :2:<path>`, base `git show :1:<path>`, theirs `git show :3:<path>`) with a fourth pane for the merged result (the working-tree file). Parse `<<<<<<<` / `=======` / `>>>>>>>` markers into conflict regions; per-region buttons: "Accept Ours", "Accept Theirs", "Accept Both", "Edit Manually". On Save: write merged content + `git add <path>`. Status updates to staged.
(C) +Phase20-GitUI @ui id:680 est:3h dep:670 Gutter hunk indicators in editor: in left gutter, mark lines added (green vertical bar), modified (yellow), deleted (red ▾ caret between lines). Source: `git diff <path>` (vs index) merged with `git diff HEAD <path>` (vs HEAD) — show vs-index by default, toggle to vs-HEAD via View menu. Recompute on document save and on Git status refresh; debounce 250 ms.
(C) +Phase20-GitUI @ui id:681 est:2h Stash management: `git stash list --format='%gd|%ai|%s'` populates a Stash panel. Per-entry actions: Apply (`git stash apply <ref>`), Pop (`git stash pop <ref>`), Drop (`git stash drop <ref>`, with confirm). Cmd-Shift-S → "Stash current changes…" prompts for message and runs `git stash push -u -m <msg>`.

---

## Cross-cutting

x 2026-06-29 +XCut @docs id:400 est:2h Architecture diagram (NORTHSTAR.md has ASCII; add SVG via excalidraw export, commit to `docs/arch.svg`).
x 2026-06-29 +XCut @docs id:401 est:1h `docs/keymap-reference.md` auto-generated from the three TOML profiles (write a tiny Swift script `scripts/gen_keymap_docs.swift`).
x 2026-06-29 +XCut @i18n id:402 est:2h Localization: pin `en` only for v0.1. Use `String(localized:)` for all user-visible strings so future locales are mechanical.
x 2026-06-29 +XCut @a11y id:403 est:3h VoiceOver: implement `accessibilityLabel`, `accessibilityRole`, `accessibilityValue` on `MetalTextView` per `NSAccessibilityElement` protocol. At minimum: read current line.
x 2026-06-29 +XCut @themes id:404 est:2h Theme picker UI in Settings. Themes live in `~/.config/itsy/themes/*.toml`.
(B) +XCut @docs id:411 est:30m dep:410 Update `README.md` feature matrix and benchmark table to reflect new scope (LSP, DAP-WIP, Git UI, Extensions tasks-only). Regenerate `docs/keymap-reference.md` once Phase 18 + Phase 19 new bindings land (id:602, id:629, etc.) via existing `scripts/gen_keymap_docs.swift`.
(C) +XCut @docs id:412 est:30m Update `bench/notes/lsp-gap.md`, `bench/notes/dap-gap.md`, `bench/notes/extension-gap.md`, `bench/notes/git-gap.md`, `bench/notes/workspace-index-gap.md`, `bench/notes/problems-gap.md` "Next slice" sections to reference the new Phase 18 / Phase 19 / Phase 20 task ids so the gap docs stay in sync with TODO.md.

---

## References (consolidated)

- Apple — [Reducing your app's launch time](https://developer.apple.com/documentation/xcode/reducing-your-app-s-launch-time)
- Apple — [Core Text Programming Guide](https://developer.apple.com/library/archive/documentation/StringsTextFonts/Conceptual/CoreText_Programming/Overview/Overview.html)
- Metal by Example — [Rendering 3D Text with Core Text](https://metalbyexample.com/text-3d/)
- Metal by Example — [Rendering Text with SDF](https://metalbyexample.com/rendering-text-in-metal-with-signed-distance-fields/)
- Xi editor — [Rope science part 1](https://github.com/xi-editor/xi-editor/blob/master/docs/docs/rope_science_01.md), [retrospective](https://raphlinus.github.io/xi/2020/06/27/xi-retrospective.html)
- Zed — [Rope & SumTree](https://zed.dev/blog/zed-decoded-rope-sumtree)
- VSCode — [Text Buffer Reimplementation](https://code.visualstudio.com/blogs/2018/03/23/text-buffer-reimplementation)
- Text data structures — [Gap Buffers vs Ropes](https://coredumped.dev/2023/08/09/text-showdown-gap-buffers-vs-ropes/)
- Tree-sitter — [official](https://github.com/tree-sitter/tree-sitter), [SwiftTreeSitter (reference wrapper)](https://github.com/viktorstrate/swift-tree-sitter)
- VimR — [Neovim GUI in Swift](https://github.com/qvacua/vimr) (reference for AppKit+Metal+rope-ish architecture, not for embedding nvim)
- CodeEdit — [source](https://github.com/CodeEditApp/CodeEdit) (reference for native AppKit code-editor patterns)
- CotEditor — [source](https://github.com/coteditor/CotEditor) (reference for plain-text editor structure)
- Hyperfine — [github](https://github.com/sharkdp/hyperfine)
- FZF algo — [src/algo/algo.go](https://github.com/junegunn/fzf/blob/master/src/algo/algo.go)
- Emerge Tools — [Swift Reference Types and Startup](https://www.emergetools.com/blog/posts/SwiftReferenceTypes)
- todo.txt format — [spec](https://github.com/todotxt/todo.txt)
