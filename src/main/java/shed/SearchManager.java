package shed;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;

public class SearchManager {
    private String searchPattern;
    private final List<Integer> matchPositions;
    private final List<Integer> matchLengths; // parallel to matchPositions for variable-length regex matches
    private int currentMatchIndex;
    private final JTextComponent textArea;
    private final Highlighter.HighlightPainter painter;
    private final Highlighter.HighlightPainter currentPainter;
    private final List<Object> searchHighlightTags;
    private boolean useRegex;

    public SearchManager(JTextComponent textArea) {
        this.textArea = textArea;
        this.matchPositions = new ArrayList<>();
        this.matchLengths = new ArrayList<>();
        this.currentMatchIndex = -1;
        this.painter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x5B8C2A));
        this.currentPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0xE07A5F));
        this.searchHighlightTags = new ArrayList<>();
        this.useRegex = false;
    }

    public void setUseRegex(boolean useRegex) { this.useRegex = useRegex; }
    public boolean isUseRegex() { return useRegex; }

    public String searchForward(String pattern) { return search(pattern, true); }
    public String searchBackward(String pattern) { return search(pattern, false); }

    public String search(String pattern, boolean forward) {
        if (pattern == null || pattern.isEmpty()) return "Error: Empty search pattern";
        this.searchPattern = pattern;
        this.useRegex = containsRegexMeta(pattern);
        rebuildMatches();
        if (matchPositions.isEmpty()) {
            clearHighlights();
            return "Pattern not found: " + pattern;
        }
        int caret = textArea.getCaretPosition();
        currentMatchIndex = findNearestMatchIndex(caret, forward);
        boolean wrapped = didWrap(caret, currentMatchIndex, forward);
        jumpToMatch(currentMatchIndex);
        return wrapMessage(wrapped);
    }

    public String nextMatch() {
        if (matchPositions.isEmpty()) return "No search pattern";
        currentMatchIndex = (currentMatchIndex + 1) % matchPositions.size();
        boolean wrapped = currentMatchIndex == 0 && matchPositions.size() > 1;
        jumpToMatch(currentMatchIndex);
        return wrapMessage(wrapped);
    }

    public String prevMatch() {
        if (matchPositions.isEmpty()) return "No search pattern";
        currentMatchIndex--;
        boolean wrapped = currentMatchIndex < 0;
        if (currentMatchIndex < 0) currentMatchIndex = matchPositions.size() - 1;
        jumpToMatch(currentMatchIndex);
        return wrapMessage(wrapped);
    }

    public void clearHighlights() {
        Highlighter highlighter = textArea.getHighlighter();
        for (Object tag : searchHighlightTags) highlighter.removeHighlight(tag);
        searchHighlightTags.clear();
    }

    public int getMatchCount() { return matchPositions.size(); }
    public String getSearchPattern() { return searchPattern; }

    private static boolean containsRegexMeta(String pattern) {
        // only treat as regex if it has unambiguous regex constructs (not just '.' or '$')
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) { return true; } // escape sequence
            if ("[](){}*+?|^".indexOf(c) >= 0) return true; // clear regex metachar
        }
        return false;
    }

    private void rebuildMatches() {
        clearHighlights();
        matchPositions.clear();
        matchLengths.clear();
        currentMatchIndex = -1;
        String text = textArea.getText();
        if (searchPattern == null || searchPattern.isEmpty() || text.isEmpty()) return;
        if (useRegex) {
            try {
                Pattern compiled = Pattern.compile(searchPattern);
                Matcher m = compiled.matcher(text);
                while (m.find()) {
                    matchPositions.add(m.start());
                    matchLengths.add(m.end() - m.start());
                }
            } catch (PatternSyntaxException e) {
                rebuildMatchesLiteral(text); // fallback to literal
            }
        } else {
            rebuildMatchesLiteral(text);
        }
    }

    private void rebuildMatchesLiteral(String text) {
        matchPositions.clear();
        matchLengths.clear();
        int index = 0;
        while (index <= text.length() - searchPattern.length()) {
            int match = text.indexOf(searchPattern, index);
            if (match < 0) break;
            matchPositions.add(match);
            matchLengths.add(searchPattern.length());
            index = match + Math.max(1, searchPattern.length());
        }
    }

    private int matchLength(int index) {
        if (index >= 0 && index < matchLengths.size()) return matchLengths.get(index);
        return searchPattern != null ? searchPattern.length() : 0;
    }

    private int findNearestMatchIndex(int caret, boolean forward) {
        if (forward) {
            for (int i = 0; i < matchPositions.size(); i++) {
                if (matchPositions.get(i) >= caret) return i;
            }
            return 0;
        }
        for (int i = matchPositions.size() - 1; i >= 0; i--) {
            if (matchPositions.get(i) < caret) return i;
        }
        return matchPositions.size() - 1;
    }

    private boolean didWrap(int caret, int matchIndex, boolean forward) {
        if (matchIndex < 0 || matchPositions.isEmpty()) return false;
        int matchPosition = matchPositions.get(matchIndex);
        return forward ? matchPosition < caret : matchPosition >= caret;
    }

    private String wrapMessage(boolean wrapped) {
        String base = "Match " + (currentMatchIndex + 1) + " of " + matchPositions.size();
        return wrapped ? base + " (wrapped)" : base;
    }

    private void jumpToMatch(int index) {
        if (index < 0 || index >= matchPositions.size()) return;
        clearHighlights();
        Highlighter highlighter = textArea.getHighlighter();
        String text = textArea.getText();
        for (int i = 0; i < matchPositions.size(); i++) {
            int start = matchPositions.get(i);
            int end = Math.min(start + matchLength(i), text.length());
            try {
                Object tag = highlighter.addHighlight(start, end, i == index ? currentPainter : painter);
                searchHighlightTags.add(tag);
            } catch (BadLocationException ignored) {}
        }
        textArea.setCaretPosition(matchPositions.get(index));
        textArea.getCaret().setVisible(true);
    }
}
