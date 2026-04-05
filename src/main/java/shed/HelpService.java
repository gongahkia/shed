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
                   "  :log           Open command log file\n" +
                   "  :jobs          Show async job list\n" +
                   "  :jobcancel id  Cancel async job\n" +
                   "  :files         File finder\n" +
                   "  :folder        Folder finder\n" +
                   "  :tree [path]   File tree in scratch buffer\n" +
                   "  :buffers       Buffer finder\n" +
                   "  :grep text     Grep finder\n" +
                   "  :git ...       Git status/diff/log/add/commit\n" +
                   "  :split/:vsplit Split the active window\n" +
                   "  Ctrl-w s/v/c   Split/vertical-split/close window\n" +
                   "  Ctrl-w h/j/k/l Move window focus\n" +
                   "  :registers     Show registers\n" +
                   "  :marks         Show marks\n" +
                   "  :themes        Show built-in themes\n" +
                   "  :zen           Toggle zen mode\n" +
                   "  :reload        Reload ~/.shedrc now\n" +
                   "  :normal keys   Replay normal keys\n" +
                   "  :!cmd          Run shell command (async)\n" +
                   "  :set nu        Enable line numbers\n" +
                   "  :set theme=x   Switch color theme\n" +
                   "  :set k=v       Set any config key in-memory\n" +
                   "  :45            Go to line 45\n" +
                   "  :1,5d          Delete a line range\n" +
                   "  :s/a/b         Substitute current line\n" +
                   "  :1,5s/a/b/g    Substitute a range\n" +
                   "  :%s/a/b/g      Substitute whole buffer\n\n" +
                   "SETTINGS KEYS\n" +
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
                return "Help: completion\n\n"
                    + "Ctrl-n requests completion from an external language server for file-backed buffers.\n"
                    + "If no server is available, Shed falls back to local buffer-word completion.\n"
                    + "Configure overrides in ~/.shedrc using lsp.<ext>.command and lsp.<ext>.args.\n";
            case "git":
                return "Help: git\n\n"
                    + ":git shows status.\n"
                    + ":git diff [args], :git log [count], :git branch show repository state.\n"
                    + ":git add <paths...>, :git restore <paths...>, :git commit <message> modify staging/commits.\n";
            case "tree":
                return "Help: tree\n\n"
                    + ":tree toggles the left side tree pane open/closed.\n"
                    + ":tree <path> uses a specific root path when opening.\n"
                    + "Use j/k to move and Enter or o to open the file in the other pane.\n";
            case "keybind":
            case "keybinding":
            case "keybindings":
                return "Help: keybindings\n\n"
                    + "Define in ~/.shedrc as keybind.<mode>.<lhs>=<rhs>.\n"
                    + "LHS/RHS accept raw characters and tokens like <esc>, <enter>, <c-w>.\n"
                    + "Use mode global for mappings active in every mode.\n"
                    + "Use value <nop> to disable a key.\n";
            case "commands":
            case "alias":
            case "aliases":
                return "Help: command aliases\n\n"
                    + "Define in ~/.shedrc as command.alias.<newname>=<builtin>.\n"
                    + "Example: command.alias.ww=w and command.alias.qq=q.\n"
                    + "Aliases are used by command execution and command completion.\n";
            default:
                return "Shed help: " + topic + "\n\n"
                    + "No dedicated topic entry exists yet for this help topic.\n"
                    + "Use :help for the full command reference.\n";
        }
    }
}
