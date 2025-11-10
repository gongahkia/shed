# Shed Keybindings Quick Reference

## Mode Switching

| Key | Action |
|-----|--------|
| `i` | Enter INSERT mode |
| `v` | Enter VISUAL mode |
| `R` | Enter REPLACE mode |
| `:` | Enter COMMAND mode |
| `/` | Enter COMMAND mode (search) |
| `ESC` | Return to NORMAL mode |

---

## NORMAL Mode

### Navigation

#### Character Movement
| Key | Action |
|-----|--------|
| `h` / `←` | Move left |
| `j` / `↓` | Move down |
| `k` / `↑` | Move up |
| `l` / `→` | Move right |

#### Word Movement
| Key | Action |
|-----|--------|
| `w` | Next word start |
| `b` | Previous word start |
| `e` | Word end |

#### Line Movement
| Key | Action |
|-----|--------|
| `0` | Line start |
| `$` | Line end |

#### File Movement
| Key | Action |
|-----|--------|
| `gg` | File start |
| `G` | File end |
| `Ctrl+d` | Half-page down |
| `Ctrl+u` | Half-page up |

### Editing

#### Copy (Yank)
| Key | Action |
|-----|--------|
| `yy` | Yank line |

#### Delete
| Key | Action |
|-----|--------|
| `dd` | Delete line |
| `dw` | Delete word |
| `D` | Delete to end of line |
| `x` | Delete character |

#### Change (Delete + Insert Mode)
| Key | Action |
|-----|--------|
| `cc` | Change line |
| `cw` | Change word |
| `C` | Change to end of line |

#### Paste
| Key | Action |
|-----|--------|
| `p` | Paste after cursor/line |
| `P` | Paste before cursor/line |

#### Undo/Redo
| Key | Action |
|-----|--------|
| `u` | Undo |
| `Ctrl+r` | Redo |

#### Repeat
| Key | Action |
|-----|--------|
| `.` | Repeat last command |

### Search
| Key | Action |
|-----|--------|
| `/pattern` | Search forward |
| `n` | Next match |
| `N` | Previous match |

---

## INSERT Mode

| Key | Action |
|-----|--------|
| `ESC` | Exit to NORMAL mode |
| Any text | Insert at cursor |

---

## VISUAL Mode

### Navigation
Same as NORMAL mode (h/j/k/l, w/b/e, 0/$, etc.)

### Operations
| Key | Action |
|-----|--------|
| `y` | Yank selection |
| `d` | Delete selection |
| `c` | Change selection (delete + INSERT mode) |
| `ESC` | Exit to NORMAL mode |

---

## REPLACE Mode

| Key | Action |
|-----|--------|
| `ESC` | Exit to NORMAL mode |
| Any character | Overwrite character at cursor |

---

## COMMAND Mode

### File Operations
| Command | Action |
|---------|--------|
| `:w` | Write (save) |
| `:q` | Quit |
| `:q!` | Force quit (discard changes) |
| `:wq` or `:x` | Write and quit |
| `:e filename` | Edit file |

### Buffer Management
| Command | Action |
|---------|--------|
| `:bn` | Next buffer |
| `:bp` | Previous buffer |
| `:ls` | List buffers |
| `:bd` | Delete buffer |
| `:bd!` | Force delete buffer |

### Search & Replace
| Command | Action |
|---------|--------|
| `/pattern` | Search forward |
| `:%s/old/new/g` | Replace all |
| `:%s/old/new` | Replace first only |

### Settings
| Command | Action |
|---------|--------|
| `:set nu` | Enable line numbers |
| `:set nonu` | Disable line numbers |

### Navigation
| Command | Action |
|---------|--------|
| `:45` | Go to line 45 |
| `45gg` | Go to line 45 (also works in NORMAL) |
| `:$` | Go to last line |

### Utilities
| Command | Action |
|---------|--------|
| `:wc` | Word count |
| `:help` | Show help |
| `:recent` | Show recent files (not yet implemented) |

### Exit Command Mode
| Key | Action |
|-----|--------|
| `ENTER` | Execute command |
| `ESC` | Cancel command |
| `BACKSPACE` | Delete character |

---

## Essential Keybindings (Must Learn)

These 15 keys cover 80% of editing:

1. `i` - Enter INSERT mode
2. `ESC` - Return to NORMAL mode
3. `hjkl` - Navigate (4 keys)
4. `dd` - Delete line
5. `yy` - Yank line
6. `p` - Paste
7. `u` - Undo
8. `:w` - Save
9. `:q` - Quit

---

## Learning Path

### Day 1: Basic Editing
- `i` to insert, `ESC` to exit
- `hjkl` for movement (disable arrow keys!)
- `:w` to save, `:q` to quit
- `dd` to delete lines, `u` to undo

### Day 2: Copy/Paste
- `yy` to yank lines
- `p` to paste
- `dd` then `p` to move lines

### Week 1: Word Movement
- `w` and `b` to move by words
- `0` and `$` for line start/end
- `dw` to delete words

### Week 2: Advanced
- `gg` and `G` for file navigation
- `/` for search, `n` for next match
- `v` for visual selection
- `.` to repeat commands
- `:e` and `:bn` for multiple files

### Month 1: Power User
- `Ctrl+d` and `Ctrl+u` for page scrolling
- `D` and `C` for end-of-line operations
- `cc` and `cw` for change operations
- `:%s/old/new/g` for replacements
- `:set nu` for line numbers

---

## Common Mistakes

### Forgetting Mode
**Problem**: Typing commands in INSERT mode (nothing happens) or text in NORMAL mode (chaos)

**Solution**: Always press `ESC` before trying a command. Look at status bar to confirm mode.

### Arrow Keys
**Problem**: Using arrow keys instead of `hjkl`

**Solution**: Force yourself to use `hjkl` for one week. It becomes muscle memory.

### Not Saving
**Problem**: Editing for hours, then `:q` refuses because of unsaved changes

**Solution**: Save often with `:w`. Or use `:wq` to save and quit in one command.

### Wrong Command
**Problem**: Typed `:w` but editor doesn't respond

**Solution**: You're probably not in COMMAND mode. Press `:` first, THEN type `w`.

---

## Vim Differences

Shed implements a **subset** of Vim commands. Notable missing features:

### Not Supported
- **Counts**: `5dd` (delete 5 lines) - not supported
- **Marks**: `ma`, `'a` (mark positions) - not supported
- **Registers**: `"ayy` (yank to register a) - not supported
- **Macros**: `qa...q` (record macro) - not supported
- **Visual Line**: `V` (line-wise visual) - not supported
- **Visual Block**: `Ctrl+v` (block visual) - not supported
- **Many motions**: `f`, `t`, `%`, etc. - not supported
- **Advanced ex commands**: `:!`, `:r`, `:term`, etc. - not supported

### Supported
All keybindings in this document are fully supported and tested.

---

## Cheat Sheet (Print-Friendly)

```
┌─────────────────────────────────────────────────────────┐
│                   SHED KEYBINDINGS                      │
├─────────────────────────────────────────────────────────┤
│ MODES:  i = INSERT    v = VISUAL    R = REPLACE        │
│         : = COMMAND   / = SEARCH    ESC = NORMAL        │
├─────────────────────────────────────────────────────────┤
│ MOVE:   hjkl = arrows    w/b = word    0/$ = line      │
│         gg/G = file      Ctrl+d/u = page                │
├─────────────────────────────────────────────────────────┤
│ EDIT:   dd = del line    yy = yank    p/P = paste      │
│         x = del char     dw = del word    D = del eol  │
│         cc = chg line    cw = chg word    C = chg eol  │
├─────────────────────────────────────────────────────────┤
│ UNDO:   u = undo    Ctrl+r = redo    . = repeat        │
├─────────────────────────────────────────────────────────┤
│ SEARCH: /pattern = find    n/N = next/prev             │
├─────────────────────────────────────────────────────────┤
│ FILE:   :w = save    :q = quit    :wq = save+quit      │
│         :e FILE = open    :bn/:bp = next/prev buffer   │
├─────────────────────────────────────────────────────────┤
│ HELP:   :help = show keybindings    :wc = word count   │
└─────────────────────────────────────────────────────────┘
```

---

## Custom Keybindings

Shed does not yet support custom keybinding remapping. This feature may be added in a future version via the `~/.shedrc` config file.

**Workaround**: Modify `src/Texteditor.java` and recompile to change keybindings.

---

*Quick reference for Shed v2.0. See FEATURES.md for detailed documentation.*
