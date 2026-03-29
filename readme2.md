# Shed Working Bitsy Parity Notes

This file documents the working-parity tranche that brings `shed` closer to the sibling `bitsy` editor while keeping Shed as a Swing, package-less, plain-`javac` project.

## Build

Compile with:

```bash
javac -Xlint:all -d /tmp/shed-build src/*.java
```

Run with:

```bash
java -cp /tmp/shed-build Texteditor [path]
```

## Window And Buffer Commands

- `:split` or `:sp`: horizontal split
- `:vsplit` or `:vsp`: vertical split
- `:close`: close the active window
- `Ctrl-w s`: horizontal split
- `Ctrl-w v`: vertical split
- `Ctrl-w c`: close active window
- `Ctrl-w h/j/k/l`: directional window focus
- `Ctrl-w w`: cycle window focus
- `Ctrl-w =`: equalize split ratios
- `:bn` / `:bp`: next or previous buffer in the active window
- `:ls`: list open buffers
- `:bd` / `:bd!`: delete current buffer

Each split keeps its own caret and viewport while sharing the same underlying buffer document.

## Normal Mode Additions

- Counts are accepted for direct motions such as `3w`, `2B`, `50%`, and `45gg`
- Added direct motions: `W`, `B`, `E`, `ge`, `gE`, `^`, `g_`, `g0`, `g$`, `{`, `}`, `(`, `)`, `%`, `H`, `M`, `L`, `zt`, `zz`, `zb`
- Added `Y` and `r{char}`
- Added motion-based operator forms for `d`, `c`, and `y`

## Text Objects And Surround

Supported text objects:

- `iw`, `aw`
- `iW`, `aW`
- `ip`, `ap`
- `is`, `as`
- `i"` / `a"`
- `i'` / `a'`
- ``i` `` / ``a` ``
- `i(` / `a(`, `i[` / `a[`, `i{` / `a{`, `i<` / `a<`

Supported surround commands:

- `cs{old}{new}`
- `ds{char}`
- `ys{object}{char}`

Examples:

- `diw`
- `ci"`
- `ya(`
- `dap`
- `cs"'`
- `ds)`
- `ysw]`

## Registers And Macros

Supported registers:

- unnamed register: `"`
- yank register: `0`
- current file register: `%`
- last ex command register: `:`
- last inserted text register: `.`
- system clipboard registers: `+` and `*`
- black-hole register: `_`
- named registers: `a-z` and `A-Z`

Register usage:

- prefix an operator or paste with `"{register}`
- examples: `"ayy`, `"+p`, `"_dw`
- `:registers` opens a scratch buffer with current register contents

Macro usage:

- `q{register}`: start recording
- `q`: stop recording
- `@{register}`: play a macro
- `@@`: replay the last macro

Macro playback uses normalized keystrokes and stops when the recursion guard is hit.

## Search, Shell, And Scratch Buffers

- `/pattern` and `?pattern` are literal, case-sensitive searches
- `n` / `N` repeat the last search
- `*` / `#` search the word under cursor
- `:!cmd` runs a shell command
- `:{range}!cmd` filters the selected line range through a shell command
- multi-line shell output opens in a scratch buffer
- `:q` from a returnable scratch buffer returns to the previous real buffer

## LSP Completion

`Ctrl-n` now attempts real stdio-backed LSP completion for file-backed buffers. If no LSP server is available, it falls back to buffer-word completion.

Built-in default server commands:

- `rs`: `rust-analyzer`
- `py`: `pyright-langserver --stdio`
- `js`, `jsx`, `ts`, `tsx`: `typescript-language-server --stdio`
- `go`: `gopls`
- `c`, `cpp`, `h`, `hpp`: `clangd`

Override defaults in `~/.shedrc`:

```ini
lsp.rs.command=rust-analyzer
lsp.py.command=pyright-langserver
lsp.py.args=--stdio
lsp.ts.command=typescript-language-server
lsp.ts.args=--stdio
```

## Existing Config Keys Used By The UI

```ini
color.normal=#BC0E4C
color.insert=#354F60
color.command=#FFC501
color.visual=#2E8B57
color.replace=#8B4513
font.family=Hack
font.size=16
tab.size=4
line.numbers=relativeabsolute
show.current.line=true
expand.tab=true
auto.indent=true
highlight.search=true
zen.mode.width=80
large.file.threshold.mb=100
large.file.line.threshold=50000
large.file.preview.lines=1000
```

## Known Non-Parity Exclusions

This tranche still intentionally does not implement the non-working or non-Swing `bitsy` surfaces that were left out of scope:

- terminal pane UI
- leader bindings
- ignorecase and smartcase search behavior
- hover and definition UI
- visual block mode
- read-only mode
