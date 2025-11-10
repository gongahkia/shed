# Shed Features Documentation

## Version 2.0 - Complete Feature List

Shed has evolved from a minimal 194-line text editor into a full-featured modal editor while maintaining its simplicity and small footprint (32KB JAR).

---

## Core Philosophy

**Simplicity First**: Shed remains true to its minimalist roots - no plugins, no complex configuration, no unnecessary features.

**Vim-Inspired**: Modal editing with familiar Vim keybindings to ease the learning curve for new Vim users.

**Privacy-Focused**: Zero telemetry, no network calls, no data collection. Your files stay yours.

**Fast & Lightweight**: Sub-second startup time, 32KB executable, minimal memory footprint.

---

## Modal Editing

Shed operates in five distinct modes:

### 1. NORMAL Mode (Default)
- **Purpose**: Navigation and command entry
- **Background**: Red/Crimson (`#BC0E4C`)
- **Editable**: No
- **Enter from**: ESC from any mode

### 2. INSERT Mode
- **Purpose**: Text insertion
- **Background**: Blue/Slate (`#354F60`)
- **Editable**: Yes
- **Enter from**: `i` in NORMAL mode
- **Exit**: ESC

### 3. VISUAL Mode
- **Purpose**: Character-wise text selection
- **Background**: Green/Forest (`#2E8B57`)
- **Editable**: No
- **Enter from**: `v` in NORMAL mode
- **Exit**: ESC or operation (y/d/c)

### 4. REPLACE Mode
- **Purpose**: Overwrite existing characters
- **Background**: Brown/Saddle (`#8B4513`)
- **Editable**: Yes (overwrite only)
- **Enter from**: `R` in NORMAL mode
- **Exit**: ESC

### 5. COMMAND Mode
- **Purpose**: Execute ex-style commands
- **Background**: Yellow/Gold (`#FFC501`)
- **Editable**: Command line only
- **Enter from**: `:` or `/` in NORMAL mode
- **Exit**: ENTER (execute) or ESC (cancel)

---

## Navigation (NORMAL Mode)

### Character Movement
| Key | Action |
|-----|--------|
| `h` or `←` | Move left one character |
| `j` or `↓` | Move down one line |
| `k` or `↑` | Move up one line |
| `l` or `→` | Move right one character |

### Word Movement
| Key | Action |
|-----|--------|
| `w` | Move forward to start of next word |
| `b` | Move backward to start of previous word |
| `e` | Move to end of current/next word |

### Line Movement
| Key | Action |
|-----|--------|
| `0` | Move to start of line |
| `$` | Move to end of line |

### File Movement
| Key | Action |
|-----|--------|
| `gg` | Move to start of file |
| `G` | Move to end of file |
| `Ctrl+d` | Scroll half-page down |
| `Ctrl+u` | Scroll half-page up |

---

## Editing Operations

### Copy (Yank)
| Key | Action |
|-----|--------|
| `yy` | Yank (copy) entire line |
| `y` (in VISUAL) | Yank selection |

Yanked text is placed in both Shed's internal clipboard and the system clipboard.

### Delete & Change
| Key | Action |
|-----|--------|
| `dd` | Delete entire line |
| `dw` | Delete word forward |
| `D` | Delete to end of line |
| `x` | Delete character under cursor |
| `cc` | Change (delete) line and enter INSERT mode |
| `cw` | Change (delete) word and enter INSERT mode |
| `C` | Change (delete) to end of line and enter INSERT mode |
| `d` (in VISUAL) | Delete selection |
| `c` (in VISUAL) | Change selection (delete + INSERT mode) |

All deletions are stored in the clipboard, allowing "cut" functionality.

### Paste
| Key | Action |
|-----|--------|
| `p` | Paste after cursor/line |
| `P` | Paste before cursor/line |

Line-wise yanks/deletes are pasted as full lines. Character-wise selections are pasted inline.

### Undo/Redo
| Key | Action |
|-----|--------|
| `u` | Undo last change |
| `Ctrl+r` | Redo undone change |

Each buffer maintains its own undo history with 100-action limit.

### Repeat
| Key | Action |
|-----|--------|
| `.` | Repeat last command (dd, yy, dw, cc, cw, D, C) |

---

## Search & Replace

### Search
| Command | Action |
|---------|--------|
| `/pattern` | Search forward for pattern |
| `n` | Jump to next match |
| `N` | Jump to previous match |

Search highlights all matches (yellow) with current match emphasized (orange).

### Replace
| Command | Action |
|---------|--------|
| `:%s/old/new/g` | Replace all occurrences in file |
| `:%s/old/new` | Replace first occurrence only |

Future: Line-level substitution (`:s/old/new`)

---

## File Operations

### Opening Files
**From command line**:
```bash
shed filename.txt        # Open specific file
shed                     # Open file chooser dialog
```

**From within Shed**:
```
:e filename.txt          # Edit file (adds to buffer list)
```

### Saving Files
| Command | Action |
|---------|--------|
| `:w` | Write (save) current buffer |
| `:wq` | Write and quit |
| `:x` | Same as :wq |

### Quitting
| Command | Action |
|---------|--------|
| `:q` | Quit (warns if unsaved changes) |
| `:q!` | Force quit (discard changes) |

Closing the window (X button) behaves like `:q` - prompts if unsaved changes exist.

---

## Multiple Buffers

Shed supports editing multiple files simultaneously without tabs or split windows.

### Buffer Commands
| Command | Action |
|---------|--------|
| `:e filename` | Edit file (add to buffer list) |
| `:bn` | Switch to next buffer |
| `:bp` | Switch to previous buffer |
| `:ls` | List all buffers (shows in dialog) |
| `:bd` | Delete (close) current buffer |
| `:bd!` | Force delete buffer (discard changes) |

### Buffer Indicators
Status bar shows:
- Filename
- `[+]` if buffer has unsaved changes
- Current buffer position (e.g., "Buffer 2 of 5")

---

## Line Numbers

Toggle line number display:

| Command | Action |
|---------|--------|
| `:set nu` | Enable line numbers |
| `:set nonu` | Disable line numbers |

Line numbers appear in a gray gutter on the left side.

---

## Jump to Line

| Command | Action |
|---------|--------|
| `:45` | Jump to line 45 |
| `45gg` | Jump to line 45 (Vim-style) |
| `:$` | Jump to last line |

---

## Configuration

Shed reads configuration from `~/.shedrc` or `~/.config/shed/shedrc`.

### Config Format
Simple key=value pairs:
```
# Color customization (hex codes)
color.normal=#BC0E4C
color.insert=#354F60
color.command=#FFC501
color.visual=#2E8B57
color.replace=#8B4513

# Font settings
font.family=Hack
font.size=16

# Editor settings
tab.size=4
line.numbers=false
```

### Customizable Settings
- **Colors**: Background color for each mode
- **Font**: Family and size (falls back to system monospace if custom font missing)
- **Tab Size**: Number of spaces per tab (default: 4)
- **Line Numbers**: Enable/disable on startup (default: false)

---

## Status Bar

The status bar displays rich context information:

```
test.java [+]  45:12  normal mode  UTF-8  194 lines
│           │     │      │             │       └─ Total line count
│           │     │      │             └─ File encoding
│           │     │      └─ Current mode
│           │     └─ Cursor position (line:column)
│           └─ Modified indicator
└─ Filename
```

In COMMAND mode, the status bar shows the command being typed:
```
:w [cursor here]
/search pattern
```

Messages (errors, confirmations) temporarily replace status bar content for 3 seconds.

---

## Utility Commands

### Word Count
```
:wc                      # Show lines, words, characters
```

Output example: `194 lines, 1523 words, 8742 characters`

### Help System
```
:help                    # Show all keybindings
:help <topic>            # Show help for specific topic (future)
```

Opens a dialog with comprehensive keybinding reference.

---

## Visual Mode Selection

1. Enter VISUAL mode with `v`
2. Move cursor to select text (h/j/k/l/w/b/e/0/$)
3. Selection updates in real-time
4. Perform operation:
   - `y` - Yank selection
   - `d` - Delete selection
   - `c` - Change selection (delete + enter INSERT mode)
5. ESC to exit without operation

---

## Keyboard Shortcuts Summary

### Mode Switching
- `i` → INSERT
- `v` → VISUAL
- `R` → REPLACE
- `:` → COMMAND
- `/` → COMMAND (search)
- `ESC` → NORMAL

### Essential Operations
- `dd` - Delete line
- `yy` - Yank line
- `p` - Paste
- `u` - Undo
- `.` - Repeat
- `:w` - Save
- `:q` - Quit

### Advanced Operations
- `gg` - Top of file
- `G` - Bottom of file
- `0` - Start of line
- `$` - End of line
- `w` - Next word
- `/` - Search
- `n` - Next match

---

## Design Decisions

### What Shed DOES NOT Have (By Design)
- ❌ Syntax highlighting (Tier 3 planned, not yet implemented)
- ❌ Auto-complete/LSP
- ❌ Plugin system
- ❌ Git integration
- ❌ File tree browser
- ❌ Split windows/panes
- ❌ Mouse-driven UI
- ❌ Network features
- ❌ Macro recording
- ❌ Terminal emulator

### Why These Omissions?
Shed is intentionally minimal to:
1. Remain approachable for beginners learning modal editing
2. Maintain fast startup and low resource usage
3. Keep the codebase understandable (~1100 lines)
4. Avoid dependency hell (zero external dependencies)
5. Focus on core editing rather than IDE features

---

## Future Enhancements (Tier 3+)

### Planned (Not Yet Implemented)
- Syntax highlighting for Java, Python, Markdown, HTML, CSS, JavaScript
- Auto-indentation (smart indent for braces)
- Bracket/parenthesis matching
- Recent files tracking (`:recent`, `:r1`, `:r2`, etc.)
- Improved line-level substitute (`:s/old/new`)

### Under Consideration
- Colorscheme support
- More granular undo (currently document-level)
- Soft word wrap toggle
- Configurable keybindings

### Will NOT Be Added
- Plugin API (complexity)
- Language Server Protocol (dependency)
- Build system integration (out of scope)
- Remote file editing (security)

---

## Performance Characteristics

**Startup Time**: <1 second (cold start)

**File Loading**:
- <500ms for 5,000-line files
- <2s for 50,000-line files

**Search Performance**:
- <100ms for 5,000-line files
- Highlights all matches instantly

**Memory Usage**:
- Base: ~15MB (JVM overhead)
- Per buffer: ~2MB per 10,000 lines

**Recommended Limits**:
- File size: <10,000 lines (for optimal performance)
- Concurrent buffers: <10 files

---

## Compatibility

**Java Requirements**: JDK/JRE 17 or 20 (tested)
- Likely works with JDK 11+ (uses Swing APIs)
- Uses `modelToView2D` / `viewToModel2D` (Java 9+)

**Operating Systems**:
- ✅ Windows 10/11
- ✅ Linux (tested on Ubuntu 22.04)
- ✅ macOS 10.15+ (Catalina and later)

**Font Requirements**:
- Ships with Hack Nerd Font (`assets/hackregfont.ttf`)
- Falls back to system monospace if font missing

---

## Error Handling

Shed handles errors gracefully:

### File I/O Errors
- **Missing file**: Shows error in status bar
- **Permission denied**: Displays error message
- **Corrupt encoding**: Warns user, attempts recovery

### User Errors
- **Invalid command**: "Command not recognised: xyz"
- **Missing arguments**: "Error: :e requires filename argument"
- **Out of range**: "Invalid line number: 999"

### Edge Cases
- **Empty file**: Handled correctly (0 lines)
- **Binary files**: Opens but may display garbage (not a binary editor)
- **Very large files**: May slow down; consider splitting file

---

## Tips & Tricks

### Efficient Editing
1. Use `.` to repeat common operations
2. Chain commands: `dw` → `p` → `.` → `.` (delete word, paste, repeat)
3. Use VISUAL mode for complex selections
4. `:wq` is faster than `:w` then `:q`

### Learning Vim Motions
1. Start with `hjkl` for all movement (disable arrow keys mentally)
2. Master `w` and `b` before moving to more complex motions
3. Learn `0` and `$` for line-level editing
4. Use `gg` and `G` to jump around files quickly

### File Management
1. Use `:e` to open related files without leaving Shed
2. `:bn` / `:bp` to switch between files
3. `:ls` to see all open buffers at a glance
4. Save often with `:w` (or remap a key if using config file later)

---

## Known Limitations

1. **No mouse support** - Keyboard only (by design)
2. **No syntax highlighting** (yet) - Plain text only in v2.0
3. **Line-level search only** - Cannot search within a specific selection
4. **No visual line mode** - Only character-wise visual mode
5. **No split windows** - One buffer visible at a time
6. **Undo granularity** - Document-level, not keystroke-level

---

## Comparison with Other Editors

### Shed vs Vim
- ✅ Simpler: Only essential Vim commands
- ✅ More accessible: Visual UI, no terminal required
- ✅ Lighter: 32KB vs 3.5MB
- ❌ Less powerful: No ex commands, limited motions
- ❌ No plugins: Vim has thousands

### Shed vs Nano
- ✅ Modal editing: More powerful once learned
- ✅ Multiple buffers: Nano lacks buffer management
- ❌ Steeper learning curve: Modes are confusing initially
- ❌ Larger: 32KB vs ~50KB

### Shed vs VSCode
- ✅ Instant startup: <1s vs 3-5s
- ✅ No bloat: 32KB vs 85MB
- ✅ Privacy: No telemetry vs extensive tracking
- ❌ No ecosystem: VSCode has extensions
- ❌ Basic features only: VSCode is an IDE

---

## Getting Help

### In-Editor Help
- `:help` - Show all keybindings

### Documentation
- `README.md` - Installation and basic usage
- `FEATURES.md` - This file (complete feature reference)
- `KEYBINDINGS.md` - Quick keybinding reference
- `ARCHITECTURE.md` - Developer documentation

### Community
- Report issues: [GitHub Issues](https://github.com/gongahkia/shed/issues)
- Contribute: Fork and submit PRs

---

*Last updated: 2025-11-10 (Shed v2.0)*
