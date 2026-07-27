I've learned a lot by looking at other people's code, so
maybe someone can learn something from looking at mine.

= = = = = = = = =

Itsy is a native code editor for macOS.

It aims for instant launch, low memory use, and responsive
editing. It has a Metal text view, split panes, file tree,
terminal, Git changes, tree-sitter syntax highlighting, LSP,
debug support, and Plain/Vim/Emacs keymaps.

For more info, see: https://gabrielongzm.com/itsy/

= = = = = = = = =

Build a local app with scripts/bootstrap.sh, then run:

  swift build -c release
  bench/scripts/make_app.sh
  open Itsy.app

Itsy is pre-release. See docs/release.md for signing and
notarization status.

MIT Licensed - see LICENSE file
