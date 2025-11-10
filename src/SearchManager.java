// Search Manager Class
// Handles search operations and match highlighting

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javax.swing.JTextArea;
import javax.swing.text.Highlighter;
import javax.swing.text.DefaultHighlighter;
import java.awt.Color;

public class SearchManager {
    private String searchPattern;
    private List<Integer> matchPositions;
    private int currentMatchIndex;
    private JTextArea textArea;
    private Highlighter.HighlightPainter painter;
    private Highlighter.HighlightPainter currentPainter;

    public SearchManager(JTextArea textArea) {
        this.textArea = textArea;
        this.matchPositions = new ArrayList<>();
        this.currentMatchIndex = -1;
        this.painter = new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW);
        this.currentPainter = new DefaultHighlighter.DefaultHighlightPainter(Color.ORANGE);
    }

    // Search for pattern in text
    public String search(String pattern, boolean caseSensitive) {
        if (pattern == null || pattern.isEmpty()) {
            return "Error: Empty search pattern";
        }

        this.searchPattern = pattern;
        clearHighlights();
        matchPositions.clear();
        currentMatchIndex = -1;

        String text = textArea.getText();
        if (text.isEmpty()) {
            return "Error: Empty buffer";
        }

        try {
            // Create pattern with case sensitivity option
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            Pattern regex = Pattern.compile(Pattern.quote(pattern), flags);
            Matcher matcher = regex.matcher(text);

            // Find all matches
            while (matcher.find()) {
                matchPositions.add(matcher.start());
            }

            if (matchPositions.isEmpty()) {
                return "Pattern not found: " + pattern;
            }

            // Highlight all matches and jump to first
            highlightAllMatches(text);
            currentMatchIndex = 0;
            jumpToMatch(0);

            return "Match 1 of " + matchPositions.size();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // Jump to next match
    public String nextMatch() {
        if (matchPositions.isEmpty()) {
            return "No search pattern";
        }

        currentMatchIndex = (currentMatchIndex + 1) % matchPositions.size();
        jumpToMatch(currentMatchIndex);

        return "Match " + (currentMatchIndex + 1) + " of " + matchPositions.size();
    }

    // Jump to previous match
    public String prevMatch() {
        if (matchPositions.isEmpty()) {
            return "No search pattern";
        }

        currentMatchIndex--;
        if (currentMatchIndex < 0) {
            currentMatchIndex = matchPositions.size() - 1;
        }
        jumpToMatch(currentMatchIndex);

        return "Match " + (currentMatchIndex + 1) + " of " + matchPositions.size();
    }

    // Clear all search highlights
    public void clearHighlights() {
        textArea.getHighlighter().removeAllHighlights();
    }

    // Get match count
    public int getMatchCount() {
        return matchPositions.size();
    }

    // Get current search pattern
    public String getSearchPattern() {
        return searchPattern;
    }

    // Highlight all matches in the text
    private void highlightAllMatches(String text) {
        try {
            Highlighter highlighter = textArea.getHighlighter();

            for (int i = 0; i < matchPositions.size(); i++) {
                int pos = matchPositions.get(i);
                int endPos = pos + searchPattern.length();

                if (endPos <= text.length()) {
                    highlighter.addHighlight(pos, endPos, painter);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Jump to specific match
    private void jumpToMatch(int index) {
        if (index < 0 || index >= matchPositions.size()) {
            return;
        }

        try {
            int pos = matchPositions.get(index);
            textArea.setCaretPosition(pos);

            // Make sure the match is visible
            textArea.getCaret().setVisible(true);

            // Re-highlight with current match emphasized
            clearHighlights();
            String text = textArea.getText();
            Highlighter highlighter = textArea.getHighlighter();

            // Highlight all matches
            for (int i = 0; i < matchPositions.size(); i++) {
                int matchPos = matchPositions.get(i);
                int endPos = matchPos + searchPattern.length();

                if (endPos <= text.length()) {
                    Highlighter.HighlightPainter p = (i == index) ? currentPainter : painter;
                    highlighter.addHighlight(matchPos, endPos, p);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Replace current match
    public String replaceCurrent(String replacement) {
        if (matchPositions.isEmpty() || currentMatchIndex < 0) {
            return "No match to replace";
        }

        try {
            int pos = matchPositions.get(currentMatchIndex);
            String text = textArea.getText();
            int endPos = pos + searchPattern.length();

            String newText = text.substring(0, pos) + replacement + text.substring(endPos);
            textArea.setText(newText);

            // Update match positions after the current one
            int lengthDiff = replacement.length() - searchPattern.length();
            for (int i = currentMatchIndex + 1; i < matchPositions.size(); i++) {
                matchPositions.set(i, matchPositions.get(i) + lengthDiff);
            }

            // Remove current match from list
            matchPositions.remove(currentMatchIndex);

            if (matchPositions.isEmpty()) {
                clearHighlights();
                return "Last match replaced";
            }

            // Adjust current index
            if (currentMatchIndex >= matchPositions.size()) {
                currentMatchIndex = 0;
            }

            // Re-highlight
            highlightAllMatches(textArea.getText());
            jumpToMatch(currentMatchIndex);

            return "Replaced. Match " + (currentMatchIndex + 1) + " of " + matchPositions.size();
        } catch (Exception e) {
            return "Error replacing: " + e.getMessage();
        }
    }

    // Replace all matches
    public String replaceAll(String replacement) {
        if (matchPositions.isEmpty()) {
            return "No matches to replace";
        }

        try {
            int count = matchPositions.size();
            String text = textArea.getText();

            // Replace from end to beginning to maintain positions
            for (int i = matchPositions.size() - 1; i >= 0; i--) {
                int pos = matchPositions.get(i);
                int endPos = pos + searchPattern.length();
                text = text.substring(0, pos) + replacement + text.substring(endPos);
            }

            textArea.setText(text);
            clearHighlights();
            matchPositions.clear();
            currentMatchIndex = -1;

            return "Replaced " + count + " occurrence" + (count > 1 ? "s" : "");
        } catch (Exception e) {
            return "Error replacing all: " + e.getMessage();
        }
    }
}
