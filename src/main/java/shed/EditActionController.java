package shed;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;

final class EditActionController {
    private final Texteditor editor;

    EditActionController(Texteditor editor) {
        this.editor = editor;
    }

    void normalizeVisualLineCaretForMotion() {
        int selectionStart = editor.writingArea.getSelectionStart();
        int selectionEnd = editor.writingArea.getSelectionEnd();
        int caret = editor.writingArea.getCaretPosition();
        if (selectionEnd > selectionStart && caret == selectionEnd && caret > 0) {
            editor.writingArea.setCaretPosition(Math.max(selectionStart, caret - 1));
        }
    }


    void applyVisualLineOperator(char operator) {
        try {
            int selStart = editor.writingArea.getSelectionStart();
            int selEnd = editor.writingArea.getSelectionEnd();
            int startLine = editor.writingArea.getLineOfOffset(selStart);
            int endLine = editor.writingArea.getLineOfOffset(selEnd);
            if (selEnd == editor.writingArea.getLineStartOffset(endLine) && endLine > startLine) {
                endLine--;
            }
            String indent = editor.configManager.getExpandTab() ? " ".repeat(editor.writingArea.getTabSize()) : "\t";
            String text = editor.writingArea.getText();
            int replaceStart = editor.writingArea.getLineStartOffset(startLine);
            int replaceEnd = editor.writingArea.getLineEndOffset(endLine);
            StringBuilder sb = new StringBuilder();
            for (int i = startLine; i <= endLine; i++) {
                int ls = editor.writingArea.getLineStartOffset(i);
                int le = editor.writingArea.getLineEndOffset(i);
                String line = text.substring(ls, le);
                switch (operator) {
                    case '>':
                        sb.append(indent).append(line);
                        break;
                    case '<':
                        int removeCount = Math.min(editor.writingArea.getTabSize(), leadingWhitespace(line));
                        sb.append(line.substring(removeCount));
                        break;
                    case '=':
                        String prevIndent = i > 0 ? indentationForLine(i - 1) : "";
                        sb.append(prevIndent).append(line.stripLeading());
                        break;
                }
            }
            editor.writingArea.replaceRange(sb.toString(), replaceStart, replaceEnd);
            editor.markModified();
            editor.showMessage("Selection " + (operator == '>' ? "indented" : operator == '<' ? "dedented" : "auto-indented"));
        } catch (BadLocationException ignored) {
        }
        if (!editor.substitutePreviewTags.isEmpty()) {
            editor.pulseCaretLine(editor.configManager.getSubstitutePreviewColor());
        }
    }


    void joinVisualSelection() {
        try {
            int selStart = editor.writingArea.getSelectionStart();
            int selEnd = editor.writingArea.getSelectionEnd();
            int startLine = editor.writingArea.getLineOfOffset(selStart);
            int endLine = editor.writingArea.getLineOfOffset(selEnd);
            if (selEnd == editor.writingArea.getLineStartOffset(endLine) && endLine > startLine) {
                endLine--;
            }
            int joins = endLine - startLine;
            for (int i = 0; i < joins; i++) {
                joinCurrentLine(true);
            }
        } catch (BadLocationException ignored) {
        }
    }


    void surroundVisualSelection(char surroundChar) {
        SurroundPair pair = surroundPair(surroundChar);
        if (pair == null) {
            editor.showMessage("Unknown surround: " + surroundChar);
            return;
        }
        int selStart = editor.writingArea.getSelectionStart();
        int selEnd = editor.writingArea.getSelectionEnd();
        if (selStart == selEnd) return;
        editor.writingArea.insert(String.valueOf(pair.close), selEnd);
        editor.writingArea.insert(String.valueOf(pair.open), selStart);
        editor.markModified();
        editor.showMessage("Surround added");
    }


    void toggleCommentSelection() {
        try {
            int selStart = editor.writingArea.getSelectionStart();
            int selEnd = editor.writingArea.getSelectionEnd();
            int startLine = editor.writingArea.getLineOfOffset(selStart);
            int endLine = editor.writingArea.getLineOfOffset(selEnd);
            if (selEnd == editor.writingArea.getLineStartOffset(endLine) && endLine > startLine) {
                endLine--;
            }
            toggleCommentLineRange(startLine, endLine);
        } catch (BadLocationException ignored) {
        }
    }


    void toggleCommentLineRange(int startLine, int endLine) {
        try {
            FileBuffer buffer = editor.getCurrentBuffer();
            if (buffer == null) return;
            String[] prefixes = editor.lineCommentPrefixesFor(buffer.getFileType());
            if (prefixes.length == 0) {
                editor.showMessage("No comment syntax for this file type");
                return;
            }
            String prefix = prefixes[0];
            String text = editor.writingArea.getText();

            boolean allCommented = true;
            for (int i = startLine; i <= endLine; i++) {
                int ls = editor.writingArea.getLineStartOffset(i);
                int le = editor.writingArea.getLineEndOffset(i);
                String trimmed = text.substring(ls, le).stripLeading();
                if (!trimmed.isEmpty() && !trimmed.startsWith(prefix)) {
                    allCommented = false;
                    break;
                }
            }

            int replaceStart = editor.writingArea.getLineStartOffset(startLine);
            int replaceEnd = editor.writingArea.getLineEndOffset(endLine);
            StringBuilder sb = new StringBuilder();

            if (allCommented) {
                for (int i = startLine; i <= endLine; i++) {
                    int ls = editor.writingArea.getLineStartOffset(i);
                    int le = editor.writingArea.getLineEndOffset(i);
                    String line = text.substring(ls, le);
                    int idx = line.indexOf(prefix);
                    if (idx >= 0) {
                        int afterPrefix = idx + prefix.length();
                        boolean hasSpace = afterPrefix < line.length() && line.charAt(afterPrefix) == ' ';
                        sb.append(line, 0, idx);
                        sb.append(line.substring(hasSpace ? afterPrefix + 1 : afterPrefix));
                    } else {
                        sb.append(line);
                    }
                }
            } else {
                int minIndent = Integer.MAX_VALUE;
                for (int i = startLine; i <= endLine; i++) {
                    int ls = editor.writingArea.getLineStartOffset(i);
                    int le = editor.writingArea.getLineEndOffset(i);
                    String line = text.substring(ls, le).stripTrailing();
                    if (line.isEmpty()) continue;
                    int indent = 0;
                    for (char ch : line.toCharArray()) {
                        if (ch == ' ' || ch == '\t') indent++;
                        else break;
                    }
                    minIndent = Math.min(minIndent, indent);
                }
                if (minIndent == Integer.MAX_VALUE) minIndent = 0;

                for (int i = startLine; i <= endLine; i++) {
                    int ls = editor.writingArea.getLineStartOffset(i);
                    int le = editor.writingArea.getLineEndOffset(i);
                    String line = text.substring(ls, le);
                    if (line.stripTrailing().isEmpty()) {
                        sb.append(line);
                    } else {
                        sb.append(line, 0, minIndent);
                        sb.append(prefix).append(' ');
                        sb.append(line.substring(minIndent));
                    }
                }
            }

            editor.writingArea.replaceRange(sb.toString(), replaceStart, replaceEnd);
            editor.markModified();
            editor.showMessage(allCommented ? "Uncommented" : "Commented");
        } catch (BadLocationException ignored) {
        }
    }


    void moveUp() {
        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            if (line > 0) {
                int prevLineStart = editor.writingArea.getLineStartOffset(line - 1);
                int prevLineEnd = editor.writingArea.getLineEndOffset(line - 1);
                int col = pos - editor.writingArea.getLineStartOffset(line);
                int newPos = Math.min(prevLineStart + col, prevLineEnd - 1);
                editor.writingArea.setCaretPosition(newPos);
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }


    void moveDown() {
        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            int totalLines = editor.writingArea.getLineCount();
            if (line < totalLines - 1) {
                int nextLineStart = editor.writingArea.getLineStartOffset(line + 1);
                int nextLineEnd = editor.writingArea.getLineEndOffset(line + 1);
                int col = pos - editor.writingArea.getLineStartOffset(line);
                int newPos = Math.min(nextLineStart + col, nextLineEnd - 1);
                editor.writingArea.setCaretPosition(newPos);
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }


    void moveLeft() {
        int pos = editor.writingArea.getCaretPosition();
        if (pos > 0) {
            editor.writingArea.setCaretPosition(pos - 1);
        }
    }


    void moveRight() {
        int pos = editor.writingArea.getCaretPosition();
        if (pos < editor.writingArea.getText().length()) {
            editor.writingArea.setCaretPosition(pos + 1);
        }
    }


    void moveWordForward() {
        String text = editor.writingArea.getText();
        int pos = editor.writingArea.getCaretPosition();
        int len = text.length();
        if (pos >= len) return;

        int cls = editor.vimCharClass(text.charAt(pos));
        if (cls > 0) {
            while (pos < len && editor.vimCharClass(text.charAt(pos)) == cls) pos++;
        }
        while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;

        editor.writingArea.setCaretPosition(Math.min(pos, len));
    }


    void moveWordBackward() {
        String text = editor.writingArea.getText();
        int pos = editor.writingArea.getCaretPosition();
        if (pos <= 0) return;

        pos--;
        while (pos > 0 && Character.isWhitespace(text.charAt(pos))) pos--;
        if (pos >= 0) {
            int cls = editor.vimCharClass(text.charAt(pos));
            while (pos > 0 && editor.vimCharClass(text.charAt(pos - 1)) == cls) pos--;
        }

        editor.writingArea.setCaretPosition(pos);
    }


    void moveWordEnd() {
        String text = editor.writingArea.getText();
        int pos = editor.writingArea.getCaretPosition();
        int len = text.length();
        if (pos >= len - 1) return;

        pos++;
        while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;
        if (pos < len) {
            int cls = editor.vimCharClass(text.charAt(pos));
            while (pos + 1 < len && editor.vimCharClass(text.charAt(pos + 1)) == cls) pos++;
        }

        editor.writingArea.setCaretPosition(Math.min(pos, len));
    }


    void moveWordForwardBig() {
        String text = editor.writingArea.getText();
        int pos = editor.writingArea.getCaretPosition();
        while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        editor.writingArea.setCaretPosition(Math.min(pos, text.length()));
    }


    void moveWordBackwardBig() {
        String text = editor.writingArea.getText();
        int pos = editor.writingArea.getCaretPosition();
        if (pos > 0) {
            pos--;
            while (pos > 0 && Character.isWhitespace(text.charAt(pos))) {
                pos--;
            }
            while (pos > 0 && !Character.isWhitespace(text.charAt(pos - 1))) {
                pos--;
            }
        }
        editor.writingArea.setCaretPosition(pos);
    }


    void moveWordEndBig() {
        String text = editor.writingArea.getText();
        int pos = editor.writingArea.getCaretPosition();
        if (pos < text.length()) {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            if (pos > 0) {
                pos--;
            }
        }
        editor.writingArea.setCaretPosition(Math.min(pos, text.length()));
    }


    void moveWordEndBackward() {
        moveWordEndBackwardInternal(false);
    }


    void moveWordEndBackwardBig() {
        moveWordEndBackwardInternal(true);
    }


    void moveWordEndBackwardInternal(boolean bigWord) {
        String text = editor.writingArea.getText();
        int pos = Math.max(0, editor.writingArea.getCaretPosition() - 1);
        while (pos > 0 && Character.isWhitespace(text.charAt(pos))) {
            pos--;
        }
        while (pos > 0 && isMotionWordChar(text.charAt(pos - 1), bigWord)) {
            pos--;
        }
        if (pos < text.length()) {
            while (pos < text.length() - 1 && isMotionWordChar(text.charAt(pos + 1), bigWord)) {
                pos++;
            }
        }
        editor.writingArea.setCaretPosition(Math.max(0, pos));
    }


    boolean isMotionWordChar(char c, boolean bigWord) {
        return bigWord ? !Character.isWhitespace(c) : isWordCharacter(c);
    }


    void moveLineStart() {
        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            int lineStart = editor.writingArea.getLineStartOffset(line);
            editor.writingArea.setCaretPosition(lineStart);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }


    void moveLineEnd() {
        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            int lineEnd = editor.writingArea.getLineEndOffset(line);
            editor.writingArea.setCaretPosition(Math.max(lineEnd - 1, 0));
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }


    void moveLineFirstNonBlank() {
        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            int lineStart = editor.writingArea.getLineStartOffset(line);
            int lineEnd = editor.writingArea.getLineEndOffset(line);
            String lineText = editor.writingArea.getText().substring(lineStart, lineEnd);
            int offset = 0;
            while (offset < lineText.length() && Character.isWhitespace(lineText.charAt(offset)) && lineText.charAt(offset) != '\n') {
                offset++;
            }
            editor.writingArea.setCaretPosition(Math.min(lineStart + offset, editor.writingArea.getText().length()));
        } catch (BadLocationException ignored) {
        }
    }


    void moveLineLastNonBlank() {
        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            int lineStart = editor.writingArea.getLineStartOffset(line);
            int lineEnd = editor.writingArea.getLineEndOffset(line);
            String lineText = editor.writingArea.getText().substring(lineStart, lineEnd);
            int offset = lineText.length() - 1;
            while (offset > 0 && Character.isWhitespace(lineText.charAt(offset))) {
                offset--;
            }
            editor.writingArea.setCaretPosition(Math.min(lineStart + offset, Math.max(lineStart, editor.writingArea.getText().length())));
        } catch (BadLocationException ignored) {
        }
    }


    void moveFileStart() {
        editor.writingArea.setCaretPosition(0);
    }


    void moveFileEnd() {
        editor.writingArea.setCaretPosition(editor.writingArea.getText().length());
    }


    void moveParagraphForward() {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            for (int i = line + 1; i < editor.writingArea.getLineCount(); i++) {
                if (lineText(i).isBlank()) {
                    int targetLine = Math.min(i + 1, editor.writingArea.getLineCount() - 1);
                    editor.writingArea.setCaretPosition(editor.writingArea.getLineStartOffset(targetLine));
                    return;
                }
            }
            moveFileEnd();
        } catch (BadLocationException ignored) {
        }
    }


    void moveParagraphBackward() {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            for (int i = Math.max(0, line - 1); i >= 0; i--) {
                if (lineText(i).isBlank()) {
                    int targetLine = Math.max(0, i - 1);
                    editor.writingArea.setCaretPosition(editor.writingArea.getLineStartOffset(targetLine));
                    return;
                }
            }
            moveFileStart();
        } catch (BadLocationException ignored) {
        }
    }


    void moveSentenceForward() {
        String text = editor.writingArea.getText();
        int pos = editor.writingArea.getCaretPosition();
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '.' || c == '!' || c == '?') {
                pos++;
                while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                    pos++;
                }
                editor.writingArea.setCaretPosition(Math.min(pos, text.length()));
                return;
            }
            pos++;
        }
        moveFileEnd();
    }


    void moveSentenceBackward() {
        String text = editor.writingArea.getText();
        int pos = Math.max(0, editor.writingArea.getCaretPosition() - 1);
        while (pos > 0) {
            char c = text.charAt(pos);
            if (c == '.' || c == '!' || c == '?') {
                pos++;
                while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                    pos++;
                }
                editor.writingArea.setCaretPosition(Math.min(pos, text.length()));
                return;
            }
            pos--;
        }
        moveFileStart();
    }


    void moveMatchingBracket() {
        String text = editor.writingArea.getText();
        int pos = editor.writingArea.getCaretPosition();
        if (text.isEmpty() || pos < 0 || pos >= text.length()) {
            return;
        }
        char current = text.charAt(pos);
        String opens = "([{<";
        String closes = ")]}>";
        int openIndex = opens.indexOf(current);
        int closeIndex = closes.indexOf(current);
        if (openIndex >= 0) {
            char close = closes.charAt(openIndex);
            int depth = 0;
            for (int i = pos; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == current) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        editor.writingArea.setCaretPosition(i);
                        return;
                    }
                }
            }
        } else if (closeIndex >= 0) {
            char open = opens.charAt(closeIndex);
            int depth = 0;
            for (int i = pos; i >= 0; i--) {
                char c = text.charAt(i);
                if (c == current) {
                    depth++;
                } else if (c == open) {
                    depth--;
                    if (depth == 0) {
                        editor.writingArea.setCaretPosition(i);
                        return;
                    }
                }
            }
        }
    }


    void moveToFilePercent(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        String text = editor.writingArea.getText();
        int target = (int) Math.round((text.length() * clamped) / 100.0);
        editor.writingArea.setCaretPosition(Math.min(target, text.length()));
    }


    void moveToScreenPosition(char position) {
        try {
            Rectangle visible = editor.writingArea.getVisibleRect();
            int y;
            switch (position) {
                case 'H':
                    y = visible.y;
                    break;
                case 'L':
                    y = visible.y + visible.height;
                    break;
                case 'M':
                default:
                    y = visible.y + (visible.height / 2);
                    break;
            }
            int offset = editor.writingArea.viewToModel2D(new Point(0, y));
            editor.writingArea.setCaretPosition(Math.min(offset, editor.writingArea.getText().length()));
        } catch (Exception ignored) {
        }
    }


    void scrollCurrentLineTo(char anchor) {
        try {
            Rectangle lineBounds = editor.writingArea.modelToView2D(editor.writingArea.getCaretPosition()).getBounds();
            Rectangle visible = editor.writingArea.getVisibleRect();
            int targetY = visible.y;
            switch (anchor) {
                case 'b':
                    targetY = Math.max(0, lineBounds.y - visible.height + lineBounds.height);
                    break;
                case 'z':
                    targetY = Math.max(0, lineBounds.y - (visible.height / 2));
                    break;
                case 't':
                default:
                    targetY = Math.max(0, lineBounds.y);
                    break;
            }
            editor.writingArea.scrollRectToVisible(new Rectangle(visible.x, targetY, visible.width, visible.height));
        } catch (BadLocationException ignored) {
        }
    }


    String lineText(int line) throws BadLocationException {
        int start = editor.writingArea.getLineStartOffset(line);
        int end = editor.writingArea.getLineEndOffset(line);
        return editor.writingArea.getText().substring(start, end);
    }


    void scrollHalfPageDown() {
        try {
            Rectangle visible = editor.writingArea.getVisibleRect();
            Point current = new Point(visible.x, visible.y + visible.height / 2);
            editor.writingArea.scrollRectToVisible(new Rectangle(current.x, current.y, visible.width, visible.height));
            int pos = editor.writingArea.viewToModel2D(current);
            editor.writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    void scrollHalfPageUp() {
        try {
            Rectangle visible = editor.writingArea.getVisibleRect();
            Point current = new Point(visible.x, Math.max(0, visible.y - visible.height / 2));
            editor.writingArea.scrollRectToVisible(new Rectangle(current.x, current.y, visible.width, visible.height));
            int pos = editor.writingArea.viewToModel2D(current);
            editor.writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    void scrollFullPageDown() {
        try {
            Rectangle visible = editor.writingArea.getVisibleRect();
            Point target = new Point(visible.x, visible.y + visible.height);
            editor.writingArea.scrollRectToVisible(new Rectangle(target.x, target.y, visible.width, visible.height));
            int pos = editor.writingArea.viewToModel2D(target);
            editor.writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    void scrollFullPageUp() {
        try {
            Rectangle visible = editor.writingArea.getVisibleRect();
            Point target = new Point(visible.x, Math.max(0, visible.y - visible.height));
            editor.writingArea.scrollRectToVisible(new Rectangle(target.x, target.y, visible.width, visible.height));
            int pos = editor.writingArea.viewToModel2D(target);
            editor.writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    void scrollLineDown() {
        try {
            Rectangle visible = editor.writingArea.getVisibleRect();
            int lineHeight = editor.writingArea.getFontMetrics(editor.writingArea.getFont()).getHeight();
            editor.writingArea.scrollRectToVisible(new Rectangle(visible.x, visible.y + lineHeight, visible.width, visible.height));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    void scrollLineUp() {
        try {
            Rectangle visible = editor.writingArea.getVisibleRect();
            int lineHeight = editor.writingArea.getFontMetrics(editor.writingArea.getFont()).getHeight();
            editor.writingArea.scrollRectToVisible(new Rectangle(visible.x, Math.max(0, visible.y - lineHeight), visible.width, visible.height));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    String showFileInfo() {
        try {
            FileBuffer buffer = editor.getCurrentBuffer();
            String name = buffer != null ? buffer.getDisplayName() : "[No file]";
            int totalLines = editor.writingArea.getLineCount();
            int currentLine = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition()) + 1;
            int percent = totalLines > 0 ? (currentLine * 100) / totalLines : 0;
            return "\"" + name + "\" " + totalLines + " lines --" + percent + "%--";
        } catch (Exception e) {
            return "Error getting file info";
        }
    }


    String goToFileUnderCursor() {
        try {
            String text = editor.writingArea.getText();
            int pos = editor.writingArea.getCaretPosition();
            // Expand from cursor to find a file-path-like string
            int start = pos;
            int end = pos;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1)) && text.charAt(start - 1) != '"' && text.charAt(start - 1) != '\'' && text.charAt(start - 1) != '<') {
                start--;
            }
            while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '"' && text.charAt(end) != '\'' && text.charAt(end) != '>') {
                end++;
            }
            if (start == end) return "No file path under cursor";
            String path = text.substring(start, end);

            File file = new File(path);
            if (!file.isAbsolute()) {
                FileBuffer buffer = editor.getCurrentBuffer();
                File baseDir = buffer != null && buffer.getFile() != null ? buffer.getFile().getParentFile() : new File(".");
                file = new File(baseDir, path);
            }
            if (file.exists() && file.isFile()) {
                editor.openFile(file);
                return "Opened " + file.getName();
            }
            return "File not found: " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }


    String openBrowserUrl() {
        try {
            String text = editor.writingArea.getText();
            int pos = editor.writingArea.getCaretPosition();
            int start = pos;
            int end = pos;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            if (start == end) return "No URL under cursor";
            String url = text.substring(start, end);
            // Strip surrounding markdown link syntax
            if (url.startsWith("[")) {
                int urlStart = url.indexOf("](");
                int urlEnd = url.indexOf(")", urlStart);
                if (urlStart >= 0 && urlEnd > urlStart) {
                    url = url.substring(urlStart + 2, urlEnd);
                }
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(new URI(url));
                    return "Opened: " + url;
                }
                return "Desktop not supported";
            }
            return "Not a URL: " + url;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }


    void selectCurrentLine() {
        int caret = editor.writingArea.getCaretPosition();
        selectLineRange(caret, caret);
        editor.editorState.visualStartPos = editor.writingArea.getSelectionStart();
    }


    void selectLineRange(int anchorPosition, int currentPosition) {
        try {
            int anchorLine = editor.writingArea.getLineOfOffset(anchorPosition);
            int currentLine = editor.writingArea.getLineOfOffset(currentPosition);
            int startLine = Math.min(anchorLine, currentLine);
            int endLine = Math.max(anchorLine, currentLine);
            int selectionStart = editor.writingArea.getLineStartOffset(startLine);
            int selectionEnd = editor.writingArea.getLineEndOffset(endLine);
            if (currentLine >= anchorLine) {
                editor.writingArea.setCaretPosition(selectionStart);
                editor.writingArea.moveCaretPosition(selectionEnd);
            } else {
                editor.writingArea.setCaretPosition(selectionEnd);
                editor.writingArea.moveCaretPosition(selectionStart);
            }
        } catch (BadLocationException ignored) {
        }
    }


    void ensureCaretVisible(JTextArea area) {
        if (area == null) return;
        try {
            Rectangle2D bounds = area.modelToView2D(area.getCaretPosition());
            if (bounds == null) return;
            int scrolloff = editor.configManager.getScrolloff();
            if (scrolloff > 0) {
                int lineHeight = area.getFontMetrics(area.getFont()).getHeight();
                Rectangle expanded = bounds.getBounds();
                expanded.y -= scrolloff * lineHeight;
                expanded.height += 2 * scrolloff * lineHeight;
                area.scrollRectToVisible(expanded);
            } else {
                area.scrollRectToVisible(bounds.getBounds());
            }
        } catch (BadLocationException ignored) {
        }
    }


    boolean isPrintableKey(KeyEvent e) {
        char c = e.getKeyChar();
        return c != KeyEvent.CHAR_UNDEFINED
            && !Character.isISOControl(c)
            && !e.isControlDown()
            && !e.isAltDown()
            && !e.isMetaDown();
    }


    String searchWordUnderCursor(boolean forward) {
        String text = editor.writingArea.getText();
        int caret = editor.writingArea.getCaretPosition();
        if (text.isEmpty() || caret >= text.length()) {
            return "No word under cursor";
        }

        int start = caret;
        int end = caret;
        while (start > 0 && isWordCharacter(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isWordCharacter(text.charAt(end))) {
            end++;
        }
        if (start == end) {
            return "No word under cursor";
        }

        String word = text.substring(start, end);
        return forward ? editor.searchManager.searchForward(word) : editor.searchManager.searchBackward(word);
    }


    boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }


    void addCursorAtNextMatch() {
        String text = editor.writingArea.getText();
        String selected = editor.writingArea.getSelectedText();
        if (selected == null || selected.isEmpty()) {
            // Get word under cursor
            int pos = editor.writingArea.getCaretPosition();
            int start = pos, end = pos;
            while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) start--;
            while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) end++;
            if (start == end) return;
            selected = text.substring(start, end);
        }
        // Find next occurrence after last cursor
        int searchFrom = editor.writingArea.getCaretPosition();
        for (int ec : editor.extraCursors) {
            searchFrom = Math.max(searchFrom, ec);
        }
        int nextIdx = text.indexOf(selected, searchFrom + 1);
        if (nextIdx < 0) nextIdx = text.indexOf(selected); // wrap around
        if (nextIdx >= 0 && !editor.extraCursors.contains(nextIdx)) {
            editor.extraCursors.add(nextIdx);
            editor.showMessage("Added cursor (" + editor.extraCursors.size() + " extra)");
        }
    }


    String formatParagraph() {
        int tw = editor.configManager.getTextWidth();
        if (tw <= 0) return "textwidth not set (use :set tw=80)";
        try {
            int caretPos = editor.writingArea.getCaretPosition();
            int startLine = editor.writingArea.getLineOfOffset(caretPos);
            int endLine = startLine;
            String text = editor.writingArea.getText();
            // expand to paragraph boundaries (blank lines)
            while (startLine > 0) {
                int ls = editor.writingArea.getLineStartOffset(startLine - 1);
                int le = editor.writingArea.getLineEndOffset(startLine - 1);
                if (text.substring(ls, le).trim().isEmpty()) break;
                startLine--;
            }
            while (endLine < editor.writingArea.getLineCount() - 1) {
                int ls = editor.writingArea.getLineStartOffset(endLine + 1);
                int le = editor.writingArea.getLineEndOffset(endLine + 1);
                if (text.substring(ls, le).trim().isEmpty()) break;
                endLine++;
            }
            int startOff = editor.writingArea.getLineStartOffset(startLine);
            int endOff = editor.writingArea.getLineEndOffset(endLine);
            String paraRaw = text.substring(startOff, endOff);
            // preserve leading indent from first line
            String indent = "";
            for (int i2 = 0; i2 < paraRaw.length(); i2++) {
                char ic = paraRaw.charAt(i2);
                if (ic == ' ' || ic == '\t') indent += ic;
                else break;
            }
            String paragraph = paraRaw.trim();
            String[] words = paragraph.split("\\s+");
            StringBuilder formatted = new StringBuilder();
            int col = indent.length();
            formatted.append(indent);
            for (String word : words) {
                if (col > 0 && col + 1 + word.length() > tw) {
                    formatted.append("\n").append(indent);
                    col = indent.length();
                }
                if (col > indent.length()) { formatted.append(" "); col++; }
                formatted.append(word);
                col += word.length();
            }
            formatted.append("\n");
            editor.writingArea.replaceRange(formatted.toString(), startOff, endOff);
            editor.markModified();
            return "Formatted paragraph to " + tw + " columns";
        } catch (BadLocationException e) { return "Error: " + e.getMessage(); }
    }


    void moveDisplayLineDown() {
        try {
            Rectangle2D r = editor.writingArea.modelToView2D(editor.writingArea.getCaretPosition());
            if (r == null) return;
            int lineH = editor.writingArea.getFontMetrics(editor.writingArea.getFont()).getHeight();
            int newY = (int) r.getY() + lineH;
            int newPos = editor.writingArea.viewToModel2D(new java.awt.geom.Point2D.Double(r.getX(), newY));
            if (newPos >= 0 && newPos <= editor.writingArea.getText().length()) editor.writingArea.setCaretPosition(newPos);
        } catch (BadLocationException ignored) {}
    }


    void moveDisplayLineUp() {
        try {
            Rectangle2D r = editor.writingArea.modelToView2D(editor.writingArea.getCaretPosition());
            if (r == null) return;
            int lineH = editor.writingArea.getFontMetrics(editor.writingArea.getFont()).getHeight();
            int newY = (int) r.getY() - lineH;
            if (newY < 0) return;
            int newPos = editor.writingArea.viewToModel2D(new java.awt.geom.Point2D.Double(r.getX(), newY));
            if (newPos >= 0 && newPos <= editor.writingArea.getText().length()) editor.writingArea.setCaretPosition(newPos);
        } catch (BadLocationException ignored) {}
    }


    void enterVisualBlockMode() {
        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            int col = pos - editor.writingArea.getLineStartOffset(line);
            editor.editorState.visualBlockStartLine = line;
            editor.editorState.visualBlockStartCol = col;
            editor.editorState.visualStartPos = pos;
            editor.setMode(EditorMode.VISUAL_BLOCK);
        } catch (BadLocationException ignored) {}
    }


    int[] getVisualBlockBounds() {
        if (editor.editorState.visualBlockStartLine < 0 || editor.editorState.visualBlockStartCol < 0) return null;
        try {
            int caretPos = editor.writingArea.getCaretPosition();
            int curLine = editor.writingArea.getLineOfOffset(caretPos);
            int curCol = caretPos - editor.writingArea.getLineStartOffset(curLine);
            int startLine = Math.min(editor.editorState.visualBlockStartLine, curLine);
            int endLine = Math.max(editor.editorState.visualBlockStartLine, curLine);
            int startCol = Math.min(editor.editorState.visualBlockStartCol, curCol);
            int endCol = Math.max(editor.editorState.visualBlockStartCol, curCol);
            return new int[]{startLine, endLine, startCol, endCol};
        } catch (BadLocationException ignored) { return null; }
    }


    void deleteVisualBlock() {
        int[] bounds = getVisualBlockBounds();
        if (bounds == null) return;
        int startLine = bounds[0], endLine = bounds[1], startCol = bounds[2], endCol = bounds[3];
        try {
            StringBuilder yanked = new StringBuilder();
            for (int line = endLine; line >= startLine; line--) {
                int ls = editor.writingArea.getLineStartOffset(line);
                int le = editor.writingArea.getLineEndOffset(line);
                String lineText = editor.writingArea.getText().substring(ls, le);
                int sc = Math.min(startCol, lineText.length());
                int ec = Math.min(endCol + 1, lineText.length());
                if (sc < ec) {
                    if (line < endLine) yanked.insert(0, "\n");
                    yanked.insert(0, lineText.substring(sc, ec));
                    editor.writingArea.replaceRange("", ls + sc, ls + ec);
                }
            }
            editor.clipboardManager.yankSelection(yanked.toString());
            storeDelete(consumePendingRegister(), yanked.toString(), false);
            editor.markModified();
            editor.showMessage("Block deleted");
        } catch (BadLocationException ignored) {}
    }


    void yankVisualBlock() {
        int[] bounds = getVisualBlockBounds();
        if (bounds == null) return;
        int startLine = bounds[0], endLine = bounds[1], startCol = bounds[2], endCol = bounds[3];
        try {
            StringBuilder yanked = new StringBuilder();
            for (int line = startLine; line <= endLine; line++) {
                int ls = editor.writingArea.getLineStartOffset(line);
                int le = editor.writingArea.getLineEndOffset(line);
                String lineText = editor.writingArea.getText().substring(ls, le);
                int sc = Math.min(startCol, lineText.length());
                int ec = Math.min(endCol + 1, lineText.length());
                if (line > startLine) yanked.append("\n");
                if (sc < ec) yanked.append(lineText, sc, ec);
            }
            editor.clipboardManager.yankSelection(yanked.toString());
            storeYank(consumePendingRegister(), yanked.toString(), false);
            editor.showMessage("Block yanked");
        } catch (BadLocationException ignored) {}
    }


    void applyMultiCursorInsert(char c) {
        if (editor.extraCursors.isEmpty()) return;
        // Sort cursors descending so insertions don't shift earlier positions
        List<Integer> sorted = new ArrayList<>(editor.extraCursors);
        sorted.sort(Collections.reverseOrder());
        String s = String.valueOf(c);
        for (int pos : sorted) {
            if (pos >= 0 && pos <= editor.writingArea.getText().length()) {
                editor.writingArea.insert(s, pos);
            }
        }
        // Shift all cursors forward by 1
        for (int i = 0; i < editor.extraCursors.size(); i++) {
            editor.extraCursors.set(i, editor.extraCursors.get(i) + 1);
        }
    }


    void applyMultiCursorBackspace() {
        if (editor.extraCursors.isEmpty()) return;
        List<Integer> sorted = new ArrayList<>(editor.extraCursors);
        sorted.sort(Collections.reverseOrder());
        for (int pos : sorted) {
            if (pos > 0 && pos <= editor.writingArea.getText().length()) {
                editor.writingArea.replaceRange("", pos - 1, pos);
            }
        }
        for (int i = 0; i < editor.extraCursors.size(); i++) {
            editor.extraCursors.set(i, Math.max(0, editor.extraCursors.get(i) - 1));
        }
    }


    void applyMultiCursorDelete() {
        if (editor.extraCursors.isEmpty()) return;
        List<Integer> sorted = new ArrayList<>(editor.extraCursors);
        sorted.sort(Collections.reverseOrder());
        for (int pos : sorted) {
            if (pos >= 0 && pos < editor.writingArea.getText().length()) {
                editor.writingArea.replaceRange("", pos, pos + 1);
            }
        }
    }


    void addCursorAbove() {
        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            if (line <= 0) return;
            int col = pos - editor.writingArea.getLineStartOffset(line);
            int prevLineStart = editor.writingArea.getLineStartOffset(line - 1);
            int prevLineEnd = editor.writingArea.getLineEndOffset(line - 1);
            int newPos = Math.min(prevLineStart + col, prevLineEnd - 1);
            if (!editor.extraCursors.contains(newPos)) {
                editor.extraCursors.add(newPos);
                editor.showMessage("Added cursor above (" + editor.extraCursors.size() + " extra)");
            }
        } catch (BadLocationException ignored) {}
    }


    void addCursorBelow() {
        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            if (line >= editor.writingArea.getLineCount() - 1) return;
            int col = pos - editor.writingArea.getLineStartOffset(line);
            int nextLineStart = editor.writingArea.getLineStartOffset(line + 1);
            int nextLineEnd = editor.writingArea.getLineEndOffset(line + 1);
            int newPos = Math.min(nextLineStart + col, nextLineEnd - 1);
            if (!editor.extraCursors.contains(newPos)) {
                editor.extraCursors.add(newPos);
                editor.showMessage("Added cursor below (" + editor.extraCursors.size() + " extra)");
            }
        } catch (BadLocationException ignored) {}
    }


    void clearExtraCursors() {
        if (!editor.extraCursors.isEmpty()) {
            editor.extraCursors.clear();
        }
    }


    void deleteWordBackwardInsert() {
        try {
            String text = editor.writingArea.getText();
            int pos = editor.writingArea.getCaretPosition();
            if (pos <= 0) return;
            int start = pos - 1;
            // Skip whitespace
            while (start > 0 && Character.isWhitespace(text.charAt(start)) && text.charAt(start) != '\n') start--;
            if (start > 0 && text.charAt(start) != '\n') {
                int cls = editor.vimCharClass(text.charAt(start));
                while (start > 0 && editor.vimCharClass(text.charAt(start - 1)) == cls) start--;
            }
            editor.writingArea.replaceRange("", start, pos);
        } catch (Exception ignored) {}
    }


    void deleteToLineStartInsert() {
        try {
            String text = editor.writingArea.getText();
            int pos = editor.writingArea.getCaretPosition();
            int lineStart = text.lastIndexOf('\n', pos - 1) + 1;
            if (pos > lineStart) {
                editor.writingArea.replaceRange("", lineStart, pos);
            }
        } catch (Exception ignored) {}
    }


    String currentLineIndentation() {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int start = editor.writingArea.getLineStartOffset(line);
            int end = editor.writingArea.getLineEndOffset(line);
            String lineText = editor.writingArea.getText().substring(start, end);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < lineText.length(); i++) {
                char c = lineText.charAt(i);
                if (c == ' ' || c == '\t') {
                    builder.append(c);
                } else {
                    break;
                }
            }
            return builder.toString();
        } catch (BadLocationException e) {
            return "";
        }
    }


    void moveLineIndentStart() {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int start = editor.writingArea.getLineStartOffset(line);
            int end = editor.writingArea.getLineEndOffset(line);
            String lineText = editor.writingArea.getText().substring(start, end);
            int offset = 0;
            while (offset < lineText.length() && Character.isWhitespace(lineText.charAt(offset)) && lineText.charAt(offset) != '\n') {
                offset++;
            }
            editor.writingArea.setCaretPosition(start + offset);
        } catch (BadLocationException ignored) {
        }
    }


    void openLineBelow() {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int lineEnd = editor.writingArea.getLineEndOffset(line);
            String indent = editor.configManager.getAutoIndent() ? currentLineIndentation() : "";
            editor.writingArea.insert("\n" + indent, lineEnd - 1);
            editor.writingArea.setCaretPosition(lineEnd + indent.length());
            editor.markModified();
        } catch (BadLocationException ignored) {
        }
    }


    void openLineAbove() {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int lineStart = editor.writingArea.getLineStartOffset(line);
            String indent = editor.configManager.getAutoIndent() ? currentLineIndentation() : "";
            editor.writingArea.insert(indent + "\n", lineStart);
            editor.writingArea.setCaretPosition(lineStart + indent.length());
            editor.markModified();
        } catch (BadLocationException ignored) {
        }
    }


    void joinCurrentLine(boolean withSpace) {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            if (line >= editor.writingArea.getLineCount() - 1) {
                editor.showMessage("Already on last line");
                return;
            }
            int lineEnd = editor.writingArea.getLineEndOffset(line);
            int nextLineStart = editor.writingArea.getLineStartOffset(line + 1);
            int nextLineEnd = editor.writingArea.getLineEndOffset(line + 1);
            String nextLine = editor.writingArea.getText().substring(nextLineStart, nextLineEnd).stripLeading();
            editor.writingArea.replaceRange(withSpace ? " " + nextLine : nextLine, lineEnd - 1, nextLineEnd);
            editor.markModified();
        } catch (BadLocationException ignored) {
        }
    }


    String applyLineOperator(char operator) {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int start = editor.writingArea.getLineStartOffset(line);
            int end = editor.writingArea.getLineEndOffset(line);
            String text = editor.writingArea.getText().substring(start, end);
            switch (operator) {
                case '>':
                    String indent = editor.configManager.getExpandTab() ? " ".repeat(editor.writingArea.getTabSize()) : "\t";
                    editor.writingArea.replaceRange(indent + text, start, end);
                    break;
                case '<':
                    int removeCount = Math.min(editor.writingArea.getTabSize(), leadingWhitespace(text));
                    editor.writingArea.replaceRange(text.substring(removeCount), start, end);
                    break;
                case '=':
                    String previousIndent = line > 0 ? indentationForLine(line - 1) : "";
                    editor.writingArea.replaceRange(previousIndent + text.stripLeading(), start, end);
                    break;
                default:
                    return "";
            }
            editor.markModified();
            return "Line updated";
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }


    int leadingWhitespace(String text) {
        int count = 0;
        while (count < text.length() && Character.isWhitespace(text.charAt(count)) && text.charAt(count) != '\n') {
            count++;
        }
        return count;
    }


    String indentationForLine(int line) {
        try {
            int start = editor.writingArea.getLineStartOffset(line);
            int end = editor.writingArea.getLineEndOffset(line);
            String lineText = editor.writingArea.getText().substring(start, end);
            int count = 0;
            while (count < lineText.length() && Character.isWhitespace(lineText.charAt(count)) && lineText.charAt(count) != '\n') {
                count++;
            }
            return lineText.substring(0, count);
        } catch (BadLocationException e) {
            return "";
        }
    }


    String findCharacter(char type, char target) {
        String text = editor.writingArea.getText();
        int caret = editor.writingArea.getCaretPosition();
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        int lineEnd = text.indexOf('\n', caret);
        if (lineEnd < 0) lineEnd = text.length();

        int result = -1;
        switch (type) {
            case 'f':
                for (int i = caret + 1; i < lineEnd; i++) {
                    if (text.charAt(i) == target) { result = i; break; }
                }
                break;
            case 'F':
                for (int i = caret - 1; i >= lineStart; i--) {
                    if (text.charAt(i) == target) { result = i; break; }
                }
                break;
            case 't':
                for (int i = caret + 1; i < lineEnd; i++) {
                    if (text.charAt(i) == target) { result = i - 1; break; }
                }
                break;
            case 'T':
                for (int i = caret - 1; i >= lineStart; i--) {
                    if (text.charAt(i) == target) { result = i + 1; break; }
                }
                break;
            default:
                break;
        }
        if (result < 0 || result >= text.length()) {
            return "Character not found: " + target;
        }
        editor.writingArea.setCaretPosition(result);
        editor.lastFindType = type;
        editor.lastFindChar = target;
        return "Moved to " + target;
    }


    String repeatFind(boolean reverse) {
        if (editor.lastFindType == '\0' || editor.lastFindChar == '\0') {
            return "No previous find command";
        }
        char repeatType = editor.lastFindType;
        if (reverse) {
            switch (editor.lastFindType) {
                case 'f':
                    repeatType = 'F';
                    break;
                case 'F':
                    repeatType = 'f';
                    break;
                case 't':
                    repeatType = 'T';
                    break;
                case 'T':
                    repeatType = 't';
                    break;
                default:
                    break;
            }
        }
        return findCharacter(repeatType, editor.lastFindChar);
    }


    void recordJumpPosition() {
        int position = editor.writingArea.getCaretPosition();
        if (editor.jumpList.isEmpty() || editor.jumpList.get(editor.jumpList.size() - 1) != position) {
            if (editor.jumpIndex >= 0 && editor.jumpIndex < editor.jumpList.size() - 1) {
                editor.jumpList = new ArrayList<>(editor.jumpList.subList(0, editor.jumpIndex + 1));
            }
            editor.jumpList.add(position);
            editor.jumpIndex = editor.jumpList.size() - 1;
        }
    }


    void jumpBack() {
        if (editor.jumpList.isEmpty() || editor.jumpIndex <= 0) {
            editor.showMessage("At oldest jump");
            return;
        }
        editor.jumpIndex--;
        editor.writingArea.setCaretPosition(Math.min(editor.jumpList.get(editor.jumpIndex), editor.writingArea.getText().length()));
    }


    void jumpForward() {
        if (editor.jumpList.isEmpty() || editor.jumpIndex >= editor.jumpList.size() - 1) {
            editor.showMessage("At newest jump");
            return;
        }
        editor.jumpIndex++;
        editor.writingArea.setCaretPosition(Math.min(editor.jumpList.get(editor.jumpIndex), editor.writingArea.getText().length()));
    }


    void recordChangePosition() {
        int position = editor.writingArea.getCaretPosition();
        if (editor.changeList.isEmpty() || editor.changeList.get(editor.changeList.size() - 1) != position) {
            editor.changeList.add(position);
            if (editor.changeList.size() > 100) {
                editor.changeList.remove(0);
            }
            editor.changeIndex = editor.changeList.size() - 1;
        }
    }


    void changePrev() {
        if (editor.changeList.isEmpty() || editor.changeIndex <= 0) {
            editor.showMessage("At oldest change");
            return;
        }
        editor.changeIndex--;
        editor.writingArea.setCaretPosition(Math.min(editor.changeList.get(editor.changeIndex), editor.writingArea.getText().length()));
    }


    void changeNext() {
        if (editor.changeList.isEmpty() || editor.changeIndex >= editor.changeList.size() - 1) {
            editor.showMessage("At newest change");
            return;
        }
        editor.changeIndex++;
        editor.writingArea.setCaretPosition(Math.min(editor.changeList.get(editor.changeIndex), editor.writingArea.getText().length()));
    }


    void insertLastText() {
        if (editor.lastInsertedText != null && !editor.lastInsertedText.isEmpty()) {
            int pos = editor.writingArea.getCaretPosition();
            editor.writingArea.insert(editor.lastInsertedText, pos);
            editor.writingArea.setCaretPosition(pos + editor.lastInsertedText.length());
            editor.markModified();
        }
    }


    int consumePendingCount() {
        if (editor.editorState.pendingCount == null || editor.editorState.pendingCount.isEmpty()) {
            return 1;
        }
        int count = Integer.parseInt(editor.editorState.pendingCount);
        editor.editorState.pendingCount = "";
        return Math.max(1, count);
    }


    void repeatAction(int count, Runnable action) {
        for (int i = 0; i < Math.max(1, count); i++) {
            action.run();
        }
    }


    Character consumePendingRegister() {
        Character register = editor.editorState.pendingRegister;
        editor.editorState.pendingRegister = null;
        return register;
    }


    void storeYank(Character register, String text, boolean lineWise) {
        RegisterContent content = lineWise ? RegisterContent.lineWise(text) : RegisterContent.characterWise(text);
        editor.registerManager.setYank(register, content);
        addToYankRing(content);
    }


    void storeDelete(Character register, String text, boolean lineWise) {
        RegisterContent content = lineWise ? RegisterContent.lineWise(text) : RegisterContent.characterWise(text);
        editor.registerManager.setDelete(register, content);
        addToYankRing(content);
    }


    void addToYankRing(RegisterContent content) {
        if (content == null || content.isMacro()) {
            return;
        }
        String text = content.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        editor.yankRing.removeIf(existing -> existing != null
            && !existing.isMacro()
            && existing.isLineWise() == content.isLineWise()
            && text.equals(existing.getText()));
        editor.yankRing.add(0, content);
        while (editor.yankRing.size() > 80) {
            editor.yankRing.remove(editor.yankRing.size() - 1);
        }
    }


    public String showYankRingPicker() {
        if (editor.yankRing.isEmpty()) {
            return "Yank ring empty";
        }
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < editor.yankRing.size(); i++) {
            RegisterContent content = editor.yankRing.get(i);
            String kind = content.isLineWise() ? "[L]" : "[C]";
            candidates.add(String.format("%02d %s %s", i + 1, kind, editor.safePreviewText(content.getText(), 100)));
        }
        String selected = editor.showPaletteDialog("Yank Ring", candidates,
            value -> value == null ? "" : "Enter to paste selected ring entry");
        if (selected == null || selected.isBlank()) {
            return "Yank ring cancelled";
        }
        int index = candidates.indexOf(selected);
        if (index < 0 || index >= editor.yankRing.size()) {
            return "Invalid yank ring selection";
        }
        RegisterContent content = editor.yankRing.get(index);
        editor.clipboardManager.pasteContent(editor.writingArea, content.getText(), content.isLineWise(), false);
        editor.markModified();
        return "Pasted yank ring item " + (index + 1);
    }


    String pasteFromRegister(boolean before) {
        RegisterContent content = editor.registerManager.get(consumePendingRegister());
        if (content == null || content.getText().isEmpty()) {
            return "Register empty";
        }
        editor.clipboardManager.pasteContent(editor.writingArea, content.getText(), content.isLineWise(), before);
        editor.markModified();
        return "Pasted";
    }


    String playMacro(Character register) {
        if (register == null) {
            return "No previously executed macro";
        }
        RegisterContent content = editor.registerManager.get(register);
        if (content == null || !content.isMacro()) {
            return "Register @" + register + " is empty or not a macro";
        }
        if (editor.macroPlaybackDepth >= 20) {
            return "Macro recursion limit reached";
        }

        editor.macroPlaybackDepth++;
        try {
            editor.lastMacroRegister = register;
            for (NormalizedKeyStroke keyStroke : content.getMacroKeys()) {
                editor.keyPressed(keyStroke.toKeyEvent(editor.writingArea));
            }
        } finally {
            editor.macroPlaybackDepth--;
        }
        return "Executed macro @" + register;
    }


    String yankToEndOfLine() {
        try {
            int start = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(start);
            int end = editor.writingArea.getLineEndOffset(line);
            String text = editor.writingArea.getText().substring(start, end);
            storeYank(consumePendingRegister(), text, false);
            return "Yanked " + text.length() + " characters";
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }


    String replaceCharacter(char replacement) {
        int caret = editor.writingArea.getCaretPosition();
        String text = editor.writingArea.getText();
        if (caret >= text.length()) {
            return "No character to replace";
        }
        editor.writingArea.replaceRange(String.valueOf(replacement), caret, caret + 1);
        editor.markModified();
        return "Replaced character";
    }


    String applyMotionOperator(char operator, String motion) {
        MotionRange range = resolveMotionRange(motion);
        return applyResolvedRange(operator, range, motion);
    }


    String applyTextObjectOperator(char operator, char modifier, char objectKey) {
        MotionRange range = resolveTextObjectRange(modifier, objectKey);
        return applyResolvedRange(operator, range, String.valueOf(modifier) + objectKey);
    }


    String applyResolvedRange(char operator, MotionRange range, String label) {
        if (range == null || range.start == range.end) {
            return "Unsupported target: " + label;
        }

        String selected = editor.writingArea.getText().substring(range.start, Math.min(range.end, editor.writingArea.getText().length()));
        switch (operator) {
            case 'y':
                storeYank(consumePendingRegister(), selected, range.lineWise);
                editor.lastCommand = "y" + label;
                return "Yanked " + selected.length() + " characters";
            case 'd':
                storeDelete(consumePendingRegister(), selected, range.lineWise);
                editor.writingArea.replaceRange("", range.start, range.end);
                editor.writingArea.setCaretPosition(Math.min(range.start, editor.writingArea.getText().length()));
                editor.lastCommand = "d" + label;
                editor.markModified();
                return "Deleted " + selected.length() + " characters";
            case 'c':
                storeDelete(consumePendingRegister(), selected, range.lineWise);
                editor.writingArea.replaceRange("", range.start, range.end);
                editor.writingArea.setCaretPosition(Math.min(range.start, editor.writingArea.getText().length()));
                editor.lastInsertedText = "";
                editor.lastCommand = "c" + label;
                editor.markModified();
                editor.setMode(EditorMode.INSERT);
                return "Changed " + selected.length() + " characters";
            default:
                return "Unsupported operator";
        }
    }


    MotionRange resolveMotionRange(String motion) {
        try {
            int original = editor.writingArea.getCaretPosition();
            if ("gg".equals(motion) || "G".equals(motion)) {
                int originalLine = editor.writingArea.getLineOfOffset(original);
                if ("gg".equals(motion)) {
                    moveFileStart();
                } else {
                    moveFileEnd();
                }
                int targetLine = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
                editor.writingArea.setCaretPosition(original);
                int startLine = Math.min(originalLine, targetLine);
                int endLine = Math.max(originalLine, targetLine);
                int start = editor.writingArea.getLineStartOffset(startLine);
                int end = editor.writingArea.getLineEndOffset(endLine);
                return new MotionRange(start, end, true);
            }

            int target = previewMotionTarget(motion);
            if (target < 0) {
                return null;
            }

            boolean inclusive = "e".equals(motion) || "E".equals(motion) || "ge".equals(motion) || "gE".equals(motion) || "$".equals(motion) || "g$".equals(motion) || "l".equals(motion);
            int start = Math.min(original, target);
            int end = Math.max(original, target);
            if (inclusive) {
                end = Math.min(end + 1, editor.writingArea.getText().length());
            }
            return new MotionRange(start, end, false);
        } catch (BadLocationException e) {
            return null;
        }
    }


    MotionRange resolveTextObjectRange(char modifier, char objectKey) {
        switch (objectKey) {
            case 'w':
            case 'W':
                return resolveWordObject(modifier == 'a', objectKey == 'W');
            case 'p':
                return resolveParagraphObject(modifier == 'a');
            case 's':
                return resolveSentenceObject(modifier == 'a');
            case '"':
            case '\'':
            case '`':
                return resolveQuoteObject(modifier == 'a', objectKey);
            case '(':
            case ')':
                return resolveBracketObject(modifier == 'a', '(', ')');
            case '[':
            case ']':
                return resolveBracketObject(modifier == 'a', '[', ']');
            case '{':
            case '}':
                return resolveBracketObject(modifier == 'a', '{', '}');
            case '<':
            case '>':
                return resolveBracketObject(modifier == 'a', '<', '>');
            default:
                return null;
        }
    }


    String handleSurroundPending(char c) {
        if (editor.pendingSurroundAction == 'c') {
            if (editor.pendingSurroundOld == null) {
                editor.pendingSurroundOld = c;
                return "Awaiting new surround";
            }
            String result = surroundChange(editor.pendingSurroundOld, c);
            editor.pendingSurroundAction = null;
            editor.pendingSurroundOld = null;
            return result;
        }

        if (editor.pendingSurroundAction == 'd') {
            String result = surroundDelete(c);
            editor.pendingSurroundAction = null;
            return result;
        }

        if (editor.pendingSurroundAction == 'y') {
            if (editor.pendingSurroundTarget == null && isTextObjectKey(c)) {
                editor.pendingSurroundTarget = c;
                return "Awaiting surround delimiter";
            }
            char target = editor.pendingSurroundTarget == null ? 'w' : editor.pendingSurroundTarget;
            String result = surroundAdd(target, c);
            editor.pendingSurroundAction = null;
            editor.pendingSurroundTarget = null;
            return result;
        }

        editor.pendingSurroundAction = null;
        editor.pendingSurroundOld = null;
        editor.pendingSurroundTarget = null;
        return "Unsupported surround";
    }


    boolean isTextObjectKey(char c) {
        return "wWps\"'`()[]{}<>".indexOf(c) >= 0;
    }


    String surroundChange(char oldChar, char newChar) {
        MotionRange range = resolveSurroundRange(oldChar);
        SurroundPair newPair = surroundPair(newChar);
        if (range == null || newPair == null) {
            return "No matching surround found";
        }
        editor.writingArea.replaceRange(String.valueOf(newPair.close), range.end - 1, range.end);
        editor.writingArea.replaceRange(String.valueOf(newPair.open), range.start, range.start + 1);
        editor.markModified();
        return "Surround changed";
    }


    String surroundDelete(char target) {
        MotionRange range = resolveSurroundRange(target);
        if (range == null) {
            return "No matching surround found";
        }
        editor.writingArea.replaceRange("", range.end - 1, range.end);
        editor.writingArea.replaceRange("", range.start, range.start + 1);
        editor.markModified();
        return "Surround deleted";
    }


    String surroundAdd(char targetObject, char surroundChar) {
        MotionRange range = resolveTextObjectRange('i', targetObject);
        SurroundPair pair = surroundPair(surroundChar);
        if (range == null || pair == null) {
            return "No valid surround target";
        }
        editor.writingArea.insert(String.valueOf(pair.close), range.end);
        editor.writingArea.insert(String.valueOf(pair.open), range.start);
        editor.markModified();
        return "Surround added";
    }


    MotionRange resolveSurroundRange(char surround) {
        if (surround == '"' || surround == '\'' || surround == '`') {
            return resolveQuoteObject(true, surround);
        }
        SurroundPair pair = surroundPair(surround);
        if (pair == null) {
            return null;
        }
        return resolveBracketObject(true, pair.open, pair.close);
    }


    SurroundPair surroundPair(char surround) {
        switch (surround) {
            case '(':
            case ')':
                return new SurroundPair('(', ')');
            case '[':
            case ']':
                return new SurroundPair('[', ']');
            case '{':
            case '}':
                return new SurroundPair('{', '}');
            case '<':
            case '>':
                return new SurroundPair('<', '>');
            case '"':
            case '\'':
            case '`':
                return new SurroundPair(surround, surround);
            default:
                return null;
        }
    }


    MotionRange resolveWordObject(boolean around, boolean bigWord) {
        String text = editor.writingArea.getText();
        if (text.isEmpty()) {
            return null;
        }
        int caret = Math.min(editor.writingArea.getCaretPosition(), text.length() - 1);
        int start = caret;
        int end = caret;
        while (start > 0 && isMotionWordChar(text.charAt(start - 1), bigWord)) {
            start--;
        }
        while (end < text.length() && isMotionWordChar(text.charAt(end), bigWord)) {
            end++;
        }
        if (around) {
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
        }
        return new MotionRange(start, end, false);
    }


    MotionRange resolveParagraphObject(boolean around) {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int startLine = line;
            int endLine = line;
            while (startLine > 0 && !lineText(startLine - 1).isBlank()) {
                startLine--;
            }
            while (endLine < editor.writingArea.getLineCount() - 1 && !lineText(endLine + 1).isBlank()) {
                endLine++;
            }
            if (around) {
                if (startLine > 0) {
                    startLine--;
                }
                if (endLine < editor.writingArea.getLineCount() - 1) {
                    endLine++;
                }
            }
            return new MotionRange(editor.writingArea.getLineStartOffset(startLine), editor.writingArea.getLineEndOffset(endLine), true);
        } catch (BadLocationException e) {
            return null;
        }
    }


    MotionRange resolveSentenceObject(boolean around) {
        String text = editor.writingArea.getText();
        int caret = editor.writingArea.getCaretPosition();
        int start = caret;
        int end = caret;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (c == '.' || c == '!' || c == '?') {
                break;
            }
            start--;
        }
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == '.' || c == '!' || c == '?') {
                end++;
                break;
            }
            end++;
        }
        if (around) {
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
        }
        return new MotionRange(start, end, false);
    }


    MotionRange resolveQuoteObject(boolean around, char quote) {
        String text = editor.writingArea.getText();
        int caret = editor.writingArea.getCaretPosition();
        int start = text.lastIndexOf(quote, Math.max(0, caret - 1));
        int end = text.indexOf(quote, caret);
        if (start < 0 || end < 0 || start == end) {
            return null;
        }
        return around ? new MotionRange(start, end + 1, false) : new MotionRange(start + 1, end, false);
    }


    MotionRange resolveBracketObject(boolean around, char open, char close) {
        String text = editor.writingArea.getText();
        int caret = editor.writingArea.getCaretPosition();
        int start = -1;
        int depth = 0;
        for (int i = Math.max(0, caret - 1); i >= 0; i--) {
            char c = text.charAt(i);
            if (c == close) {
                depth++;
            } else if (c == open) {
                if (depth == 0) {
                    start = i;
                    break;
                }
                depth--;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = -1;
        depth = 0;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                if (depth == 0) {
                    end = i;
                    break;
                }
                depth--;
            }
        }
        if (end < 0) {
            return null;
        }
        return around ? new MotionRange(start, end + 1, false) : new MotionRange(start + 1, end, false);
    }


    int previewMotionTarget(String motion) {
        int original = editor.writingArea.getCaretPosition();
        switch (motion) {
            case "h":
                moveLeft();
                break;
            case "l":
                moveRight();
                break;
            case "w":
                moveWordForward();
                break;
            case "b":
                moveWordBackward();
                break;
            case "e":
                moveWordEnd();
                break;
            case "W":
                moveWordForwardBig();
                break;
            case "B":
                moveWordBackwardBig();
                break;
            case "E":
                moveWordEndBig();
                break;
            case "0":
            case "g0":
                moveLineStart();
                break;
            case "^":
                moveLineFirstNonBlank();
                break;
            case "$":
            case "g$":
                moveLineEnd();
                break;
            case "g_":
                moveLineLastNonBlank();
                break;
            case "ge":
                moveWordEndBackward();
                break;
            case "gE":
                moveWordEndBackwardBig();
                break;
            default:
                return -1;
        }
        int target = editor.writingArea.getCaretPosition();
        editor.writingArea.setCaretPosition(original);
        return target;
    }

}
