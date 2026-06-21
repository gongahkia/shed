package shed;

import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SyntaxUiController {
    private final Texteditor editor;

    SyntaxUiController(Texteditor editor) {
        this.editor = editor;
    }

    void updateCurrentLineHighlight() {
        Highlighter highlighter = editor.writingArea.getHighlighter();
        if (editor.currentLineHighlightTag != null) {
            highlighter.removeHighlight(editor.currentLineHighlightTag);
            editor.currentLineHighlightTag = null;
        }

        if (!editor.configManager.getShowCurrentLine() || editor.editorState.mode == EditorMode.VISUAL || editor.editorState.mode == EditorMode.VISUAL_LINE) {
            return;
        }

        try {
            int caret = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caret);
            int start = editor.writingArea.getLineStartOffset(line);
            int end = editor.writingArea.getLineEndOffset(line);
            editor.currentLineHighlightTag = highlighter.addHighlight(start, end, editor.currentLinePainter);
        } catch (BadLocationException ignored) {
        }
    }


    String getGitBlameForCurrentLine(FileBuffer buffer) {
        if (buffer == null || buffer.getFile() == null || buffer.isModified()) return null;
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition()) + 1;
            File file = buffer.getFile();
            ProcessBuilder pb = new ProcessBuilder("git", "blame", "-L", line + "," + line, "--porcelain", file.getName());
            pb.directory(file.getParentFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            String author = null;
            String summary = null;
            for (String l : output.split("\n")) {
                if (l.startsWith("author ")) author = l.substring(7);
                else if (l.startsWith("summary ")) summary = l.substring(8);
            }
            if (author != null && summary != null) {
                return author + ": " + summary;
            }
        } catch (Exception ignored) {}
        return null;
    }


    String findCurrentBreadcrumb() {
        try {
            FileBuffer buffer = editor.getCurrentBuffer();
            if (buffer == null) {
                return null;
            }
            int caret = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caret) + 1;
            List<SymbolService.Symbol> symbols = editor.symbolService.collectSymbols(editor.writingArea.getText(), buffer.getFileType());
            if (!symbols.isEmpty()) {
                List<SymbolService.Symbol> trail = editor.symbolService.breadcrumbTrail(symbols, line);
                if (!trail.isEmpty()) {
                    StringBuilder breadcrumb = new StringBuilder();
                    for (int i = 0; i < trail.size(); i++) {
                        if (i > 0) {
                            breadcrumb.append(" > ");
                        }
                        breadcrumb.append(trail.get(i).getName());
                    }
                    String value = breadcrumb.toString();
                    if (value.length() > 88) {
                        return "..." + value.substring(value.length() - 85);
                    }
                    return value;
                }
            }
        } catch (BadLocationException ignored) {
        }
        return findCurrentScopeHeuristic();
    }


    String findCurrentScopeHeuristic() {
        try {
            String text = editor.writingArea.getText();
            int caret = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caret);
            // Search backward from current line for a function/class/method definition
            for (int i = line; i >= Math.max(0, line - 200); i--) {
                int ls = editor.writingArea.getLineStartOffset(i);
                int le = editor.writingArea.getLineEndOffset(i);
                String lineText = text.substring(ls, le).trim();
                // Match common patterns: function/def/fn/func/class/impl/pub fn/public/private/protected
                if (lineText.matches("^(public|private|protected|static|async|export|default)?\\s*(class|interface|enum|struct|impl|trait)\\s+\\w+.*")
                        || lineText.matches("^(public|private|protected|static|abstract|final)?\\s*(\\w+\\s+)*\\w+\\s*\\([^)]*\\).*\\{?\\s*$")
                        || lineText.matches("^(def|fn|func|function|sub|proc|method)\\s+\\w+.*")
                        || lineText.matches("^(pub\\s+)?(fn|async fn)\\s+\\w+.*")
                        || lineText.matches("^(const|let|var)\\s+\\w+\\s*=\\s*(function|\\([^)]*\\)\\s*=>).*")) {
                    // Extract just the name portion
                    String name = lineText.replaceAll("[{(].*", "").replaceAll("\\s*->.*", "").trim();
                    if (name.length() > 50) name = name.substring(0, 50) + "...";
                    return name;
                }
            }
        } catch (BadLocationException ignored) {}
        return null;
    }


    void updateMatchingBracketHighlight() {
        Highlighter highlighter = editor.writingArea.getHighlighter();
        for (Object tag : editor.matchBracketTags) {
            highlighter.removeHighlight(tag);
        }
        editor.matchBracketTags.clear();

        try {
            String text = editor.writingArea.getText();
            int caret = editor.writingArea.getCaretPosition();
            if (text.isEmpty()) return;

            // Check char at caret and before caret
            int bracketPos = -1;
            if (caret < text.length() && isBracketChar(text.charAt(caret))) {
                bracketPos = caret;
            } else if (caret > 0 && isBracketChar(text.charAt(caret - 1))) {
                bracketPos = caret - 1;
            }
            if (bracketPos < 0) return;

            char bracket = text.charAt(bracketPos);
            int matchPos = findMatchingBracketPos(text, bracketPos, bracket);
            if (matchPos < 0) return;

            Highlighter.HighlightPainter matchPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x44FFFFFF, true));
            editor.matchBracketTags.add(highlighter.addHighlight(bracketPos, bracketPos + 1, matchPainter));
            editor.matchBracketTags.add(highlighter.addHighlight(matchPos, matchPos + 1, matchPainter));
        } catch (BadLocationException ignored) {
        }
    }


    boolean isBracketChar(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}';
    }


    int findMatchingBracketPos(String text, int pos, char bracket) {
        char match;
        int direction;
        switch (bracket) {
            case '(': match = ')'; direction = 1; break;
            case ')': match = '('; direction = -1; break;
            case '[': match = ']'; direction = 1; break;
            case ']': match = '['; direction = -1; break;
            case '{': match = '}'; direction = 1; break;
            case '}': match = '{'; direction = -1; break;
            default: return -1;
        }
        int depth = 0;
        for (int i = pos; i >= 0 && i < text.length(); i += direction) {
            char c = text.charAt(i);
            if (c == bracket) depth++;
            else if (c == match) depth--;
            if (depth == 0) return i;
        }
        return -1;
    }


    void refreshLineNumberPanel() {
        for (EditorPane pane : editor.editorPanes) {
            pane.getLineNumberPanel().setMode(editor.lineNumberMode);
            pane.getLineNumberPanel().setHighlightCurrentLine(editor.configManager.getShowCurrentLine());
            pane.getLineNumberPanel().setColors(
                editor.configManager.getLineNumberBackground(),
                editor.configManager.getLineNumberForeground(),
                editor.configManager.getLineNumberActiveForeground()
            );
            if (editor.lineNumberMode == LineNumberMode.NONE) {
                pane.getScrollPane().setRowHeaderView(null);
            } else {
                pane.getScrollPane().setRowHeaderView(pane.getLineNumberPanel());
            }
            pane.getLineNumberPanel().repaint();
        }
        editor.editorHostPanel.revalidate();
        editor.editorHostPanel.repaint();
    }


    void applyThemeColors() {
        Color editorForeground = editor.configManager.getEditorForeground();
        Color caretColor = editor.configManager.getCaretColor();
        Color selectionColor = editor.configManager.getSelectionColor();
        Color selectionTextColor = editor.configManager.getSelectionTextColor();

        editor.currentLinePainter = new DefaultHighlighter.DefaultHighlightPainter(editor.configManager.getCurrentLineHighlightColor());
        editor.substitutePreviewPainter = new DefaultHighlighter.DefaultHighlightPainter(editor.configManager.getSubstitutePreviewColor());
        editor.syntaxKeywordColor = editor.configManager.getSyntaxKeywordColor();
        editor.syntaxStringColor = editor.configManager.getSyntaxStringColor();
        editor.syntaxCommentColor = editor.configManager.getSyntaxCommentColor();
        editor.syntaxNumberColor = editor.configManager.getSyntaxNumberColor();

        editor.statusBar.setBackground(editor.configManager.getStatusBarBackground());
        editor.statusBar.setForeground(editor.configManager.getStatusBarForeground());
        editor.commandBar.setBackground(editor.configManager.getCommandBarBackground());
        editor.commandBar.setForeground(editor.configManager.getCommandBarForeground());
        editor.applyDramaticFooterStyling();

        for (EditorPane pane : editor.editorPanes) {
            JTextArea area = pane.getTextArea();
            area.setForeground(editorForeground);
            area.setCaretColor(caretColor);
            area.setSelectionColor(selectionColor);
            area.setSelectedTextColor(selectionTextColor);
        }

        if (editor.editorState.mode != null) {
            editor.writingArea.setBackground(editor.getModeBackground(editor.editorState.mode));
        }
        editor.updateZenModeLayout();

        refreshLineNumberPanel();
        updateCurrentLineHighlight();
        applySyntaxHighlighting();
        updateSubstitutePreview();
        editor.updateStatusBar();
    }


    void applySyntaxHighlighting() {
        clearSyntaxHighlighting();

        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null) {
            return;
        }

        String text = editor.writingArea.getText();
        if (text.isEmpty()) {
            return;
        }

        boolean[] masked = new boolean[text.length()];
        FileType fileType = buffer.getFileType();

        highlightComments(text, fileType, masked);
        highlightStrings(text, fileType, masked);
        highlightNumbers(text, masked);
        if (fileType == FileType.JAVA) {
            highlightJavaAnnotations(text, masked);
        }
        highlightScopeRules(text, fileType, masked);
        highlightKeywords(text, syntaxKeywordsFor(fileType), masked);
        if (editor.configManager.getShowWhitespace()) {
            Highlighter highlighter = editor.writingArea.getHighlighter();
            highlightTrailingWhitespace(highlighter, text);
        }
        editor.applyBracketHighlighting();
        editor.writingArea.repaint();
    }


    void clearSyntaxHighlighting() {
        Highlighter highlighter = editor.writingArea.getHighlighter();
        for (Object tag : editor.syntaxHighlightTags) {
            highlighter.removeHighlight(tag);
        }
        editor.syntaxHighlightTags.clear();
        editor.syntaxForegroundSpans.clear();
        editor.clearBracketHighlighting();
    }


    String[] syntaxKeywordsFor(FileType fileType) {
        return editor.syntaxHighlightService.keywordsFor(fileType);
    }


    void highlightJavaAnnotations(String text, boolean[] masked) {
        int i = 0;
        while (i < text.length()) {
            if (masked[i] || text.charAt(i) != '@') {
                i++;
                continue;
            }
            int start = i;
            int end = i + 1;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (!isIdentifierChar(c) && c != '.') {
                    break;
                }
                end++;
            }
            if (end > start + 1) {
                addSyntaxHighlight(start, end, editor.syntaxKeywordColor, masked);
                i = end;
            } else {
                i++;
            }
        }
    }


    void highlightKeywords(String text, String[] keywords, boolean[] masked) {
        if (keywords == null || keywords.length == 0) {
            return;
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isEmpty()) {
                continue;
            }
            int index = 0;
            while (index <= text.length() - keyword.length()) {
                int match = text.indexOf(keyword, index);
                if (match < 0) {
                    break;
                }
                int end = match + keyword.length();
                if (isKeywordMatch(text, match, keyword, masked)) {
                    addSyntaxHighlight(match, end, editor.syntaxKeywordColor, masked);
                }
                index = match + Math.max(1, keyword.length());
            }
        }
    }


    boolean isKeywordMatch(String text, int start, String keyword, boolean[] masked) {
        int end = start + keyword.length();
        if (start < 0 || end > text.length() || isMasked(masked, start, end)) {
            return false;
        }
        boolean needsLeftBoundary = isIdentifierChar(keyword.charAt(0));
        boolean needsRightBoundary = isIdentifierChar(keyword.charAt(keyword.length() - 1));
        if (needsLeftBoundary && start > 0 && isIdentifierChar(text.charAt(start - 1))) {
            return false;
        }
        if (needsRightBoundary && end < text.length() && isIdentifierChar(text.charAt(end))) {
            return false;
        }
        return true;
    }


    void highlightComments(String text, FileType fileType, boolean[] masked) {
        String[] linePrefixes = lineCommentPrefixesFor(fileType);
        String[][] blockPairs = blockCommentPairsFor(fileType);
        int i = 0;
        while (i < text.length()) {
            if (masked[i]) {
                i++;
                continue;
            }

            boolean matched = false;
            for (String prefix : linePrefixes) {
                if (matchesAt(text, i, prefix)) {
                    int end = i + prefix.length();
                    while (end < text.length() && text.charAt(end) != '\n') {
                        end++;
                    }
                    addSyntaxHighlight(i, end, editor.syntaxCommentColor, masked);
                    i = Math.max(i + 1, end);
                    matched = true;
                    break;
                }
            }
            if (matched) {
                continue;
            }

            for (String[] pair : blockPairs) {
                String open = pair[0];
                String close = pair[1];
                if (!matchesAt(text, i, open)) {
                    continue;
                }
                int closeIndex = text.indexOf(close, i + open.length());
                int end = closeIndex < 0 ? text.length() : closeIndex + close.length();
                addSyntaxHighlight(i, end, editor.syntaxCommentColor, masked);
                i = Math.max(i + 1, end);
                matched = true;
                break;
            }
            if (!matched) {
                i++;
            }
        }
    }


    void highlightStrings(String text, FileType fileType, boolean[] masked) {
        int i = 0;
        while (i < text.length()) {
            if (masked[i]) {
                i++;
                continue;
            }

            if (fileType == FileType.JAVA && matchesAt(text, i, "\"\"\"")) {
                int closeIndex = text.indexOf("\"\"\"", i + 3);
                int end = closeIndex < 0 ? text.length() : closeIndex + 3;
                addSyntaxHighlight(i, end, editor.syntaxStringColor, masked);
                i = Math.max(i + 1, end);
                continue;
            }

            if (fileType == FileType.PYTHON && (matchesAt(text, i, "\"\"\"") || matchesAt(text, i, "'''"))) {
                String delimiter = matchesAt(text, i, "\"\"\"") ? "\"\"\"" : "'''";
                int closeIndex = text.indexOf(delimiter, i + delimiter.length());
                int end = closeIndex < 0 ? text.length() : closeIndex + delimiter.length();
                addSyntaxHighlight(i, end, editor.syntaxStringColor, masked);
                i = Math.max(i + 1, end);
                continue;
            }

            char c = text.charAt(i);
            if (!isStringDelimiter(fileType, c)) {
                i++;
                continue;
            }

            boolean multiline = c == '`';
            int end = i + 1;
            boolean escaped = false;
            while (end < text.length()) {
                char current = text.charAt(end);
                if (!multiline && current == '\n') {
                    break;
                }
                if (!escaped && current == c) {
                    end++;
                    break;
                }
                if (current == '\\' && !escaped) {
                    escaped = true;
                } else {
                    escaped = false;
                }
                end++;
            }
            addSyntaxHighlight(i, Math.max(i + 1, end), editor.syntaxStringColor, masked);
            i = Math.max(i + 1, end);
        }
    }


    void highlightTrailingWhitespace(Highlighter highlighter, String text) {
        Highlighter.HighlightPainter trailingPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x80FF4444, true));
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                int trailStart = i;
                while (trailStart > lineStart && (text.charAt(trailStart - 1) == ' ' || text.charAt(trailStart - 1) == '\t')) {
                    trailStart--;
                }
                if (trailStart < i) {
                    try {
                        editor.syntaxHighlightTags.add(highlighter.addHighlight(trailStart, i, trailingPainter));
                    } catch (BadLocationException ignored) {}
                }
                lineStart = i + 1;
            }
        }
    }


    void highlightScopeRules(String text, FileType fileType, boolean[] masked) {
        List<SyntaxHighlightService.SyntaxRule> rules = editor.syntaxHighlightService.scopeRulesFor(fileType);
        for (SyntaxHighlightService.SyntaxRule rule : rules) {
            java.util.regex.Matcher m = rule.pattern.matcher(text);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                if (start < masked.length && masked[start]) continue;
                Color color;
                switch (rule.scope) {
                    case "type": color = editor.configManager.getSyntaxTypeColor(); break;
                    case "function": color = editor.configManager.getSyntaxFunctionColor(); break;
                    case "constant": color = editor.configManager.getSyntaxConstantColor(); break;
                    case "annotation": color = editor.configManager.getSyntaxAnnotationColor(); break;
                    case "number": color = editor.configManager.getSyntaxNumberColor(); break;
                    default: continue;
                }
                editor.syntaxForegroundSpans.add(new SyntaxSpan(start, Math.min(end, text.length()), color));
            }
        }
    }


    void highlightNumbers(String text, boolean[] masked) {
        int i = 0;
        while (i < text.length()) {
            if (masked[i]) {
                i++;
                continue;
            }
            if (!Character.isDigit(text.charAt(i)) || (i > 0 && isIdentifierChar(text.charAt(i - 1)))) {
                i++;
                continue;
            }

            int start = i;
            int end = i + 1;
            while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                end++;
            }
            if (end + 1 < text.length() && text.charAt(end) == '.' && Character.isDigit(text.charAt(end + 1))) {
                end++;
                while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                    end++;
                }
            }
            if (end < text.length() && (text.charAt(end) == 'e' || text.charAt(end) == 'E')) {
                int exponent = end + 1;
                if (exponent < text.length() && (text.charAt(exponent) == '+' || text.charAt(exponent) == '-')) {
                    exponent++;
                }
                if (exponent < text.length() && Character.isDigit(text.charAt(exponent))) {
                    end = exponent + 1;
                    while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                        end++;
                    }
                }
            }
            if (end >= text.length() || !isIdentifierChar(text.charAt(end))) {
                addSyntaxHighlight(start, end, editor.syntaxNumberColor, masked);
            }
            i = Math.max(i + 1, end);
        }
    }


    void addSyntaxHighlight(int start, int end, Color color, boolean[] masked) {
        if (start < 0 || end <= start || start >= masked.length) {
            return;
        }
        int safeEnd = Math.min(end, masked.length);
        if (isMasked(masked, start, safeEnd)) {
            return;
        }
        editor.syntaxForegroundSpans.add(new SyntaxSpan(start, safeEnd, color));
        markMasked(masked, start, safeEnd);
    }


    boolean isMasked(boolean[] masked, int start, int end) {
        for (int i = start; i < end && i < masked.length; i++) {
            if (masked[i]) {
                return true;
            }
        }
        return false;
    }


    void markMasked(boolean[] masked, int start, int end) {
        for (int i = Math.max(0, start); i < end && i < masked.length; i++) {
            masked[i] = true;
        }
    }


    boolean matchesAt(String text, int index, String token) {
        if (token == null || token.isEmpty() || index < 0 || index + token.length() > text.length()) {
            return false;
        }
        return text.regionMatches(index, token, 0, token.length());
    }


    boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }


    boolean isStringDelimiter(FileType fileType, char c) {
        return editor.syntaxHighlightService.isStringDelimiter(fileType, c);
    }


    String[] lineCommentPrefixesFor(FileType fileType) {
        return editor.syntaxHighlightService.lineCommentPrefixesFor(fileType);
    }


    String[][] blockCommentPairsFor(FileType fileType) {
        return editor.syntaxHighlightService.blockCommentPairsFor(fileType);
    }


    void updateSubstitutePreview() {
        clearSubstitutePreview();
        if (editor.editorState.mode != EditorMode.COMMAND || editor.editorState.commandBuffer == null || !editor.editorState.commandBuffer.startsWith(":")) {
            return;
        }

        String command = editor.editorState.commandBuffer.substring(1);
        SubstitutePreview preview = parseSubstitutePreview(command);
        if (preview == null || preview.pattern.isEmpty()) {
            return;
        }

        try {
            int startOffset = editor.writingArea.getLineStartOffset(Math.max(0, preview.startLine));
            int endLine = Math.min(editor.writingArea.getLineCount() - 1, preview.endLine);
            int endOffset = editor.writingArea.getLineEndOffset(endLine);
            String text = editor.writingArea.getText();
            String region = text.substring(startOffset, endOffset);
            Highlighter highlighter = editor.writingArea.getHighlighter();
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(preview.pattern).matcher(region);
                while (m.find()) {
                    int ms = startOffset + m.start();
                    int me = startOffset + m.end();
                    if (me > endOffset) break;
                    editor.substitutePreviewTags.add(highlighter.addHighlight(ms, me, editor.substitutePreviewPainter));
                }
            } catch (java.util.regex.PatternSyntaxException e) {
                int searchFrom = startOffset;
                while (searchFrom <= endOffset - preview.pattern.length()) {
                    int match = text.indexOf(preview.pattern, searchFrom);
                    if (match < 0 || match >= endOffset) break;
                    editor.substitutePreviewTags.add(highlighter.addHighlight(match, match + preview.pattern.length(), editor.substitutePreviewPainter));
                    searchFrom = match + Math.max(1, preview.pattern.length());
                }
            }
        } catch (BadLocationException ignored) {
        }
    }


    void clearSubstitutePreview() {
        Highlighter highlighter = editor.writingArea.getHighlighter();
        for (Object tag : editor.substitutePreviewTags) {
            highlighter.removeHighlight(tag);
        }
        editor.substitutePreviewTags.clear();
    }


    SubstitutePreview parseSubstitutePreview(String command) {
        String working = command;
        int startLine = getCurrentCaretLine();
        int endLine = startLine;

        if (working.startsWith("%")) {
            startLine = 0;
            endLine = Math.max(0, editor.writingArea.getLineCount() - 1);
            working = working.substring(1);
        } else {
            int rangeEnd = findRangeCommandStart(working);
            if (rangeEnd > 0) {
                String rangePart = working.substring(0, rangeEnd);
                String[] parts = rangePart.split(",", -1);
                try {
                    if (parts.length == 2) {
                        startLine = Math.max(0, Integer.parseInt(parts[0]) - 1);
                        endLine = Math.max(startLine, Integer.parseInt(parts[1]) - 1);
                    } else if (parts.length == 1) {
                        startLine = Math.max(0, Integer.parseInt(parts[0]) - 1);
                        endLine = startLine;
                    }
                    working = working.substring(rangeEnd);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (!working.startsWith("s/")) {
            return null;
        }

        String[] parts = working.substring(2).split("/", -1);
        if (parts.length == 0) {
            return null;
        }
        return new SubstitutePreview(parts[0], startLine, endLine);
    }


    int findRangeCommandStart(String command) {
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (!Character.isDigit(c) && c != ',') {
                return i;
            }
        }
        return -1;
    }


    int getCurrentCaretLine() {
        try {
            return editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
        } catch (BadLocationException e) {
            return 0;
        }
    }

}
