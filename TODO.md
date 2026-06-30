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


---

## Phase 3 — Metal text renderer

(B) 2026-06-28 +Phase3-Renderer @metal id:049 est:2h dep:046 ProMotion 120Hz support: ensure `CAMetalLayer.maximumDrawableCount = 3`, `wantsExtendedDynamicRangeContent = false`, `CVDisplayLink` runs at refresh rate. Verify on 120Hz display.

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

## Cross-cutting


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
