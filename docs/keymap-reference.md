# Keymap Reference

Generated from `Sources/PicoKeymap/Resources/keys.*.toml`.

Regenerate:

```sh
scripts/gen_keymap_docs.swift
```

## Plain

- Source: `Sources/PicoKeymap/Resources/keys.plain.toml`
- Bindings: `18`

### mode.insert

| Key | Command |
|---|---|
| `Left` | `editor.moveLeft` |
| `Right` | `editor.moveRight` |
| `Cmd-Left` | `editor.moveLineStart` |
| `Cmd-Right` | `editor.moveLineEnd` |
| `Cmd-S` | `file.save` |
| `Cmd-W` | `pane.close` |
| `Cmd-\` | `pane.splitHorizontal` |
| `Cmd-Opt-\` | `pane.splitVertical` |
| `Cmd-Opt-Left` | `pane.focusLeft` |
| `Cmd-Opt-Right` | `pane.focusRight` |
| `Cmd-Opt-Up` | `pane.focusUp` |
| `Cmd-Opt-Down` | `pane.focusDown` |
| `Cmd-F` | `edit.find` |
| `Cmd-G` | `edit.findNext` |
| `Cmd-Shift-G` | `edit.findPrevious` |
| `Cmd-Ctrl-G` | `edit.selectAllFindMatches` |
| `Cmd-D` | `editor.addNextSelection` |
| `Cmd-Shift-F` | `edit.findInProject` |

## Vim

- Source: `Sources/PicoKeymap/Resources/keys.vim.toml`
- Bindings: `112`

### mode.normal

| Key | Command |
|---|---|
| `h` | `editor.moveLeft` |
| `j` | `editor.moveDown` |
| `k` | `editor.moveUp` |
| `l` | `editor.moveRight` |
| `w` | `editor.moveWordForward` |
| `Shift-W` | `editor.moveBigWordForward` |
| `b` | `editor.moveWordBackward` |
| `Shift-B` | `editor.moveBigWordBackward` |
| `e` | `editor.moveWordEnd` |
| `Shift-E` | `editor.moveBigWordEnd` |
| `0` | `editor.moveLineStart` |
| `Shift-6` | `editor.moveLineStart` |
| `Shift-4` | `editor.moveLineEnd` |
| `gg` | `editor.moveBufferStart` |
| `Shift-G` | `editor.moveBufferEnd` |
| `Shift-[` | `editor.moveParagraphBackward` |
| `Shift-]` | `editor.moveParagraphForward` |
| `f` | `editor.findCharForward` |
| `Shift-F` | `editor.findCharBackward` |
| `t` | `editor.tillCharForward` |
| `Shift-T` | `editor.tillCharBackward` |
| `;` | `editor.repeatCharFind` |
| `,` | `editor.repeatCharFindReverse` |
| `d` | `vim.operator.delete` |
| `c` | `vim.operator.change` |
| `y` | `vim.operator.yank` |
| `Shift-'` | `vim.registerPrefix` |
| `p` | `vim.pasteAfter` |
| `Shift-P` | `vim.pasteBefore` |
| `v` | `vim.visual.char` |
| `Shift-V` | `vim.visual.line` |
| `Ctrl-V` | `vim.visual.block` |
| `Shift-;` | `vim.ex.start` |
| `u` | `edit.undo` |
| `Ctrl-R` | `edit.redo` |
| `Cmd-D` | `editor.addNextSelection` |
| `Cmd-Ctrl-G` | `edit.selectAllFindMatches` |
| `Cmd-W` | `pane.close` |
| `Cmd-\` | `pane.splitHorizontal` |
| `Cmd-Opt-\` | `pane.splitVertical` |
| `Cmd-Opt-Left` | `pane.focusLeft` |
| `Cmd-Opt-Right` | `pane.focusRight` |
| `Cmd-Opt-Up` | `pane.focusUp` |
| `Cmd-Opt-Down` | `pane.focusDown` |
| `i` | `mode.insert` |
| `/` | `vim.searchForward` |
| `Shift-/` | `vim.searchBackward` |
| `n` | `edit.findNext` |
| `Shift-N` | `edit.findPrevious` |

### mode.visual

| Key | Command |
|---|---|
| `Cmd-D` | `editor.addNextSelection` |
| `Cmd-Ctrl-G` | `edit.selectAllFindMatches` |
| `Cmd-W` | `pane.close` |
| `Cmd-\` | `pane.splitHorizontal` |
| `Cmd-Opt-\` | `pane.splitVertical` |
| `Cmd-Opt-Left` | `pane.focusLeft` |
| `Cmd-Opt-Right` | `pane.focusRight` |
| `Cmd-Opt-Up` | `pane.focusUp` |
| `Cmd-Opt-Down` | `pane.focusDown` |
| `Esc` | `mode.normal` |
| `h` | `editor.moveLeft` |
| `j` | `editor.moveDown` |
| `k` | `editor.moveUp` |
| `l` | `editor.moveRight` |
| `w` | `editor.moveWordForward` |
| `b` | `editor.moveWordBackward` |
| `e` | `editor.moveWordEnd` |
| `0` | `editor.moveLineStart` |
| `Shift-4` | `editor.moveLineEnd` |
| `d` | `vim.operator.delete` |
| `c` | `vim.operator.change` |
| `y` | `vim.operator.yank` |

### mode.operatorPending

| Key | Command |
|---|---|
| `h` | `editor.moveLeft` |
| `j` | `editor.moveDown` |
| `k` | `editor.moveUp` |
| `l` | `editor.moveRight` |
| `w` | `editor.moveWordForward` |
| `Shift-W` | `editor.moveBigWordForward` |
| `b` | `editor.moveWordBackward` |
| `Shift-B` | `editor.moveBigWordBackward` |
| `e` | `editor.moveWordEnd` |
| `Shift-E` | `editor.moveBigWordEnd` |
| `0` | `editor.moveLineStart` |
| `Shift-6` | `editor.moveLineStart` |
| `Shift-4` | `editor.moveLineEnd` |
| `d` | `vim.operator.line.delete` |
| `c` | `vim.operator.line.change` |
| `y` | `vim.operator.line.yank` |
| `iw` | `vim.textObject.innerWord` |
| `aw` | `vim.textObject.aroundWord` |
| `i Shift-'` | `vim.textObject.innerDoubleQuote` |
| `a Shift-'` | `vim.textObject.aroundDoubleQuote` |
| `i '` | `vim.textObject.innerSingleQuote` |
| `a '` | `vim.textObject.aroundSingleQuote` |
| `i Shift-9` | `vim.textObject.innerParen` |
| `a Shift-9` | `vim.textObject.aroundParen` |
| `i [` | `vim.textObject.innerBracket` |
| `a [` | `vim.textObject.aroundBracket` |
| `i Shift-[` | `vim.textObject.innerBrace` |
| `a Shift-[` | `vim.textObject.aroundBrace` |
| `ip` | `vim.textObject.innerParagraph` |
| `ap` | `vim.textObject.aroundParagraph` |

### mode.insert

| Key | Command |
|---|---|
| `Cmd-D` | `editor.addNextSelection` |
| `Cmd-Ctrl-G` | `edit.selectAllFindMatches` |
| `Cmd-W` | `pane.close` |
| `Cmd-\` | `pane.splitHorizontal` |
| `Cmd-Opt-\` | `pane.splitVertical` |
| `Cmd-Opt-Left` | `pane.focusLeft` |
| `Cmd-Opt-Right` | `pane.focusRight` |
| `Cmd-Opt-Up` | `pane.focusUp` |
| `Cmd-Opt-Down` | `pane.focusDown` |
| `Esc` | `mode.normal` |
| `jk` | `mode.normal` |

## Emacs

- Source: `Sources/PicoKeymap/Resources/keys.emacs.toml`
- Bindings: `33`

### mode.emacs

| Key | Command |
|---|---|
| `C-f` | `editor.moveRight` |
| `C-b` | `editor.moveLeft` |
| `C-n` | `editor.moveDown` |
| `C-p` | `editor.moveUp` |
| `C-a` | `editor.moveLineStart` |
| `C-e` | `editor.moveLineEnd` |
| `M-f` | `editor.moveWordForward` |
| `M-b` | `editor.moveWordBackward` |
| `M-<` | `editor.moveBufferStart` |
| `M->` | `editor.moveBufferEnd` |
| `C-w` | `emacs.killRegion` |
| `M-w` | `emacs.copyRegion` |
| `C-y` | `emacs.yank` |
| `M-y` | `emacs.yankPop` |
| `Cmd-D` | `editor.addNextSelection` |
| `Cmd-Ctrl-G` | `edit.selectAllFindMatches` |
| `Cmd-W` | `pane.close` |
| `Cmd-\` | `pane.splitHorizontal` |
| `Cmd-Opt-\` | `pane.splitVertical` |
| `Cmd-Opt-Left` | `pane.focusLeft` |
| `Cmd-Opt-Right` | `pane.focusRight` |
| `Cmd-Opt-Up` | `pane.focusUp` |
| `Cmd-Opt-Down` | `pane.focusDown` |
| `C-s` | `emacs.isearchForward` |
| `C-r` | `emacs.isearchBackward` |
| `C-x C-s` | `file.save` |
| `C-x C-f` | `file.open` |
| `C-x b` | `file.nextBuffer` |
| `C-x k` | `file.close` |
| `C-x 0` | `pane.close` |
| `C-x 1` | `pane.closeOthers` |
| `C-x 2` | `pane.splitHorizontal` |
| `C-x 3` | `pane.splitVertical` |

