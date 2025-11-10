# Shed Architecture Documentation

## Overview

Shed has been refactored from a single-file architecture to a modular, maintainable codebase while preserving its minimalist philosophy.

## Project Structure

```
shed/
├── src/
│   ├── Texteditor.java      # Main class, UI coordination, event handling
│   ├── EditorMode.java       # Mode state machine enum
│   ├── FileBuffer.java       # File buffer management
│   ├── ClipboardManager.java # Yank/paste/delete operations
│   ├── CommandHandler.java   # Command parsing and execution
│   ├── ConfigManager.java    # Configuration file handling
│   └── SearchManager.java    # Search and highlighting
├── build/
│   └── Shed.jar             # Compiled executable
├── assets/
│   └── hackregfont.ttf      # Bundled Hack Nerd Font
├── docs/
│   ├── ARCHITECTURE.md      # This file
│   ├── FEATURES.md          # Feature documentation
│   ├── KEYBINDINGS.md       # Keybinding reference
│   └── CHANGELOG.md         # Version history
└── for-testing/             # Test files
```

## Core Components

### 1. Texteditor (Main Class)
**Responsibility**: UI creation, event coordination, window management

**Key Methods**:
- `Texteditor()` - Constructor, initializes UI components
- `keyPressed(KeyEvent)` - Delegates to mode-specific handlers
- `updateStatusBar()` - Refreshes status bar with current state
- `openFile(File)` - Loads file into buffer
- `saveFile()` - Persists current buffer to disk

**State Management**:
- `currentMode` - Current editor mode (EditorMode enum)
- `currentBuffer` - Active FileBuffer
- `buffers` - List of open buffers
- `clipboardManager` - Shared clipboard instance
- `commandHandler` - Command processor

### 2. EditorMode (Enum)
**Responsibility**: Define valid editor modes and their properties

**Modes**:
- `NORMAL` - Navigation and command entry
- `INSERT` - Text insertion
- `VISUAL` - Character-wise selection
- `REPLACE` - Overwrite mode
- `COMMAND` - Command input mode

**Properties per mode**:
- `displayName` - User-facing mode name
- `backgroundColor` - Mode-specific background color
- `isEditable` - Whether text area accepts input

### 3. FileBuffer
**Responsibility**: Manage individual file state

**Properties**:
- `file` - Associated File object
- `content` - Text content (String)
- `modified` - Dirty flag
- `encoding` - Character encoding (default UTF-8)
- `lineCount` - Total lines
- `undoManager` - UndoManager instance

**Methods**:
- `load()` - Read file from disk
- `save()` - Write to disk
- `getDisplayName()` - Get filename for status bar
- `isModified()` - Check dirty flag

### 4. ClipboardManager
**Responsibility**: Handle yank/delete/paste operations

**Properties**:
- `clipboardBuffer` - Internal clipboard storage
- `systemClipboard` - Java system clipboard reference
- `lastYankWasLine` - Track line-wise vs character-wise yank

**Methods**:
- `yankLine(String)` - Copy line to clipboard
- `yankSelection(String)` - Copy selection
- `deleteLine(int)` - Remove line from buffer
- `deleteChar(int)` - Remove character
- `paste(int, boolean)` - Insert clipboard at position
- `getClipboardContent()` - Retrieve clipboard text

### 5. CommandHandler
**Responsibility**: Parse and execute ex-style commands

**Methods**:
- `execute(String, Texteditor)` - Parse and run command
- `handleWrite()` - Save file (:w)
- `handleQuit(boolean)` - Exit with optional force (:q, :q!)
- `handleEdit(String)` - Open file (:e filename)
- `handleBufferNext()` - Switch to next buffer (:bn)
- `handleBufferPrev()` - Switch to previous buffer (:bp)
- `handleSearch(String)` - Initiate search (:/ pattern)
- `handleReplace(String, String, boolean)` - Find and replace (:%s)
- `handleSet(String)` - Toggle settings (:set nu)
- `handleHelp(String)` - Show help (:help)

**Command Parsing**:
- Tokenize on whitespace
- Support flags (!, g, etc.)
- Validate argument counts
- Return error messages to status bar

### 6. ConfigManager
**Responsibility**: Load and apply user configuration

**Config File**: `~/.shedrc` (or `~/.config/shed/shedrc`)

**Format**: Simple key=value pairs
```
color.normal=#BC0E4C
color.insert=#354F60
color.command=#FFC501
color.visual=#2E8B57
font.family=Hack
font.size=16
tab.size=4
line.numbers=false
```

**Methods**:
- `loadConfig()` - Read config file
- `parseConfig(String)` - Parse key=value pairs
- `getColor(String)` - Retrieve color setting
- `getFontFamily()` - Get font name
- `getFontSize()` - Get font size
- `getTabSize()` - Get tab width
- `getLineNumbers()` - Get line number preference

### 7. SearchManager
**Responsibility**: Handle search, highlight matches

**Properties**:
- `searchPattern` - Current search regex
- `matches` - List of match positions
- `currentMatchIndex` - Position in match list
- `highlighter` - TextArea highlighter

**Methods**:
- `search(String, JTextArea)` - Find all matches
- `highlightMatches()` - Apply highlighting
- `nextMatch()` - Jump to next match (n)
- `prevMatch()` - Jump to previous match (N)
- `clearHighlights()` - Remove all highlights
- `getMatchCount()` - Total matches found

## Data Flow

### Opening a File
```
main() → Texteditor() → JFileChooser/CLI args
  → FileBuffer.load() → JTextArea.setText()
  → updateStatusBar()
```

### Normal Mode Navigation
```
keyPressed(e) → currentMode == NORMAL
  → parse motion key (w/b/e/0/$)
  → calculate new caret position
  → JTextArea.setCaretPosition()
```

### Yank/Delete/Paste
```
keyPressed('yy') → ClipboardManager.yankLine()
  → extract line at cursor → store in buffer
  → systemClipboard.setContents()
  → status bar feedback

keyPressed('p') → ClipboardManager.paste()
  → get clipboard content → insert at cursor
  → FileBuffer.setModified(true)
```

### Command Execution
```
keyPressed(':') → mode = COMMAND → commandBuffer = ":"
keyPressed('w') → commandBuffer = ":w"
keyPressed(ENTER) → CommandHandler.execute(":w")
  → FileBuffer.save() → status bar feedback
  → mode = NORMAL
```

### Search Workflow
```
keyPressed('/') → mode = COMMAND → commandBuffer = "/"
keyPressed('f','o','o') → commandBuffer = "/foo"
keyPressed(ENTER) → SearchManager.search("foo")
  → highlightMatches() → jump to first match
  → status bar shows "Match 1 of 5"

keyPressed('n') → SearchManager.nextMatch()
  → jump to match 2 → update status bar
```

## Thread Safety

**Single-threaded design**: All operations on EDT (Event Dispatch Thread) via Swing event handling. No explicit threading required.

**File I/O**: Blocking I/O is acceptable given target file sizes (<10K lines). For larger files, consider:
- SwingWorker for background loading
- Progress indicator during load/save
- Warn user about large files

## Error Handling

**Philosophy**: Fail gracefully, inform user via status bar

**Error Categories**:
1. **File I/O errors**: Catch IOException, display message
2. **Invalid commands**: Parse errors shown in status bar
3. **Search failures**: "Pattern not found" message
4. **Font loading**: Fall back to system monospace

**Example**:
```java
try {
    FileBuffer.save();
    statusBar.setText("Saved " + filename);
} catch (IOException e) {
    statusBar.setText("Error: " + e.getMessage());
}
```

## Performance Considerations

**Target Performance**:
- Startup time: <1 second
- File open: <500ms for 5K line file
- Search: <100ms for 5K line file
- JAR size: <30KB

**Optimization Strategies**:
- Lazy load buffers (only read file when accessed)
- Limit syntax highlighting to visible region
- Cache regex patterns in SearchManager
- Limit undo history to 100 actions

## Extension Points

**Future enhancements without architectural changes**:
1. Plugin system via Java reflection
2. Macro recording (store keystroke sequences)
3. Split windows (multiple JTextArea instances)
4. External tool integration (call shell commands)

**Not planned** (would require major refactor):
- LSP support (requires async I/O, JSON parsing)
- GUI customization beyond config file
- Embedded terminal

## Testing Strategy

**Manual testing focus** (no unit test framework added to preserve simplicity):
1. Test each mode transition
2. Verify all keybindings
3. Test file I/O with various encodings
4. Test undo/redo boundaries
5. Test search with edge cases (empty file, no matches, regex special chars)
6. Test config file parsing with malformed input

**Regression testing**:
- Keep `for-testing/` directory with test files
- Document known edge cases
- Test on Windows, Linux, macOS

## Build Process

**Manual build**:
```bash
cd src
javac *.java
jar cfm ../build/Shed.jar ../build/Manifest.txt *.class
```

**Manifest.txt**:
```
Main-Class: Texteditor
Class-Path: .
```

**No build automation** (ant, maven, gradle) to maintain zero-dependency philosophy.

## Version History

See CHANGELOG.md for detailed version history.

**Current version**: 2.0 (Tier 1 + Tier 2 features complete)

---

*Last updated: 2025-11-10*
