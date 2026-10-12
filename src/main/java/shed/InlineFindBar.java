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
    private record Scan(FileBuffer buffer, VersionedTextSnapshot text, String query, int[] positions, boolean truncated) { }

    private final Texteditor editor;
    private final JLabel title = new JLabel();
    private final JTextField query = new JTextField();
    private final JTextField replacement = new JTextField();
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
        lower.add(feedback, BorderLayout.SOUTH);
        add(lower, BorderLayout.CENTER);

        AccessibilitySupport.describe(query, "Find text", "Search the active editor without closing the editor.");
        AccessibilitySupport.describe(replacement, "Replacement text", "Preview and replace literal matches in the active editor.");
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
        if (scan.positions().length == 0) { feedback.setText("No matches"); return; }
        selected = selected < 0 ? 0 : Math.floorMod(selected + (forward ? 1 : -1), scan.positions().length);
        int position = scan.positions()[selected];
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
        for (JTextField field : List.of(query, replacement)) {
            field.setBackground(editor.configManager.getNormalColor());
            field.setForeground(editor.configManager.getEditorForeground());
            field.setCaretColor(editor.configManager.getCaretColor());
            field.setSelectionColor(editor.configManager.getSelectionColor());
            field.setSelectedTextColor(editor.configManager.getSelectionTextColor());
        }
        repaint();
    }

    private void showMode(Mode nextMode, String initial) {
        mode = nextMode;
        title.setText(nextMode == Mode.GO_TO_LINE ? "Go to line" : "Find");
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
        String pattern = query.getText();
        long generation = scanGeneration;
        scanJobId = editor.asyncJobService.submit("inline find", token -> {
            String source = text.text();
            int[] positions = new int[Math.min(256, MAX_MATCHES)];
            int size = 0;
            boolean truncated = false;
            for (int from = 0; from <= source.length() - pattern.length();) {
                if (token.isCancelled()) return null;
                int match = source.indexOf(pattern, from);
                if (match < 0) break;
                if (size == MAX_MATCHES) { truncated = true; break; }
                if (size == positions.length) positions = java.util.Arrays.copyOf(positions, Math.min(MAX_MATCHES, positions.length * 2));
                positions[size++] = match;
                from = match + Math.max(1, pattern.length());
            }
            return new Scan(buffer, text, pattern, java.util.Arrays.copyOf(positions, size), truncated);
        }, (job, result, error) -> {
            if (generation != scanGeneration || !isVisible() || mode == Mode.GO_TO_LINE
                || result == null || error != null || result.buffer() != editor.getCurrentBuffer()
                || result.text() != result.buffer().textSnapshot()) return;
            scanJobId = -1;
            scan = result;
            int caret = editor.writingArea.getCaretPosition();
            int index = java.util.Arrays.binarySearch(result.positions(), caret);
            selected = index >= 0 ? index : Math.min(result.positions().length - 1, -index - 1);
            if (selected < 0) selected = -1;
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
        int last = Math.min(scan.positions().length, selected + 76);
        for (int index = first; index < last; index++) {
            int start = scan.positions()[index];
            try {
                highlightTags.add(highlighter.addHighlight(start, start + scan.query().length(),
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
        int count = scan.positions().length;
        String summary = scan.truncated() ? "200,000+ matches; refine search" : count == 1 ? "1 match" : count + " matches";
        if (selected >= 0) summary += " · " + (selected + 1) + " selected";
        if (mode == Mode.REPLACE && count > 0) {
            String before = scan.query();
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

    private void replaceOne() {
        if (scan == null || selected < 0 || !editor.writingArea.isEditable()
            || scan.buffer() != editor.getCurrentBuffer() || scan.text() != scan.buffer().textSnapshot()) return;
        int start = scan.positions()[selected];
        editor.writingArea.replaceRange(replacement.getText(), start, start + scan.query().length());
        editor.writingArea.setCaretPosition(start + replacement.getText().length());
        queryChanged();
    }

    private void replaceAll() {
        if (scan == null || scan.truncated() || !editor.writingArea.isEditable()
            || scan.buffer() != editor.getCurrentBuffer() || scan.text() != scan.buffer().textSnapshot()) return;
        FileBuffer buffer = scan.buffer();
        VersionedTextSnapshot text = scan.text();
        String pattern = scan.query();
        String value = replacement.getText();
        replaceAll.setEnabled(false);
        feedback.setText("Preparing replacement…");
        editor.asyncJobService.submit("replace all", token -> editor.substituteService.replaceLiteral(text.text(), pattern, value, true),
            (job, result, error) -> {
                if (error != null || result == null) { feedback.setText("Replacement failed"); return; }
                if (buffer != editor.getCurrentBuffer() || buffer.textSnapshot() != text
                    || !pattern.equals(query.getText()) || !value.equals(replacement.getText())) {
                    feedback.setText("Document changed; preview again before replacing");
                    queryChanged();
                    return;
                }
                try {
                    BoundedUndoManager history = (BoundedUndoManager) buffer.getUndoManager();
                    history.beginCompoundEdit();
                    try {
                        buffer.getDocument().replace(0, buffer.getDocument().getLength(), result.getUpdatedText(), null);
                    } finally {
                        history.endCompoundEdit();
                    }
                    editor.writingArea.setCaretPosition(Math.max(0, result.getFirstMatchOffset()));
                    queryChanged();
                    editor.showMessage("Replaced " + result.getMatchCount() + " matches");
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
