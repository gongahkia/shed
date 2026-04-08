# `Shed` Commands

This is the complete built-in command reference for command mode in `Shed`.

## Command Syntax

| Form | Action |
| :--- | :--- |
| `:command [args]` | Run an Ex command |
| `/pattern` | Search forward |
| `?pattern` | Search backward |
| `:N` | Go to line `N` |
| `:N,Mcommand` | Apply command to line range `N..M` (where supported) |
| `:%command` | Apply command to full buffer (where supported) |
| `:!cmd` | Run shell command asynchronously |
| `:N,M!cmd` | Filter a line range through shell command asynchronously |

## Core File + Buffer Commands

| Command | Action |
| :--- | :--- |
| `:w`, `:write` | Write current buffer (or `:w <path>` to save as) |
| `:q`, `:quit` | Quit (prompts on unsaved changes) |
| `:q!` | Force quit |
| `:wq`, `:x` | Write and quit |
| `:e <file>`, `:edit <file>` | Open file in a buffer |
| `:bn`, `:bnext` | Next buffer |
| `:bp`, `:bprev` | Previous buffer |
| `:ls` | List open buffers |
| `:bd`, `:bdelete` | Delete current buffer |
| `:bd!` | Force delete current buffer |
| `:buffers`, `:buf` | Open buffer picker |
| `:recent` | Show recent files |
| `:wa`, `:wall` | Write all modified file-backed buffers |
| `:qa`, `:qall` | Quit all |
| `:qa!`, `:qall!` | Force quit all |
| `:wqa`, `:wqall`, `:xa`, `:xall` | Write all then quit all |

## Search, Replace, and Text Operators

| Command | Action |
| :--- | :--- |
| `/pattern` | Search forward |
| `?pattern` | Search backward |
| `:s/old/new/` | Substitute first match on current line |
| `:s/old/new/g` | Substitute all matches on current line |
| `:N,Ms/old/new/g` | Substitute in explicit line range |
| `:%s/old/new/g` | Substitute in whole buffer |
| `:g/pattern/cmd` | Run `cmd` on lines matching regex |
| `:v/pattern/cmd` | Run `cmd` on lines not matching regex |
| `:d`, `:delete` | Delete current line |
| `:N,Md` | Delete explicit line range |
| `:normal <keys>`, `:norm <keys>` | Replay normal-mode keys on current line or a range |
| `:noh`, `:nohlsearch` | Clear search highlights |

## Async Shell, Jobs, and Tasks

| Command | Action |
| :--- | :--- |
| `:!<cmd>` | Run shell command as async job |
| `:N,M!<cmd>` | Filter selected line range through shell command as async job |
| `:drop <cmd>` | Run async command against current file path (`%` expands to quoted file path) |
| `:jobs` | Show async jobs buffer |
| `:jobcancel <id>`, `:jobkill <id>` | Cancel running async job |
| `:task`, `:task list` | Show project tasks (`.shedtasks`) |
| `:task add <name> <command>` | Save task |
| `:task remove <name>`, `:task rm <name>`, `:task delete <name>` | Remove task |
| `:task run <name>` | Run named task |
| `:task <name>` | Shortcut for running named task |

Notes:
- `:task test` and `:task build` have built-in fallbacks for Maven/npm/Make projects if not explicitly defined.

## Settings and Configuration

### `:set` command

| Command | Action |
| :--- | :--- |
| `:set nu`, `:set number` | Enable absolute line numbers |
| `:set nonu`, `:set nonumber` | Disable line numbers |
| `:set rnu`, `:set relativenumber` | Enable relative line numbers |
| `:set nornu`, `:set norelativenumber` | Disable relative numbering (back to absolute) |
| `:set list`, `:set nolist` | Toggle whitespace visualization |
| `:set wrap`, `:set nowrap` | Toggle soft wrap |
| `:set hls`, `:set hlsearch` | Enable search highlight |
| `:set nohls`, `:set nohlsearch` | Disable search highlight |
| `:set ai`, `:set autoindent` | Enable auto-indent |
| `:set noai`, `:set noautoindent` | Disable auto-indent |
| `:set et`, `:set expandtab` | Enable expand-tab |
| `:set noet`, `:set noexpandtab` | Disable expand-tab |
| `:set cul`, `:set cursorline` | Enable current-line highlight |
| `:set nocul`, `:set nocursorline` | Disable current-line highlight |
| `:set tabstop=<n>`, `:set ts=<n>` | Set tab size (`1..16`) |
| `:set line.numbers=<mode>` | Set line-number mode (`none`, `absolute`, `relative`, `hybrid`) |
| `:set theme` | Show current theme |
| `:set theme=<name>` | Apply theme |
| `:set colorscheme=<name>` | Apply theme (alias) |
| `:set theme <name>` | Apply theme (space form) |
| `:set colorscheme <name>` | Apply theme (space form) |
| `:set conceallevel=<0|1|2>` | Set markdown conceal level |
| `:set bracketcolor`, `:set bracketcolors` | Toggle bracket pair colorization |
| `:set autopairs`, `:set noautopairs` | Toggle autopairs |
| `:set textwidth=<n>`, `:set tw=<n>` | Set text width |
| `:set scrolloff=<n>`, `:set so=<n>` | Set scrolloff |
| `:set <key>=<value>` | Set arbitrary runtime config key |
| `:set! <key>=<value>` | Set and persist key to `~/.shed/shedrc` |

### Config file commands

| Command | Action |
| :--- | :--- |
| `:settings`, `:shedrc`, `:config` | Open user config file |
| `:config save`, `:config write` | Persist current runtime config to disk |
| `:reload`, `:source` | Reload config from disk |
| `:clean`, `:shedclean` | Remove Shed data under `~/.shed` and reset in-memory history |

## Window, Picker, and UI Commands

| Command | Action |
| :--- | :--- |
| `:split`, `:sp` | Horizontal split |
| `:vsplit`, `:vsp` | Vertical split |
| `:close`, `:clo` | Close active window |
| `:files` | Project file finder |
| `:folder`, `:folders` | Folder chooser + file picker |
| `:grep <text>`, `:rg <text>` | Grep finder (also populates quickfix) |
| `:palette`, `:commands` | Command palette |
| `:undolist`, `:undotree` | Show undo state summary |
| `:themes` | Show built-in themes |
| `:theater off|subtle|full` | Apply dramatic UI preset |
| `:zen` | Toggle zen mode |
| `:minimap` | Toggle minimap panel |
| `:term`, `:terminal` | Open integrated terminal split |

## Quickfix and Diagnostics Commands

| Command | Action |
| :--- | :--- |
| `:copen` | Open quickfix list |
| `:cclose` | Close quickfix list |
| `:cnext`, `:cn` | Next quickfix entry |
| `:cprev`, `:cp` | Previous quickfix entry |
| `:cfirst` | First quickfix entry |
| `:clast` | Last quickfix entry |
| `:cc` | Jump to current quickfix entry |
| `:cc <index>` | Jump to one-based quickfix entry index |
| `:diagnostics`, `:diag`, `:ldiag` | Push current-buffer LSP diagnostics into quickfix |
| `:dnext`, `:dn` | Jump to next diagnostic |
| `:dprev`, `:dp` | Jump to previous diagnostic |

## LSP and Symbol Commands

### Top-level LSP shortcuts

| Command | Action |
| :--- | :--- |
| `:lsp <subcommand>` | Run explicit LSP subcommand |
| `:definition` | LSP go-to-definition |
| `:hover` | LSP hover |
| `:references` | LSP references to quickfix |

### `:lsp` subcommands

| Command | Action |
| :--- | :--- |
| `:lsp completion`, `:lsp complete`, `:lsp comp` | Completion picker (LSP with local fallback) |
| `:lsp definition`, `:lsp def` | Go to definition |
| `:lsp hover` | Show hover info |
| `:lsp references`, `:lsp refs` | Find references and open quickfix |
| `:lsp rename <newName>` | Prepare rename preview |
| `:lsp renameapply`, `:lsp rename!` | Apply pending rename edits |
| `:lsp renamecancel`, `:lsp renameclear` | Discard pending rename |
| `:lsp codeaction [index]`, `:lsp codeactions [index]`, `:lsp actions [index]`, `:lsp ca [index]` | List/apply code actions |
| `:lsp diagnostics`, `:lsp diag` | Same as `:diagnostics` |
| `:lsp status` | Show running servers + errors |
| `:lsp restart [ext]` | Restart LSP for extension (defaults to current buffer extension) |
| `:lsp stop [ext]` | Stop LSP for extension |
| `:lsp servers` | List configured + built-in server mappings |
| `:lsp log` | Show LSP error log |

### Symbol and location helpers

| Command | Action |
| :--- | :--- |
| `:symbols [query]`, `:sym [query]` | Symbol picker for current buffer |
| `:45` | Go to line 45 (any numeric command) |

## Git Commands

### Top-level

| Command | Action |
| :--- | :--- |
| `:git` | Show git status |
| `:git help` | Show git help buffer |

### `:git` subcommands

| Command | Action |
| :--- | :--- |
| `:git status`, `:git st` | Status (`--short --branch`) |
| `:git diff [args]` | Diff |
| `:git log [count]` | Compact graph log (default 20) |
| `:git branch`, `:git branches` | List branches |
| `:git add <paths...>`, `:git stage <paths...>` | Stage paths |
| `:git restore <paths...>`, `:git unstage <paths...>` | Unstage paths |
| `:git commit <message>` | Commit staged changes |
| `:git amend <message>`, `:git amend --no-edit` | Amend latest commit |
| `:git checkout <arg>`, `:git co <arg>` | Checkout branch/path |
| `:git switch <branch>`, `:git sw <branch>` | Switch branch |
| `:git hunk stage [line]` | Stage hunk at current or explicit line |
| `:git hunk unstage [line]` | Unstage hunk at current or explicit line |
| `:git hunk revert [line]` | Revert hunk at current or explicit line |

## Tree Commands

| Command | Action |
| :--- | :--- |
| `:tree` | Toggle tree pane (open/close) |
| `:tree <path>` | Open tree rooted at path |
| `:tree refresh` | Refresh tree |
| `:tree reveal` | Reveal current file in tree |
| `:tree new <path>` | Create file |
| `:tree mkdir <path>` | Create directory |
| `:tree rename <from> <to>` | Rename/move path |
| `:tree rm <path>`, `:tree delete <path>` | Delete file or empty directory |
| `:tree rm! <path>`, `:tree delete! <path>` | Force recursive delete |

## Session and Workspace Commands

| Command | Action |
| :--- | :--- |
| `:session save [name]` | Save session JSON |
| `:session load [name]` | Load session (blocks if unsaved changes exist) |
| `:session load! [name]` | Force load session |
| `:session list` | List sessions |
| `:workspace save [name]`, `:ws save [name]` | Save workspace profile |
| `:workspace load [name]`, `:ws load [name]` | Load workspace profile |
| `:workspace load! [name]`, `:ws load! [name]` | Force load workspace profile |
| `:workspace list`, `:ws list` | List workspace profiles |

## Markdown and Writing Commands

| Command | Action |
| :--- | :--- |
| `:toc` | Open markdown table-of-contents buffer |
| `:outline` | Open markdown outline in split |
| `:toggle`, `:checkbox` | Toggle markdown checkbox under cursor |
| `:table` | Insert default `3x2` markdown table |
| `:table NxM` | Insert `NxM` table |
| `:table align` | Align current markdown table |
| `:table sort N` | Sort table by column `N` ascending |
| `:table sort N desc` | Sort table by column `N` descending |
| `:table insert-col` / `insertcol` / `addcol` | Insert table column after current column |
| `:table delete-col` / `deletecol` / `delcol` | Delete current (or specified) table column |
| `:link` | Insert markdown link template |
| `:img`, `:image` | Insert markdown image template |
| `:conceal 0|1|2`, `:conceallevel 0|1|2` | Set markdown conceal level |
| `:snippets`, `:snippet` | Show snippets for current file type |
| `:bracketcolor`, `:bracketcolors` | Toggle bracket pair colorization |

## Registers, Marks, Help, and Misc

| Command | Action |
| :--- | :--- |
| `:registers`, `:reg` | Show register contents |
| `:yankring`, `:pastepicker`, `:yr` | Yank/delete history picker |
| `:marks` | Show marks for current buffer |
| `:wc`, `:wordcount` | Show line/word/char count |
| `:log`, `:commandlog` | Open command log file |
| `:help [topic]`, `:h [topic]` | Open help buffer |

## Plugin Management Commands

| Command | Action |
| :--- | :--- |
| `:plugin`, `:plugins`, `:plugin list` | List loaded plugins |
| `:plugin packages`, `:plugin pkg` | Show managed plugin package metadata |
| `:plugin reload` | Reload plugins from disk |
| `:plugin info <name>` | Show plugin details |
| `:plugin path` | Show plugin directory + disabled plugins |
| `:plugin enable <name>` | Enable disabled plugin |
| `:plugin disable <name>` | Disable plugin (`.disabled`) |
| `:plugin new <name>` | Create and open plugin template |
| `:plugin install <name> <version> <source> [--checksum=<sha256>] [--pin]` | Install managed plugin package |
| `:plugin update [name]` | Update managed package(s), skipping pinned |
| `:plugin remove <name>`, `:plugin uninstall <name>` | Remove managed package |
| `:plugin pin <name>` | Pin package version |
| `:plugin unpin <name>` | Unpin package version |

## Extended / User-Defined Commands

| Mechanism | What it adds |
| :--- | :--- |
| `command.alias.<new>=<builtin>` in `~/.shed/shedrc` | Adds command aliases resolved before execution |
| `command.user.<name>=<shell>` in `~/.shed/shedrc` | Adds `:<name>` shell-backed user commands |
| `.shed` plugins (`# @command name=shell`) | Adds plugin-defined `:<name>` commands |
