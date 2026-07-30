# `Shed` Keybinds

### User overlays

Use `keybind.<scope>.<lhs>` in TOML or `:keymap` for a searchable GUI. In the Vim profile, a scope-specific overlay wins over `global`; Plain and Emacs retain their fixed bindings. `:keymap reset <scope> <lhs>` removes the persisted overlay.

### Plain profile

Set `keymap.profile = "plain"` for non-modal editing. Plain bypasses Vim mode handling and `keybind.<mode>.*` remaps; standard Swing text, selection, clipboard, undo, and redo bindings remain active.

| Key-binds | Function |
| :---: | :---: |
| `Ctrl`/`Cmd` + `S` | Save current file |
| `Ctrl`/`Cmd` + `O` or `P` | Find file |
| `Ctrl`/`Cmd` + `Shift` + `P` | Command palette |
| `Ctrl`/`Cmd` + `B` | Buffer picker |
| `Ctrl`/`Cmd` + `W` | Close active split |
| `F1` | Plain-keymap help |

### Emacs profile

Set `keymap.profile = "emacs"` for fixed Emacs bindings. Emacs bypasses Vim mode handling and `keybind.<mode>.*` remaps. `C-x` accepts exactly one following key; unsupported chords and `C-g` cancel harmlessly.

| Key-binds | Function |
| :---: | :---: |
| `C-f` / `C-b` / `C-n` / `C-p` | Character and line navigation |
| `C-a` / `C-e` | Beginning/end of line |
| `M-f` / `M-b` | Word navigation |
| `C-v` / `M-v` / `M-<` / `M->` | Page/file navigation |
| `C-d` / `C-k` / `C-w` / `M-w` / `C-y` | Delete, kill, copy, yank |
| `C-x C-s` / `C-x C-f` / `C-x C-b` | Save, find file, buffer picker |
| `C-x b` / `C-x k` / `C-x C-c` | Buffer picker, kill buffer, quit |
| `M-x` | Command palette |
| `C-g` | Cancel pending `C-x` chord |
| `C-h` or `F1` | Emacs-keymap help |

### Mode Switching

| Key-binds | Function |
| :---: | :---: |
| `Ctrl`/`Cmd` + `Shift` + `P` | Command palette |
| `i` | Enter `Insert` mode |
| `v` | Enter `Visual` mode |
| `R` | Enter `Replace` mode |
| `:` | Enter `Command` mode |
| `/` | Enter `Command` mode (search) |
| `ESC` | Return to `Normal` mode |

### `Normal` mode

#### Character Movement

| Key-binds | Function |
| :---: | :---: |
| `h` or `←` | Move one character left |
| `j` or `↓` | Move one line down |
| `k` or `↑` | Move one line up |
| `l` or `→` | Move one character right |

#### Word Movement

| Key-binds | Function |
| :---: | :---: |
| `w` | Move to start of next word |
| `b` | Move to start of previous word |
| `e` | Move to end of current/next word |

#### Line Movement

| Key-binds | Function |
| :---: | :---: |
| `0` | Move to start of line |
| `$` | Move to end of line |

#### File Movement

| Key-binds | Function |
| :---: | :---: |
| `gg` | Move to start of file |
| `G` | Move to end of file |
| `Ctrl+d` | Scroll half-page down |
| `Ctrl+u` | Scroll half-page up |

#### Copy (Yank)

| Key-binds | Function |
| :---: | :---: |
| `yy` | Yank (copy) entire line |

#### Delete

| Key-binds | Function |
| :---: | :---: |
| `dd` | Delete entire line |
| `dw` | Delete word forward |
| `D` | Delete to end of line |
| `x` | Delete character under cursor |

#### Change (Delete + Insert Mode)

| Key-binds | Function |
| :---: | :---: |
| `cc` | Change entire line |
| `cw` | Change word forward |
| `C` | Change to end of line |

#### Paste

| Key-binds | Function |
| :---: | :---: |
| `p` | Paste after cursor/line |
| `P` | Paste before cursor/line |

#### Undo/Redo

| Key-binds | Function |
| :---: | :---: |
| `u` | Undo last change |
| `Ctrl+r` | Redo undone change |

#### Search

| Key-binds | Function |
| :---: | :---: |
| `/pattern` | Search forward for pattern |
| `n` | Jump to next match |
| `N` | Jump to previous match |

#### Repeat

| Key-binds | Function |
| :---: | :---: |
| `.` | Repeat last command |

#### Experimental multi-selection

Enable ``multi.selection.enabled`` first. ``Ctrl+Shift+D`` adds the next matching selection, and ``Alt+J`` / ``Alt+K`` add a cursor below / above (``Shift`` reverses either direction). Insert text, ``Backspace``, and ``Delete`` apply to every active selection; ``Esc`` clears extras. The total includes the primary cursor and is capped by ``multi.selection.max.cursors``.

### `Insert` mode

| Key-binds | Function |
| :---: | :---: |
| `ESC` | Exit to `Normal` mode |
| Any text | Insert text at cursor |

### `Visual` mode

| Key-binds | Function |
| :---: | :---: |
| `h/j/k/l` | Navigate (same as Normal mode) |
| `w/b/e` | Word movement (same as Normal mode) |
| `0/$` | Line start/end (same as Normal mode) |
| `y` | Yank (copy) selection |
| `d` | Delete selection |
| `c` | Change selection (delete + Insert mode) |
| `ESC` | Exit to `Normal` mode |

### `Replace` mode

| Key-binds | Function |
| :---: | :---: |
| `ESC` | Exit to `Normal` mode |
| Any character | Overwrite character at cursor |

### `Command` mode

#### File Operations

| Key-binds | Function |
| :---: | :---: |
| `:w` | Save changes to file |
| `:q` | Quit (prompts if unsaved changes) |
| `:q!` | Force quit (discard changes) |
| `:wq` or `:x` | Save and quit |
| `:e filename` | Edit file (add to buffers) |

#### Buffer Management

| Key-binds | Function |
| :---: | :---: |
| `:bn` | Switch to next buffer |
| `:bp` | Switch to previous buffer |
| `:ls` | List all open buffers |
| `:bd` | Delete current buffer |
| `:bd!` | Force delete buffer (discard changes) |

#### Search & Replace

| Key-binds | Function |
| :---: | :---: |
| `/pattern` | Search forward for pattern |
| `:%s/old/new/g` | Replace all occurrences |
| `:%s/old/new` | Replace first occurrence only |

#### Settings

| Key-binds | Function |
| :---: | :---: |
| `:set nu` | Enable line numbers |
| `:set nonu` | Disable line numbers |

#### Navigation

| Key-binds | Function |
| :---: | :---: |
| `:45` | Go to line 45 |
| `45gg` | Go to line 45 (also works in Normal mode) |

#### Utilities

| Key-binds | Function |
| :---: | :---: |
| `:wc` | Show word count |
| `:help` | Display help dialog |

#### Exit Command Mode

| Key-binds | Function |
| :---: | :---: |
| `ENTER` | Execute command |
| `ESC` | Cancel command |
| `BACKSPACE` | Delete character |
