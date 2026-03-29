// Command Handler Class
// Parses and executes ex-style commands (:w, :q, :e, etc.)

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CommandHandler {
    private Texteditor editor;
    private DateTimeFormatter timeFormat;

    public CommandHandler(Texteditor editor) {
        this.editor = editor;
        this.timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");
    }

    // Main command execution method
    public String execute(String command) {
        if (command == null || command.isEmpty()) {
            return "No command entered";
        }

        // Remove leading ':' if present
        if (command.startsWith(":")) {
            command = command.substring(1);
        }

        // Remove leading '/' for search commands
        boolean isSearch = command.startsWith("/");
        if (isSearch) {
            command = command.substring(1);
            return handleSearch(command);
        }

        if (command.equals("s") || command.equals("%s") ||
            command.startsWith("s/") || command.startsWith("%s/")) {
            return handleSubstitute(command);
        }

        // Parse command and arguments
        String[] parts = command.trim().split("\\s+");
        String cmd = parts[0];
        boolean force = cmd.endsWith("!");
        if (force) {
            cmd = cmd.substring(0, cmd.length() - 1);
        }

        try {
            switch (cmd) {
                case "w":
                case "write":
                    return handleWrite();

                case "q":
                case "quit":
                    return handleQuit(force);

                case "wq":
                case "x":
                    return handleWriteQuit();

                case "e":
                case "edit":
                    if (parts.length < 2) {
                        return "Error: :e requires filename argument";
                    }
                    return handleEdit(parts[1]);

                case "bn":
                case "bnext":
                    return handleBufferNext();

                case "bp":
                case "bprev":
                    return handleBufferPrev();

                case "ls":
                case "buffers":
                    return handleListBuffers();

                case "bd":
                case "bdelete":
                    return handleBufferDelete(force);

                case "set":
                    if (parts.length < 2) {
                        return "Error: :set requires argument";
                    }
                    return handleSet(parts[1]);

                case "help":
                    String topic = parts.length > 1 ? parts[1] : "";
                    return handleHelp(topic);

                case "wc":
                case "wordcount":
                    return handleWordCount();

                case "recent":
                    return handleRecent();
                default:
                    // Check if it's a line number
                    try {
                        int lineNum = Integer.parseInt(cmd);
                        return handleGotoLine(lineNum);
                    } catch (NumberFormatException e) {
                        return "Command not recognised: " + cmd;
                    }
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // :w - Write (save) current buffer
    private String handleWrite() {
        try {
            FileBuffer buffer = editor.getCurrentBuffer();
            if (buffer == null) {
                return "Error: No file open";
            }

            buffer.setContent(editor.getTextArea().getText());
            buffer.save();

            String timestamp = timeFormat.format(LocalDateTime.now());
            return "\"" + buffer.getDisplayName() + "\" " +
                   buffer.getLineCount() + "L written " + timestamp;
        } catch (IOException e) {
            return "Error saving file: " + e.getMessage();
        }
    }

    // :q - Quit current buffer
    private String handleQuit(boolean force) {
        return editor.requestQuit(force);
    }

    // :wq - Write and quit
    private String handleWriteQuit() {
        String writeResult = handleWrite();
        if (writeResult.startsWith("Error")) {
            return writeResult;
        }
        editor.requestQuit(true);
        return "Saved and quitting";
    }

    // :e filename - Edit file
    private String handleEdit(String filename) {
        try {
            File file = new File(filename);
            editor.openFile(file);
            return "Opened: " + filename;
        } catch (Exception e) {
            return "Error opening file: " + e.getMessage();
        }
    }

    // :bn - Next buffer
    private String handleBufferNext() {
        return editor.nextBuffer();
    }

    // :bp - Previous buffer
    private String handleBufferPrev() {
        return editor.prevBuffer();
    }

    // :ls - List buffers
    private String handleListBuffers() {
        return editor.listBuffers();
    }

    // :bd - Delete buffer
    private String handleBufferDelete(boolean force) {
        return editor.deleteBuffer(force);
    }

    // :set - Toggle settings
    private String handleSet(String option) {
        if (option.equals("nu") || option.equals("number")) {
            editor.toggleLineNumbers(true);
            return "Line numbers enabled";
        } else if (option.equals("nonu") || option.equals("nonumber")) {
            editor.toggleLineNumbers(false);
            return "Line numbers disabled";
        } else {
            return "Unknown option: " + option;
        }
    }

    // /pattern - Search
    private String handleSearch(String pattern) {
        if (pattern.isEmpty()) {
            return "Error: Empty search pattern";
        }
        return editor.search(pattern);
    }

    // :help - Show help
    private String handleHelp(String topic) {
        editor.showHelp(topic);
        return "Showing help";
    }

    // :wc - Word count
    private String handleWordCount() {
        String text = editor.getTextArea().getText();
        int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
        int words = text.isEmpty() ? 0 : text.trim().split("\\s+").length;
        int chars = text.length();

        return lines + " lines, " + words + " words, " + chars + " characters";
    }

    // :recent - Show recent files
    private String handleRecent() {
        return editor.showRecentFiles();
    }

    // :s/old/new or :%s/old/new/g - Substitute
    private String handleSubstitute(String command) {
        try {
            SubstituteCommand parsed = parseSubstitute(command);
            if (parsed == null) {
                return "Error: Invalid substitute syntax";
            }

            return editor.substitute(
                parsed.searchPattern,
                parsed.replacement,
                parsed.wholeBuffer,
                parsed.replaceAll
            );
        } catch (Exception e) {
            return "Error in substitute: " + e.getMessage();
        }
    }

    private SubstituteCommand parseSubstitute(String command) {
        boolean wholeBuffer;
        if (command.startsWith("%s/")) {
            wholeBuffer = true;
            command = command.substring(3);
        } else if (command.startsWith("s/")) {
            wholeBuffer = false;
            command = command.substring(2);
        } else {
            return null;
        }

        String[] parts = command.split("/", -1);
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

        return new SubstituteCommand(searchPattern, replacement, wholeBuffer, "g".equals(flags));
    }

    private static class SubstituteCommand {
        private final String searchPattern;
        private final String replacement;
        private final boolean wholeBuffer;
        private final boolean replaceAll;

        private SubstituteCommand(String searchPattern, String replacement, boolean wholeBuffer, boolean replaceAll) {
            this.searchPattern = searchPattern;
            this.replacement = replacement;
            this.wholeBuffer = wholeBuffer;
            this.replaceAll = replaceAll;
        }
    }

    // :45 - Go to line number
    private String handleGotoLine(int lineNum) {
        return editor.gotoLine(lineNum);
    }
}
