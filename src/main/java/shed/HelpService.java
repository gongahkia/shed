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
                   "  q{a-z} @a @@   Macro record and playback\n" +
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
                   "COMMANDS\n" +
                   "  :w [file]      Write current buffer\n" +
                   "  :q / :q!       Quit buffer/editor\n" +
                   "  :wq / :x       Write and quit\n" +
                   "  :e file        Edit file\n" +
                   "  :bn / :bp      Next/previous buffer\n" +
                   "  :ls            List buffers\n" +
                   "  :bd            Delete buffer\n" +
                   "  :recent        Show recent files\n" +
                   "  :settings      Open user settings file\n" +
                   "  :config save   Persist current runtime config to ~/.shed/shedrc\n" +
                   "  :log           Open command log file\n" +
                   "  :session ...   Session save/load/list\n" +
                   "  :workspace ... Workspace profile save/load/list\n" +
                   "  :clean         Remove Shed data files\n" +
                   "  :jobs          Show async job list\n" +
                   "  :jobcancel id  Cancel async job\n" +
                   "  :drop cmd      Run async command with current file path\n" +
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
                   "  :copen         Open quickfix list\n" +
                   "  :cnext/:cprev  Next/previous quickfix entry\n" +
                   "  :cc [n]        Jump to quickfix entry\n" +
                   "  :lsp ...       LSP commands (def/hover/refs/rename/actions)\n" +
                   "  :lsp status    Show running LSP servers\n" +
                   "  :lsp servers   List all configured + builtin LSP servers\n" +
                   "  :lsp restart   Restart LSP server for current extension\n" +
                   "  :diagnostics   Push diagnostics into quickfix\n" +
                   "  :dnext/:dprev  Jump next/prev diagnostic\n" +
                   "  :git ...       Git status/diff/log/add/commit\n" +
                   "  :split/:vsplit Split the active window\n" +
                   "  Ctrl-w s/v/c   Split/vertical-split/close window\n" +
                   "  Ctrl-w h/j/k/l Move window focus\n" +
                   "  :registers     Show registers\n" +
                   "  :yankring      Pick from yank/delete history and paste\n" +
                   "  :marks         Show marks\n" +
                   "  :themes        Show built-in themes\n" +
                   "  :theater X     Dramatic UI preset (off/subtle/full)\n" +
                   "  :zen           Toggle zen mode\n" +
                   "  :reload        Reload ~/.shed/shedrc now\n" +
                   "  :normal keys   Replay normal keys\n" +
                   "  :!cmd          Run shell command (async)\n" +
                   "  :set nu        Enable line numbers\n" +
                   "  :set theme=x   Switch color theme\n" +
                   "  :set k=v       Set any config key in-memory\n" +
                   "  :set! k=v      Set and persist key to ~/.shed/shedrc\n" +
                   "  :45            Go to line 45\n" +
                   "  :1,5d          Delete a line range\n" +
                   "  :s/a/b         Substitute current line\n" +
                   "  :1,5s/a/b/g    Substitute a range\n" +
                   "  :%s/a/b/g      Substitute whole buffer\n\n" +
                   "PLUGINS\n" +
                   "  :plugin           List loaded plugins\n" +
                   "  :plugin reload    Reload plugins from ~/.shed/plugins/\n" +
                   "  :plugin enable/disable <name>  Toggle plugins\n" +
                   "  :plugin new <name> Create + open plugin template\n" +
                   "  :help plugins     Plugin authoring guide\n\n" +
                   "SETTINGS KEYS\n" +
                   "  project override file: .shedrc.local (nearest parent)\n" +
                   "  project.config.allow.unsafe=false limits local overrides to UI/editor keys\n" +
                   "  tree.delete.protect.critical=true blocks deleting /, home, and cwd via :tree rm\n" +
                   "  ui.whichkey.hints=true shows prefix-key hints (g/z/Ctrl-w/...)\n" +
                   "  first-open trust prompts gate local .shedrc.local/.shed plugins per project\n" +
                   "  command.alias.<name>=<builtin>\n" +
                   "  keybind.<mode>.<lhs>=<rhs>\n" +
                   "  modes: normal/insert/visual/visual_line/replace/command/search/global\n" +
                   "  tokens: <esc> <enter> <tab> <space> <bs> <del> <up>/<down>/<left>/<right> <c-x>\n\n" +
                   "note: this is a help buffer. use :q to return.\n";
        }

        switch (normalizedTopic) {
            case "windows":
            case "split":
            case "vsplit":
                return "Help: windows\n\n"
                    + ":split / :sp creates a horizontal split.\n"
                    + ":vsplit / :vsp creates a vertical split.\n"
                    + ":close closes the active split when more than one window exists.\n"
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
                    + "q{register} starts recording into a named register.\n"
                    + "q stops recording.\n"
                    + "@{register} replays a macro and @@ replays the last executed macro.\n"
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
                    + "  Ctrl-n           trigger completion (falls back to buffer words)\n"
                    + "  :lsp definition  go to definition\n"
                    + "  :lsp hover       show hover info\n"
                    + "  :lsp references  find references\n"
                    + "  :lsp rename X    preview rename edits for symbol -> X\n"
                    + "  :lsp renameapply apply pending rename preview\n"
                    + "  :lsp renamecancel discard pending rename preview\n"
                    + "  :lsp codeaction  list/apply code actions\n"
                    + "  :diagnostics     push diagnostics to quickfix\n"
                    + "  :dnext/:dprev    jump through diagnostics\n\n"
                    + "MANAGEMENT\n"
                    + "  :lsp status      show running servers and errors\n"
                    + "  :lsp servers     list configured (shedrc) + builtin servers\n"
                    + "  :lsp restart [ext] restart server (default: current buffer ext)\n"
                    + "  :lsp stop [ext]  stop a server\n"
                    + "  :lsp log         show LSP error log\n\n"
                    + "CONFIGURATION\n"
                    + "  lsp.<ext>.command=<binary>   server command in ~/.shed/shedrc\n"
                    + "  lsp.<ext>.args=<flags>       server arguments\n"
                    + "  Builtin servers: rs py js jsx ts tsx go c cpp h hpp\n";
            case "git":
                return "Help: git\n\n"
                    + ":git shows status.\n"
                    + ":git diff [args], :git log [count], :git branch show repository state.\n"
                    + ":git add|stage <paths...>, :git restore|unstage <paths...> modify staging.\n"
                    + ":git checkout <arg>, :git switch <branch> move HEAD.\n"
                    + ":git commit <message>, :git amend <message|--no-edit> create/update commits.\n";
            case "tree":
                return "Help: tree\n\n"
                    + ":tree toggles the left side tree pane open/closed.\n"
                    + ":tree <path> uses a specific root path when opening.\n"
                    + ":tree refresh reloads the current tree root.\n"
                    + ":tree reveal sets tree root to the current file's directory.\n"
                    + ":tree new/mkdir/rename/rm perform file operations from command mode.\n"
                    + "tree.delete.protect.critical blocks deleting root/home/cwd unless disabled.\n"
                    + "Use j/k to move and Enter or o to open the file in the other pane.\n";
            case "session":
            case "sessions":
                return "Help: sessions\n\n"
                    + ":session save [name] stores open file-backed buffers.\n"
                    + ":session load [name] restores a saved session.\n"
                    + ":session load! [name] restores even when buffers are modified.\n"
                    + ":session list lists all saved sessions.\n"
                    + ":workspace save/load/list is similar, with project-profile naming + UI settings.\n"
                    + "Configure session.restore.on.start/session.autoload/session.dir in ~/.shed/shedrc.\n";
            case "keybind":
            case "keybinding":
            case "keybindings":
                return "Help: keybindings\n\n"
                    + "Define in ~/.shed/shedrc as keybind.<mode>.<lhs>=<rhs>.\n"
                    + "LHS/RHS accept raw characters and tokens like <esc>, <enter>, <c-w>.\n"
                    + "Use mode global for mappings active in every mode.\n"
                    + "Use value <nop> to disable a key.\n";
            case "commands":
            case "alias":
            case "aliases":
                return "Help: command aliases\n\n"
                    + "Define in ~/.shed/shedrc as command.alias.<newname>=<builtin>.\n"
                    + "Example: command.alias.ww=w and command.alias.qq=q.\n"
                    + "Aliases are used by command execution and command completion.\n";
            case "plugin":
            case "plugins":
                return "Help: plugins\n\n"
                    + "Shed loads .shed and .lua plugin files from ~/.shed/plugins/ at startup.\n\n"
                    + "DECLARATIVE PLUGINS (.shed)\n"
                    + "  Directive comments starting with # @.\n\n"
                    + "  DIRECTIVES\n"
                    + "    # @name <plugin-name>\n"
                    + "    # @description <one-line summary>\n"
                    + "    # @command <name>=<shell command>\n"
                    + "    # @bind <mode> <lhs>=<rhs>\n"
                    + "    # @event <event>=:<command>\n\n"
                    + "  INTERPOLATION (expanded in shell commands)\n"
                    + "    %file %line %col %word %selection\n\n"
                    + "  EXAMPLE (~/.shed/plugins/fmt.shed)\n"
                    + "    # @name fmt\n"
                    + "    # @description auto-format on save\n"
                    + "    # @command fmt=!prettier --write %file\n"
                    + "    # @event BufWrite=:fmt\n\n"
                    + "LUA PLUGINS (.lua)\n"
                    + "  Full scripting via embedded LuaJ. Each .lua file runs in a\n"
                    + "  sandboxed environment with the shed.* API table.\n\n"
                    + "  BUFFER API\n"
                    + "    shed.get_line(n)        get line n (1-indexed)\n"
                    + "    shed.set_line(n, text)  replace line n\n"
                    + "    shed.line_count()       number of lines\n"
                    + "    shed.get_text()         full buffer text\n"
                    + "    shed.file_path()        current file path or \"\"\n"
                    + "    shed.file_name()        display name\n"
                    + "    shed.is_modified()      boolean\n\n"
                    + "  CURSOR API\n"
                    + "    shed.cursor_line()      1-indexed line\n"
                    + "    shed.cursor_col()       0-indexed column\n\n"
                    + "  COMMAND API\n"
                    + "    shed.command(str)       execute ex command, returns result\n"
                    + "    shed.message(str)       show in command bar\n"
                    + "    shed.shell(cmd)         run shell, return stdout\n\n"
                    + "  CONFIG API\n"
                    + "    shed.config_get(key)    get config value\n"
                    + "    shed.config_set(key,v)  set config value\n\n"
                    + "  MODE / EVENTS\n"
                    + "    shed.mode()             current mode name\n"
                    + "    shed.on(event, fn)      register callback\n\n"
                    + "  EVENTS: BufOpen, BufWrite, ModeChange\n\n"
                    + "  EXAMPLE (~/.shed/plugins/autosave.lua)\n"
                    + "    shed.on(\"BufWrite\", function()\n"
                    + "      shed.message(\"saved: \" .. shed.file_name())\n"
                    + "    end)\n\n"
                    + "COMMANDS\n"
                    + "  :plugin           list loaded plugins\n"
                    + "  :plugin reload    reload all plugins from disk\n"
                    + "  :plugin info X    show details for plugin X\n"
                    + "  :plugin enable X  enable a disabled plugin\n"
                    + "  :plugin disable X disable a plugin (renames to .disabled)\n"
                    + "  :plugin new X     create and open a new plugin template\n"
                    + "  :plugin path      show plugins directory + disabled list\n";
            default:
                return "Shed help: " + topic + "\n\n"
                    + "No dedicated topic entry exists yet for this help topic.\n"
                    + "Use :help for the full command reference.\n";
        }
    }
}
