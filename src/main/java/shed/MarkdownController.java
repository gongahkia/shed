package shed;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

final class MarkdownController {
    private final Texteditor editor;

    MarkdownController(Texteditor editor) {
        this.editor = editor;
    }

    void maybePreviewMarkdown(FileBuffer buffer) {
        if (buffer == null || buffer.getFileType() != FileType.MARKDOWN || buffer.getFile() == null) {
            return;
        }
        if (buffer.isLargeFile()) {
            editor.showMessage("Markdown preview unavailable for a large file");
            return;
        }
        if (buffer.getFile().equals(editor.lastPreviewedMarkdown)) {
            return;
        }
        editor.lastPreviewedMarkdown = buffer.getFile();
        try {
            File previewFile = File.createTempFile("shed-markdown-", ".html");
            previewFile.deleteOnExit();
            String html = renderMarkdownPreview(buffer.getFullContent(), buffer.getDisplayName());
            Files.writeString(previewFile.toPath(), html, StandardCharsets.UTF_8);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(previewFile.toURI());
            }
        } catch (IOException ignored) {
        }
    }


    String renderMarkdownPreview(String markdown, String title) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<title>").append(title).append("</title>");
        html.append("<style>body{font-family:Georgia,serif;max-width:880px;margin:40px auto;padding:0 24px;line-height:1.6;background:#faf7ef;color:#1f2933;}pre{background:#111827;color:#f9fafb;padding:16px;overflow:auto;}code{background:#e5e7eb;padding:2px 4px;}h1,h2,h3{line-height:1.2;}blockquote{border-left:4px solid #cbd5e1;padding-left:12px;color:#475569;}</style>");
        html.append("</head><body>");
        boolean inCode = false;
        for (String line : markdown.split("\n", -1)) {
            String escaped = escapeHtml(line);
            if (line.startsWith("```")) {
                html.append(inCode ? "</pre>" : "<pre>");
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                html.append(escaped).append("\n");
                continue;
            }
            if (line.startsWith("### ")) {
                html.append("<h3>").append(escapeHtml(line.substring(4))).append("</h3>");
            } else if (line.startsWith("## ")) {
                html.append("<h2>").append(escapeHtml(line.substring(3))).append("</h2>");
            } else if (line.startsWith("# ")) {
                html.append("<h1>").append(escapeHtml(line.substring(2))).append("</h1>");
            } else if (line.startsWith("> ")) {
                html.append("<blockquote>").append(escapeHtml(line.substring(2))).append("</blockquote>");
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                html.append("<p>&bull; ").append(escapeHtml(line.substring(2))).append("</p>");
            } else if (line.isBlank()) {
                html.append("<br/>");
            } else {
                html.append("<p>").append(escaped).append("</p>");
            }
        }
        if (inCode) {
            html.append("</pre>");
        }
        html.append("</body></html>");
        return html.toString();
    }


    String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }


    String[] getCurrentLines() {
        return editor.writingArea.getText().split("\n", -1);
    }


    String toggleFoldAtCursor() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) {
            return "";
        }
        int line = editor.getCurrentCaretLine();
        String[] lines = getCurrentLines();
        if (line < 0 || line >= lines.length) return "";
        if (!editor.markdownService.isHeading(lines[line])) {
            return "Not on a heading line";
        }
        MarkdownService.FoldRange range = editor.markdownService.computeFoldRange(lines, line);
        if (range == null) {
            return "Nothing to fold";
        }
        Boolean folded = editor.foldedLines.get(line);
        if (folded != null && folded) {
            return unfoldHeading(line, lines);
        } else {
            return foldHeading(line, range, lines);
        }
    }


    String foldHeading(int headingLine, MarkdownService.FoldRange range, String[] lines) {
        try {
            int foldCount = range.endLine - range.startLine;
            int startOffset = editor.writingArea.getLineStartOffset(range.startLine + 1);
            int endOffset = editor.writingArea.getLineEndOffset(range.endLine);
            String hidden = editor.writingArea.getText().substring(startOffset, endOffset);
            editor.foldHiddenContent.put(headingLine, hidden);
            editor.foldedLines.put(headingLine, true);
            editor.suppressDocumentEvents = true;
            editor.writingArea.replaceRange("", startOffset, endOffset);
            // Append fold indicator to heading line
            int headingEnd = editor.writingArea.getLineEndOffset(headingLine);
            String indicator = " ... (" + foldCount + " lines)";
            editor.writingArea.insert(indicator, headingEnd - 1);
            editor.suppressDocumentEvents = false;
            return "Folded " + foldCount + " lines";
        } catch (BadLocationException e) {
            editor.suppressDocumentEvents = false;
            return "Fold error: " + e.getMessage();
        }
    }


    String unfoldHeading(int headingLine, String[] lines) {
        String hidden = editor.foldHiddenContent.get(headingLine);
        if (hidden == null) return "Nothing to unfold";
        try {
            // Remove fold indicator from heading line
            String headingText = lines[headingLine];
            int indicatorIdx = headingText.indexOf(" ... (");
            if (indicatorIdx > 0) {
                int headingStart = editor.writingArea.getLineStartOffset(headingLine);
                int headingEnd = editor.writingArea.getLineEndOffset(headingLine);
                String cleanHeading = headingText.substring(0, indicatorIdx);
                editor.suppressDocumentEvents = true;
                editor.writingArea.replaceRange(cleanHeading + "\n" + hidden, headingStart, headingEnd);
                editor.suppressDocumentEvents = false;
            } else {
                int afterHeading = editor.writingArea.getLineEndOffset(headingLine);
                editor.suppressDocumentEvents = true;
                editor.writingArea.insert(hidden, afterHeading);
                editor.suppressDocumentEvents = false;
            }
            editor.foldedLines.put(headingLine, false);
            editor.foldHiddenContent.remove(headingLine);
            return "Unfolded";
        } catch (BadLocationException e) {
            editor.suppressDocumentEvents = false;
            return "Unfold error: " + e.getMessage();
        }
    }


    String foldAll() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "";
        String[] lines = getCurrentLines();
        List<MarkdownService.FoldRange> ranges = editor.markdownService.computeAllFoldRanges(lines);
        int count = 0;
        // Fold from bottom to top to preserve line numbers
        for (int i = ranges.size() - 1; i >= 0; i--) {
            MarkdownService.FoldRange range = ranges.get(i);
            if (!Boolean.TRUE.equals(editor.foldedLines.get(range.startLine))) {
                lines = getCurrentLines();
                foldHeading(range.startLine, range, lines);
                count++;
            }
        }
        return count > 0 ? "Folded " + count + " sections" : "Nothing to fold";
    }


    String unfoldAll() {
        if (editor.foldHiddenContent.isEmpty()) return "Nothing to unfold";
        // Unfold from bottom to top
        List<Integer> foldedHeadings = new ArrayList<>(editor.foldedLines.keySet());
        foldedHeadings.sort(Collections.reverseOrder());
        int count = 0;
        for (int heading : foldedHeadings) {
            if (Boolean.TRUE.equals(editor.foldedLines.get(heading))) {
                String[] lines = getCurrentLines();
                if (heading < lines.length) {
                    unfoldHeading(heading, lines);
                    count++;
                }
            }
        }
        editor.foldedLines.clear();
        editor.foldHiddenContent.clear();
        return count > 0 ? "Unfolded " + count + " sections" : "Nothing to unfold";
    }


    String globalFoldCycle() {
        boolean anyFolded = editor.foldedLines.values().stream().anyMatch(v -> v);
        if (anyFolded) {
            return unfoldAll();
        } else {
            return foldAll();
        }
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
        String[] lines = getCurrentLines();
        String toc = editor.markdownService.generateToc(lines);
        FileBuffer tocBuffer = FileBuffer.createScratch("[TOC]", toc);
        editor.buffers.add(tocBuffer);
        editor.loadBufferIntoEditor(tocBuffer);
        return "Table of contents";
    }


    public String showOutline() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        String[] lines = getCurrentLines();
        String toc = editor.markdownService.generateToc(lines);
        // Open in a split
        String splitResult = editor.splitWindow(true);
        FileBuffer tocBuffer = FileBuffer.createScratch("[Outline]", toc);
        editor.buffers.add(tocBuffer);
        editor.loadBufferIntoEditor(tocBuffer);
        return "Outline opened";
    }


    String markdownHeadingShift(boolean demote) {
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        if (line < 0 || line >= lines.length) return "";
        if (!editor.markdownService.isHeading(lines[line])) {
            return editor.applyLineOperator(demote ? '>' : '<');
        }
        String newLine = demote ? editor.markdownService.demoteHeading(lines[line]) : editor.markdownService.promoteHeading(lines[line]);
        if (newLine.equals(lines[line])) {
            return demote ? "Already at h6" : "Already at h1";
        }
        try {
            int startOffset = editor.writingArea.getLineStartOffset(line);
            int endOffset = editor.writingArea.getLineEndOffset(line);
            editor.suppressDocumentEvents = true;
            editor.writingArea.replaceRange(newLine + "\n", startOffset, endOffset);
            editor.suppressDocumentEvents = false;
            editor.markModified();
            return demote ? "Demoted heading" : "Promoted heading";
        } catch (BadLocationException e) {
            editor.suppressDocumentEvents = false;
            return "Error: " + e.getMessage();
        }
    }


    String markdownSubtreeShift(boolean demote) {
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        if (line < 0 || line >= lines.length || !editor.markdownService.isHeading(lines[line])) {
            return "Not on a heading line";
        }
        String[] newLines = demote ? editor.markdownService.demoteSubtree(lines, line) : editor.markdownService.promoteSubtree(lines, line);
        MarkdownService.FoldRange range = editor.markdownService.computeFoldRange(lines, line);
        int start = line;
        int end = range != null ? range.endLine : line;
        try {
            int startOffset = editor.writingArea.getLineStartOffset(start);
            int endOffset = editor.writingArea.getLineEndOffset(end);
            StringBuilder replacement = new StringBuilder();
            for (int i = start; i <= end; i++) {
                if (i > start) replacement.append("\n");
                replacement.append(newLines[i]);
            }
            replacement.append("\n");
            editor.suppressDocumentEvents = true;
            editor.writingArea.replaceRange(replacement.toString(), startOffset, endOffset);
            editor.suppressDocumentEvents = false;
            editor.markModified();
            return demote ? "Demoted subtree" : "Promoted subtree";
        } catch (BadLocationException e) {
            editor.suppressDocumentEvents = false;
            return "Error: " + e.getMessage();
        }
    }


    boolean isOnTableLine() {
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        return line >= 0 && line < lines.length && editor.markdownService.isTableRow(lines[line]);
    }


    String markdownTableNextCell(boolean reverse) {
        try {
            int line = editor.getCurrentCaretLine();
            String[] lines = getCurrentLines();
            if (line < 0 || line >= lines.length) return "";
            String currentLine = lines[line];
            int lineStart = editor.writingArea.getLineStartOffset(line);
            int posInLine = editor.writingArea.getCaretPosition() - lineStart;

            if (reverse) {
                int offset = editor.markdownService.prevCellOffset(currentLine, posInLine);
                editor.writingArea.setCaretPosition(lineStart + offset);
                return "";
            }

            int nextOffset = editor.markdownService.nextCellOffset(currentLine, posInLine + 1);
            if (nextOffset <= posInLine + 1 || nextOffset >= currentLine.length() - 1) {
                // Move to next row or create new row
                int tableStart = editor.markdownService.tableStartLine(lines, line);
                int tableEnd = editor.markdownService.tableEndLine(lines, line);
                if (line >= tableEnd) {
                    // Create new row
                    String[] cells = editor.markdownService.parseCells(currentLine);
                    int[] widths = new int[cells.length];
                    for (int c = 0; c < cells.length; c++) widths[c] = Math.max(3, cells[c].length());
                    String newRow = editor.markdownService.newTableRow(cells.length, widths);
                    int endOfLine = editor.writingArea.getLineEndOffset(line);
                    editor.suppressDocumentEvents = true;
                    editor.writingArea.insert("\n" + newRow, endOfLine - 1);
                    editor.suppressDocumentEvents = false;
                    editor.markModified();
                    // Move to first cell of new row
                    int newLineStart = editor.writingArea.getLineStartOffset(line + 1);
                    String newLineText = lines.length > line + 1 ? newRow : editor.writingArea.getText().split("\n", -1)[line + 1];
                    int firstCell = editor.markdownService.nextCellOffset(newLineText, 0);
                    editor.writingArea.setCaretPosition(newLineStart + firstCell);
                } else {
                    // Skip separator lines
                    int nextLine = line + 1;
                    while (nextLine <= tableEnd && editor.markdownService.isTableSeparator(lines[nextLine])) {
                        nextLine++;
                    }
                    if (nextLine <= tableEnd) {
                        int nextLineStart = editor.writingArea.getLineStartOffset(nextLine);
                        int firstCell = editor.markdownService.nextCellOffset(lines[nextLine], 0);
                        editor.writingArea.setCaretPosition(nextLineStart + firstCell);
                    }
                }
            } else {
                editor.writingArea.setCaretPosition(lineStart + nextOffset);
            }
            return "";
        } catch (BadLocationException e) {
            return "Table navigation error";
        }
    }


    public String alignMarkdownTable() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        if (!editor.markdownService.isInsideTable(lines, line)) return "Not inside a table";
        int start = editor.markdownService.tableStartLine(lines, line);
        int end = editor.markdownService.tableEndLine(lines, line);
        String aligned = editor.markdownService.alignTable(lines, start, end);
        try {
            int startOffset = editor.writingArea.getLineStartOffset(start);
            int endOffset = editor.writingArea.getLineEndOffset(end);
            editor.suppressDocumentEvents = true;
            editor.writingArea.replaceRange(aligned + "\n", startOffset, endOffset);
            editor.suppressDocumentEvents = false;
            editor.markModified();
            return "Table aligned";
        } catch (BadLocationException e) {
            editor.suppressDocumentEvents = false;
            return "Error aligning table: " + e.getMessage();
        }
    }


    public String sortMarkdownTable(String args) {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        if (!editor.markdownService.isInsideTable(lines, line)) return "Not inside a table";
        int col = 0;
        boolean ascending = true;
        if (args != null && !args.isEmpty()) {
            String[] parts = args.trim().split("\\s+");
            try {
                col = Integer.parseInt(parts[0]) - 1;
            } catch (NumberFormatException ignored) {}
            if (parts.length > 1 && parts[1].equalsIgnoreCase("desc")) ascending = false;
        }
        int start = editor.markdownService.tableStartLine(lines, line);
        int end = editor.markdownService.tableEndLine(lines, line);
        String sorted = editor.markdownService.sortTable(lines, start, end, col, ascending);
        try {
            int startOffset = editor.writingArea.getLineStartOffset(start);
            int endOffset = editor.writingArea.getLineEndOffset(end);
            editor.suppressDocumentEvents = true;
            editor.writingArea.replaceRange(sorted + "\n", startOffset, endOffset);
            editor.suppressDocumentEvents = false;
            editor.markModified();
            return "Table sorted by column " + (col + 1);
        } catch (BadLocationException e) {
            editor.suppressDocumentEvents = false;
            return "Error sorting table: " + e.getMessage();
        }
    }


    public String insertTableColumn(String args) {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        if (!editor.markdownService.isInsideTable(lines, line)) return "Not inside a table";
        int start = editor.markdownService.tableStartLine(lines, line);
        int end = editor.markdownService.tableEndLine(lines, line);
        int lineStart = 0;
        try { lineStart = editor.writingArea.getLineStartOffset(line); } catch (BadLocationException ignored) {}
        int col = editor.markdownService.cellColumn(lines[line], editor.writingArea.getCaretPosition() - lineStart);
        String result = editor.markdownService.insertColumn(lines, start, end, col);
        try {
            int startOffset = editor.writingArea.getLineStartOffset(start);
            int endOffset = editor.writingArea.getLineEndOffset(end);
            editor.suppressDocumentEvents = true;
            editor.writingArea.replaceRange(result + "\n", startOffset, endOffset);
            editor.suppressDocumentEvents = false;
            editor.markModified();
            return "Column inserted";
        } catch (BadLocationException e) {
            editor.suppressDocumentEvents = false;
            return "Error inserting column: " + e.getMessage();
        }
    }


    public String deleteTableColumn(String args) {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        if (!editor.markdownService.isInsideTable(lines, line)) return "Not inside a table";
        int start = editor.markdownService.tableStartLine(lines, line);
        int end = editor.markdownService.tableEndLine(lines, line);
        int col = 0;
        if (args != null && !args.isEmpty()) {
            try { col = Integer.parseInt(args.trim()) - 1; } catch (NumberFormatException ignored) {}
        } else {
            int lineStart = 0;
            try { lineStart = editor.writingArea.getLineStartOffset(line); } catch (BadLocationException ignored) {}
            col = editor.markdownService.cellColumn(lines[line], editor.writingArea.getCaretPosition() - lineStart);
        }
        String result = editor.markdownService.deleteColumn(lines, start, end, col);
        try {
            int startOffset = editor.writingArea.getLineStartOffset(start);
            int endOffset = editor.writingArea.getLineEndOffset(end);
            editor.suppressDocumentEvents = true;
            editor.writingArea.replaceRange(result + "\n", startOffset, endOffset);
            editor.suppressDocumentEvents = false;
            editor.markModified();
            return "Column deleted";
        } catch (BadLocationException e) {
            editor.suppressDocumentEvents = false;
            return "Error deleting column: " + e.getMessage();
        }
    }


    public String insertTableTemplate(String args) {
        int cols = 3, rows = 2;
        if (args != null && !args.isEmpty()) {
            String[] parts = args.trim().split("[xX]");
            try {
                if (parts.length >= 1) cols = Integer.parseInt(parts[0].trim());
                if (parts.length >= 2) rows = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {}
        }
        String template = editor.markdownService.createTableTemplate(cols, rows);
        editor.writingArea.insert(template, editor.writingArea.getCaretPosition());
        editor.markModified();
        return "Table inserted (" + cols + "x" + rows + ")";
    }


    public String toggleCheckbox() {
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        if (line < 0 || line >= lines.length) return "";
        if (!editor.markdownService.isCheckbox(lines[line])) return "Not a checkbox line";
        String toggled = editor.markdownService.toggleCheckbox(lines[line]);
        try {
            int startOffset = editor.writingArea.getLineStartOffset(line);
            int endOffset = editor.writingArea.getLineEndOffset(line);
            editor.suppressDocumentEvents = true;
            editor.writingArea.replaceRange(toggled + "\n", startOffset, endOffset);
            editor.suppressDocumentEvents = false;
            editor.markModified();
            return toggled.contains("[x]") ? "Checked" : "Unchecked";
        } catch (BadLocationException e) {
            editor.suppressDocumentEvents = false;
            return "Error: " + e.getMessage();
        }
    }


    String handleMarkdownEnter() {
        String[] lines = getCurrentLines();
        int line = editor.getCurrentCaretLine();
        if (line < 0 || line >= lines.length) return null;
        String currentLine = lines[line];

        if (editor.markdownService.isEmptyListItem(currentLine)) {
            // Remove the empty list prefix
            try {
                int startOffset = editor.writingArea.getLineStartOffset(line);
                int endOffset = editor.writingArea.getLineEndOffset(line);
                editor.suppressDocumentEvents = true;
                editor.writingArea.replaceRange("\n", startOffset, endOffset);
                editor.suppressDocumentEvents = false;
                editor.lastInsertedText += "\n";
                return "";
            } catch (BadLocationException e) {
                editor.suppressDocumentEvents = false;
                return null;
            }
        }

        String continuation = editor.markdownService.listContinuation(currentLine);
        if (continuation != null) {
            SwingUtilities.invokeLater(() -> {
                editor.writingArea.insert(continuation, editor.writingArea.getCaretPosition());
            });
            editor.lastInsertedText += "\n" + continuation;
            return "";
        }
        return null;
    }


    public String insertLink() {
        String template = editor.markdownService.insertLinkTemplate();
        editor.writingArea.insert(template, editor.writingArea.getCaretPosition());
        editor.markModified();
        return "Link template inserted";
    }


    public String insertImage() {
        String template = editor.markdownService.insertImageTemplate();
        editor.writingArea.insert(template, editor.writingArea.getCaretPosition());
        editor.markModified();
        return "Image template inserted";
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
            try {
                editor.openFile(target);
                return "Opened: " + target.getName();
            } catch (IOException e) {
                return "Error opening file: " + e.getMessage();
            }
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
        try {
            int line = editor.getCurrentCaretLine();
            int lineStart = editor.writingArea.getLineStartOffset(line);
            int lineEnd = editor.writingArea.getLineEndOffset(line);
            String lineText = editor.writingArea.getText(lineStart, lineEnd - lineStart).trim();
            if (!lineText.startsWith("```")) return "Not a code fence line";
            String prefix = lineText.substring(3).trim();
            String[] matches = editor.markdownService.filterCodeFenceLanguages(prefix);
            if (matches.length == 0) return "No matching language";
            String chosen = matches[0];
            // Replace the line with the completed fence
            editor.suppressDocumentEvents = true;
            editor.writingArea.replaceRange("```" + chosen + "\n", lineStart, lineEnd);
            editor.suppressDocumentEvents = false;
            editor.markModified();
            return "Language: " + chosen;
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }


    String expandSnippetAtCursor() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null) return "No buffer";
        FileType ft = buf.getFileType();
        int pos = editor.writingArea.getCaretPosition();
        String text = editor.writingArea.getText();

        // Find the word before cursor
        int wordStart = pos;
        while (wordStart > 0 && !Character.isWhitespace(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        if (wordStart == pos) return "No trigger word";
        String trigger = text.substring(wordStart, pos);
        SnippetService.Snippet snippet = editor.snippetService.findExact(ft, trigger);
        if (snippet == null) return "No snippet: " + trigger;
        String expanded = editor.snippetService.expand(snippet);
        int cursorOffset = editor.snippetService.cursorOffset(snippet);
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
        String listing = editor.snippetService.listSnippets(ft);
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
