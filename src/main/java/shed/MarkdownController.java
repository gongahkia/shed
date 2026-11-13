package shed;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;

final class MarkdownController {
    private final Texteditor editor;
    private final Map<FileBuffer, PreviewBinding> previews = new IdentityHashMap<>();

    MarkdownController(Texteditor editor) {
        this.editor = editor;
    }

    String handlePreviewCommand(String args) {
        String operation = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "preview", "open" -> openPreview();
            case "close" -> closePreview();
            case "refresh" -> refreshPreview();
            default -> "Usage: :markdown preview|close|refresh";
        };
    }

    String openPreview() {
        EditorPane sourcePane = editor.getActivePane();
        FileBuffer buffer = editor.getCurrentBuffer();
        if (sourcePane == null || buffer == null || buffer.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        if (sourcePane.isMarkdownPreview()) return "Markdown preview already open";
        if (buffer.isLargeFile()) return "Markdown preview unavailable for a large file";
        PreviewBinding existing = previews.get(buffer);
        if (existing != null && editor.editorPanes.contains(existing.previewPane)) {
            editor.activateEditorPane(existing.sourcePane);
            editor.requestActivePaneFocus();
            return "Markdown preview already open";
        }
        if (existing != null) releasePreview(existing);

        EditorPane previewPane = editor.createEditorPane(editor.getSize());
        editor.editorPanes.add(previewPane);
        if (editor.windowLayoutRoot == null) editor.windowLayoutRoot = WindowLayoutNode.leaf(sourcePane);
        if (!editor.windowLayoutRoot.splitLeaf(sourcePane, previewPane, WindowLayoutNode.Orientation.HORIZONTAL, false, 0.5)) {
            editor.editorPanes.remove(previewPane);
            return "Markdown preview could not create a split";
        }
        editor.loadBufferIntoPane(previewPane, buffer, 0);
        MarkdownPreviewPane preview = new MarkdownPreviewPane(buffer, editor::resolveUiFont,
            editor.configManager::getNormalColor, editor.configManager::getEditorForeground,
            href -> editor.showMessage(openPreviewLink(buffer, href)),
            () -> editor.activateEditorPane(previewPane), editor.configManager::getMarkdownPreviewScrollSync,
            editor.configManager::getMarkdownPreviewRemoteImages, sourcePane, editor);
        previewPane.setMarkdownPreviewComponent(preview);
        previews.put(buffer, new PreviewBinding(sourcePane, previewPane, preview));
        editor.renderWindowLayout();
        sourcePane.getTextArea().requestFocusInWindow();
        return "Markdown preview opened";
    }

    void closePreviewForSource(EditorPane sourcePane) {
        PreviewBinding binding = previewForSource(sourcePane);
        if (binding != null && editor.editorPanes.contains(binding.previewPane)) editor.closePane(binding.previewPane);
    }

    boolean hasPreviewForSource(EditorPane sourcePane) {
        return previewForSource(sourcePane) != null;
    }

    void detachPreview(EditorPane pane) {
        Iterator<Map.Entry<FileBuffer, PreviewBinding>> iterator = previews.entrySet().iterator();
        while (iterator.hasNext()) {
            PreviewBinding binding = iterator.next().getValue();
            if (binding.previewPane == pane || binding.sourcePane == pane) {
                binding.preview.disposePreview();
                iterator.remove();
            }
        }
    }

    void disposePreviews() {
        for (PreviewBinding binding : previews.values()) binding.preview.disposePreview();
        previews.clear();
    }

    void refreshPreviews() {
        for (PreviewBinding binding : previews.values()) binding.preview.refreshAppearance();
    }

    void refreshPreviewForBuffer(FileBuffer buffer) {
        PreviewBinding binding = previews.get(buffer);
        if (binding != null) binding.preview.refreshNow();
    }

    private String closePreview() {
        PreviewBinding binding = previews.get(editor.getCurrentBuffer());
        if (binding == null || !editor.editorPanes.contains(binding.previewPane)) return "No Markdown preview is open";
        return editor.closePane(binding.previewPane).equals("Window closed") ? "Markdown preview closed" : "Markdown preview could not close";
    }

    private String refreshPreview() {
        PreviewBinding binding = previews.get(editor.getCurrentBuffer());
        if (binding == null || !editor.editorPanes.contains(binding.previewPane)) return "No Markdown preview is open";
        binding.preview.refreshNow();
        return "Markdown preview refreshed";
    }

    private PreviewBinding previewForSource(EditorPane sourcePane) {
        for (PreviewBinding binding : previews.values()) {
            if (binding.sourcePane == sourcePane) return binding;
        }
        return null;
    }

    private void releasePreview(PreviewBinding binding) {
        binding.preview.disposePreview();
        previews.values().removeIf(candidate -> candidate == binding);
    }

    private String openPreviewLink(FileBuffer source, String href) {
        try {
            URI uri = new URI(href);
            String scheme = uri.getScheme();
            if (scheme == null) {
                String path = href.split("#", 2)[0];
                if (path.isEmpty()) return "";
                activatePreviewSource(source);
                File parent = source.getFile() == null ? new File(".") : source.getFile().getParentFile();
                File target = new File(parent == null ? new File(".") : parent, path);
                if (!target.isFile()) return "Linked file not found: " + path;
                editor.openFileAsync(target);
                return "Opening: " + target.getName();
            }
            if (scheme.equalsIgnoreCase("file")) {
                activatePreviewSource(source);
                File target = new File(uri);
                if (!target.isFile()) return "Linked file not found: " + target.getPath();
                editor.openFileAsync(target);
                return "Opening: " + target.getName();
            }
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("mailto")) {
                return "Unsupported Markdown link";
            }
            if (!Desktop.isDesktopSupported()) return "Desktop links are unavailable";
            Desktop.getDesktop().browse(uri);
            return "Opened: " + href;
        } catch (IOException | URISyntaxException error) {
            return "Markdown link failed: " + error.getMessage();
        }
    }

    private void activatePreviewSource(FileBuffer source) {
        PreviewBinding binding = previews.get(source);
        if (binding != null && editor.editorPanes.contains(binding.sourcePane)) editor.activateEditorPane(binding.sourcePane);
    }

    private record PreviewBinding(EditorPane sourcePane, EditorPane previewPane, MarkdownPreviewPane preview) {}


    String[] getCurrentLines() {
        return editor.writingArea.getText().split("\n", -1);
    }


    // Folding must remain presentation-only. Until that view exists, never edit
    // the shared source document (including through direct helper calls).
    private static final String FOLDING_UNAVAILABLE =
        "Markdown folding is temporarily unavailable";

    String toggleFoldAtCursor() {
        return FOLDING_UNAVAILABLE;
    }

    String foldHeading(int headingLine, MarkdownService.FoldRange range, String[] lines) {
        return FOLDING_UNAVAILABLE;
    }

    String unfoldHeading(int headingLine, String[] lines) {
        return FOLDING_UNAVAILABLE;
    }

    String foldAll() {
        return FOLDING_UNAVAILABLE;
    }

    String unfoldAll() {
        return FOLDING_UNAVAILABLE;
    }

    String globalFoldCycle() {
        return FOLDING_UNAVAILABLE;
    }


    String navigateHeading(boolean forward) {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "";
        String[] lines = getCurrentLines();
        int currentLine = editor.getCurrentCaretLine();
        int target = forward ? editor.markdownService.nextHeading(lines, currentLine) : editor.markdownService.prevHeading(lines, currentLine);
        if (target < 0) {
            return forward ? "No next heading" : "No previous heading";
        }
        editor.recordJumpPosition();
        return editor.gotoLine(target + 1);
    }


    String navigateHeadingAtLevel(boolean forward, int level) {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "";
        String[] lines = getCurrentLines();
        int currentLine = editor.getCurrentCaretLine();
        int target = forward ? editor.markdownService.nextHeadingAtLevel(lines, currentLine, level) : editor.markdownService.prevHeadingAtLevel(lines, currentLine, level);
        if (target < 0) {
            return "No " + (forward ? "next" : "previous") + " h" + level + " heading";
        }
        editor.recordJumpPosition();
        return editor.gotoLine(target + 1);
    }


    public String showTableOfContents() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        MarkdownOutlineDialog.showFor(editor, buf, getCurrentLines());
        return "Table of contents opened";
    }


    public String showOutline() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        MarkdownOutlineDialog.showFor(editor, buf, getCurrentLines());
        return "Outline opened";
    }


    private String mutationUnavailable() {
        EditorPane pane = editor.getActivePane();
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null) return "No buffer";
        if (buffer.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        return sourceWritable(pane, buffer) ? null : "Markdown edit unavailable: read-only or unsupported surface";
    }

    private boolean sourceWritable(EditorPane pane, FileBuffer buffer) {
        return pane != null && pane == editor.getActivePane() && buffer == editor.getCurrentBuffer()
            && pane.getBuffer() == buffer && !buffer.isLargeFile() && !buffer.isShowingPreviewOnly()
            && !pane.isMarkdownPreview() && pane.getAlternateComponent() == null && pane.getTerminalPane() == null
            && pane.getTextArea() == editor.writingArea && editor.writingArea.getDocument() == buffer.getDocument()
            && editor.writingArea.isEnabled() && !editor.suppressDocumentEvents
            // Vim disables ordinary typing in normal/visual/command modes, not source commands.
            && (pane.surface().editable() || editor.configManager.getKeymapProfile() == KeymapProfile.VIM
                && !editor.editorState.mode.isEditable());
    }

    private String replace(int start, int end, String text, int caret, String success) throws BadLocationException {
        EditorPane pane = editor.getActivePane();
        FileBuffer buffer = editor.getCurrentBuffer();
        MarkdownEdit edit = new MarkdownEdit(buffer, editor.writingArea, pane.surface(),
            () -> sourceWritable(pane, buffer), start, end);
        String failure = edit.apply(text, caret);
        return failure.isEmpty() ? success : failure;
    }

    private record SourceLine(int start, int end, String text) { }

    /** Excludes only the actual terminator; the final line need not have one. */
    private SourceLine sourceLine(int line) throws BadLocationException {
        int start = editor.writingArea.getLineStartOffset(line);
        int end = Math.min(editor.writingArea.getLineEndOffset(line), editor.writingArea.getDocument().getLength());
        if (end > start && editor.writingArea.getText(end - 1, 1).equals("\n")) end--;
        return new SourceLine(start, end, editor.writingArea.getText(start, end - start));
    }

    String markdownHeadingShift(boolean demote) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            SourceLine line = sourceLine(editor.getCurrentCaretLine());
            String changed;
            String success;
            if (!editor.markdownService.isHeading(line.text())) {
                if (demote) changed = (editor.effectiveExpandTab() ? " ".repeat(editor.writingArea.getTabSize()) : "\t") + line.text();
                else {
                    int count = 0;
                    while (count < line.text().length() && count < editor.writingArea.getTabSize()
                        && Character.isWhitespace(line.text().charAt(count))) count++;
                    changed = line.text().substring(count);
                }
                success = "Line updated";
            } else {
                changed = demote ? editor.markdownService.demoteHeading(line.text()) : editor.markdownService.promoteHeading(line.text());
                if (changed.equals(line.text())) return demote ? "Already at h6" : "Already at h1";
                success = demote ? "Demoted heading" : "Promoted heading";
            }
            return replace(line.start(), line.end(), changed, -1, success);
        } catch (BadLocationException error) { return "Markdown edit unavailable: invalid line"; }
    }

    String markdownSubtreeShift(boolean demote) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            int lineIndex = editor.getCurrentCaretLine();
            SourceLine first = sourceLine(lineIndex), last = first;
            int level = editor.markdownService.headingLevel(first.text());
            if (level == 0) return "Not on a heading line";
            StringBuilder changed = new StringBuilder(demote ? editor.markdownService.demoteHeading(first.text())
                : editor.markdownService.promoteHeading(first.text()));
            for (int i = lineIndex + 1; i < editor.writingArea.getLineCount(); i++) {
                SourceLine line = sourceLine(i);
                int nextLevel = editor.markdownService.headingLevel(line.text());
                if (nextLevel > 0 && nextLevel <= level) break;
                if (line.end() - first.start() > MarkdownService.MAX_TABLE_CHARS) return "Markdown subtree too large to edit safely";
                changed.append('\n').append(demote ? editor.markdownService.demoteHeading(line.text())
                    : editor.markdownService.promoteHeading(line.text()));
                last = line;
            }
            return replace(first.start(), last.end(), changed.toString(), -1, demote ? "Demoted subtree" : "Promoted subtree");
        } catch (BadLocationException error) { return "Markdown edit unavailable: invalid line"; }
    }

    boolean isOnTableLine() {
        try { return editor.markdownService.isTableRow(sourceLine(editor.getCurrentCaretLine()).text()); }
        catch (BadLocationException error) { return false; }
    }

    private record TableRange(int start, int end, int currentRow, String[] lines) { }

    private TableRange currentTable() throws BadLocationException {
        int current = editor.getCurrentCaretLine(), first = current, last = current;
        if (!editor.markdownService.isTableRow(sourceLine(current).text())) throw new IllegalArgumentException("Not inside a table");
        int currentStart = sourceLine(current).start();
        while (first > 0 && editor.markdownService.isTableRow(sourceLine(first - 1).text())) {
            first--;
            if (currentStart - sourceLine(first).start() > MarkdownService.MAX_TABLE_CHARS) throw new IllegalArgumentException("Table too large to edit safely");
        }
        int start = sourceLine(first).start();
        while (last + 1 < editor.writingArea.getLineCount() && editor.markdownService.isTableRow(sourceLine(last + 1).text())) {
            last++;
            if (sourceLine(last).end() - start > MarkdownService.MAX_TABLE_CHARS) throw new IllegalArgumentException("Table too large to edit safely");
        }
        int end = sourceLine(last).end();
        if (end - start > MarkdownService.MAX_TABLE_CHARS) throw new IllegalArgumentException("Table too large to edit safely");
        String[] lines = editor.writingArea.getText(start, end - start).split("\n", -1);
        editor.markdownService.validateEditableTable(lines, 0, lines.length - 1);
        return new TableRange(start, end, current - first, lines);
    }

    String markdownTableNextCell(boolean reverse) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            TableRange table = currentTable();
            SourceLine line = sourceLine(editor.getCurrentCaretLine());
            int columnOffset = editor.writingArea.getCaretPosition() - line.start();
            if (reverse) {
                editor.writingArea.setCaretPosition(line.start() + editor.markdownService.prevCellOffset(line.text(), columnOffset));
                return "";
            }
            int next = editor.markdownService.nextCellOffset(line.text(), columnOffset + 1);
            if (next > columnOffset + 1 && next < line.text().length() - 1) {
                editor.writingArea.setCaretPosition(line.start() + next);
                return "";
            }
            if (table.currentRow() == table.lines().length - 1) {
                String[] cells = editor.markdownService.parseCells(line.text());
                int[] widths = new int[cells.length];
                for (int c = 0; c < cells.length; c++) widths[c] = Math.max(3, cells[c].length());
                String row = editor.markdownService.newTableRow(cells.length, widths);
                return replace(line.end(), line.end(), "\n" + row,
                    line.end() + 1 + editor.markdownService.nextCellOffset(row, 0), "");
            }
            int nextRow = table.currentRow() + 1;
            if (editor.markdownService.isTableSeparator(table.lines()[nextRow])) nextRow++;
            if (nextRow < table.lines().length) {
                int nextStart = line.end() + 1;
                for (int i = table.currentRow() + 1; i < nextRow; i++) nextStart += table.lines()[i].length() + 1;
                editor.writingArea.setCaretPosition(nextStart + editor.markdownService.nextCellOffset(table.lines()[nextRow], 0));
            }
            return "";
        } catch (BadLocationException error) { return "Table navigation unavailable"; }
        catch (IllegalArgumentException error) { return error.getMessage(); }
    }

    public String alignMarkdownTable() { return transformTable("align", ""); }
    public String sortMarkdownTable(String args) { return transformTable("sort", args); }
    public String insertTableColumn(String args) { return transformTable("insert", args); }
    public String deleteTableColumn(String args) { return transformTable("delete", args); }

    private String transformTable(String operation, String args) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            TableRange table = currentTable();
            String[] lines = table.lines();
            int last = lines.length - 1;
            int columns = editor.markdownService.parseCells(lines[0]).length;
            SourceLine line = sourceLine(editor.getCurrentCaretLine());
            int column = editor.markdownService.cellColumn(line.text(), editor.writingArea.getCaretPosition() - line.start());
            String result, success;
            switch (operation) {
                case "align" -> { result = editor.markdownService.alignTable(lines, 0, last); success = "Table aligned"; }
                case "sort" -> {
                    String[] parts = args == null || args.isBlank() ? new String[]{"1"} : args.trim().split("\\s+");
                    column = Integer.parseInt(parts[0]) - 1;
                    if (column < 0 || column >= columns || parts.length > 2
                        || parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))
                        return "Invalid table sort column or direction";
                    result = editor.markdownService.sortTable(lines, 0, last, column, parts.length < 2 || !parts[1].equalsIgnoreCase("desc"));
                    success = "Table sorted by column " + (column + 1);
                }
                case "insert" -> { result = editor.markdownService.insertColumn(lines, 0, last, column); success = "Column inserted"; }
                default -> {
                    if (args != null && !args.isBlank()) column = Integer.parseInt(args.trim()) - 1;
                    if (column < 0 || column >= columns || columns == 1) return "Invalid table column deletion";
                    result = editor.markdownService.deleteColumn(lines, 0, last, column); success = "Column deleted";
                }
            }
            if (result.length() > MarkdownService.MAX_TABLE_CHARS) return "Formatted table too large to edit safely";
            return replace(table.start(), table.end(), result, -1, success);
        } catch (BadLocationException error) { return "Table edit unavailable: invalid range"; }
        catch (NumberFormatException error) { return "Invalid table column"; }
        catch (IllegalArgumentException error) { return error.getMessage(); }
    }

    public String insertTableTemplate(String args) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        int cols = 3, rows = 2;
        try {
            if (args != null && !args.isBlank()) {
                String[] parts = args.trim().split("[xX]", 3);
                if (parts.length > 2) return "Invalid table dimensions";
                cols = Integer.parseInt(parts[0].trim());
                if (parts.length == 2) rows = Integer.parseInt(parts[1].trim());
            }
            return insertTemplate(editor.markdownService.createTableTemplate(cols, rows), "Table inserted (" + cols + "x" + rows + ")");
        } catch (IllegalArgumentException error) { return "Invalid table dimensions or size"; }
    }

    public String toggleCheckbox() {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            SourceLine line = sourceLine(editor.getCurrentCaretLine());
            if (!editor.markdownService.isCheckbox(line.text())) return "Not a checkbox line";
            String toggled = editor.markdownService.toggleCheckbox(line.text());
            return replace(line.start(), line.end(), toggled, -1, toggled.contains("[x]") ? "Checked" : "Unchecked");
        } catch (BadLocationException error) { return "Markdown edit unavailable: invalid line"; }
    }

    String handleMarkdownEnter() {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            int start = editor.writingArea.getSelectionStart(), end = editor.writingArea.getSelectionEnd();
            int lineIndex = editor.writingArea.getLineOfOffset(start);
            SourceLine line = sourceLine(lineIndex);
            // Continue the prefix before the selection/caret, never a different active editor later.
            String before = editor.writingArea.getText(line.start(), start - line.start());
            if (editor.markdownService.isEmptyListItem(before) && before.equals(line.text()) && start == end) {
                int rangeEnd = line.end() < editor.writingArea.getDocument().getLength() ? line.end() + 1 : line.end();
                String result = replace(line.start(), rangeEnd, "\n", line.start() + 1, "");
                if (result.isEmpty()) recordContinuation("\n");
                return result;
            }
            String continuation = editor.markdownService.listContinuation(before);
            if (continuation == null) {
                if (editor.configManager.getKeymapProfile() != KeymapProfile.VIM) return null;
                int indent = 0;
                while (indent < before.length() && (before.charAt(indent) == ' ' || before.charAt(indent) == '\t')) indent++;
                continuation = editor.configManager.getAutoIndent() ? before.substring(0, indent) : "";
            }
            String inserted = "\n" + continuation;
            String result = replace(start, end, inserted, start + inserted.length(), "");
            if (result.isEmpty()) recordContinuation(inserted);
            return result;
        } catch (BadLocationException error) { return "Markdown continuation unavailable"; }
        catch (IllegalArgumentException error) { return error.getMessage(); }
    }

    private void recordContinuation(String inserted) {
        if (editor.configManager.getKeymapProfile() == KeymapProfile.VIM) editor.lastInsertedText += inserted;
    }

    private String insertTemplate(String template, String success) {
        try {
            int start = editor.writingArea.getCaretPosition();
            return replace(start, start, template, start + template.length(), success);
        } catch (BadLocationException error) { return "Markdown edit unavailable: invalid range"; }
    }

    public String insertLink() {
        String unavailable = mutationUnavailable();
        return unavailable != null ? unavailable : insertTemplate(editor.markdownService.insertLinkTemplate(), "Link template inserted");
    }

    public String insertImage() {
        String unavailable = mutationUnavailable();
        return unavailable != null ? unavailable : insertTemplate(editor.markdownService.insertImageTemplate(), "Image template inserted");
    }


    public String goToMarkdownLink() {
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        if (line < 0 || line >= lines.length) return "No link found";
        int lineStart = 0;
        try { lineStart = editor.writingArea.getLineStartOffset(line); } catch (BadLocationException ignored) {}
        int posInLine = editor.writingArea.getCaretPosition() - lineStart;
        String url = editor.markdownService.extractLinkUrl(lines[line], posInLine);
        if (url == null) return editor.goToFileUnderCursor();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(new URI(url));
                    return "Opened: " + url;
                }
            } catch (Exception e) {
                return "Error opening URL: " + e.getMessage();
            }
        }
        // Treat as relative file path
        FileBuffer buf = editor.getCurrentBuffer();
        File base = buf != null && buf.getFile() != null ? buf.getFile().getParentFile() : new File(".");
        File target = new File(base, url);
        if (target.exists()) {
            editor.openFileAsync(target);
            return "Opening: " + target.getName();
        }
        return "File not found: " + url;
    }


    public String setConcealLevel(int level) {
        editor.concealLevel = Math.max(0, Math.min(2, level));
        editor.applySyntaxHighlighting();
        return "Conceal level: " + editor.concealLevel;
    }


    boolean isOnCodeFenceLine() {
        try {
            int line = editor.getCurrentCaretLine();
            int lineStart = editor.writingArea.getLineStartOffset(line);
            int lineEnd = editor.writingArea.getLineEndOffset(line);
            String lineText = editor.writingArea.getText(lineStart, lineEnd - lineStart).trim();
            return lineText.startsWith("```");
        } catch (BadLocationException e) {
            return false;
        }
    }


    String completeCodeFenceLanguage() {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            SourceLine line = sourceLine(editor.getCurrentCaretLine());
            String text = line.text().trim();
            if (!text.startsWith("```")) return "Not a code fence line";
            String[] matches = editor.markdownService.filterCodeFenceLanguages(text.substring(3).trim());
            if (matches.length == 0) return "No matching language";
            int languageStart = line.start() + line.text().indexOf("```") + 3;
            return replace(languageStart, line.end(), matches[0], -1, "Language: " + matches[0]);
        } catch (BadLocationException error) { return "Markdown edit unavailable: invalid line"; }
    }


    String expandSnippetAtCursor() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null) return "No buffer";
        FileType ft = buf.getFileType();
        if (ft == FileType.MARKDOWN) {
            String unavailable = mutationUnavailable();
            if (unavailable != null) return unavailable;
        }
        int pos = editor.writingArea.getCaretPosition();
        String text = editor.writingArea.getText();

        // Find the word before cursor
        int wordStart = pos;
        while (wordStart > 0 && !Character.isWhitespace(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        if (wordStart == pos) return "No trigger word";
        String trigger = text.substring(wordStart, pos);
        SnippetService.Snippet snippet = editor.snippetService.findExact(ft, editor.snippetLanguageId(buf), trigger);
        if (snippet == null) return "No snippet: " + trigger;
        String expanded = editor.snippetService.expand(snippet);
        int cursorOffset = editor.snippetService.cursorOffset(snippet);
        if (ft == FileType.MARKDOWN) {
            try {
                return replace(wordStart, pos, expanded, wordStart + (cursorOffset >= 0 ? cursorOffset : expanded.length()), "Expanded: " + trigger);
            } catch (BadLocationException error) { return "Markdown edit unavailable: invalid snippet range"; }
        }
        editor.suppressDocumentEvents = true;
        editor.writingArea.replaceRange(expanded, wordStart, pos);
        editor.suppressDocumentEvents = false;
        if (cursorOffset >= 0) {
            editor.writingArea.setCaretPosition(Math.min(wordStart + cursorOffset, editor.writingArea.getText().length()));
        }
        editor.markModified();
        return "Expanded: " + trigger;
    }


    public String listSnippets() {
        FileBuffer buf = editor.getCurrentBuffer();
        FileType ft = buf != null ? buf.getFileType() : null;
        String listing = editor.snippetService.listSnippets(ft, editor.snippetLanguageId(buf));
        FileBuffer snippetBuf = FileBuffer.createScratch("[Snippets]", listing);
        editor.buffers.add(snippetBuf);
        editor.loadBufferIntoEditor(snippetBuf);
        return "Showing snippets";
    }


    public String toggleBracketColors() {
        editor.bracketColorEnabled = !editor.bracketColorEnabled;
        applyBracketHighlighting();
        return editor.bracketColorEnabled ? "Bracket colors enabled" : "Bracket colors disabled";
    }


    void applyBracketHighlighting() {
        clearBracketHighlighting();
        if (!editor.bracketColorEnabled) return;
        String text = editor.writingArea.getText();
        if (text.isEmpty()) return;
        List<BracketColorService.ColoredBracket> brackets = editor.bracketColorService.computeBracketColors(text);
        Highlighter highlighter = editor.writingArea.getHighlighter();
        for (BracketColorService.ColoredBracket bracket : brackets) {
            try {
                Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(bracket.color());
                editor.bracketHighlightTags.add(highlighter.addHighlight(bracket.offset, bracket.offset + 1, painter));
            } catch (BadLocationException ignored) {}
        }
    }


    void clearBracketHighlighting() {
        Highlighter highlighter = editor.writingArea.getHighlighter();
        for (Object tag : editor.bracketHighlightTags) {
            highlighter.removeHighlight(tag);
        }
        editor.bracketHighlightTags.clear();
    }

}
