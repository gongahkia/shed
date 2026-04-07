package shed;

// Command Handler Class
// Parses and executes ex-style commands (:w, :q, :e, ranged :s/:d/:! etc.)

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CommandHandler {
    private final Texteditor editor;
    private final DateTimeFormatter timeFormat;
    private final Map<String, CommandAction> commandRegistry;

    public CommandHandler(Texteditor editor) {
        this.editor = editor;
        this.timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");
        this.commandRegistry = new HashMap<>();
        registerCommands();
    }

    public String execute(String command) {
        if (command == null || command.isEmpty()) {
            return "No command entered";
        }

        command = command.trim();
        if (command.startsWith(":")) {
            command = command.substring(1);
        }
        editor.rememberExCommand(":" + command);

        if (command.startsWith("/")) {
            return editor.search(command.substring(1));
        }
        if (command.startsWith("?")) {
            return editor.searchBackward(command.substring(1));
        }

        try {
            RangeParseResult range = parseRange(command);
            String working = range.remaining.trim();

            if (working.startsWith("!")) {
                String shellCommand = working.substring(1).trim();
                if (range.hasRange()) {
                    return editor.filterRangeWithCommand(range.start, range.resolveEnd(editor), shellCommand);
                }
                return editor.runShellCommand(shellCommand);
            }

            if (working.startsWith("s/")) {
                return handleSubstitute(working, range);
            }
            if (working.startsWith("g/") || working.startsWith("v/")) {
                return handleGlobal(working);
            }

            String cmd;
            String args;
            int firstSpace = working.indexOf(' ');
            if (firstSpace >= 0) {
                cmd = working.substring(0, firstSpace);
                args = working.substring(firstSpace + 1).trim();
            } else {
                cmd = working;
                args = "";
            }

            boolean force = cmd.endsWith("!");
            if (force) {
                cmd = cmd.substring(0, cmd.length() - 1);
            }
            String normalizedCmd = cmd.toLowerCase(Locale.ROOT);
            String resolvedCmd = editor.resolveCommandAlias(normalizedCmd);

            if (resolvedCmd.isEmpty()) {
                return "";
            }

            CommandAction action = commandRegistry.get(resolvedCmd);
            if (action != null) {
                return action.execute(args, range, force);
            }

            // Check user-defined commands from ~/.shed/shedrc
            Map<String, String> userCommands = editor.getConfigManager().getUserCommands();
            if (userCommands.containsKey(resolvedCmd)) {
                String shellCmd = userCommands.get(resolvedCmd);
                if (args != null && !args.isEmpty()) shellCmd += " " + args;
                return editor.runUserCommand(resolvedCmd, shellCmd);
            }

            try {
                return editor.gotoLine(Integer.parseInt(resolvedCmd));
            } catch (NumberFormatException ignored) {
                return "Command not recognised: " + cmd;
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private void registerCommands() {
        registerCommand((args, range, force) -> handleWrite(args.isEmpty() ? null : args), "w", "write");
        registerCommand((args, range, force) -> editor.requestQuit(force), "q", "quit");
        registerCommand((args, range, force) -> handleWriteQuit(args.isEmpty() ? null : args), "wq", "x");
        registerCommand((args, range, force) -> handleEdit(args), "e", "edit");
        registerCommand((args, range, force) -> editor.nextBuffer(), "bn", "bnext");
        registerCommand((args, range, force) -> editor.prevBuffer(), "bp", "bprev");
        registerCommand((args, range, force) -> editor.listBuffers(), "ls");
        registerCommand((args, range, force) -> editor.deleteBuffer(force), "bd", "bdelete");
        registerCommand((args, range, force) -> handleSet(args, force), "set");
        registerCommand((args, range, force) -> handleConfig(args), "settings", "config", "shedrc");
        registerCommand((args, range, force) -> editor.openCommandLogBuffer(), "log", "commandlog");
        registerCommand((args, range, force) -> editor.handleSessionCommand(args), "session", "sessions");
        registerCommand((args, range, force) -> editor.handleWorkspaceProfileCommand(args), "workspace", "ws");
        registerCommand((args, range, force) -> editor.showJobs(), "jobs");
        registerCommand((args, range, force) -> editor.cancelJob(args), "jobcancel", "jobkill");
        registerCommand((args, range, force) -> editor.runDropCommand(args), "drop");
        registerCommand((args, range, force) -> editor.handleTreeCommand(args), "tree");
        registerCommand((args, range, force) -> editor.handleGitCommand(args), "git");
        registerCommand((args, range, force) -> {
            editor.showHelp(args);
            return "Showing help";
        }, "help", "h");
        registerCommand((args, range, force) -> handleWordCount(), "wc", "wordcount");
        registerCommand((args, range, force) -> editor.showRecentFiles(), "recent");
        registerCommand((args, range, force) -> handleDelete(range), "d", "delete");
        registerCommand((args, range, force) -> editor.showFileFinder(), "files");
        registerCommand((args, range, force) -> editor.showFolderFinder(), "folder", "folders");
        registerCommand((args, range, force) -> editor.showBufferFinder(), "buffers", "buf");
        registerCommand((args, range, force) -> editor.splitWindow(false), "split", "sp");
        registerCommand((args, range, force) -> editor.splitWindow(true), "vsplit", "vsp");
        registerCommand((args, range, force) -> editor.closeActiveWindow(), "close", "clo");
        registerCommand((args, range, force) -> editor.showGrepFinder(args), "grep", "rg");
        registerCommand((args, range, force) -> editor.openQuickfixList(), "copen");
        registerCommand((args, range, force) -> editor.closeQuickfixList(), "cclose");
        registerCommand((args, range, force) -> editor.quickfixNext(), "cnext", "cn");
        registerCommand((args, range, force) -> editor.quickfixPrev(), "cprev", "cp");
        registerCommand((args, range, force) -> editor.quickfixFirst(), "cfirst");
        registerCommand((args, range, force) -> editor.quickfixLast(), "clast");
        registerCommand((args, range, force) -> editor.quickfixCurrent(args), "cc");
        registerCommand((args, range, force) -> editor.handleLspCommand(args), "lsp");
        registerCommand((args, range, force) -> editor.lspGoToDefinition(), "definition");
        registerCommand((args, range, force) -> editor.lspHover(), "hover");
        registerCommand((args, range, force) -> editor.lspReferences(), "references");
        registerCommand((args, range, force) -> editor.showDiagnostics(), "diagnostics", "diag", "ldiag");
        registerCommand((args, range, force) -> editor.diagnosticsNext(), "dnext", "dn");
        registerCommand((args, range, force) -> editor.diagnosticsPrev(), "dprev", "dp");
        registerCommand((args, range, force) -> editor.showRegisters(), "registers", "reg");
        registerCommand((args, range, force) -> editor.showMarks(), "marks");
        registerCommand((args, range, force) -> editor.showThemes(), "themes");
        registerCommand((args, range, force) -> editor.toggleZenMode(), "zen");
        registerCommand((args, range, force) -> editor.applyTheaterPreset(args), "theater");
        registerCommand((args, range, force) -> editor.toggleMinimap(), "minimap");
        registerCommand((args, range, force) -> handleNormal(args, range), "normal", "norm");
        registerCommand((args, range, force) -> editor.reloadConfigFromDisk(), "reload", "source");
        registerCommand((args, range, force) -> editor.cleanShedDataFiles(), "clean", "shedclean");
        registerCommand((args, range, force) -> editor.clearSearchHighlights(), "noh", "nohlsearch");
        registerCommand((args, range, force) -> editor.showCommandPalette(), "palette", "commands");
        registerCommand((args, range, force) -> editor.showUndoHistory(), "undolist", "undotree");
        registerCommand((args, range, force) -> editor.writeAll(), "wa", "wall");
        registerCommand((args, range, force) -> editor.quitAll(force), "qa", "qall");
        registerCommand((args, range, force) -> { String r = editor.writeAll(); if (r.startsWith("Error")) return r; return editor.quitAll(force); }, "wqa", "wqall", "xa", "xall");

        // Markdown / orgmode commands
        registerCommand((args, range, force) -> editor.showTableOfContents(), "toc");
        registerCommand((args, range, force) -> editor.showOutline(), "outline");
        registerCommand((args, range, force) -> editor.toggleCheckbox(), "toggle", "checkbox");
        registerCommand((args, range, force) -> handleTableCommand(args), "table");
        registerCommand((args, range, force) -> editor.insertLink(), "link");
        registerCommand((args, range, force) -> editor.insertImage(), "img", "image");
        registerCommand((args, range, force) -> editor.listSnippets(), "snippets", "snippet");
        registerCommand((args, range, force) -> editor.toggleBracketColors(), "bracketcolor", "bracketcolors");
        registerCommand((args, range, force) -> editor.openTerminal(), "term", "terminal");
        registerCommand((args, range, force) -> handleConceal(args), "conceal", "conceallevel");

        // Plugin commands
        registerCommand((args, range, force) -> handlePlugin(args), "plugin", "plugins");
    }

    private void registerCommand(CommandAction action, String... names) {
        for (String name : names) {
            if (name != null && !name.isEmpty()) {
                commandRegistry.put(name, action);
            }
        }
    }

    public List<String> getCommandNames() {
        List<String> names = new ArrayList<>(commandRegistry.keySet());
        Collections.sort(names);
        return names;
    }

    private String handleWrite(String targetPath) {
        try {
            FileBuffer buffer = editor.getCurrentBuffer();
            if (buffer == null) {
                return "Error: No file open";
            }

            buffer.setContent(editor.getTextArea().getText());
            if (targetPath != null && !targetPath.isEmpty()) {
                buffer.saveAs(new File(targetPath));
            } else {
                buffer.save();
            }
            editor.notifyCurrentBufferSaved();
            String reloadResult = editor.reloadConfigIfSettingsBuffer(buffer);

            String timestamp = timeFormat.format(LocalDateTime.now());
            String base = "\"" + buffer.getDisplayName() + "\" " + buffer.getLineCount() + "L written " + timestamp;
            if (reloadResult != null && !reloadResult.isEmpty()) {
                return base + " (" + reloadResult + ")";
            }
            return base;
        } catch (IOException e) {
            return "Error saving file: " + e.getMessage();
        }
    }

    private String handleWriteQuit(String targetPath) {
        String writeResult = handleWrite(targetPath);
        if (writeResult.startsWith("Error")) {
            return writeResult;
        }
        editor.requestQuit(true);
        return "Saved and quitting";
    }

    private String handleEdit(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "Error: :e requires filename argument";
        }
        try {
            editor.openFile(new File(filename));
            return "Opened: " + filename;
        } catch (IOException e) {
            return "Error opening file: " + e.getMessage();
        }
    }

    private String handleSet(String option, boolean persist) {
        if (option == null || option.isEmpty()) {
            return "Error: :set requires argument";
        }

        if (persist) {
            int separator = option.indexOf('=');
            if (separator <= 0) {
                return "Persistent set requires key=value (example: :set! ui.dramatic=true)";
            }
            String key = option.substring(0, separator).trim();
            String value = option.substring(separator + 1).trim();
            return editor.setConfigOptionPersistent(key, value);
        }

        if (option.equals("nu") || option.equals("number")) {
            editor.toggleLineNumbers(true);
            return "Line numbers enabled";
        }
        if (option.equals("nonu") || option.equals("nonumber")) {
            editor.toggleLineNumbers(false);
            return "Line numbers disabled";
        }
        if (option.equals("rnu") || option.equals("relativenumber")) {
            editor.setLineNumberMode(LineNumberMode.RELATIVE);
            return "Relative line numbers enabled";
        }
        if (option.equals("nornu") || option.equals("norelativenumber")) {
            editor.setLineNumberMode(LineNumberMode.ABSOLUTE);
            return "Relative line numbers disabled";
        }
        if (option.equals("list")) {
            editor.setConfigOption("list", "true");
            return "Whitespace visualization enabled";
        }
        if (option.equals("nolist")) {
            editor.setConfigOption("list", "false");
            return "Whitespace visualization disabled";
        }
        if (option.equals("wrap")) {
            editor.setWrap(true);
            return "Line wrapping enabled";
        }
        if (option.equals("nowrap")) {
            editor.setWrap(false);
            return "Line wrapping disabled";
        }
        if (option.equals("hls") || option.equals("hlsearch")) {
            editor.setHighlightSearch(true);
            return "Search highlighting enabled";
        }
        if (option.equals("nohls") || option.equals("nohlsearch")) {
            editor.setHighlightSearch(false);
            return "Search highlighting disabled";
        }
        if (option.equals("ai") || option.equals("autoindent")) {
            editor.setAutoIndent(true);
            return "Auto-indent enabled";
        }
        if (option.equals("noai") || option.equals("noautoindent")) {
            editor.setAutoIndent(false);
            return "Auto-indent disabled";
        }
        if (option.equals("et") || option.equals("expandtab")) {
            editor.setExpandTab(true);
            return "Expand tab enabled";
        }
        if (option.equals("noet") || option.equals("noexpandtab")) {
            editor.setExpandTab(false);
            return "Expand tab disabled";
        }
        if (option.equals("cul") || option.equals("cursorline")) {
            editor.setShowCurrentLine(true);
            return "Current line highlight enabled";
        }
        if (option.equals("nocul") || option.equals("nocursorline")) {
            editor.setShowCurrentLine(false);
            return "Current line highlight disabled";
        }
        if (option.startsWith("tabstop=") || option.startsWith("ts=")) {
            return editor.setTabSizeFromCommand(option.substring(option.indexOf('=') + 1));
        }
        if (option.startsWith("line.numbers=")) {
            return editor.setLineNumberMode(option.substring(option.indexOf('=') + 1));
        }
        if (option.equals("theme")) {
            return "Current theme: " + editor.getCurrentThemeName();
        }
        if (option.startsWith("theme=")) {
            return editor.setThemeFromCommand(option.substring(option.indexOf('=') + 1).trim());
        }
        if (option.startsWith("colorscheme=")) {
            return editor.setThemeFromCommand(option.substring(option.indexOf('=') + 1).trim());
        }
        if (option.startsWith("theme ")) {
            return editor.setThemeFromCommand(option.substring("theme ".length()).trim());
        }
        if (option.startsWith("colorscheme ")) {
            return editor.setThemeFromCommand(option.substring("colorscheme ".length()).trim());
        }
        if (option.startsWith("conceallevel=")) {
            try {
                int level = Integer.parseInt(option.substring("conceallevel=".length()).trim());
                return editor.setConcealLevel(level);
            } catch (NumberFormatException e) {
                return "Invalid conceal level";
            }
        }
        if (option.equals("bracketcolor") || option.equals("bracketcolors")) {
            return editor.toggleBracketColors();
        }
        if (option.equals("autopairs")) {
            editor.setConfigOption("auto.pairs", "true");
            return "Auto-pairs enabled";
        }
        if (option.equals("noautopairs")) {
            editor.setConfigOption("auto.pairs", "false");
            return "Auto-pairs disabled";
        }
        if (option.startsWith("textwidth=") || option.startsWith("tw=")) {
            return editor.setConfigOption("textwidth", option.substring(option.indexOf('=') + 1).trim());
        }
        if (option.startsWith("scrolloff=") || option.startsWith("so=")) {
            return editor.setConfigOption("scrolloff", option.substring(option.indexOf('=') + 1).trim());
        }
        int separator = option.indexOf('=');
        if (separator > 0) {
            String key = option.substring(0, separator).trim();
            String value = option.substring(separator + 1).trim();
            return editor.setConfigOption(key, value);
        }
        return "Unknown option: " + option;
    }

    private String handleConfig(String args) {
        if (args == null || args.isBlank()) {
            return editor.openSettingsBuffer();
        }
        String trimmed = args.trim().toLowerCase(Locale.ROOT);
        if ("save".equals(trimmed) || "write".equals(trimmed)) {
            return editor.saveConfigToDisk();
        }
        return "Usage: :config [save]";
    }

    private String handleWordCount() {
        String text = editor.getTextArea().getText();
        int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        int chars = text.length();
        return lines + " lines, " + words + " words, " + chars + " characters";
    }

    private String handleDelete(RangeParseResult range) {
        if (!range.hasRange()) {
            int line = editor.getCurrentLineNumber();
            return editor.deleteLineRange(line, line);
        }
        return editor.deleteLineRange(range.start, range.resolveEnd(editor));
    }

    private String handleTableCommand(String args) {
        if (args == null || args.isEmpty()) {
            return editor.insertTableTemplate("3x2");
        }
        String sub = args.trim().toLowerCase(Locale.ROOT);
        if (sub.startsWith("align")) {
            return editor.alignMarkdownTable();
        } else if (sub.startsWith("sort")) {
            String sortArgs = args.trim().length() > 4 ? args.trim().substring(4).trim() : "";
            return editor.sortMarkdownTable(sortArgs);
        } else if (sub.startsWith("insert-col") || sub.startsWith("insertcol") || sub.startsWith("addcol")) {
            return editor.insertTableColumn(args.contains(" ") ? args.substring(args.indexOf(' ') + 1).trim() : "");
        } else if (sub.startsWith("delete-col") || sub.startsWith("deletecol") || sub.startsWith("delcol")) {
            return editor.deleteTableColumn(args.contains(" ") ? args.substring(args.indexOf(' ') + 1).trim() : "");
        } else if (sub.matches("\\d+[xX]\\d+")) {
            return editor.insertTableTemplate(sub);
        } else {
            return "Unknown table subcommand: " + args + " (try: align, sort, insert-col, delete-col, NxM)";
        }
    }

    private String handleConceal(String args) {
        if (args == null || args.isEmpty()) {
            return "Usage: :conceal 0|1|2";
        }
        try {
            int level = Integer.parseInt(args.trim());
            return editor.setConcealLevel(level);
        } catch (NumberFormatException e) {
            return "Invalid conceal level: " + args;
        }
    }

    private String handlePlugin(String args) {
        if (args == null || args.isEmpty()) return editor.showPluginList();
        String trimmed = args.trim();
        int space = trimmed.indexOf(' ');
        String sub = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
        String subArgs = space < 0 ? "" : trimmed.substring(space + 1).trim();
        switch (sub) {
            case "list": return editor.showPluginList();
            case "reload": return editor.reloadPlugins();
            case "enable": return subArgs.isEmpty() ? "Usage: :plugin enable <name>" : editor.enablePlugin(subArgs);
            case "disable": return subArgs.isEmpty() ? "Usage: :plugin disable <name>" : editor.disablePlugin(subArgs);
            case "info": return subArgs.isEmpty() ? "Usage: :plugin info <name>" : editor.showPluginInfo(subArgs);
            case "path": return editor.showPluginPath();
            case "new": return subArgs.isEmpty() ? "Usage: :plugin new <name>" : editor.createAndOpenPlugin(subArgs);
            default: return "Unknown :plugin subcommand: " + sub;
        }
    }

    private String handleNormal(String keys, RangeParseResult range) {
        int currentLine = editor.getCurrentLineNumber();
        int start = range.hasRange() ? range.start : currentLine;
        int end = range.hasRange() ? range.resolveEnd(editor) : currentLine;
        return editor.executeNormalKeys(keys, start, end);
    }

    private String handleGlobal(String command) {
        boolean invert = command.startsWith("v/");
        String rest = command.substring(2); // skip "g/" or "v/"
        int slashIdx = rest.indexOf('/');
        if (slashIdx < 0) return "Error: :g/pattern/command syntax required";
        String pattern = rest.substring(0, slashIdx);
        String subCommand = rest.substring(slashIdx + 1).trim();
        if (pattern.isEmpty()) return "Error: empty pattern";
        if (subCommand.isEmpty()) return "Error: empty command";
        java.util.regex.Pattern compiled;
        try { compiled = java.util.regex.Pattern.compile(pattern); }
        catch (java.util.regex.PatternSyntaxException e) { return "Error: invalid regex: " + e.getMessage(); }
        javax.swing.JTextArea area = editor.getTextArea();
        // collect matching line numbers first, then execute bottom-to-top
        String text = area.getText();
        String[] lines = text.split("\n", -1);
        List<Integer> matchingLines = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            boolean matches = compiled.matcher(lines[i]).find();
            if (matches != invert) matchingLines.add(i);
        }
        int matchCount = matchingLines.size();
        // execute bottom-to-top so deletions don't shift unprocessed lines
        for (int idx = matchingLines.size() - 1; idx >= 0; idx--) {
            int lineNum = matchingLines.get(idx);
            // re-check bounds since prior commands may have changed line count
            if (lineNum >= area.getLineCount()) continue;
            editor.gotoLine(lineNum + 1);
            execute(":" + subCommand);
        }
        return matchCount + " line" + (matchCount == 1 ? "" : "s") + " matched";
    }

    private String handleSubstitute(String command, RangeParseResult range) {
        SubstituteCommand parsed = parseSubstitute(command);
        if (parsed == null) {
            return "Error: Invalid substitute syntax";
        }

        if (range.hasRange()) {
            return editor.substituteRange(parsed.searchPattern, parsed.replacement, range.start, range.resolveEnd(editor), parsed.replaceAll);
        }
        return editor.substitute(parsed.searchPattern, parsed.replacement, false, parsed.replaceAll);
    }

    private SubstituteCommand parseSubstitute(String command) {
        if (!command.startsWith("s/")) {
            return null;
        }

        String[] parts = command.substring(2).split("/", -1);
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }

        String searchPattern = parts[0];
        String replacement = parts[1];
        String flags = parts.length == 3 ? parts[2] : "";
        if (searchPattern.isEmpty()) {
            return null;
        }
        if (!flags.isEmpty() && !"g".equals(flags)) {
            return null;
        }
        return new SubstituteCommand(searchPattern, replacement, "g".equals(flags));
    }

    private RangeParseResult parseRange(String input) {
        if (input.startsWith("%")) {
            return new RangeParseResult(1, Integer.MAX_VALUE, input.substring(1), true);
        }

        int index = 0;
        while (index < input.length()) {
            char c = input.charAt(index);
            if (Character.isDigit(c) || c == ',') {
                index++;
            } else {
                break;
            }
        }

        if (index == 0 || index >= input.length()) {
            return new RangeParseResult(0, 0, input, false);
        }

        String rangePart = input.substring(0, index);
        String[] parts = rangePart.split(",", -1);
        try {
            if (parts.length == 1) {
                int line = Integer.parseInt(parts[0]);
                return new RangeParseResult(line, line, input.substring(index), true);
            }
            if (parts.length == 2) {
                int start = Integer.parseInt(parts[0]);
                int end = Integer.parseInt(parts[1]);
                return new RangeParseResult(start, end, input.substring(index), true);
            }
        } catch (NumberFormatException ignored) {
        }

        return new RangeParseResult(0, 0, input, false);
    }

    private static class RangeParseResult {
        private final int start;
        private final int end;
        private final String remaining;
        private final boolean present;

        private RangeParseResult(int start, int end, String remaining, boolean present) {
            this.start = start;
            this.end = end;
            this.remaining = remaining;
            this.present = present;
        }

        private boolean hasRange() {
            return present;
        }

        private int resolveEnd(Texteditor editor) {
            if (end == Integer.MAX_VALUE) {
                return editor.getTextArea().getLineCount();
            }
            return end;
        }
    }

    private static class SubstituteCommand {
        private final String searchPattern;
        private final String replacement;
        private final boolean replaceAll;

        private SubstituteCommand(String searchPattern, String replacement, boolean replaceAll) {
            this.searchPattern = searchPattern;
            this.replacement = replacement;
            this.replaceAll = replaceAll;
        }
    }

    @FunctionalInterface
    private interface CommandAction {
        String execute(String args, RangeParseResult range, boolean force);
    }
}
