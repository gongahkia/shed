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

In the `:` prompt, paste with `Cmd`/`Ctrl` + `V` and use Left/Right to move the block cursor. Typing a path argument for `:e`, `:edit`, `:w`, `:write`, or `:tree` shows local path suggestions; click one or press Tab to insert it.

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

## Support Information

| Command | Action |
| :--- | :--- |
| `:version`, `:about`, `:buildinfo` | Show version, available build commit/target, and local Java/OS details |

Packaged jars include deterministic version and Java-target manifest entries. `Shed-Commit` is emitted only when the build supplies `-Dshed.build.commit=<commit>`; otherwise the manifest and support display omit it.

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
| `:task`, `:task ui` | Open graphical Tasks/Jobs panel |
| `:task text`, `:task text list` | Show legacy task scratch buffer |
| `:task add <name> <command>` | Save task |
| `:task remove <name>`, `:task rm <name>`, `:task delete <name>` | Remove task |
| `:task dry-run <name>` | Resolve and show task execution plan without starting it |
| `:task run <name>` | Explicitly run named task |
| `:task remote <connection-id> <name>` | Explicitly run named task through a matching connected remote workspace |
| `:task remote-dry-run <connection-id> <name>` | Resolve and show remote task plan without starting it |
| `:task cancel <id>` | Cancel a running task job |

Notes:
- `:task run test` and `:task run build` have built-in fallbacks for Maven/npm/Make projects if not explicitly defined.
- Task schema, variable, shell, quickfix, and presentation policy: [Workspace Tasks](TASKS.md).

## Test Explorer

| Command | Action |
| :--- | :--- |
| `:test`, `:test ui` | Open the docked Tests panel; this does not scan or start a process |
| `:test refresh` | Explicitly detect configured/local runners and discover tests |
| `:test run` | Run every detected/configured adapter in the selected workspace root |
| `:test run <test-id>` | Run one discovered test by its exact id |
| `:test debug <test-id>` | Start the adapter explicitly mapped by that test adapter's `.shedtests` `debug_configuration` |
| `:test failed`, `:test rerun-failed` | Run the failed tests retained for this session |
| `:test cancel` | Cancel running test jobs for the selected root |
| `:test text` | Open a text summary of the session-local test state |
| `:coverage import <report>` | Asynchronously import a local JaCoCo XML, Cobertura XML, LCOV, or Go `-coverprofile` report for the selected root |
| `:coverage clear` | Clear imported session-local coverage for the selected root |
| `:coverage text` | Open imported coverage totals and per-file line summaries |

The Tests panel supports root selection, status/text filtering, Refresh, Run All, Run Selection, Debug Selection, Rerun Failed, Cancel, **Import Coverage**, **Clear Coverage**, output inspection, and source navigation. Imports are explicit and local; covered/uncovered lines render in the active editor gutter. Tests are discovered only after an explicit refresh. Failure locations are also published to Problems under `test:<adapter>` without replacing quickfix entries. Adapter declarations, direct argv overrides, debug mappings, report-cache policy, and supported built-ins are in [Testing](TESTS.md).

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
| `:set! <key>=<value>` | Set and persist key to `~/.shed/config.toml` |

### Config file commands

| Command | Action |
| :--- | :--- |
| `:settings`, `:config` | Open graphical Settings Editor |
| `:config file`, `:config toml`, `:config text` | Open user config file |
| `:config inspector`, `:config ui` | Open graphical Settings Editor |
| `:config save`, `:config write` | Persist current runtime config to disk |
| `:config status` | Show the current config load or recovery report |
| `:reload`, `:source` | Reload config from disk |
| `:clean`, `:shedclean` | Remove Shed data under `~/.shed` and reset in-memory history |

## Window, Picker, and UI Commands

| Command | Action |
| :--- | :--- |
| `:split`, `:sp` | Horizontal split (`Cmd+D`) |
| `:vsplit`, `:vsp` | Vertical split (`Cmd+Shift+D`) |
| `:close`, `:clo` | Close active window (`Cmd+W`) |
| `:files` | Project file finder |
| `:folder`, `:folders` | Folder chooser + file picker |
| `:grep <text>`, `:rg <text>` | Start cancellable incremental workspace text search; opens quickfix on completion |
| `:largefile`, `:lf` | Show active [large-file mode](LARGE_FILE_SUPPORT.md), limits, and remediation |
| `:projectreplace settings` | Show persisted project-replace safety controls |
| `:projectreplace`, `:projectreplace ui` | Open the docked Project Replace review panel |
| `:projectreplace text <subcommand>` | Run the legacy text workflow |
| `:projectreplace enable`, `:projectreplace disable` | Persist the project-replace opt-in gate |
| `:projectreplace preview /find/replacement/` | Build an in-memory literal replacement preview; no file is written |
| `:projectreplace replace /find/replacement/ [confirm]` | Run the non-preview path only when preview is deliberately disabled |
| `:projectreplace file <id> [on\|off\|toggle]` | Select or deselect all previewed matches in a file |
| `:projectreplace match <id> [on\|off\|toggle]` | Select or deselect one previewed match |
| `:projectreplace apply confirm` | Start an explicit cancellable atomic apply for selected unchanged files when confirmation is required |
| `:projectreplace cancel` | Discard the current replacement preview |
| `:projectreplace preview-required on\|off`, `:projectreplace confirm on\|off`, `:projectreplace backup on\|off` | Persist preview, confirmation, or backup controls |
| `:projectreplace scope workspace\|current-file` | Persist replacement preview scope |
| `:palette`, `:commands` | Command palette. Alongside raw `:command` entries, it exposes direct named actions for graphical and contextual surfaces such as Settings, Workspace Folders, Git Changes, Tests, Debug, Language Services, coverage import, formatter policy, code actions, peek, hierarchies, Markdown Preview, and snippets. |
| `:undolist`, `:undotree` | Show undo state summary |
| `:themes` | Show built-in themes |
| `:zen` | Toggle Goyo layout with Limelight; restores Limelight's prior state when disabled |
| `:goyo` | Toggle the distraction-free layout without changing Limelight; hides status/line numbers/minimap/tree/tool windows while retaining every pane and split |
| `:limelight` | Toggle paragraph focus dimming; the current paragraph or selected text stays bright |
| `:minimap` | Toggle minimap panel |
| `:term`, `:terminal` | Open integrated terminal split; terminal input owns focus, uses the system clipboard (`Cmd-C`/`Cmd-V` on macOS, `Ctrl-Shift-C`/`Ctrl-Shift-V` elsewhere), and ignores zero-size resize events |

## Workspace Index Commands

| Command | Action |
| :--- | :--- |
| `:workspace index`, `:workspace index status` | Show selected search source, persistent-index preference, cache status, and local cache cost |
| `:workspace index enable` | Persist the explicit persistent-index preference without building an index |
| `:workspace index disable` | Persist the ad-hoc default without deleting existing cache files |
| `:workspace index benchmark` | Start an explicit cancellable local index-build measurement |

## Performance and Local Diagnostics Commands

| Command | Action |
| :--- | :--- |
| `:perf`, `:perf status` | Show local-only timing, diagnostic-log, and workspace-index benchmark status with measurement limits |
| `:perf diagnostics`, `:perf log` | Show the newest readable structured entries from the local diagnostic log |
| `:perf benchmark` | Start the existing cancellable local workspace-index benchmark |

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
| `:problems`, `:problems ui` | Open the docked, detachable Problems view of live LSP diagnostics plus retained quickfix producers |
| `:problems text` | Open the current aggregated Problems list as a scratch buffer |
| `:problems all\|errors\|warnings\|info\|hints\|other` | Open Problems with a severity filter |
| `:dnext`, `:dn` | Jump to next diagnostic |
| `:dprev`, `:dp` | Jump to previous diagnostic |

## Debug Commands

| Command | Action |
| :--- | :--- |
| `:debug`, `:debug ui` | Open the docked Debug panel |
| `:debug text [subcommand]` | Open legacy debug scratch output |
| `:debug select <name>`, `:debug start [name]`, `:debug stop`, `:debug restart [name]` | Control an explicitly configured DAP session |

## LSP and Symbol Commands

### Top-level LSP shortcuts

| Command | Action |
| :--- | :--- |
| `:lsp <subcommand>` | Run explicit LSP subcommand |
| `:definition` | LSP go-to-definition |
| `:typedefinition`, `:typedef` | LSP go-to-type-definition |
| `:hover` | LSP hover |
| `:references` | LSP references to quickfix |

### `:lsp` subcommands

| Command | Action |
| :--- | :--- |
| `:lsp completion`, `:lsp complete`, `:lsp comp` | Completion picker (LSP with local fallback) |
| Insert-mode typing | Debounced suggestions after two word characters or a server-advertised trigger character; LSP details resolve only for the selected item |
| Insert-mode `Ctrl-n` | Manually open the asynchronous completion popup, including at an empty prefix; user snippets, LSP, and cached open-buffer words are merged; Up/Down or Ctrl-p/Ctrl-n selects, Tab/Enter applies, Escape cancels |
| After any snippet completion | Tab/Shift-Tab moves through ordered placeholders; positions survive ordinary edits |
| Insert `(` or `,` | Shows capability-gated asynchronous signature help; the next edit or Escape cancels it |
| `:lsp definition`, `:lsp def` | Go to definition |
| `:lsp typedefinition`, `:lsp type`, `:lsp typedef` | Go to type definition |
| `:lsp peek definition`, `:peek definition` | Asynchronously show a temporary read-only definition split; Enter opens and Escape restores the layout |
| `:lsp peek type`, `:peek type` | Asynchronously show a temporary read-only type-definition split |
| `:lsp calls incoming\|outgoing` | Open searchable lazy LSP call hierarchy |
| `:lsp typehierarchy supertypes\|subtypes` | Open searchable lazy LSP type hierarchy |
| `:lsp hover` | Show hover info |
| `:lsp semantic`, `:lsp semantictokens` | Show current-document semantic tokens when supported; supported tokens also render inline by default |
| `:lsp inlay`, `:lsp inlayhints` | Show current-document inlay hints when supported; supported hints also render inline by default |
| `:lsp format` | Apply server formatting edits to the current document when supported |
| `:format`, `:fmt` | Format with the current extension's selected formatter policy |
| `:formatter`, `:formatpolicy` | Edit the current extension's formatter mode, direct command, args, and format-on-save policy |
| `:lsp references`, `:lsp refs` | Find references and open quickfix |
| `:lsp rename <newName>` | Prepare rename preview |
| `:lsp renameapply`, `:lsp rename!` | Apply pending rename edits |
| `:lsp renamecancel`, `:lsp renameclear` | Discard pending rename |
| `:lsp codeaction [index]`, `:lsp codeactions [index]`, `:lsp actions [index]`, `:lsp ca [index]` | Asynchronously show diagnostic-anchored actions at the caret; index prepares that action's preview |
| `:lsp codeaction apply` | Apply the reviewed code-action preview |
| `:lsp diagnostics`, `:lsp diag` | Same as `:diagnostics` |
| `:lsp status` | Show running servers + errors |
| `:lsp restart [ext]` | Restart LSP for extension (defaults to current buffer extension) |
| `:lsp stop [ext]` | Stop LSP for extension |
| `:lsp servers` | List configured + built-in server mappings |
| `:lsp manage`, `:lsp manage ui` | Open the local Language Services panel; each install/update requires a fresh GUI confirmation |
| `:languageservices`, `:language-services`, `:lspmanage` | Command-Palette-friendly alias that opens Language Services |
| `:lsp manage status` | Show managed-LSP status without probing or network access |
| `:lsp log` | Show LSP error log |

### Symbol and location helpers

| Command | Action |
| :--- | :--- |
| `:symbols [query]`, `:sym [query]` | Asynchronous LSP document-symbol picker for the current file; falls back to local heuristics when unavailable |
| `:workspace symbols <query>`, `:workspace sym <query>` | Explicit asynchronous query across active LSP workspace servers; no background symbol index is built |
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
| `:git workbench`, `:git changes`, `:git ui` | Open docked Git Changes panel |
| `:git worktree`, `:git worktrees`, `:git stash`, `:git stashes` | Open graphical worktree and stash controls |
| `:git text [subcommand]` | Run Git command with legacy text presentation |
| `:git status`, `:git st` | Status (`--short --branch`) |
| `:git diff [args]` | Diff |
| `:git log [count]` | Colored topology graph (default 20); click a commit for its details. Falls back to the ASCII log when graphical Git history is disabled. |
| `:git branch`, `:git branches` | List branches; press Enter on a local branch to switch to it |
| `:git add <paths...>`, `:git stage <paths...>` | Stage paths |
| `:git restore <paths...>`, `:git unstage <paths...>` | Unstage paths |
| `:git commit <message>` | Commit staged changes |
| `:git amend <message>`, `:git amend --no-edit` | Amend latest commit |
| `:git checkout <arg>`, `:git co <arg>` | Checkout branch/path |
| `:git switch <branch>`, `:git sw <branch>` | Switch branch |
| `:git permalink [line]`, `:git link [line]` | Show an immutable current-file permalink for supported GitHub, GitLab, or Bitbucket `origin` remotes |
| `:git hunk stage [line]` | Stage hunk at current or explicit line |
| `:git hunk unstage [line]` | Unstage hunk at current or explicit line |
| `:git hunk revert [line]` | Revert hunk at current or explicit line |

## Update Commands

| Command | Action |
| :--- | :--- |
| `:update`, `:update status` | Show local consent, configuration, trusted-metadata, and error state; no request |
| `:update consent`, `:update enable` | Show consent boundary, then enable signed metadata checks |
| `:update disable` | Revoke consent and cancel the tracked check |
| `:update check` | Start a consent-gated signed metadata check |
| `:update open` | Open a verified platform installer URL in the system browser |
| `:update rollback` | Report the no-op rollback boundary because Shed never replaces itself |

See [Update Checks](UPDATES.md) for endpoint/key configuration and metadata validation.

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
| `:workspace roots`, `:workspace ui` | Show/manage workspace folders |
| `:workspace add <folder>` | Add a local folder without changing the active tree |
| `:workspace open <folder>`, `:workspace switch <folder\|index>` | Make a workspace folder active and show its tree |
| `:workspace remove <folder\|index>` | Remove a folder from the workspace; files are retained |
| `:workspace import <manifest>` | Replace folders from a validated `.shed-workspace` or `.code-workspace` folder list |
| `:workspace export <manifest>` | Write the current folders as a portable manifest |

For a file inside a configured workspace folder, task discovery, extension SCM, Dev Container commands, and extension workspace integrations use the deepest folder containing that file. For a scratch or outside file, they use the selected workspace folder. The Tests panel intentionally exposes its own root selector, so a user can inspect or run a sibling project's tests without changing editors.

Portable-manifest format, safety boundary, and its distinction from private session profiles: [Workspace Manifests](WORKSPACE_MANIFESTS.md).

## Markdown and Writing Commands

| Command | Action |
| :--- | :--- |
| `:toc` | Open markdown table-of-contents buffer |
| `:outline` | Open markdown outline in split |
| `:markdown preview`, `:mdpreview` | Open a live native Markdown preview beside the source buffer |
| `:markdown refresh` | Re-render the open Markdown preview |
| `:markdown close` | Close the open Markdown preview |
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
| `:snippets open`, `:snippets edit` | Create if needed and open global user snippets |
| `:bracketcolor`, `:bracketcolors` | Toggle bracket pair colorization |

Markdown preview is native, live, and side-by-side; it renders CommonMark + GFM, TeX math, local images, and Mermaid fences while escaping raw HTML and never fetching remote assets. See [Markdown Preview](MARKDOWN_PREVIEW.md).

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

## Extensions, SCM, Remote Workspaces, and Notebooks

| Command | Action |
| :--- | :--- |
| `:extension`, `:extension status` | Show installed Java extension receipts, errors, and contributions |
| `:extension install <path-or-https> [--checksum=<sha256>]` | Explicitly install a Java extension JAR |
| `:extension enable\|disable\|remove <id>`, `:extension reload` | Change local extension activation |
| `:view`, `:view list` | List extension tool views |
| `:view <extension:id>` | Open a contributed docked tool view |
| `:customeditor`, `:customeditor list` | List contributed custom text/binary editors |
| `:customeditor reopen` | Reopen the current file with its matching contributed editor |
| `:scm`, `:scm list` | List SCM providers that support the active workspace |
| `:scm status [provider]` | Show SCM status from one/all supported providers |
| `:scm <provider> <declared-action> [args]` | Run an explicitly declared provider action |
| `:remote providers` | List built-in and contributed remote workspace providers |
| `:remote open <uri>` | Explicitly connect a remote workspace as a local working tree/mirror |
| `:remote pull\|push\|close <id>` | Synchronize or disconnect a remote workspace |
| `:remote exec <id> <command...>` | Explicitly run a direct-argv command in a connection when its provider supports execution |
| `:remote terminal <id> [command...]` | Open an explicit interactive terminal at the connection root when its provider supports it |
| `:container status` | Show the active workspace's `.devcontainer/devcontainer.json` when present |
| `:container up` | Explicitly run the local `devcontainer up` workflow as a cancellable job |
| `:container exec <command...>`, `:container terminal [command...]` | Explicitly run/open a direct-argv command through the local Dev Container CLI |
| `:container open <container> <absolute-path>` | Open an explicit Docker container mirror |
| `:integration`, `:integration list` | List supporting database, deployment, collaboration, and container extension integrations |
| `:integration <extension:id> help` | Show provider-declared actions |
| `:integration <extension:id> <action> [arguments]` | Run an explicitly declared workspace-integration action |
| `:notebook open`, `:notebook run`, `:notebook raw` | Open, explicitly execute, or show raw JSON for the current `.ipynb` file |
| `:terminal list`, `:terminal profile <id>` | Show/open terminal profiles |
| `:terminal commands`, `:terminal cwd` | Show in-memory Bash/Zsh/Fish shell-integration events or latest reported cwd |

See [Java Extensions](EXTENSIONS.md), [Workspace Integrations](WORKSPACE_INTEGRATIONS.md), [Remote Workspaces](REMOTE_WORKSPACES.md), [Jupyter Notebooks](NOTEBOOKS.md), and [Terminal](TERMINAL.md).

## Extended / User-Defined Commands

| Mechanism | What it adds |
| :--- | :--- |
| `"command.alias.<new>" = "<builtin>"` in `~/.shed/config.toml` | Adds command aliases resolved before execution |
| `"command.user.<name>" = "<shell>"` in `~/.shed/config.toml` | Adds `:<name>` shell-backed user commands |
| `.shed` plugins (`# @command name=shell`) | Adds plugin-defined `:<name>` commands |
