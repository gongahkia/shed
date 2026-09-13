package shed;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

final class PaletteController {
    private record PaletteAction(String label, String command, String description) { }

    /*
     * Keep surface actions separate from raw Ex commands. A top-level command such as
     * :git is searchable, but it cannot communicate or invoke its graphical sub-surfaces.
     */
    private static final List<PaletteAction> SURFACE_ACTIONS = actions(
        action("Open File", "open file", "Choose and open a local file."),
        action("Open Folder", "open folder", "Choose a local folder, activate it as the workspace, and show its file tree."),
        action("Toggle File Tree", "tree", "Open or close the workspace file tree."),
        action("File Finder", "files", "Open the project file finder."),
        action("Recent Files", "recent", "Open the recently used files list."),
        action("Buffer Picker", "buffers", "Show every open buffer, filter the list, and switch to a selected buffer."),
        action("Switch to Next Open Buffer", "bn", "Switch to the next open buffer."),
        action("Switch to Previous Open Buffer", "bp", "Switch to the previous open buffer."),
        action("Close Current Buffer", "bdelete", "Close the current buffer, prompting when needed."),
        action("Discard Current Buffer", "bdelete!", "Close the current buffer without saving its changes."),
        action("Save Current Buffer", "write", "Write the current buffer."),
        action("Save All Buffers", "wall", "Write all modified file-backed buffers."),
        action("Save and Close Active Window", "wq", "Write the current buffer, then close its editor split."),
        action("Save All and Quit", "wqa", "Write all modified buffers and quit."),
        action("Close Active Window", "q", "Close the active editor split, prompting when its buffer has unsaved changes."),
        action("Close Active Window Without Saving", "q!", "Close the active editor split without saving its changes."),
        action("Quit All", "qa", "Quit all buffers, prompting when needed."),
        action("Quit All Without Saving", "qa!", "Quit Shed without saving modified buffers."),

        action("Split Below", "s", "Create a horizontal split below the active editor."),
        action("Split Right", "vs", "Create a vertical split beside the active editor."),
        action("Focus Next Split", "window next", "Focus the next editor split."),
        action("Focus Split Left", "window left", "Focus the split to the left."),
        action("Focus Split Right", "window right", "Focus the split to the right."),
        action("Focus Split Above", "window up", "Focus the split above."),
        action("Focus Split Below", "window down", "Focus the split below."),
        action("Equalize Splits", "window equalize", "Give each editor split an equal share of space."),
        action("Grow Current Split", "window grow", "Increase the active split's size."),
        action("Shrink Current Split", "window shrink", "Decrease the active split's size."),
        action("Zoom In", "zoom in", "Increase the entire interface scale by 10%."),
        action("Zoom Out", "zoom out", "Decrease the entire interface scale by 10%."),
        action("Reset UI Zoom", "zoom reset", "Restore the interface scale to 100%."),

        action("Open Quickfix", "copen", "Open the current quickfix list."),
        action("Next Quickfix Result", "cnext", "Move to the next quickfix result."),
        action("Previous Quickfix Result", "cprev", "Move to the previous quickfix result."),
        action("First Quickfix Result", "cfirst", "Move to the first quickfix result."),
        action("Last Quickfix Result", "clast", "Move to the last quickfix result."),
        action("Open Current Quickfix Result", "cc", "Open the selected quickfix result."),
        action("Problems", "problems", "Open the unified diagnostics and quickfix Problems panel."),
        action("Show Diagnostics", "diagnostics", "Show diagnostics for the active buffer."),
        action("Next Diagnostic", "dnext", "Move to the next diagnostic."),
        action("Previous Diagnostic", "dprev", "Move to the previous diagnostic."),

        action("Settings", "settings", "Open the Settings inspector, including font, landing-buffer, and Markdown-preview settings."),
        action("Open Settings TOML", "settings file", "Open the persisted settings.toml buffer."),
        action("Configuration Status", "config status", "Show configuration loading and recovery details."),
        action("Apply Suggested Config Repairs", "config heal", "Persist the reviewed deterministic configuration repairs."),
        action("Reload Configuration", "reload", "Reload configuration from disk."),
        action("Keymap Inspector", "keymap", "Inspect and edit validated keymap overlays."),
        action("Themes", "themes", "Show built-in themes."),
        action("Toggle Zen Mode", "zen", "Toggle the distraction-free Zen layout."),
        action("Toggle Goyo Mode", "goyo", "Toggle the Goyo layout."),
        action("Toggle Limelight", "limelight", "Toggle paragraph focus dimming."),
        action("Toggle Minimap", "minimap", "Toggle the minimap panel."),
        action("Undo History", "undolist", "Show the undo history summary."),
        action("Clear Search Highlights", "noh", "Clear active search highlights."),
        action("Command Log", "log", "Open the command log buffer."),
        action("Show Help", "help", "Open the built-in help buffer."),
        action("About Shed", "version", "Show Shed version and local runtime details."),

        action("Format Current Buffer", "format", "Format the active buffer with its selected formatter."),
        action("Formatter Policy", "formatter", "Configure the current language's formatter and format-on-save policy."),
        action("Markdown Preview", "markdownpreview", "Open the live native Markdown preview beside the source buffer."),
        action("Table of Contents", "toc", "Open the current Markdown document's table of contents."),
        action("Document Outline", "outline", "Open the current document outline."),
        action("Toggle Markdown Checkbox", "toggle", "Toggle the checkbox at the caret."),
        action("Insert Markdown Table", "table", "Insert the default Markdown table template."),
        action("Insert Link", "link", "Insert a link at the caret."),
        action("Insert Image", "image", "Choose and insert a local image reference."),
        action("Edit Snippets", "snippets edit", "Open the user snippets buffer for editing."),
        action("Toggle Bracket Colors", "bracketcolor", "Toggle matching bracket colorization."),
        action("Integrated Terminal", "terminal", "Open the integrated terminal split."),
        action("Word Count", "wordcount", "Show line, word, and character counts."),
        action("Registers", "registers", "Show register contents."),
        action("Yank Ring", "yankring", "Open the copied and deleted text history."),
        action("Marks", "marks", "Show marks for the active buffer."),

        action("Language Services", "lsp manage", "Open the local Language Services panel."),
        action("Show Completions", "lsp completion", "Request completion candidates at the caret."),
        action("Go to Definition", "definition", "Go to the definition at the caret."),
        action("Go to Type Definition", "typedefinition", "Go to the type definition at the caret."),
        action("Go to Implementation", "implementation", "Go to the implementation at the caret."),
        action("Highlight Symbol Occurrences", "lsp highlights", "Highlight server-reported occurrences for the symbol at the caret."),
        action("Show Hover Information", "hover", "Show language-service hover information at the caret."),
        action("Find References", "references", "Find references for the symbol at the caret."),
        action("Code Actions", "lsp codeaction", "Show diagnostic-anchored code actions at the caret."),
        action("Peek Definition", "peek definition", "Open a temporary read-only definition preview."),
        action("Peek Type Definition", "peek type", "Open a temporary read-only type-definition preview."),
        action("Incoming Call Hierarchy", "lsp calls incoming", "Open the incoming-call hierarchy for the symbol at the caret."),
        action("Outgoing Call Hierarchy", "lsp calls outgoing", "Open the outgoing-call hierarchy for the symbol at the caret."),
        action("Type Supertypes", "lsp typehierarchy supertypes", "Open the supertype hierarchy for the symbol at the caret."),
        action("Type Subtypes", "lsp typehierarchy subtypes", "Open the subtype hierarchy for the symbol at the caret."),
        action("Document Symbols", "symbols", "Open the document-symbol picker, with a local fallback."),

        action("Workspace Folders", "workspace ui", "Open the workspace-folder manager."),
        action("Workspace Index Status", "workspace index status", "Show the workspace search index status."),
        action("Project Replace", "projectreplace", "Open the reviewed project-wide replacement panel."),
        action("Tasks", "task", "Open the workspace Tasks panel."),
        action("Tests", "test", "Open the Test Explorer."),
        action("Import Coverage Report", "coverage ui", "Open Tests, then use Import Coverage to choose a local coverage report."),
        action("Debug", "debug", "Open the Debug tool panel."),
        action("Toolchain Status", "toolchain status", "Show selected local toolchains and advisory candidates."),
        action("Large File Status", "largefile", "Show active large-file limits and status."),
        action("Async Jobs", "jobs", "Show asynchronous jobs."),
        action("Git Changes", "git workbench", "Open the docked Git Changes workbench."),
        action("Git Status", "git status", "Show the active repository's Git status."),
        action("Git Branches", "git branches", "Show the active repository's branches."),
        action("Git Conflict Resolution", "git conflict", "Open the graphical conflict-resolution view."),
        action("Git History and Remotes", "git history", "Open graphical local history and explicit remote controls."),
        action("Git Worktrees and Stashes", "git worktrees", "Open graphical worktree and stash controls."),
        action("Git Graph", "git log", "Open graphical local Git history when enabled."),
        action("GitHub Pull Requests", "github prs", "Open pull-request review after GitHub review consent has been granted."),
        action("Remote Workspaces", "remote list", "Inspect explicit remote connections, active execution sessions, and loopback SSH forwards."),
        action("Dev Container", "container status", "Inspect the active workspace's Dev Container configuration and session routing state."),
        action("Compose Status", "compose status", "Show workspace Compose configuration status."),
        action("Database Status", "database status", "Show workspace database configuration status."),
        action("Workspace Integrations", "integration", "Show configured workspace integrations."),
        action("Plugin Manager", "plugin", "Show installed plugins."),
        action("Extension Manager", "extension", "Show installed extensions and their contributions."),
        action("Custom Editors", "customeditor", "Show available custom editors."),
        action("Update Status", "update", "Show the configured update channel's status.")
    );

    private static PaletteAction action(String label, String command, String description) {
        return new PaletteAction(label, command, description);
    }

    private static List<PaletteAction> actions(PaletteAction... actions) {
        Set<String> labels = new HashSet<>();
        Set<String> commands = new HashSet<>();
        for (PaletteAction action : actions) {
            if (action == null || action.label().isBlank() || action.command().isBlank()) {
                throw new IllegalStateException("Command palette actions require a label and command");
            }
            if (!labels.add(normalize(action.label()))) {
                throw new IllegalStateException("Duplicate command palette label: " + action.label());
            }
            if (!commands.add(normalize(action.command()))) {
                throw new IllegalStateException("Duplicate command palette command: " + action.command());
            }
        }
        return List.copyOf(Arrays.asList(actions));
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private final Texteditor editor;
    private final WorkspaceSearchCoordinator workspaceSearchCoordinator;
    private final WorkspaceReplaceCoordinator workspaceReplaceCoordinator;

    PaletteController(Texteditor editor) {
        this.editor = editor;
        this.workspaceSearchCoordinator = new WorkspaceSearchCoordinator(editor);
        this.workspaceReplaceCoordinator = new WorkspaceReplaceCoordinator(editor);
    }

    public String showCommandPalette() {
        List<String> candidates = commandPaletteCandidates();
        String selected = showPaletteDialog("Command Palette", candidates);
        if (selected == null || selected.isEmpty()) return "Command palette cancelled";
        PaletteAction action = surfaceAction(selected);
        return action == null ? "Command palette selection unavailable" : editor.commandHandler.execute(action.command());
    }

    static List<String> surfaceActionNames() {
        return SURFACE_ACTIONS.stream().map(PaletteAction::label).toList();
    }

    static List<String> commandPaletteCandidates() {
        return surfaceActionNames();
    }

    static List<String> surfaceActionCommands() {
        return SURFACE_ACTIONS.stream().map(PaletteAction::command).toList();
    }

    static String surfaceActionCommand(String label) {
        PaletteAction action = surfaceAction(label);
        return action == null ? null : action.command();
    }

    private static PaletteAction surfaceAction(String label) {
        if (label == null) return null;
        for (PaletteAction action : SURFACE_ACTIONS) {
            if (action.label().equals(label)) return action;
        }
        return null;
    }


    public String showBufferFinder() {
        List<String> candidates = bufferPickerCandidates(editor.buffers, editor.currentBufferIndex);
        String selection = showPaletteDialog("Open Buffers", candidates, value -> "Switch to " + value);
        if (selection == null || selection.isEmpty()) {
            return "Buffer finder cancelled";
        }
        int colon = selection.indexOf(':');
        if (colon > 0) {
            try {
                int bufferIndex = Integer.parseInt(selection.substring(0, colon).trim()) - 1;
                editor.switchToBuffer(bufferIndex);
                return "Switched to buffer";
            } catch (NumberFormatException ignored) {
            }
        }
        return "Buffer finder cancelled";
    }

    static List<String> bufferPickerCandidates(List<FileBuffer> buffers, int currentBufferIndex) {
        if (buffers == null || buffers.isEmpty()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>(buffers.size());
        for (int i = 0; i < buffers.size(); i++) {
            FileBuffer buffer = buffers.get(i);
            StringBuilder candidate = new StringBuilder().append(i + 1).append(": ")
                .append(buffer == null ? "[Unavailable]" : buffer.getDisplayName());
            if (buffer != null && buffer.isModified()) {
                candidate.append(" [+]");
            }
            if (i == currentBufferIndex) {
                candidate.append(" (current)");
            }
            candidates.add(candidate.toString());
        }
        return List.copyOf(candidates);
    }


    public String showGrepFinder(String pattern) {
        return workspaceSearchCoordinator.search(pattern);
    }


    String handleProjectReplace(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "ui".equalsIgnoreCase(trimmed)) {
            editor.showToolWindow(ToolWindowHost.Tab.REPLACE);
            return "Project Replace panel opened";
        }
        if (trimmed.equalsIgnoreCase("text")) return workspaceReplaceCoordinator.handle("settings");
        if (trimmed.regionMatches(true, 0, "text ", 0, 5)) return workspaceReplaceCoordinator.handle(trimmed.substring(5).trim());
        return workspaceReplaceCoordinator.handle(argument);
    }

    WorkspaceReplaceCoordinator workspaceReplaceCoordinator() { return workspaceReplaceCoordinator; }


    String showHeuristicSymbols(String argument) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null) {
            return "No buffer";
        }
        List<SymbolService.Symbol> symbols = editor.symbolService.collectSymbols(editor.writingArea.getText(), buffer.getFileType());
        if (symbols.isEmpty()) {
            return "No symbols found";
        }
        String query = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        List<SymbolService.Symbol> filtered = new ArrayList<>();
        for (SymbolService.Symbol symbol : symbols) {
            if (query.isEmpty()) {
                filtered.add(symbol);
                continue;
            }
            String haystack = (symbol.getName() + " " + symbol.getKind()).toLowerCase(Locale.ROOT);
            if (haystack.contains(query)) {
                filtered.add(symbol);
            }
        }
        if (filtered.isEmpty()) {
            return "No symbols matched: " + query;
        }

        Map<String, SymbolService.Symbol> candidateMap = new LinkedHashMap<>();
        for (SymbolService.Symbol symbol : filtered) {
            String candidate = formatSymbolCandidate(symbol);
            if (candidateMap.containsKey(candidate)) {
                candidate = candidate + "  [#" + symbol.getLine() + "]";
            }
            candidateMap.put(candidate, symbol);
        }
        List<String> candidates = new ArrayList<>(candidateMap.keySet());
        String selection = showPaletteDialog("Symbols", candidates, value -> describeSymbolCandidate(value, candidateMap, symbols));
        if (selection == null || selection.isEmpty()) {
            return "Symbols cancelled";
        }
        SymbolService.Symbol selected = candidateMap.get(selection);
        if (selected == null) {
            return "Invalid symbol selection";
        }
        return editor.gotoLine(selected.getLine());
    }

    String showLspSymbols(List<LspClient.NavigationSymbol> symbols, String argument, boolean workspace) {
        if (symbols == null || symbols.isEmpty()) return "No symbols found";
        String query = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        Map<String, LspClient.NavigationSymbol> candidates = new LinkedHashMap<>();
        for (LspClient.NavigationSymbol symbol : symbols) {
            if (symbol == null || symbol.getName().isBlank()) continue;
            String haystack = (symbol.getName() + " " + symbol.getDetail() + " " + symbolKind(symbol.getKind())).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            String candidate = formatLspSymbolCandidate(symbol, workspace);
            int duplicate = 2;
            String unique = candidate;
            while (candidates.containsKey(unique)) unique = candidate + "  [#" + duplicate++ + "]";
            candidates.put(unique, symbol);
        }
        if (candidates.isEmpty()) return "No symbols matched: " + query;
        String selection = showPaletteDialog(workspace ? "Workspace Symbols" : "Symbols", new ArrayList<>(candidates.keySet()),
            value -> describeLspSymbol(value, candidates));
        if (selection == null || selection.isEmpty()) return "Symbols cancelled";
        LspClient.NavigationSymbol selected = candidates.get(selection);
        return selected == null ? "Invalid symbol selection" : editor.openLspSymbol(selected);
    }

    String showWorkspaceHeuristicSymbols(List<WorkspaceSymbolService.Match> symbols, String argument, boolean truncated) {
        if (symbols == null || symbols.isEmpty()) return "No local workspace symbols found";
        Map<String, WorkspaceSymbolService.Match> candidates = new LinkedHashMap<>();
        for (WorkspaceSymbolService.Match symbol : symbols) {
            if (symbol == null || symbol.name().isBlank()) continue;
            String candidate = formatWorkspaceSymbolCandidate(symbol);
            int duplicate = 2;
            String unique = candidate;
            while (candidates.containsKey(unique)) unique = candidate + "  [#" + duplicate++ + "]";
            candidates.put(unique, symbol);
        }
        if (candidates.isEmpty()) return "No local workspace symbols found";
        String title = truncated ? "Workspace Symbols (local, limited)" : "Workspace Symbols (local)";
        String selection = showPaletteDialog(title, new ArrayList<>(candidates.keySet()), value -> describeWorkspaceSymbol(value, candidates));
        if (selection == null || selection.isEmpty()) return "Workspace symbols cancelled";
        WorkspaceSymbolService.Match selected = candidates.get(selection);
        if (selected == null) return "Invalid workspace symbol selection";
        try {
            File file = new File(selected.filePath());
            if (!file.isFile()) return "Workspace symbol file is no longer available: " + selected.relativePath();
            editor.openFile(file);
            return editor.gotoLine(selected.line());
        } catch (IOException error) {
            return "Could not open workspace symbol: " + error.getMessage();
        }
    }


    String formatSymbolCandidate(SymbolService.Symbol symbol) {
        StringBuilder indent = new StringBuilder();
        for (int i = 1; i < symbol.getLevel(); i++) {
            indent.append("  ");
        }
        return String.format("%4d  %-8s  %s%s",
            symbol.getLine(),
            symbol.getKind(),
            indent,
            symbol.getName());
    }

    private String formatLspSymbolCandidate(LspClient.NavigationSymbol symbol, boolean workspace) {
        StringBuilder indent = new StringBuilder();
        for (int i = 1; i < symbol.getLevel(); i++) indent.append("  ");
        String detail = symbol.getDetail().isBlank() ? "" : "  " + symbol.getDetail();
        String path = workspace ? "  " + displayUriPath(symbol.getUri()) : "";
        return String.format("%4d  %-10s  %s%s%s%s", symbol.getLine() + 1, symbolKind(symbol.getKind()), indent,
            symbol.getName(), detail, path);
    }

    private String formatWorkspaceSymbolCandidate(WorkspaceSymbolService.Match symbol) {
        StringBuilder indent = new StringBuilder();
        for (int index = 1; index < symbol.level(); index++) indent.append("  ");
        return String.format("%4d  %-10s  %s%s  %s", symbol.line(), symbol.kind(), indent, symbol.name(), symbol.relativePath());
    }

    private String describeWorkspaceSymbol(String selection, Map<String, WorkspaceSymbolService.Match> candidates) {
        WorkspaceSymbolService.Match symbol = candidates.get(selection);
        if (symbol == null) return selection == null ? "Select a symbol to jump." : selection;
        return "Line " + symbol.line() + " [" + symbol.kind() + "]\n" + symbol.relativePath();
    }

    private String describeLspSymbol(String selection, Map<String, LspClient.NavigationSymbol> candidates) {
        LspClient.NavigationSymbol symbol = candidates.get(selection);
        if (symbol == null) return selection == null ? "Select a symbol to jump." : selection;
        String detail = symbol.getDetail().isBlank() ? "" : "\n" + symbol.getDetail();
        return "Line " + (symbol.getLine() + 1) + " [" + symbolKind(symbol.getKind()) + "]" + detail + "\n" + displayUriPath(symbol.getUri());
    }

    private static String symbolKind(int kind) {
        return switch (kind) {
            case 5 -> "class";
            case 6 -> "method";
            case 7 -> "property";
            case 8 -> "field";
            case 11 -> "interface";
            case 12 -> "function";
            case 13 -> "variable";
            case 22 -> "enum";
            case 23 -> "constructor";
            case 24 -> "namespace";
            default -> "symbol";
        };
    }

    private static String displayUriPath(String uri) {
        if (uri == null || uri.isBlank()) return "";
        try {
            java.net.URI parsed = java.net.URI.create(uri);
            if ("file".equalsIgnoreCase(parsed.getScheme())) return new File(parsed).getPath();
        } catch (Exception ignored) {
        }
        return uri;
    }


    String describeSymbolCandidate(
        String selection,
        Map<String, SymbolService.Symbol> candidateMap,
        List<SymbolService.Symbol> allSymbols
    ) {
        if (selection == null || selection.isBlank()) {
            return "Select a symbol to jump.";
        }
        SymbolService.Symbol symbol = candidateMap.get(selection);
        if (symbol == null) {
            return selection;
        }
        List<SymbolService.Symbol> trail = editor.symbolService.breadcrumbTrail(allSymbols, symbol.getLine());
        StringBuilder breadcrumb = new StringBuilder();
        for (int i = 0; i < trail.size(); i++) {
            if (i > 0) {
                breadcrumb.append(" > ");
            }
            breadcrumb.append(trail.get(i).getName());
        }
        return "Line " + symbol.getLine()
            + " [" + symbol.getKind() + "]\n"
            + (breadcrumb.length() == 0 ? symbol.getName() : breadcrumb.toString());
    }


    void collectFiles(File directory, List<String> results) {
        if (directory == null || results.size() >= 200 || shouldSkipHiddenPath(directory)) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (results.size() >= 200) {
                return;
            }
            if (file.isDirectory()) {
                collectFiles(file, results);
            } else {
                results.add(file.getPath());
            }
        }
    }


    List<String> grepFiles(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> rgResults = grepFilesWithRipgrep(pattern);
        if (!rgResults.isEmpty()) {
            return rgResults;
        }
        List<String> results = new ArrayList<>();
        grepFilesRecursive(new File("."), pattern, results);
        return results;
    }

    List<String> grepFilesWithRipgrep(String pattern) {
        List<String> results = new ArrayList<>();
        String rg = findExecutableOnPath("rg");
        if (rg == null) {
            return results;
        }
        try {
            Process process = new ProcessBuilder(
                rg, "--line-number", "--no-heading", "--color", "never", "--", pattern, "."
            ).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        results.add(line);
                    }
                    if (results.size() >= 200) {
                        process.destroyForcibly();
                        return results;
                    }
                }
            }
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ArrayList<>();
            }
            return process.exitValue() == 0 ? results : new ArrayList<>();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    String findExecutableOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String entry : path.split(File.pathSeparator)) {
            File candidate = new File(entry, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }


    void grepFilesRecursive(File directory, String pattern, List<String> results) {
        if (directory == null || results.size() >= 200 || shouldSkipHiddenPath(directory)) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (results.size() >= 200) {
                return;
            }
            if (file.isDirectory()) {
                grepFilesRecursive(file, pattern, results);
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(pattern)) {
                        results.add(file.getPath() + ":" + (i + 1) + ":" + lines.get(i).trim());
                    }
                    if (results.size() >= 200) {
                        return;
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }


    String describeCommandPaletteCandidate(String selection) {
        if (selection == null || selection.isBlank()) {
            return "Type to fuzzy-filter actions and commands, then press Enter.";
        }
        PaletteAction action = surfaceAction(selection);
        if (action != null) return action.description();
        String cmd = selection.startsWith(":") ? selection.substring(1) : selection;
        int split = cmd.indexOf(' ');
        String base = (split >= 0 ? cmd.substring(0, split) : cmd).toLowerCase(Locale.ROOT);
        switch (base) {
            case "w":
            case "write":
                return "Write current buffer to disk.";
            case "q":
            case "quit":
            case "q!":
                return "Close the active editor window.";
            case "wq":
            case "x":
                return "Write the current buffer, then close its editor window.";
            case "e":
            case "edit":
                return "Open file into a buffer.";
            case "bn":
            case "bnext":
                return "Switch to next buffer.";
            case "bp":
            case "bprev":
                return "Switch to previous buffer.";
            case "buffers":
            case "buf":
                return "Show all open buffers, filter the list, and switch to a selected buffer.";
            case "bd":
            case "bdelete":
                return "Delete current buffer.";
            case "set":
                return "Set runtime option (use :set! key=value to persist).";
            case "settings":
                return "Open global settings file.";
            case "config":
                return "Open settings or persist with :config save.";
            case "keymap":
            case "keymaps":
                return "Inspect, search, save, or reset validated Vim keymap overlays.";
            case "log":
            case "commandlog":
                return "Open command log scratch buffer.";
            case "session":
            case "sessions":
                return "Save/load/list named sessions.";
            case "workspace":
            case "ws":
                return "Save/load/list profiles; inspect or control persistent workspace indexing.";
            case "jobs":
                return "Show async job list.";
            case "jobcancel":
            case "jobkill":
                return "Cancel async job by id.";
            case "drop":
                return "Run async command against current file path.";
            case "task":
                return "Run project tasks (:task test/build) with quickfix integration.";
            case "remote":
                return "Inspect or explicitly control remote workspace mirrors, session routing, and SSH forwards.";
            case "container":
                return "Inspect or explicitly control the local Dev Container CLI bridge.";
            case "test":
                return "Open or control the explicit-refresh Test Explorer.";
            case "coverage":
            case "cov":
                return "Import, clear, or inspect session-local coverage in Tests.";
            case "help":
            case "h":
                return "Open help text (topic optional).";
            case "wc":
            case "wordcount":
                return "Show line/word/character counts.";
            case "recent":
                return "Show recent files scratch buffer.";
            case "d":
            case "delete":
                return "Delete current line or a range.";
            case "files":
                return "Open project file finder.";
            case "projectreplace":
            case "preplace":
                return "Preview and explicitly apply selected project-wide literal replacements.";
            case "split":
            case "sp":
                return "Create horizontal split.";
            case "vsplit":
            case "vsp":
                return "Create vertical split.";
            case "close":
            case "clo":
                return "Close active split/window.";
            case "tree":
                return "Open tree pane and perform file operations.";
            case "git":
                return "Run integrated git subcommands.";
            case "grep":
            case "rg":
                return "Search project text and populate quickfix.";
            case "copen":
                return "Open quickfix list.";
            case "cclose":
                return "Close quickfix list.";
            case "cnext":
            case "cn":
                return "Jump to next quickfix entry.";
            case "cprev":
            case "cp":
                return "Jump to previous quickfix entry.";
            case "cfirst":
                return "Jump to first quickfix entry.";
            case "clast":
                return "Jump to last quickfix entry.";
            case "cc":
                return "Jump to selected quickfix entry.";
            case "lsp":
                return "Run LSP actions and server management.";
            case "debug":
            case "dap":
                return "Select and control explicit Debug Adapter Protocol sessions.";
            case "definition":
                return "Jump to symbol definition.";
            case "typedefinition":
            case "typedef":
                return "Jump to the type definition behind the symbol.";
            case "highlights":
            case "documenthighlights":
                return "Highlight server-reported occurrences for the symbol at the caret.";
            case "hover":
                return "Show hover docs in scratch buffer.";
            case "references":
                return "Find references and open quickfix.";
            case "diagnostics":
            case "diag":
            case "ldiag":
                return "Push diagnostics into quickfix.";
            case "problems":
            case "problem":
                return "Open unified LSP and quickfix problems.";
            case "dnext":
            case "dn":
                return "Jump to next diagnostic.";
            case "dprev":
            case "dp":
                return "Jump to previous diagnostic.";
            case "symbols":
            case "sym":
                return "Open LSP symbol picker, with local fallback.";
            case "registers":
            case "reg":
                return "Show register contents.";
            case "yankring":
            case "pastepicker":
            case "yr":
                return "Pick from yank/delete history and paste.";
            case "marks":
                return "Show mark list for active buffer.";
            case "themes":
                return "Show and switch built-in themes.";
            case "zen":
                return "Toggle Goyo layout with Limelight.";
            case "goyo":
                return "Toggle centered Goyo layout.";
            case "limelight":
                return "Toggle paragraph focus dimming.";
            case "minimap":
                return "Toggle minimap side panel.";
            case "normal":
            case "norm":
                return "Execute normal-mode keys on current/ranged lines.";
            case "reload":
            case "source":
                return "Reload ~/.shed/config.toml from disk.";
            case "clean":
            case "shedclean":
                return "Remove Shed metadata files.";
            case "noh":
            case "nohlsearch":
                return "Clear search highlights.";
            case "plugin":
            case "plugins":
                return "Manage plugins and package install/update/pin flows.";
            case "palette":
            case "commands":
                return "Open command palette.";
            case "undolist":
            case "undotree":
                return "Show undo history.";
            case "wa":
            case "wall":
                return "Write all modified buffers.";
            case "qa":
            case "qall":
                return "Quit all buffers/windows.";
            case "wqa":
            case "wqall":
            case "xa":
            case "xall":
                return "Write all buffers, then quit all.";
            case "toc":
                return "Open markdown table of contents.";
            case "outline":
                return "Open markdown outline split.";
            case "toggle":
            case "checkbox":
                return "Toggle markdown checkbox under cursor.";
            case "table":
                return "Insert/align/sort/edit markdown table.";
            case "link":
                return "Insert markdown link template.";
            case "img":
            case "image":
                return "Insert markdown image template.";
            case "snippets":
            case "snippet":
                return "List snippets for current file type.";
            case "bracketcolor":
            case "bracketcolors":
                return "Toggle bracket pair colorization.";
            case "term":
            case "terminal":
                return "Open an integrated shell split.";
            case "conceal":
            case "conceallevel":
                return "Set markdown conceal level (0/1/2).";
            default:
                return "Run command :" + base;
        }
    }


    String describeGrepCandidate(String selection) {
        if (selection == null || selection.isBlank()) {
            return "No match selected.";
        }
        String[] parts = selection.split(":", 3);
        if (parts.length >= 3) {
            return "Open " + parts[0] + " line " + parts[1] + "\n" + parts[2];
        }
        return selection;
    }


    String showPaletteDialog(String title, List<String> candidates) {
        return showPaletteDialog(title, candidates, null, null);
    }

    String showPaletteDialog(String title, List<String> candidates, ListCellRenderer<? super String> renderer) {
        return showPaletteDialog(title, candidates, null, renderer);
    }

    String showPaletteDialog(String title, List<String> candidates, PalettePreviewProvider previewProvider) {
        return showPaletteDialog(title, candidates, previewProvider, null);
    }

    private String showPaletteDialog(String title, List<String> candidates, PalettePreviewProvider previewProvider,
                                     ListCellRenderer<? super String> renderer) {
        // undecorated modal dialog styled as floating picker
        JDialog dialog = new JDialog(editor, title, true);
        dialog.setUndecorated(true);
        dialog.getRootPane().setBorder(javax.swing.BorderFactory.createLineBorder(editor.configManager.getCaretColor(), 1));
        dialog.setLayout(new BorderLayout(6, 6));
        dialog.getContentPane().setBackground(editor.configManager.getCommandBarBackground());
        java.awt.Font uiFont = editor.resolveUiFont();
        JTextField filterField = new JTextField();
        AccessibilitySupport.describe(filterField, title + " filter", "Filter available " + title.toLowerCase(Locale.ROOT) + " entries.");
        filterField.setFont(uiFont);
        filterField.setBackground(editor.configManager.getCommandBarBackground());
        filterField.setForeground(editor.configManager.getCommandBarForeground());
        filterField.setCaretColor(editor.configManager.getCaretColor());
        filterField.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, editor.configManager.getCaretColor()),
            javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        DefaultListModel<String> model = new DefaultListModel<>();
        for (String candidate : candidates) model.addElement(candidate);
        JList<String> list = new JList<>(model);
        AccessibilitySupport.describe(list, title + " results", "Matching " + title.toLowerCase(Locale.ROOT) + " entries. Use Up and Down to select, Enter to open, or Escape to dismiss.");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(uiFont);
        list.setBackground(editor.configManager.getCommandBarBackground());
        list.setForeground(editor.configManager.getCommandBarForeground());
        list.setSelectionBackground(editor.configManager.getSelectionColor());
        list.setSelectionForeground(editor.configManager.getSelectionTextColor());
        if (renderer != null) list.setCellRenderer(renderer);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        JLabel titleLabel = new JLabel(" " + title);
        titleLabel.setForeground(editor.configManager.getCaretColor());
        titleLabel.setFont(uiFont.deriveFont(java.awt.Font.BOLD));
        titleLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 2, 6));
        JTextArea previewArea = new JTextArea() {
            @Override public Dimension getPreferredSize() {
                return editor.editorUiController.scaleUiDimension(260, 320);
            }
        };
        AccessibilitySupport.describe(previewArea, title + " preview", "Preview of the selected " + title.toLowerCase(Locale.ROOT) + " entry.");
        previewArea.setEditable(false);
        previewArea.setLineWrap(true);
        previewArea.setWrapStyleWord(true);
        previewArea.setFocusable(false);
        previewArea.setFont(uiFont.deriveFont(Math.max(11f, uiFont.getSize2D() - 1f)));
        previewArea.setBackground(editor.configManager.getStatusBarBackground());
        previewArea.setForeground(editor.configManager.getStatusBarForeground());
        previewArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, editor.configManager.getCaretColor()),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        previewArea.setVisible(previewProvider != null);
        final Runnable syncPreview = () -> {
            String value = list.getSelectedValue();
            if (previewProvider == null) {
                previewArea.setText(value == null ? "" : value);
                return;
            }
            String preview = previewProvider.preview(value);
            previewArea.setText(preview == null ? "" : preview);
            previewArea.setCaretPosition(0);
        };
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refilter(); }
            public void removeUpdate(DocumentEvent e) { refilter(); }
            public void changedUpdate(DocumentEvent e) { refilter(); }
            private void refilter() {
                String query = filterField.getText();
                model.clear();
                if (query.isEmpty()) { for (String c2 : candidates) model.addElement(c2); }
                else { for (String m : editor.fuzzyMatchService.matchStrings(query, candidates, 0)) model.addElement(m); }
                if (!model.isEmpty()) list.setSelectedIndex(0);
                syncPreview.run();
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                syncPreview.run();
            }
        });
        final String[] selection = new String[1];
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) { selection[0] = list.getSelectedValue(); dialog.dispose(); } }
        });
        filterField.addActionListener(e -> { selection[0] = list.getSelectedValue(); dialog.dispose(); });
        filterField.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) dialog.dispose();
                else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_DOWN) { int idx = list.getSelectedIndex(); if (idx < model.getSize() - 1) list.setSelectedIndex(idx + 1); e.consume(); }
                else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_UP) { int idx = list.getSelectedIndex(); if (idx > 0) list.setSelectedIndex(idx - 1); e.consume(); }
            }
        });
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titleLabel, BorderLayout.NORTH);
        header.add(filterField, BorderLayout.SOUTH);
        dialog.add(header, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list) {
            @Override public Dimension getPreferredSize() {
                return editor.editorUiController.scaleUiDimension(600, 320);
            }
        };
        sp.setBorder(null);
        dialog.add(sp, BorderLayout.CENTER);
        if (previewProvider != null) dialog.add(previewArea, BorderLayout.EAST);
        syncPreview.run();
        Dimension targetSize = previewProvider == null ? new Dimension(620, 400) : new Dimension(720, 420);
        editor.editorUiController.prepareDialog(dialog, targetSize.width, targetSize.height);
        dialog.pack();
        dialog.setLocationRelativeTo(editor);
        dialog.setVisible(true);
        return selection[0];
    }


    boolean shouldSkipHiddenPath(File file) {
        if (file == null) {
            return true;
        }
        String path = file.getPath();
        if (".".equals(path) || "./".equals(path)) {
            return false;
        }
        return file.getName().startsWith(".");
    }


    public String showRegisters() {
        List<String> lines = editor.registerManager.getDisplayLines();
        if (lines.isEmpty()) {
            return "No registers populated";
        }
        editor.showScratchBuffer("[registers]", String.join("\n", lines));
        return "Showing registers";
    }


    public String showMarks() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || buffer.getMarks().isEmpty()) {
            return "No marks set";
        }
        List<String> lines = new ArrayList<>();
        for (java.util.Map.Entry<Character, Integer> entry : buffer.getMarks().entrySet()) {
            lines.add(entry.getKey() + " " + describeOffset(entry.getValue()));
        }
        editor.showScratchBuffer("[marks]", String.join("\n", lines));
        return "Showing marks";
    }


    String trimForRegisterDisplay(String value) {
        String singleLine = value.replace("\n", "\\n");
        if (singleLine.length() > 80) {
            return singleLine.substring(0, 77) + "...";
        }
        return singleLine;
    }


    String describeOffset(int offset) {
        try {
            int line = editor.writingArea.getLineOfOffset(Math.min(offset, editor.writingArea.getText().length()));
            int col = offset - editor.writingArea.getLineStartOffset(line);
            return (line + 1) + ":" + (col + 1);
        } catch (BadLocationException e) {
            return "1:1";
        }
    }

}
