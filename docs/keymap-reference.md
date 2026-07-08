# Keymap Reference

Generated from `Sources/ItsyKeymap/Resources/keys.*.toml`.

Regenerate:

```sh
scripts/gen_keymap_docs.swift
```

## Plain

- Source: `Sources/ItsyKeymap/Resources/keys.plain.toml`
- Bindings: `47`

### mode.insert

| Key | Command |
|---|---|
| `Left` | `editor.moveLeft` |
| `Right` | `editor.moveRight` |
| `Cmd-Left` | `editor.moveLineStart` |
| `Cmd-Right` | `editor.moveLineEnd` |
| `Cmd-N` | `file.new` |
| `Cmd-O` | `file.open` |
| `Cmd-Shift-N` | `file.newWindow` |
| `Cmd-S` | `file.save` |
| `Cmd-W` | `pane.close` |
| `Cmd-,` | `app.settings` |
| `Cmd-P` | `view.commandPalette` |
| `Cmd-Shift-P` | `view.commandPalette` |
| `Cmd-K Cmd-S` | `app.keyboardShortcuts` |
| `Cmd-B` | `view.sidebar.toggle` |
| `Cmd-J` | `terminal.toggle` |
| `Cmd-Shift-.` | `view.hiddenFiles.toggle` |
| `Ctrl-Tab` | `file.nextBuffer` |
| `Ctrl-Shift-Tab` | `file.previousBuffer` |
| `Cmd-1` | `file.selectTab.1` |
| `Cmd-2` | `file.selectTab.2` |
| `Cmd-3` | `file.selectTab.3` |
| `Cmd-4` | `file.selectTab.4` |
| `Cmd-5` | `file.selectTab.5` |
| `Cmd-6` | `file.selectTab.6` |
| `Cmd-7` | `file.selectTab.7` |
| `Cmd-8` | `file.selectTab.8` |
| `Cmd-9` | `file.selectTab.9` |
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
| `Cmd-T` | `nav.gotoSymbolWorkspace` |
| `Cmd-Shift-O` | `nav.gotoSymbolFile` |
| `Cmd-Opt-7` | `view.outline` |
| `Ctrl-Alt-N` | `problems.next` |
| `Ctrl-Alt-P` | `problems.previous` |
| `Ctrl-Space` | `lsp.completion` |
| `Shift-Opt-F` | `lsp.formatDocument` |
| `Cmd-.` | `lsp.codeAction` |

## Vim

- Source: `Sources/ItsyKeymap/Resources/keys.vim.toml`
- Bindings: `281`

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
| `Ctrl-O` | `vim.jumpOlder` |
| `Ctrl-I` | `vim.jumpNewer` |
| `gd` | `lsp.definition` |
| `g Shift-D` | `lsp.declaration` |
| `gf` | `file.openUnderCursor` |
| `gt` | `file.nextBuffer` |
| `g Shift-T` | `file.previousBuffer` |
| `''` | `vim.jumpBack` |
| `m a` | `vim.mark.set.a` |
| `m b` | `vim.mark.set.b` |
| `m c` | `vim.mark.set.c` |
| `m d` | `vim.mark.set.d` |
| `m e` | `vim.mark.set.e` |
| `m f` | `vim.mark.set.f` |
| `m g` | `vim.mark.set.g` |
| `m h` | `vim.mark.set.h` |
| `m i` | `vim.mark.set.i` |
| `m j` | `vim.mark.set.j` |
| `m k` | `vim.mark.set.k` |
| `m l` | `vim.mark.set.l` |
| `m m` | `vim.mark.set.m` |
| `m n` | `vim.mark.set.n` |
| `m o` | `vim.mark.set.o` |
| `m p` | `vim.mark.set.p` |
| `m q` | `vim.mark.set.q` |
| `m r` | `vim.mark.set.r` |
| `m s` | `vim.mark.set.s` |
| `m t` | `vim.mark.set.t` |
| `m u` | `vim.mark.set.u` |
| `m v` | `vim.mark.set.v` |
| `m w` | `vim.mark.set.w` |
| `m x` | `vim.mark.set.x` |
| `m y` | `vim.mark.set.y` |
| `m z` | `vim.mark.set.z` |
| `\` a` | `vim.mark.jump.a` |
| `\` b` | `vim.mark.jump.b` |
| `\` c` | `vim.mark.jump.c` |
| `\` d` | `vim.mark.jump.d` |
| `\` e` | `vim.mark.jump.e` |
| `\` f` | `vim.mark.jump.f` |
| `\` g` | `vim.mark.jump.g` |
| `\` h` | `vim.mark.jump.h` |
| `\` i` | `vim.mark.jump.i` |
| `\` j` | `vim.mark.jump.j` |
| `\` k` | `vim.mark.jump.k` |
| `\` l` | `vim.mark.jump.l` |
| `\` m` | `vim.mark.jump.m` |
| `\` n` | `vim.mark.jump.n` |
| `\` o` | `vim.mark.jump.o` |
| `\` p` | `vim.mark.jump.p` |
| `\` q` | `vim.mark.jump.q` |
| `\` r` | `vim.mark.jump.r` |
| `\` s` | `vim.mark.jump.s` |
| `\` t` | `vim.mark.jump.t` |
| `\` u` | `vim.mark.jump.u` |
| `\` v` | `vim.mark.jump.v` |
| `\` w` | `vim.mark.jump.w` |
| `\` x` | `vim.mark.jump.x` |
| `\` y` | `vim.mark.jump.y` |
| `\` z` | `vim.mark.jump.z` |
| `' a` | `vim.mark.jumpLine.a` |
| `' b` | `vim.mark.jumpLine.b` |
| `' c` | `vim.mark.jumpLine.c` |
| `' d` | `vim.mark.jumpLine.d` |
| `' e` | `vim.mark.jumpLine.e` |
| `' f` | `vim.mark.jumpLine.f` |
| `' g` | `vim.mark.jumpLine.g` |
| `' h` | `vim.mark.jumpLine.h` |
| `' i` | `vim.mark.jumpLine.i` |
| `' j` | `vim.mark.jumpLine.j` |
| `' k` | `vim.mark.jumpLine.k` |
| `' l` | `vim.mark.jumpLine.l` |
| `' m` | `vim.mark.jumpLine.m` |
| `' n` | `vim.mark.jumpLine.n` |
| `' o` | `vim.mark.jumpLine.o` |
| `' p` | `vim.mark.jumpLine.p` |
| `' q` | `vim.mark.jumpLine.q` |
| `' r` | `vim.mark.jumpLine.r` |
| `' s` | `vim.mark.jumpLine.s` |
| `' t` | `vim.mark.jumpLine.t` |
| `' u` | `vim.mark.jumpLine.u` |
| `' v` | `vim.mark.jumpLine.v` |
| `' w` | `vim.mark.jumpLine.w` |
| `' x` | `vim.mark.jumpLine.x` |
| `' y` | `vim.mark.jumpLine.y` |
| `' z` | `vim.mark.jumpLine.z` |
| `q a` | `vim.macro.record.a` |
| `q b` | `vim.macro.record.b` |
| `q c` | `vim.macro.record.c` |
| `q d` | `vim.macro.record.d` |
| `q e` | `vim.macro.record.e` |
| `q f` | `vim.macro.record.f` |
| `q g` | `vim.macro.record.g` |
| `q h` | `vim.macro.record.h` |
| `q i` | `vim.macro.record.i` |
| `q j` | `vim.macro.record.j` |
| `q k` | `vim.macro.record.k` |
| `q l` | `vim.macro.record.l` |
| `q m` | `vim.macro.record.m` |
| `q n` | `vim.macro.record.n` |
| `q o` | `vim.macro.record.o` |
| `q p` | `vim.macro.record.p` |
| `q q` | `vim.macro.record.q` |
| `q r` | `vim.macro.record.r` |
| `q s` | `vim.macro.record.s` |
| `q t` | `vim.macro.record.t` |
| `q u` | `vim.macro.record.u` |
| `q v` | `vim.macro.record.v` |
| `q w` | `vim.macro.record.w` |
| `q x` | `vim.macro.record.x` |
| `q y` | `vim.macro.record.y` |
| `q z` | `vim.macro.record.z` |
| `q /` | `vim.searchHistory.forward` |
| `q Shift-/` | `vim.searchHistory.backward` |
| `q Shift-;` | `vim.commandHistory` |
| `Shift-2` | `vim.macro.replayPrefix` |
| `p` | `vim.pasteAfter` |
| `Shift-P` | `vim.pasteBefore` |
| `r` | `vim.replace.char` |
| `Shift-R` | `vim.replace.mode` |
| `Shift-\`` | `vim.case.toggle` |
| `gu` | `vim.case.lowerOperator` |
| `g Shift-U` | `vim.case.upperOperator` |
| `Shift-. Shift-.` | `vim.indent.right` |
| `Shift-, Shift-,` | `vim.indent.left` |
| `=` | `vim.format.operator` |
| `==` | `vim.format.line` |
| `gq` | `vim.format.reflowOperator` |
| `zc` | `vim.fold.close` |
| `zo` | `vim.fold.open` |
| `za` | `vim.fold.toggle` |
| `z Shift-C` | `vim.fold.closeRecursive` |
| `z Shift-O` | `vim.fold.openRecursive` |
| `z Shift-A` | `vim.fold.toggleRecursive` |
| `z Shift-M` | `vim.fold.closeAll` |
| `z Shift-R` | `vim.fold.openAll` |
| `Ctrl-W s` | `pane.splitHorizontal` |
| `Ctrl-W v` | `pane.splitVertical` |
| `Ctrl-W h` | `pane.focusLeft` |
| `Ctrl-W j` | `pane.focusDown` |
| `Ctrl-W k` | `pane.focusUp` |
| `Ctrl-W l` | `pane.focusRight` |
| `Ctrl-W w` | `pane.focusNext` |
| `Ctrl-W q` | `pane.close` |
| `Ctrl-W o` | `pane.closeOthers` |
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
| `Cmd-T` | `nav.gotoSymbolWorkspace` |
| `Cmd-Shift-O` | `nav.gotoSymbolFile` |
| `Cmd-Opt-7` | `view.outline` |
| `Ctrl-Alt-N` | `problems.next` |
| `Ctrl-Alt-P` | `problems.previous` |
| `Ctrl-Space` | `lsp.completion` |
| `Shift-K` | `lsp.hover` |
| `gr` | `lsp.references` |
| `Space c a` | `lsp.codeAction` |
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
| `Cmd-T` | `nav.gotoSymbolWorkspace` |
| `Cmd-Shift-O` | `nav.gotoSymbolFile` |
| `Cmd-Opt-7` | `view.outline` |
| `Ctrl-Alt-N` | `problems.next` |
| `Ctrl-Alt-P` | `problems.previous` |
| `Space c a` | `lsp.codeAction` |
| `=` | `lsp.formatSelection` |
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
| `is` | `vim.textObject.innerSentence` |
| `as` | `vim.textObject.aroundSentence` |
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
| `it` | `vim.textObject.innerTag` |
| `at` | `vim.textObject.aroundTag` |

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
| `Cmd-T` | `nav.gotoSymbolWorkspace` |
| `Cmd-Shift-O` | `nav.gotoSymbolFile` |
| `Cmd-Opt-7` | `view.outline` |
| `Ctrl-Alt-N` | `problems.next` |
| `Ctrl-Alt-P` | `problems.previous` |
| `Esc` | `mode.normal` |
| `Ctrl-Space` | `lsp.completion` |
| `jk` | `mode.normal` |

## Emacs

- Source: `Sources/ItsyKeymap/Resources/keys.emacs.toml`
- Bindings: `62`

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
| `C-t` | `emacs.transposeChars` |
| `M-t` | `emacs.transposeWords` |
| `M-u` | `emacs.uppercaseWord` |
| `M-l` | `emacs.lowercaseWord` |
| `M-c` | `emacs.capitalizeWord` |
| `C-M-f` | `emacs.forwardSexp` |
| `C-M-b` | `emacs.backwardSexp` |
| `C-M-k` | `emacs.killSexp` |
| `C-M-Space` | `emacs.markSexp` |
| `C-/` | `edit.undo` |
| `C-_` | `edit.redo` |
| `Cmd-D` | `editor.addNextSelection` |
| `Cmd-Ctrl-G` | `edit.selectAllFindMatches` |
| `Cmd-W` | `pane.close` |
| `Cmd-\` | `pane.splitHorizontal` |
| `Cmd-Opt-\` | `pane.splitVertical` |
| `Cmd-Opt-Left` | `pane.focusLeft` |
| `Cmd-Opt-Right` | `pane.focusRight` |
| `Cmd-Opt-Up` | `pane.focusUp` |
| `Cmd-Opt-Down` | `pane.focusDown` |
| `Cmd-T` | `nav.gotoSymbolWorkspace` |
| `Cmd-Shift-O` | `nav.gotoSymbolFile` |
| `Cmd-Opt-7` | `view.outline` |
| `Ctrl-Alt-N` | `problems.next` |
| `Ctrl-Alt-P` | `problems.previous` |
| `Ctrl-Space` | `emacs.setMark` |
| `C-s` | `emacs.isearchForward` |
| `C-r` | `emacs.isearchBackward` |
| `C-x C-s` | `file.save` |
| `C-x C-f` | `file.open` |
| `C-x C-c` | `app.quit` |
| `C-x C-x` | `emacs.exchangePointMark` |
| `C-x b` | `file.nextBuffer` |
| `C-x k` | `file.close` |
| `C-x 0` | `pane.close` |
| `C-x 1` | `pane.closeOthers` |
| `C-x 2` | `pane.splitHorizontal` |
| `C-x 3` | `pane.splitVertical` |
| `C-x o` | `pane.focusNext` |
| `C-x Shift-9` | `emacs.macro.start` |
| `C-x Shift-0` | `emacs.macro.end` |
| `C-x e` | `emacs.macro.run` |
| `C-x r k` | `emacs.rectangle.kill` |
| `C-x r y` | `emacs.rectangle.yank` |
| `C-x r t` | `emacs.rectangle.string` |
| `M-x` | `view.commandPalette` |
| `M-g g` | `nav.gotoLine` |
| `M-Shift-5` | `emacs.queryReplace` |

