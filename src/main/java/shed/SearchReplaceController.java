package shed;

import javax.swing.text.BadLocationException;

final class SearchReplaceController {
    private final Texteditor editor;

    SearchReplaceController(Texteditor editor) {
        this.editor = editor;
    }

    public String search(String pattern) {
        editor.recordJumpPosition();
        String result = editor.searchManager.searchForward(pattern);
        if (!editor.configManager.getHighlightSearch()) {
            editor.searchManager.clearHighlights();
        }
        return result;
    }


    public String searchBackward(String pattern) {
        editor.recordJumpPosition();
        String result = editor.searchManager.searchBackward(pattern);
        if (!editor.configManager.getHighlightSearch()) {
            editor.searchManager.clearHighlights();
        }
        return result;
    }


    public String substitute(String pattern, String replacement, boolean wholeBuffer, boolean replaceAll) {
        if (wholeBuffer) {
            ReplacementResult result = replaceLiteral(editor.writingArea.getText(), pattern, replacement, replaceAll);
            if (result.matchCount == 0) {
                return "Pattern not found: " + pattern;
            }
            editor.writingArea.setText(result.updatedText);
            editor.writingArea.setCaretPosition(Math.min(Math.max(0, result.firstMatchOffset), editor.writingArea.getText().length()));
            editor.markModified();
            editor.searchManager.clearHighlights();
            return "Replaced " + result.matchCount + " occurrence" + (result.matchCount == 1 ? "" : "s");
        } else {
            return substituteCurrentLine(pattern, replacement, replaceAll);
        }
    }


    String substituteCurrentLine(String pattern, String replacement, boolean replaceAll) {
        try {
            int caretPosition = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caretPosition);
            int lineStart = editor.writingArea.getLineStartOffset(line);
            int lineEnd = editor.writingArea.getLineEndOffset(line);
            String lineText = editor.writingArea.getText().substring(lineStart, lineEnd);

            ReplacementResult result = replaceLiteral(lineText, pattern, replacement, replaceAll);
            if (result.matchCount == 0) {
                return "Pattern not found: " + pattern;
            }

            editor.writingArea.replaceRange(result.updatedText, lineStart, lineEnd);
            editor.writingArea.setCaretPosition(Math.min(lineStart + result.firstMatchOffset, editor.writingArea.getText().length()));
            editor.searchManager.clearHighlights();
            return "Replaced " + result.matchCount + " occurrence" + (result.matchCount == 1 ? "" : "s");
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }


    ReplacementResult replaceLiteral(String text, String pattern, String replacement, boolean replaceAll) {
        SubstituteService.Result r = editor.substituteService.replaceRegex(text, pattern, replacement, replaceAll);
        return new ReplacementResult(r.getUpdatedText(), r.getMatchCount(), r.getFirstMatchOffset());
    }

}
