# Writing Plugins for Shed

Shed supports two plugin formats: **declarative** (`.shed`) and **scripted** (`.lua`). Place plugin files in `~/.shed/plugins/` — they are loaded automatically on startup.

## Quick Start

```console
:plugin new myplugin        # creates ~/.shed/plugins/myplugin.shed and opens it
:plugin new myplugin.lua    # creates a Lua plugin instead
:plugin reload              # reload all plugins after editing
```

## Managing Plugins

| Command | Action |
| :--- | :--- |
| `:plugin` | List loaded plugins |
| `:plugin info <name>` | Show details (commands, events, bindings) |
| `:plugin enable <name>` | Re-enable a disabled plugin |
| `:plugin disable <name>` | Disable (appends `.disabled` to filename) |
| `:plugin reload` | Reload all plugins from disk |
| `:plugin path` | Show plugin directory and disabled plugins |
| `:plugin new <name>` | Create template and open for editing |
| `:plugin packages` | Show managed plugin package metadata |
| `:plugin install <name> <version> <source> [--checksum=sha256] [--pin]` | Install package-managed plugin |
| `:plugin update [name]` | Reinstall package(s), skipping pinned |
| `:plugin remove <name>` | Remove package + plugin file |
| `:plugin pin/unpin <name>` | Toggle version pinning |

## Declarative Plugins (`.shed`)

A `.shed` file is a list of `# @` directives. Lines without this prefix are ignored.

### Directives

| Directive | Description |
| :--- | :--- |
| `# @name <name>` | Plugin name (shown in `:plugin`) |
| `# @description <text>` | One-line description |
| `# @command <name>=<shell>` | Register a command (invoked via `:<name>`) |
| `# @bind <mode> <lhs>=<rhs>` | Register a keybinding |
| `# @event <event>=:<command>` | Run command when event fires |

### Interpolation Variables

Shell commands support these variables, expanded at execution time:

| Variable | Value |
| :--- | :--- |
| `%file` | Current buffer file path |
| `%line` | Current line number (1-based) |
| `%col` | Current column (0-based) |
| `%word` | Word under cursor |
| `%selection` | Visual selection text |

### Example: Format on Save

```
# @name fmt
# @description auto-format current file on save
# @command fmt=!prettier --write %file
# @event BufWrite=:fmt
```

### Example: Git Blame

```
# @name git-blame
# @description show git blame for current line
# @command git-blame=!git blame -L %line,%line %file
# @bind normal gb=:git-blame
```

### Example: Open in Browser

```
# @name browser
# @description open current file in default browser
# @command browser=!open %file
# @bind normal go=:browser
```

## Lua Plugins (`.lua`)

Lua plugins run in a sandboxed [LuaJ](https://github.com/luaj/luaj) environment with access to the `shed.*` API table. No filesystem or OS access is available — use `shed.shell()` for controlled shell execution.

### API Reference

#### Buffer

```lua
shed.get_line(n)        -- get line n (1-indexed), returns string
shed.set_line(n, text)  -- replace line n, returns boolean
shed.line_count()       -- number of lines in buffer
shed.get_text()         -- full buffer text
shed.file_path()        -- absolute file path (or "" for scratch buffers)
shed.file_name()        -- display name
shed.is_modified()      -- true if buffer has unsaved changes
```

#### Cursor

```lua
shed.cursor_line()      -- current line number (1-indexed)
shed.cursor_col()       -- current column (0-indexed)
```

#### Commands

```lua
shed.command(str)       -- execute an ex command (e.g. "w", "bn", "set nu")
                        -- returns the result string
shed.message(str)       -- show a message in the command bar
shed.shell(cmd)         -- run shell command, return stdout as string
```

#### Config

```lua
shed.config_get(key)    -- get a shedrc config value (nil if not set)
shed.config_set(key, v[, persist]) -- set config, optionally persist to ~/.shed/shedrc
```

#### Theme

```lua
shed.theme()               -- current theme id
shed.themes()              -- array-style list of available theme ids
shed.theme_set(name[,persist]) -- apply theme, optional persist
shed.palette_get()         -- table of active palette keys -> hex colors
shed.palette_set(tbl[,persist]) -- apply color/ui overrides from table
shed.theater(preset)       -- apply dramatic preset: off/subtle/full
```

#### Mode

```lua
shed.mode()             -- current mode name: "normal", "insert", "visual", etc.
```

#### Events

```lua
shed.on(event, fn)      -- register a callback for an editor event
                        -- fn receives the event name as its argument
```

### Events

| Event | Fires when |
| :--- | :--- |
| `BufOpen` | A file is opened |
| `BufWrite` | A buffer is saved |
| `ModeChange` | The editor mode changes |
| `ThemeChange` | Theme/palette updates are applied |

### Example: Save Notification

```lua
shed.on("BufWrite", function()
  shed.message("saved: " .. shed.file_name())
end)
```

### Example: Theme-aware Mode Accent

```lua
shed.on("ModeChange", function()
  local mode = shed.mode()
  if mode == "insert" then
    shed.palette_set({ command_bg = "#1F3A5F" })
  elseif mode == "normal" then
    shed.palette_set({ command_bg = "#2B2F3A" })
  end
end)
```

### Example: Trim Trailing Whitespace

```lua
shed.on("BufWrite", function()
  for i = 1, shed.line_count() do
    local line = shed.get_line(i)
    local trimmed = line:gsub("%s+$", "")
    if trimmed ~= line then
      shed.set_line(i, trimmed)
    end
  end
end)
```

### Example: Word Count in Status

```lua
shed.on("BufOpen", function()
  local text = shed.get_text()
  local _, count = text:gsub("%S+", "")
  shed.message(count .. " words")
end)
```

### Example: Auto-Insert Header

```lua
shed.on("BufOpen", function()
  if shed.line_count() == 1 and shed.get_line(1) == "" then
    local path = shed.file_path()
    if path:match("%.py$") then
      shed.set_line(1, "#!/usr/bin/env python3")
    end
  end
end)
```

### Example: Run Tests on Save

```lua
shed.on("BufWrite", function()
  local path = shed.file_path()
  if path:match("_test%.go$") then
    local output = shed.shell("go test ./... 2>&1 | tail -1")
    shed.message(output:gsub("\n", ""))
  end
end)
```

## Tips

- Plugin files are loaded in alphabetical order. Prefix with numbers (`01-`, `02-`) to control load order.
- Use `:plugin disable <name>` to temporarily turn off a plugin without deleting it.
- Lua plugins are sandboxed: `os`, `io`, `dofile`, `loadfile` are unavailable. Use `shed.shell()` for external commands.
- Event callbacks have a max recursion depth of 3 to prevent infinite loops.
- Interpolation variables (`%file`, `%line`, etc.) work in all user commands, not just plugins.
- Use `:help plugins` inside Shed for a quick reference.
