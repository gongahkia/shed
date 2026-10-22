package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;

/** Nonmodal find, replace, and line navigation for the desktop editor. */
final class InlineFindBar extends JPanel {
    private static final int MAX_MATCHES = 200_000;
    private enum Mode { FIND, REPLACE, GO_TO_LINE }
    private record Scan(FileBuffer buffer, VersionedTextSnapshot text, SearchPattern pattern, List<SearchPattern.Range> ranges, boolean truncated) { }

    private final Texteditor editor;
    private final JLabel title = new JLabel();
    private final JTextField query = new JTextField();
    private final JTextField replacement = new JTextField();
    private final javax.swing.JCheckBox matchCase = new javax.swing.JCheckBox("Match case", true);
    private final javax.swing.JCheckBox regex = new javax.swing.JCheckBox("Regex");
    private final javax.swing.JCheckBox wholeWord = new javax.swing.JCheckBox("Whole word");
    private final JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    private final JLabel feedback = new JLabel(" ");
    private final JPanel replaceRow = new JPanel(new BorderLayout(8, 0));
    private final JButton previous = button("Previous", this::previous);
    private final JButton next = button("Next", this::next);
    private final JButton go = button("Go", this::goToLine);
    private final JButton replaceOne = button("Replace", this::replaceOne);
    private final JButton replaceAll = button("Replace All", this::replaceAll);
    private final Timer scanTimer;
    private final List<Object> highlightTags = new ArrayList<>();
    private Highlighter highlightedIn;
    private Mode mode = Mode.FIND;
    private Scan scan;
    private int selected = -1;
    private int scanJobId = -1;
    private long scanGeneration;

    InlineFindBar(Texteditor editor) {
        super(new BorderLayout(0, 3));
        this.editor = editor;
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, editor.configManager.getStatusBarBackground()),
            BorderFactory.createEmptyBorder(5, 8, 4, 8)));
        JPanel queryRow = new JPanel(new BorderLayout(8, 0));
        queryRow.setOpaque(false);
        title.setPreferredSize(new Dimension(UiZoom.scale(95, editor.configManager.getUiZoom()), 1));
        queryRow.add(title, BorderLayout.WEST);
        queryRow.add(query, BorderLayout.CENTER);
        JPanel queryActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        queryActions.setOpaque(false);
        queryActions.add(previous);
        queryActions.add(next);
        queryActions.add(go);
        queryActions.add(button("Close", this::close));
        queryRow.add(queryActions, BorderLayout.EAST);
        add(queryRow, BorderLayout.NORTH);

        replaceRow.setOpaque(false);
        JLabel replaceLabel = new JLabel("Replace with");
        replaceLabel.setPreferredSize(title.getPreferredSize());
        replaceLabel.setLabelFor(replacement);
        replaceRow.add(replaceLabel, BorderLayout.WEST);
        replaceRow.add(replacement, BorderLayout.CENTER);
        JPanel replaceActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        replaceActions.setOpaque(false);
        replaceActions.add(replaceOne);
        replaceActions.add(replaceAll);
        replaceRow.add(replaceActions, BorderLayout.EAST);
        JPanel lower = new JPanel(new BorderLayout());
        lower.setOpaque(false);
        lower.add(replaceRow, BorderLayout.NORTH);
        for (javax.swing.JCheckBox option : List.of(matchCase, regex, wholeWord)) {
            optionsRow.add(option);
            option.addActionListener(event -> queryChanged());
            option.setOpaque(false);
        }
        optionsRow.setOpaque(false);
        optionsRow.add(button("In Files", () -> {
            try { editor.paletteController.showWorkspaceSearch(searchPattern()); }
            catch (IllegalArgumentException error) { feedback.setText(error.getMessage()); }
        }));
        JPanel optionsAndFeedback = new JPanel(new BorderLayout());
        optionsAndFeedback.setOpaque(false);
        optionsAndFeedback.add(optionsRow, BorderLayout.NORTH);
        optionsAndFeedback.add(feedback, BorderLayout.SOUTH);
        lower.add(optionsAndFeedback, BorderLayout.SOUTH);
        add(lower, BorderLayout.CENTER);

        AccessibilitySupport.describe(query, "Find text", "Search the active editor without closing the editor.");
        AccessibilitySupport.describe(replacement, "Replacement text", "Replace matches in the active editor. Regex replacements support $1 and ${name} groups.");
        scanTimer = new Timer(90, event -> submitScan());
        scanTimer.setRepeats(false);
        query.getDocument().addDocumentListener(new SimpleDocumentListener(this::queryChanged));
        replacement.getDocument().addDocumentListener(new SimpleDocumentListener(this::updateFeedback));
        query.addActionListener(event -> { if (mode == Mode.GO_TO_LINE) goToLine(); else next(); });
        replacement.addActionListener(event -> replaceOne());
        installKey(query);
        installKey(replacement);
        setVisible(false);
        refreshAppearance();
    }

    SearchPattern searchPattern() { return new SearchPattern(query.getText(), matchCase.isSelected(), regex.isSelected(), wholeWord.isSelected()); }
    void showFind(SearchPattern pattern) {
        matchCase.setSelected(pattern.caseSensitive()); regex.setSelected(pattern.regex()); wholeWord.setSelected(pattern.wholeWord());
        showMode(Mode.FIND, pattern.query());
    }
    void showFind(String initial) { showMode(Mode.FIND, initial); }
    void showReplace(String initial) { showMode(Mode.REPLACE, initial); }
    void showGoToLine() { showMode(Mode.GO_TO_LINE, ""); }

    boolean hasSearchQuery() { return mode != Mode.GO_TO_LINE && !query.getText().isBlank(); }
    String queryText() { return hasSearchQuery() ? query.getText() : ""; }

    void navigate(boolean forward) {
        if (scan == null || scan.buffer() != editor.getCurrentBuffer()
            || scan.text() != editor.getCurrentBuffer().textSnapshot()) {
            showFind(query.getText());
            return;
        }
        if (scan.ranges().size() == 0) { feedback.setText("No matches"); return; }
        selected = selected < 0 ? 0 : Math.floorMod(selected + (forward ? 1 : -1), scan.ranges().size());
        int position = scan.ranges().get(selected).start();
        editor.recordJumpPosition();
        editor.writingArea.setCaretPosition(position);
        editor.writingArea.requestFocusInWindow();
        highlight();
        updateFeedback();
    }

    void onDocumentChanged() {
        if (isVisible() && mode != Mode.GO_TO_LINE) queryChanged();
        else scan = null;
    }

    void onBufferChanged() {
        clearHighlights();
        scan = null;
        selected = -1;
        if (isVisible() && mode != Mode.GO_TO_LINE) queryChanged();
    }

    void refreshAppearance() {
        setBackground(editor.configManager.getCommandBarBackground());
        title.setForeground(editor.configManager.getCommandBarForeground());
        feedback.setForeground(editor.configManager.getCommandBarForeground());
        for (javax.swing.JCheckBox option : List.of(matchCase, regex, wholeWord)) option.setForeground(editor.configManager.getCommandBarForeground());
        for (JTextField field : List.of(query, replacement)) {
            field.setBackground(editor.configManager.getNormalColor());
            field.setForeground(editor.configManager.getEditorForeground());
            field.setCaretColor(editor.configManager.getCaretColor());
            field.setSelectionColor(editor.configManager.getSelectionColor());
            field.setSelectedTextColor(editor.configManager.getSelectionTextColor());
        }
        styleButtons(this);
        if (!editor.stagedStartupPending) SearchControlMetrics.apply(this, editor);
        repaint();
    }

    private void styleButtons(java.awt.Container parent) {
        for (java.awt.Component child : parent.getComponents()) {
            if (child instanceof JButton button) {
                button.setOpaque(true);
                button.setContentAreaFilled(true);
                button.setBackground(editor.configManager.getStatusBarBackground());
                button.setForeground(editor.configManager.getCommandBarForeground());
                button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(editor.configManager.getSelectionColor()),
                    BorderFactory.createEmptyBorder(4, 9, 4, 9)));
            }
            if (child instanceof java.awt.Container nested) styleButtons(nested);
        }
    }

    private void showMode(Mode nextMode, String initial) {
        mode = nextMode;
        title.setText(nextMode == Mode.GO_TO_LINE ? "Go to line" : "Find");
        optionsRow.setVisible(nextMode != Mode.GO_TO_LINE);
        replaceRow.setVisible(nextMode == Mode.REPLACE);
        previous.setVisible(nextMode != Mode.GO_TO_LINE);
        next.setVisible(nextMode != Mode.GO_TO_LINE);
        go.setVisible(nextMode == Mode.GO_TO_LINE);
        setVisible(true);
        String value = initial == null ? "" : initial;
        if (!value.equals(query.getText())) query.setText(value);
        query.selectAll();
        SwingUtilities.invokeLater(query::requestFocusInWindow);
        if (nextMode == Mode.GO_TO_LINE) feedback.setText("Line 1–" + editor.writingArea.getLineCount());
        else queryChanged();
        revalidate();
    }

    private void queryChanged() {
        scanGeneration++;
        if (scanJobId >= 0) editor.asyncJobService.cancel(scanJobId);
        scanJobId = -1;
        scan = null;
        selected = -1;
        clearHighlights();
        replaceOne.setEnabled(false);
        replaceAll.setEnabled(false);
        if (mode == Mode.GO_TO_LINE) {
            feedback.setText("Line 1–" + editor.writingArea.getLineCount());
            return;
        }
        if (query.getText().isEmpty()) {
            feedback.setText("Type to find text");
            replaceAll.setEnabled(false);
            return;
        }
        feedback.setText("Searching…");
        replaceAll.setEnabled(false);
        scanTimer.restart();
    }

    private void submitScan() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || mode == Mode.GO_TO_LINE) return;
        VersionedTextSnapshot text = buffer.textSnapshot();
        SearchPattern pattern;
        try { pattern = searchPattern(); pattern.compile(); }
        catch (IllegalArgumentException error) { feedback.setText("Invalid search: " + error.getMessage()); return; }
        long generation = scanGeneration;
        scanJobId = editor.asyncJobService.submit("inline find", token -> {
            SearchPattern.Scan result = pattern.scan(text.text(), MAX_MATCHES, token::isCancelled);
            return new Scan(buffer, text, pattern, result.ranges(), result.truncated());
        }, (job, result, error) -> {
            if (generation != scanGeneration || !isVisible() || mode == Mode.GO_TO_LINE) return;
            scanJobId = -1;
            if (error != null) { feedback.setText("Search failed: " + error.getMessage()); return; }
            if (result == null || result.buffer() != editor.getCurrentBuffer() || result.text() != result.buffer().textSnapshot()) return;
            scan = result;
            int caret = editor.writingArea.getCaretPosition();
            selected = result.ranges().isEmpty() ? -1 : result.ranges().size() - 1;
            for (int index = 0; index < result.ranges().size(); index++) {
                if (result.ranges().get(index).start() >= caret) { selected = index; break; }
            }
            highlight();
            updateFeedback();
        });
    }

    private void highlight() {
        clearHighlights();
        if (scan == null || selected < 0) return;
        Highlighter highlighter = editor.writingArea.getHighlighter();
        highlightedIn = highlighter;
        int first = Math.max(0, selected - 75);
        int last = Math.min(scan.ranges().size(), selected + 76);
        for (int index = first; index < last; index++) {
            int start = scan.ranges().get(index).start();
            try {
                highlightTags.add(highlighter.addHighlight(start, scan.ranges().get(index).end(),
                    new DefaultHighlighter.DefaultHighlightPainter(index == selected
                        ? editor.configManager.getSelectionColor() : editor.configManager.getCurrentLineHighlightColor())));
            } catch (BadLocationException ignored) { }
        }
    }

    private void clearHighlights() {
        if (highlightedIn != null) {
            for (Object tag : highlightTags) highlightedIn.removeHighlight(tag);
        }
        highlightTags.clear();
        highlightedIn = null;
    }

    private void updateFeedback() {
        if (mode == Mode.GO_TO_LINE) return;
        if (scan == null) return;
        int count = scan.ranges().size();
        String summary = scan.truncated() ? "200,000+ matches; refine search" : count == 1 ? "1 match" : count + " matches";
        if (selected >= 0) summary += " · " + (selected + 1) + " selected";
        if (mode == Mode.REPLACE && count > 0) {
            String before = scan.pattern().query();
            String after = replacement.getText();
            summary += " · Preview: " + compact(before) + " → " + compact(after);
        }
        feedback.setText(summary);
        replaceAll.setText("Replace All (" + (scan.truncated() ? "200,000+" : count) + ")");
        replaceAll.setEnabled(count > 0 && !scan.truncated());
        replaceOne.setEnabled(count > 0);
    }

    private static String compact(String value) {
        String single = value.replace('\n', ' ');
        return single.length() > 24 ? single.substring(0, 21) + "…" : single;
    }

    private void previous() { navigate(false); }
    private void next() { navigate(true); }

    private boolean replacementQueryMatches(SearchPattern expected) {
        if (!isVisible() || mode != Mode.REPLACE) return false;
        try { return expected.equals(searchPattern()); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private void replaceOne() {
        if (scan == null || selected < 0 || !editor.writingArea.isEditable()
            || scan.buffer() != editor.getCurrentBuffer() || scan.text() != scan.buffer().textSnapshot()) return;
        Scan expected = scan;
        SearchPattern.Range range = scan.ranges().get(selected);
        String value = replacement.getText();
        editor.asyncJobService.submit("replace match", token -> expected.pattern().replacementAt(expected.text().text(), range, value),
            (job, result, error) -> {
                if (expected.buffer() != editor.getCurrentBuffer() || expected.text() != expected.buffer().textSnapshot()
                    || !replacementQueryMatches(expected.pattern()) || !value.equals(replacement.getText())) return;
                if (error != null || result == null) { updateFeedback(); feedback.setText("Replacement failed: " + (error == null ? "cancelled" : error.getMessage())); return; }
                editor.writingArea.replaceRange(result, range.start(), range.end());
                queryChanged();
            });
    }

    private void replaceAll() {
        if (scan == null || scan.truncated() || !editor.writingArea.isEditable()
            || scan.buffer() != editor.getCurrentBuffer() || scan.text() != scan.buffer().textSnapshot()) return;
        FileBuffer buffer = scan.buffer();
        VersionedTextSnapshot text = scan.text();
        SearchPattern pattern = scan.pattern();
        String value = replacement.getText();
        replaceAll.setEnabled(false);
        feedback.setText("Preparing replacement…");
        editor.asyncJobService.submit("replace all", token -> pattern.replaceAll(text.text(), value, token::isCancelled),
            (job, result, error) -> {
                if (buffer != editor.getCurrentBuffer() || buffer.textSnapshot() != text
                    || !replacementQueryMatches(pattern) || !value.equals(replacement.getText())) {
                    feedback.setText("Document changed; preview again before replacing");
                    queryChanged();
                    return;
                }
                if (error != null || result == null) {
                    updateFeedback(); feedback.setText("Replacement failed: " + (error == null ? "cancelled" : error.getMessage())); return;
                }
                try {
                    BoundedUndoManager history = (BoundedUndoManager) buffer.getUndoManager();
                    history.beginCompoundEdit();
                    try {
                        buffer.getDocument().replace(0, buffer.getDocument().getLength(), result.text(), null);
                    } finally {
                        history.endCompoundEdit();
                    }
                    editor.writingArea.setCaretPosition(Math.max(0, result.firstOffset()));
                    queryChanged();
                    editor.showMessage("Replaced " + result.count() + " matches");
                } catch (BadLocationException failure) {
                    feedback.setText("Replacement failed: " + failure.getMessage());
                }
            });
    }

    private void goToLine() {
        try {
            int line = Integer.parseInt(query.getText().trim());
            if (line < 1 || line > editor.writingArea.getLineCount()) throw new NumberFormatException();
            editor.showMessage(editor.gotoLine(line));
            close();
        } catch (NumberFormatException error) {
            feedback.setText("Enter a line from 1 to " + editor.writingArea.getLineCount());
        }
    }

    private void close() {
        scanTimer.stop();
        if (scanJobId >= 0) editor.asyncJobService.cancel(scanJobId);
        scanJobId = -1;
        clearHighlights();
        setVisible(false);
        editor.requestActivePaneFocus();
    }

    private void installKey(JTextField field) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "shed.inline-find.close");
        field.getActionMap().put("shed.inline-find.close", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { close(); }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
            "shed.inline-find.previous");
        field.getActionMap().put("shed.inline-find.previous", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { previous(); }
        });
    }

    private static JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }

    private record SimpleDocumentListener(Runnable changed) implements DocumentListener {
        @Override public void insertUpdate(DocumentEvent event) { changed.run(); }
        @Override public void removeUpdate(DocumentEvent event) { changed.run(); }
        @Override public void changedUpdate(DocumentEvent event) { changed.run(); }
    }
}
