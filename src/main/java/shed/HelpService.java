package shed;

public class HelpService {
    public String getHelpText(String topic, String version) {
        String normalizedTopic = topic == null ? "" : topic.trim().toLowerCase();
        if (normalizedTopic.isEmpty()) {
            return "Shed v" + version + "\n\n" +
                   "NORMAL MODE\n" +
                   "  h/j/k/l        Move left/down/up/right\n" +
                   "  w/b/e          Move by word\n" +
                   "  W/B/E ge/gE    WORD and backward-end motions\n" +
                   "  f/F/t/T ; ,    Find/till-char and repeat\n" +
                   "  0/^/$ g0/g$ g_ Line start/indent/end variants\n" +
                   "  gg/G 50%       File start/end and percent jump\n" +
                   "  { } ( ) H M L  Paragraph, sentence, screen motions\n" +
                   "  zt/zz/zb       Scroll current line to top/center/bottom\n" +
                   "  i/a/A/I/o/O    Insert variants\n" +
                   "  v/V/R          Visual/visual-line/replace\n" +
                   "  yy/dd/cc       Yank/delete/change line\n" +
                   "  dw/cw diw ci\"  Motion and text-object operators\n" +
                   "  D/C/Y r{char}  End-of-line yank/delete/change and replace-char\n" +
                   "  >>/<</==       Indent/dedent/auto-indent line\n" +
                   "  J/gJ           Join lines with/without space\n" +
                   "  cs/ds/ys       Surround change/delete/add\n" +
                   "  q{a-z}/q{A-Z} @a @@  Macro record, append, and playback\n" +
                   "  m{a-z}         Set mark\n" +
                   "  '{a-z}/`{a-z}  Jump to mark\n" +
                   "  Ctrl-o/Ctrl-i  Jump back/forward\n" +
                   "  g;/g,          Previous/next change\n" +
                   "  \"ap \"+p      Register-targeted edit and paste\n" +
                   "  p/P            Paste after/before\n" +
                   "  u/Ctrl-r       Undo/redo\n" +
                   "  /pattern       Search forward\n" +
                   "  ?pattern       Search backward\n" +
                   "  n/N            Next/previous match\n" +
                   "  * / #          Search word under cursor\n" +
                   "  .              Repeat last command\n\n" +
                   "PLAIN PROFILE\n" +
                   "  keymap.profile=plain keeps native text input active and bypasses Vim modes.\n" +
                   "  keymap.profile=emacs enables fixed Emacs chords without Vim modes.\n" +
                   "  F1 or :help plain/:help emacs lists fixed shortcuts.\n\n" +
                   "COMMANDS\n" +
                   "  :w [file]      Write current buffer\n" +
                   "  :q / :q!       Close active editor window\n" +
                   "  :wq / :x       Write, then close active editor window\n" +
                   "  :qa / :wqa     Quit all editor windows\n" +
                   "  :e file        Edit file\n" +
                   "  :bn / :bp      Next/previous buffer\n" +
                   "  :bd            Delete buffer\n" +
                   "  :recent        Show recent files\n" +
                   "  :settings      Open user settings file\n" +
                   "  :config save   Persist current runtime config to ~/.shed/config.toml\n" +
                   "  :config heal   Persist reviewed deterministic config repairs\n" +
                   "  :config status Show config load/recovery details\n" +
                   "  :config inspector Open typed settings inspector\n" +
                   "  :log           Open command log file\n" +
                   "  :session ...   Session save/load/list\n" +
                   "  :workspace ... Workspace profiles or index status/controls\n" +
                   "  :clean         Remove Shed data files\n" +
                   "  :version       Show local version and support metadata\n" +
                   "  :drop cmd      Run async command with current file path\n" +
                   "  :task ...      Run/save project tasks with quickfix integration\n" +
                   "  :open [target] Open a file chooser or a workspace folder\n" +
                   "  :files         File finder\n" +
                   "  :folder        Folder finder\n" +
                   "  :tree [path]   Toggle/open file tree pane\n" +
                   "  :tree refresh  Refresh tree pane from root\n" +
                   "  :tree reveal   Reveal current file in tree root\n" +
                   "  :tree new p    Create file at path p\n" +
                   "  :tree mkdir p  Create directory at path p\n" +
                   "  :tree rename a b Rename path a -> b\n" +
                   "  :tree rm p     Delete file/empty directory\n" +
                   "  :buffers       Buffer finder\n" +
                   "  :grep text     Grep finder\n" +
                   "  :projectreplace ... Preview/apply selected project replacements\n" +
                   "  :copen         Open quickfix list\n" +
                   "  :cnext/:cprev  Next/previous quickfix entry\n" +
                   "  :cc [n]        Jump to quickfix entry\n" +
                   "  :lsp ...       LSP commands (def/type/hierarchies/refs/rename/actions)\n" +
                   "  :format        Format using current file-type policy\n" +
                   "  :formatter     Edit current file-type formatter policy\n" +
                   "  :lsp status    Show running LSP servers\n" +
                   "  :lsp servers   List all configured + builtin LSP servers\n" +
                   "  :lsp manage    Open managed Language Services\n" +
                   "  :lsp restart   Restart LSP server for current file type\n" +
                   "  :diagnostics   Push diagnostics into quickfix\n" +
                   "  :dnext/:dprev  Jump next/prev diagnostic\n" +
                   "  :symbols [q]   Symbol picker (class/function/heading)\n" +
                   "  :git ...       Git status/diff/log/add/commit; workbench, conflicts, and history UI\n" +
                   "  :github ...    Local capability, consent, or consent-gated PR discovery\n" +
                   "  :update ...    Consent-gated signed update metadata controls\n" +
                   "  :s/:vs         Split below/right\n" +
                   "  Ctrl-w s/v/c   Split/vertical-split/close window\n" +
                   "  Ctrl-w h/j/k/l Move window focus\n" +
                   "  :registers     Show registers\n" +
                   "  :yankring      Pick from yank/delete history and paste\n" +
                   "  :marks         Show marks\n" +
                   "  :themes        Show built-in themes\n" +
                   "  :zen           Toggle Goyo layout with Limelight\n" +
                   "  :goyo          Toggle Goyo layout\n" +
                   "  :limelight     Toggle paragraph focus dimming\n" +
                   "  :reload        Reload ~/.shed/config.toml now\n" +
                   "  :config heal   Persist reviewed deterministic config repairs\n" +
                   "  :config status Show config load/recovery details\n" +
                   "  :config reference Open generated typed settings reference\n" +
                   "  :help settings  Open generated typed settings reference\n" +
                   "  :normal keys   Replay normal keys\n" +
                   "  :!cmd          Run shell command (async)\n" +
                   "  :set nu        Enable line numbers\n" +
                   "  :set theme=x   Switch color theme\n" +
                   "  :set k=v       Set any config key in-memory\n" +
                   "  :set! k=v      Set and persist key to ~/.shed/config.toml\n" +
                   "  :keymap        Open searchable keymap inspector\n" +
                   "  :keymap list   Show effective bindings and precedence\n" +
                   "  :45            Go to line 45\n" +
                   "  :1,5d          Delete a line range\n" +
                   "  :s/a/b         Substitute current line\n" +
                   "  :1,5s/a/b/g    Substitute a range\n" +
                   "  :%s/a/b/g      Substitute whole buffer\n\n" +
                   "SETTINGS KEYS\n" +
                   "  project override file: .shed.toml (nearest parent)\n" +
                   "  project.config.allow.unsafe=false limits local overrides to UI/editor keys\n" +
                   "  tree.delete.protect.critical=true blocks deleting /, home, and cwd via :tree rm\n" +
                   "  ui.whichkey.hints=true shows prefix-key hints (g/z/Ctrl-w/...)\n" +
                   "  first-open trust prompts gate local .shed.toml per project\n" +
                   "  \"command.alias.<name>\" = \"<builtin>\"\n" +
                   "  \"keybind.<mode>.<lhs>\" = \"<rhs>\"\n" +
                   "  modes: normal/insert/visual/visual_line/replace/command/search/global\n" +
                   "  tokens: <esc> <enter> <tab> <space> <bs> <del> <up>/<down>/<left>/<right> <c-x>\n\n" +
                   "note: this is a help buffer. use :q to return.\n";
        }

        switch (normalizedTopic) {
            case "keymap":
            case "keymaps":
                return "Help: keymaps\n\n"
                    + "keymap.profile=vim is the default modal profile.\n"
                    + "keymap.profile=plain provides non-modal native text input.\n"
                    + "keymap.profile=emacs provides Emacs navigation and Ctrl-X chords.\n"
                    + "Use :help plain or :help emacs for profile bindings.\n"
                    + ":keymap opens a searchable GUI with effective bindings and conflicts.\n"
                    + ":keymap list [query] shows the same surface in a buffer.\n"
                    + ":keymap set <scope> <lhs>=<rhs> saves a validated Vim overlay.\n"
                    + ":keymap reset <scope> <lhs> removes that persisted overlay.\n"
                    + "Vim precedence is scope-specific overlay, global overlay, then built-in dispatch.\n"
                    + "Plain and Emacs fixed bindings bypass Vim overlays.\n";
            case "plain":
                return "Help: Plain keymap\n\n"
                    + "Set keymap.profile=plain in config.toml or with :set keymap.profile=plain.\n"
                    + "Text input, navigation, selection, clipboard, undo, and redo use native Swing bindings.\n"
                    + "Ctrl/Cmd-S       Save current file\n"
                    + "Ctrl/Cmd-O/P     Find file\n"
                    + "Ctrl/Cmd-Shift-P Command palette\n"
                    + "Ctrl/Cmd-B       Buffer picker\n"
                    + "Ctrl/Cmd-W       Close active split\n"
                    + "F1               This help\n\n"
                    + "Plain bypasses Vim mode handling and keybind.<mode> remaps.\n";
            case "emacs":
                return "Help: Emacs keymap\n\n"
                    + "Set keymap.profile=emacs in config.toml or with :set keymap.profile=emacs.\n"
                    + "C-f/C-b/C-n/C-p     Character and line navigation\n"
                    + "C-a/C-e, M-f/M-b    Line and word navigation\n"
                    + "C-v/M-v, M-</M->    Page and file navigation\n"
                    + "C-d/C-k/C-w/M-w/C-y Delete, kill, copy, and yank\n"
                    + "C-x C-s/C-f/C-b/C-c Save, find file, buffers, quit\n"
                    + "C-x b/k             Buffers or kill current buffer\n"
                    + "M-x                 Command palette\n"
                    + "C-g                 Cancel a pending C-x chord\n"
                    + "C-h or F1           This help\n\n"
                    + "C-x accepts one next key; unsupported chords are ignored. Emacs bypasses Vim mode handling and keybind.<mode> remaps.\n";
            case "windows":
            case "split":
            case "vsplit":
                return "Help: windows\n\n"
                    + ":s / :split / :sp creates a horizontal split.\n"
                    + ":vs / :vsplit / :vsp creates a vertical split.\n"
                    + ":close closes the active split when more than one window exists.\n"
                    + ":window next / previous cycles split focus; grow / shrink [percent] resizes the active split.\n"
                    + "Ctrl-w s/v/c mirrors the split commands.\n"
                    + "Ctrl-w h/j/k/l changes window focus.\n"
                    + "Ctrl-w w cycles focus and Ctrl-w = equalizes split ratios.\n";
            case "registers":
            case "reg":
                return "Help: registers\n\n"
                    + "Use \"{register} before yank/delete/change/paste.\n"
                    + "Supported special registers: \", 0, %, :, ., +, *, _.\n"
                    + "Named registers a-z and A-Z are also supported.\n"
                    + ":registers opens a scratch buffer with current register contents.\n";
            case "macros":
            case "macro":
                return "Help: macros\n\n"
                    + "q{a-z} records into a named register; q{A-Z} appends to that register.\n"
                    + "q stops recording. Recorded Insert-mode keys are replayed as text.\n"
                    + "[count]@{register} replays a macro count times; @@ replays the last executed macro.\n"
                    + "Macro playback is recursion-limited to avoid runaway loops.\n";
            case "textobjects":
            case "text-objects":
            case "objects":
                return "Help: text objects\n\n"
                    + "Supported forms include iw/aw, iW/aW, ip/ap, is/as,\n"
                    + "quoted objects for \", ', ` and bracket objects for () [] {} <>.\n"
                    + "Use them with d/c/y, for example diw, ci\", ya(, or dap.\n";
            case "surround":
                return "Help: surround\n\n"
                    + "cs{old}{new} changes an existing surround pair.\n"
                    + "ds{char} removes a surround pair.\n"
                    + "ys{object}{char} adds a surround around a supported text object.\n"
                    + "Examples: cs\"', ds), ysw].\n";
            case "lsp":
            case "completion":
                return "Help: LSP\n\n"
                    + "USAGE\n"
                    + "  Ctrl-n (insert)  async completion; Tab/Enter applies, Escape cancels\n"
                    + "                    selected LSP items show detail and documentation\n"
                    + "                    stale responses are ignored\n"
                    + "  Tab/Shift-Tab     move through unchanged LSP snippet placeholders\n"
                    + "  ( or , (insert)  async signature help; the next edit cancels it\n"
                    + "  :lsp definition  go to definition\n"
                    + "  :lsp type definition  go to type definition\n"
                    + "  :lsp implementation  go to implementation; multiple results use quickfix\n"
                    + "  :lsp highlights [clear]  highlight server-reported symbol occurrences\n"
                    + "  :lsp calls incoming|outgoing  searchable lazy call hierarchy\n"
                    + "  :lsp typehierarchy supertypes|subtypes  searchable lazy type hierarchy\n"
                    + "  :lsp hover       show hover info\n"
                    + "  :lsp references  find references\n"
                    + "  :lsp rename X    preview rename edits for symbol -> X\n"
                    + "  :lsp renameapply apply pending rename preview\n"
                    + "  :lsp renamecancel discard pending rename preview\n"
                    + "  :lsp codeaction  show diagnostic-anchored code actions\n"
                    + "  :diagnostics     push diagnostics to quickfix\n"
                    + "  :dnext/:dprev    jump through diagnostics\n"
                    + "  :coverage import <report>  import local coverage\n\n"
                    + "MANAGEMENT\n"
                    + "  :lsp status      show running servers and errors\n"
                    + "  :lsp servers     list configured (config.toml) + builtin servers\n"
                    + "  :lsp manage      open managed Language Services; installs require approval\n"
                    + "  :lsp manage manual <ext>  show config.toml user-managed alternative\n"
                    + "  :lsp restart [ext] restart server (default: current buffer ext)\n"
                    + "  :lsp stop [ext]  stop a server\n"
                    + "  :lsp log         show LSP error log\n\n"
                    + "  :format          format using LSP or configured external formatter\n"
                    + "  :formatter       edit current file-type formatter policy\n\n"
                    + "CONFIGURATION\n"
                    + "  \"lsp.<ext>.command\" = \"<binary>\"   server command in ~/.shed/config.toml\n"
                    + "  \"lsp.<ext>.args\" = \"<flags>\"       server arguments\n"
                    + "  Builtin servers: rs py js jsx ts tsx go c cpp h hpp\n";
            case "symbols":
            case "symbol":
            case "sym":
                return "Help: symbols\n\n"
                    + ":symbols opens a quick picker of classes/functions/headings in the current buffer.\n"
                    + ":symbols <query> fuzzy-filters before opening the picker.\n"
                    + "Selecting an entry jumps to its line.\n"
                    + "Current-symbol breadcrumbs are shown in the status bar.\n";
            case "task":
            case "tasks":
                return "Help: tasks\n\n"
                    + "Tasks are saved per project in .shedtasks; canonical files use validated TOML.\n"
                    + ":task list shows saved tasks.\n"
                    + ":task add <name> <command> saves a task.\n"
                    + ":task remove <name> removes a task.\n"
                    + ":task <name> or :task run <name> executes a task asynchronously.\n"
                    + ":task remote <connection-id> <name> explicitly runs a task in a matching connected workspace.\n"
                    + ":task container <name> explicitly runs a task in the active project's running Dev Container.\n"
                    + "Output is parsed into quickfix when lines match file:line:col:message.\n"
                    + "Built-in fallback names are supported: :task test and :task build.\n";
            case "compose":
            case "docker-compose":
                return "Help: Docker Compose\n\n"
                    + ":compose status reads a local root Compose configuration without contacting Docker.\n"
                    + ":compose up/build [service...], ps, services, and logs [service...] are explicit jobs.\n"
                    + ":compose exec <service> <command...> runs direct argv; :compose terminal opens an interactive service shell.\n"
                    + ":compose redeploy <service> builds and recreates that service; :compose down does not forward volume/image deletion flags.\n";
            case "docker":
            case "containers":
                return "Help: Docker containers\n\n"
                    + ":docker opens the themed Docker workbench; :docker list keeps the text listing.\n"
                    + ":docker inspect|start|stop|restart <container> are explicit jobs.\n"
                    + ":docker logs <container> [lines], :docker exec <container> <command...>, and :docker terminal <container> [command...] work with a named local container.\n"
                    + ":docker ui and :docker workbench are explicit aliases for the Docker workbench.\n:docker open <container> <absolute-container-path> creates a local mirror workspace.\n";
            case "container":
            case "devcontainer":
                return "Help: Dev Container\n\n"
                    + ":container build, up, lifecycle, stop, and down use the installed Dev Container CLI for the active workspace.\n"
                    + ":container connect routes new terminals and tasks through the running development container for this application session.\n"
                    + ":container exec <command...> and :container terminal [command...] are explicit container actions.\n";
            case "remote":
            case "remotes":
                return "Help: remote workspaces\n\n"
                    + ":remote open <uri> creates a local mirror. :remote reconnect <id> refreshes that connection.\n"
                    + ":remote bootstrap <ssh-uri> creates the requested remote SSH workspace directory without installing software.\n"
                    + ":remote sync start <id> [seconds] enables opt-in automatic pulls; :remote sync stop <id> stops them.\n"
                    + ":remote exec runs an explicit command in a background job; :remote use routes new terminals and tasks to a connected workspace.\n";
            case "database":
            case "db":
                return "Help: PostgreSQL\n\n"
                    + ":database status is local-only and opens no connection.\n"
                    + ":database query <quoted-sql>, tables, and file <workspace-relative.sql> start explicit psql jobs.\n"
                    + ":database terminal opens interactive psql. Shed stores no connection strings or credentials.\n";
            case "git":
                return "Help: git\n\n"
                    + ":git shows status.\n"
                    + ":git diff [args], :git log [count], :git branch show repository state.\n"
                    + ":git add|stage <paths...>, :git restore|unstage <paths...> modify staging.\n"
                    + ":git checkout <arg>, :git switch <branch> move HEAD.\n"
                    + ":git permalink [line|start-end] shows an immutable link for the current GitHub/GitLab/Bitbucket file; GitHub/GitLab support ranges.\n"
                    + ":git commit <message>, :git amend <message|--no-edit> create/update commits.\n";
            case "update":
            case "updates":
                return "Help: update checks\n\n"
                    + ":update status is local-only and shows consent/configuration/check state.\n"
                    + ":update consent enables a signed metadata check only after confirmation.\n"
                    + ":update disable revokes consent and cancels the tracked check.\n"
                    + ":update check verifies signed HTTPS metadata in a background job.\n"
                    + ":update open delegates a verified installer URL to the system browser.\n"
                    + "Shed never downloads, installs, replaces, or restarts itself.\n";
            case "tree":
                return "Help: tree\n\n"
                    + ":tree toggles the left side tree pane open/closed.\n"
                    + ":tree <path> uses a specific root path when opening.\n"
                    + ":tree refresh reloads the current tree root.\n"
                    + ":tree reveal sets tree root to the current file's directory.\n"
                    + ":tree new/mkdir/rename/rm perform file operations from command mode.\n"
                    + "tree.delete.protect.critical blocks deleting root/home/cwd unless disabled.\n"
                    + "Use arrows or j/k to move, Left/Right to collapse/expand, and Enter or o to open the selected file.\n";
            case "session":
            case "sessions":
                return "Help: sessions\n\n"
                    + ":session save [name] stores open file-backed buffers.\n"
                    + ":session load [name] restores a saved session.\n"
                    + ":session load! [name] restores even when buffers are modified.\n"
                    + ":session list lists all saved sessions.\n"
                    + ":workspace save/load/list is similar, with project-profile naming + UI settings.\n"
                    + ":workspace index status shows ad-hoc versus persistent-index status and cost.\n"
                    + ":workspace index enable/disable persists the explicit index preference; :workspace index benchmark measures an explicit build.\n"
                    + "Configure session.restore.on.start/session.autoload/session.dir in ~/.shed/config.toml.\n";
            case "perf":
            case "performance":
                return "Help: local performance diagnostics\n\n"
                    + ":perf opens the local-only timing, diagnostic-log, and benchmark dashboard.\n"
                    + ":perf diagnostics shows readable entries from the local diagnostic log.\n"
                    + ":perf benchmark starts a cancellable workspace-index measurement when a workspace is available.\n"
                    + "Timings and heap observations are local measurements, not portability guarantees.\n";
            case "keybind":
            case "keybinding":
            case "keybindings":
                return "Help: keybindings\n\n"
                    + "Define in ~/.shed/config.toml as \"keybind.<mode>.<lhs>\" = \"<rhs>\".\n"
                    + "LHS/RHS accept raw characters and tokens like <esc>, <enter>, <c-w>.\n"
                    + "Use mode global for mappings active in every mode.\n"
                    + "Use value <nop> to disable a key.\n";
            case "commands":
            case "alias":
            case "aliases":
                return "Help: command aliases\n\n"
                    + "Define in ~/.shed/config.toml as \"command.alias.<newname>\" = \"<builtin>\".\n"
                    + "Example: \"command.alias.ww\" = \"w\".\n"
                    + "Aliases are used by command execution and command completion.\n";
            default:
                return "Shed help: " + topic + "\n\n"
                    + "No dedicated topic entry exists yet for this help topic.\n"
                    + "Use :help for the full command reference.\n";
        }
    }
}
