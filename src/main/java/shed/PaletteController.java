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
    private final Texteditor editor;
    private final WorkspaceSearchCoordinator workspaceSearchCoordinator;
    private final WorkspaceReplaceCoordinator workspaceReplaceCoordinator;

    PaletteController(Texteditor editor) {
        this.editor = editor;
        this.workspaceSearchCoordinator = new WorkspaceSearchCoordinator(editor);
        this.workspaceReplaceCoordinator = new WorkspaceReplaceCoordinator(editor);
    }

    public String showCommandPalette() {
        List<String> commands = editor.commandHandler.getCommandNames();
        List<String> candidates = new ArrayList<>();
        for (String cmd : commands) {
            candidates.add(":" + cmd);
        }
        String selected = showPaletteDialog("Command Palette", candidates, this::describeCommandPaletteCandidate);
        if (selected == null || selected.isEmpty()) return "Command palette cancelled";
        String cmd = selected.startsWith(":") ? selected.substring(1) : selected;
        return editor.commandHandler.execute(cmd);
    }


    public String showBufferFinder() {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < editor.buffers.size(); i++) {
            candidates.add((i + 1) + ": " + editor.buffers.get(i).getDisplayName());
        }
        String selection = showPaletteDialog("Buffers", candidates, value -> "Switch to " + value);
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


    public String showSymbols(String argument) {
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
            return "Type to fuzzy-filter commands, then press Enter.";
        }
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
                return "Quit current buffer/editor.";
            case "wq":
            case "x":
                return "Write buffer, then quit.";
            case "e":
            case "edit":
                return "Open file into a buffer.";
            case "bn":
            case "bnext":
                return "Switch to next buffer.";
            case "bp":
            case "bprev":
                return "Switch to previous buffer.";
            case "ls":
                return "List open buffers.";
            case "buffers":
            case "buf":
                return "Open buffer picker.";
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
            case "folder":
            case "folders":
                return "Pick folder, then open file picker.";
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
            case "hover":
                return "Show hover docs in scratch buffer.";
            case "references":
                return "Find references and open quickfix.";
            case "diagnostics":
            case "diag":
            case "ldiag":
                return "Push diagnostics into quickfix.";
            case "dnext":
            case "dn":
                return "Jump to next diagnostic.";
            case "dprev":
            case "dp":
                return "Jump to previous diagnostic.";
            case "symbols":
            case "sym":
                return "Open symbol picker and jump by class/function/heading.";
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
            case "theater":
                return "Apply dramatic UI preset: off/subtle/full.";
            case "zen":
                return "Toggle centered zen layout.";
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
        return showPaletteDialog(title, candidates, null);
    }


    void animatePaletteDialogOpen(JDialog dialog, Dimension targetSize) {
        if (!editor.dramaticCommandPaletteEnabled || !editor.dramaticMotionAllowed()) {
            return;
        }
        int steps = Math.max(5, Math.min(12, editor.dramaticAnimationMs / 20));
        int startWidth = Math.max(420, (int) Math.round(targetSize.width * 0.88));
        int startHeight = Math.max(260, (int) Math.round(targetSize.height * 0.88));
        Point target = dialog.getLocation();
        int dx = (targetSize.width - startWidth) / 2;
        int dy = 18;
        dialog.setSize(startWidth, startHeight);
        dialog.setLocation(target.x + dx, target.y + dy);
        javax.swing.Timer timer = new javax.swing.Timer(editor.animationDelayForSteps(steps), null);
        final int[] tick = new int[] {0};
        timer.addActionListener(ev -> {
            double t = editor.easeOut((double) tick[0] / steps);
            int width = (int) Math.round(startWidth + (targetSize.width - startWidth) * t);
            int height = (int) Math.round(startHeight + (targetSize.height - startHeight) * t);
            int x = target.x + (targetSize.width - width) / 2;
            int y = target.y + (int) Math.round(dy * (1.0 - t));
            dialog.setSize(width, height);
            dialog.setLocation(x, y);
            tick[0]++;
            if (tick[0] > steps) {
                timer.stop();
                dialog.setSize(targetSize);
                dialog.setLocation(target);
            }
        });
        timer.start();
    }


    String showPaletteDialog(String title, List<String> candidates, PalettePreviewProvider previewProvider) {
        // undecorated modal dialog styled as floating picker
        JDialog dialog = new JDialog(editor, title, true);
        dialog.setUndecorated(true);
        dialog.getRootPane().setBorder(javax.swing.BorderFactory.createLineBorder(editor.configManager.getCaretColor(), 1));
        dialog.setLayout(new BorderLayout(6, 6));
        dialog.getContentPane().setBackground(editor.configManager.getCommandBarBackground());
        JTextField filterField = new JTextField();
        AccessibilitySupport.describe(filterField, title + " filter", "Filter available " + title.toLowerCase(Locale.ROOT) + " entries.");
        filterField.setFont(editor.writingArea.getFont());
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
        list.setFont(editor.writingArea.getFont());
        list.setBackground(editor.configManager.getCommandBarBackground());
        list.setForeground(editor.configManager.getCommandBarForeground());
        list.setSelectionBackground(editor.configManager.getSelectionColor());
        list.setSelectionForeground(editor.configManager.getSelectionTextColor());
        if (!model.isEmpty()) list.setSelectedIndex(0);
        JLabel titleLabel = new JLabel(" " + title);
        titleLabel.setForeground(editor.configManager.getCaretColor());
        titleLabel.setFont(editor.writingArea.getFont().deriveFont(java.awt.Font.BOLD));
        titleLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 2, 6));
        JTextArea previewArea = new JTextArea();
        AccessibilitySupport.describe(previewArea, title + " preview", "Preview of the selected " + title.toLowerCase(Locale.ROOT) + " entry.");
        previewArea.setEditable(false);
        previewArea.setLineWrap(true);
        previewArea.setWrapStyleWord(true);
        previewArea.setFocusable(false);
        previewArea.setPreferredSize(new Dimension(260, 320));
        previewArea.setFont(editor.writingArea.getFont().deriveFont(Math.max(11f, editor.writingArea.getFont().getSize2D() - 1f)));
        previewArea.setBackground(editor.configManager.getStatusBarBackground());
        previewArea.setForeground(editor.configManager.getStatusBarForeground());
        previewArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, editor.blendColor(editor.configManager.getCaretColor(), editor.configManager.getCommandBarBackground(), 0.45)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        previewArea.setVisible(previewProvider != null && editor.dramaticCommandPaletteEnabled);
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
        dialog.add(titleLabel, BorderLayout.NORTH);
        dialog.add(filterField, BorderLayout.CENTER);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(600, 320));
        sp.setBorder(null);
        dialog.add(sp, BorderLayout.SOUTH);
        dialog.add(previewArea, BorderLayout.EAST);
        syncPreview.run();
        Dimension targetSize = editor.dramaticCommandPaletteEnabled ? new Dimension(720, 420) : new Dimension(620, 400);
        dialog.setSize(targetSize);
        dialog.setLocationRelativeTo(editor);
        animatePaletteDialogOpen(dialog, targetSize);
        editor.playCue(CueType.SUCCESS);
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
