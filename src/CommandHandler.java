// Command Handler Class
// Parses and executes ex-style commands (:w, :q, :e, ranged :s/:d/:! etc.)

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class CommandHandler {
    private final Texteditor editor;
    private final DateTimeFormatter timeFormat;

    public CommandHandler(Texteditor editor) {
        this.editor = editor;
        this.timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");
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

            switch (resolvedCmd) {
                case "w":
                case "write":
                    return handleWrite(args.isEmpty() ? null : args);
                case "q":
                case "quit":
                    return editor.requestQuit(force);
                case "wq":
                case "x":
                    return handleWriteQuit(args.isEmpty() ? null : args);
                case "e":
                case "edit":
                    return handleEdit(args);
                case "bn":
                case "bnext":
                    return editor.nextBuffer();
                case "bp":
                case "bprev":
                    return editor.prevBuffer();
                case "ls":
                    return editor.listBuffers();
                case "bd":
                case "bdelete":
                    return editor.deleteBuffer(force);
                case "set":
                    return handleSet(args);
                case "settings":
                case "config":
                case "shedrc":
                    return editor.openSettingsBuffer();
                case "log":
                case "commandlog":
                    return editor.openCommandLogBuffer();
                case "tree":
                    return editor.showFileTree(args);
                case "git":
                    return editor.handleGitCommand(args);
                case "help":
                case "h":
                    editor.showHelp(args);
                    return "Showing help";
                case "wc":
                case "wordcount":
                    return handleWordCount();
                case "recent":
                    return editor.showRecentFiles();
                case "d":
                case "delete":
                    return handleDelete(range);
                case "files":
                    return editor.showFileFinder();
                case "folder":
                case "folders":
                    return editor.showFolderFinder();
                case "buffers":
                case "buf":
                    return editor.showBufferFinder();
                case "split":
                case "sp":
                    return editor.splitWindow(false);
                case "vsplit":
                case "vsp":
                    return editor.splitWindow(true);
                case "close":
                case "clo":
                    return editor.closeActiveWindow();
                case "grep":
                case "rg":
                    return editor.showGrepFinder(args);
                case "registers":
                case "reg":
                    return editor.showRegisters();
                case "marks":
                    return editor.showMarks();
                case "themes":
                    return editor.showThemes();
                case "zen":
                    return editor.toggleZenMode();
                case "normal":
                case "norm":
                    return handleNormal(args, range);
                case "reload":
                case "source":
                    return editor.reloadConfigFromDisk();
                case "":
                    return "";
                default:
                    try {
                        return editor.gotoLine(Integer.parseInt(resolvedCmd));
                    } catch (NumberFormatException ignored) {
                        return "Command not recognised: " + cmd;
                    }
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
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

    private String handleSet(String option) {
        if (option == null || option.isEmpty()) {
            return "Error: :set requires argument";
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
        int separator = option.indexOf('=');
        if (separator > 0) {
            String key = option.substring(0, separator).trim();
            String value = option.substring(separator + 1).trim();
            return editor.setConfigOption(key, value);
        }
        return "Unknown option: " + option;
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

    private String handleNormal(String keys, RangeParseResult range) {
        int currentLine = editor.getCurrentLineNumber();
        int start = range.hasRange() ? range.start : currentLine;
        int end = range.hasRange() ? range.resolveEnd(editor) : currentLine;
        return editor.executeNormalKeys(keys, start, end);
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
}
