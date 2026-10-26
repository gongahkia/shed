package shed;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.beans.PropertyChangeListener;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

/** EDT-owned preview. Model output never enters the document until explicit acceptance. */
final class InlineCompletionSession implements AutoCloseable {
    private final JTextArea area;
    private final BooleanSupplier eligible;
    private final BiFunction<String, String, CompletableFuture<String>> provider;
    private final Consumer<String> insertion, errors;
    private final IntSupplier delay;
    private final Timer timer;
    private Document document;
    private long revision, generation, automaticEpoch;
    private CompletableFuture<String> pending;
    private Suggestion suggestion;
    private boolean closed, accepting;
    private record Suggestion(Document document, long revision, int caret, String text) { }

    private final DocumentListener changes = new DocumentListener() {
        public void insertUpdate(DocumentEvent event) { changed(event, true); }
        public void removeUpdate(DocumentEvent event) { changed(event, false); }
        public void changedUpdate(DocumentEvent event) { changed(event, false); }
    };
    private final CaretListener caret = event -> invalidate();
    private final FocusAdapter focus = new FocusAdapter() {
        @Override public void focusLost(FocusEvent event) { dismiss(); }
    };
    private final PropertyChangeListener binding = event -> {
        if ("document".equals(event.getPropertyName())) bind();
        else if ("editable".equals(event.getPropertyName()) || "enabled".equals(event.getPropertyName())) invalidate();
    };

    InlineCompletionSession(JTextArea area, BooleanSupplier eligible, IntSupplier delay,
                            BiFunction<String, String, CompletableFuture<String>> provider,
                            Consumer<String> insertion, Consumer<String> errors) {
        this.area = area; this.eligible = eligible; this.delay = delay; this.provider = provider;
        this.insertion = insertion; this.errors = errors;
        timer = new Timer(500, event -> request()); timer.setRepeats(false);
        bind(); area.addCaretListener(caret); area.addFocusListener(focus); area.addPropertyChangeListener(binding);
    }

    private void bind() {
        dismiss(); if (document != null) document.removeDocumentListener(changes);
        document = area.getDocument(); revision++; document.addDocumentListener(changes);
    }

    private void changed(DocumentEvent event, boolean inserted) {
        revision++; invalidate();
        if (!inserted || accepting || closed) return;
        long changedRevision = revision, epoch = automaticEpoch; Document changedDocument = document;
        int expectedCaret = event.getOffset() + event.getLength();
        // Caret movement from this insertion settles before the idle timer starts.
        SwingUtilities.invokeLater(() -> {
            if (closed || revision != changedRevision || document != changedDocument || epoch != automaticEpoch
                || area.getCaretPosition() != expectedCaret || !canRequest()) return;
            timer.setInitialDelay(Math.max(100, Math.min(5000, delay.getAsInt()))); timer.restart();
        });
    }

    private boolean canRequest() {
        return !closed && area.isEditable() && area.isEnabled() && area.getSelectionStart() == area.getSelectionEnd() && eligible.getAsBoolean();
    }

    void request() {
        dismiss(); if (!canRequest()) return;
        int offset = area.getCaretPosition();
        try {
            int start = Math.max(0, offset - OllamaCompletionClient.MAX_PREFIX);
            int end = Math.min(document.getLength(), offset + OllamaCompletionClient.MAX_SUFFIX);
            String context = document.getText(start, end - start);
            int relative = offset - start;
            if (!GraphemeBoundary.isBoundary(context, relative)) return;
            // Bounded slices must not send an isolated half of a surrogate pair.
            if (!context.isEmpty() && Character.isLowSurrogate(context.charAt(0))) { context = context.substring(1); relative--; }
            if (!context.isEmpty() && Character.isHighSurrogate(context.charAt(context.length() - 1))) context = context.substring(0, context.length() - 1);
            if (relative < 0 || relative > context.length()) return;
            long request = generation, capturedRevision = revision; Document capturedDocument = document;
            pending = provider.apply(context.substring(0, relative), context.substring(relative));
            pending.whenComplete((text, error) -> SwingUtilities.invokeLater(() -> {
                if (closed || request != generation || capturedDocument != document || capturedRevision != revision
                    || offset != area.getCaretPosition() || !canRequest()) return;
                pending = null;
                if (error != null) { if (!(error instanceof java.util.concurrent.CancellationException)) errors.accept("Inline suggestion unavailable. Check the local Ollama model and settings."); return; }
                if (text == null || text.isEmpty() || text.length() > OllamaCompletionClient.MAX_COMPLETION) return;
                suggestion = new Suggestion(document, revision, offset, text); area.repaint();
            }));
        } catch (BadLocationException | RuntimeException unavailable) { errors.accept("Inline suggestion unavailable. Check the local Ollama model and settings."); }
    }

    boolean visible() { return suggestion != null && suggestion.document() == document && suggestion.revision() == revision
        && suggestion.caret() == area.getCaretPosition() && canRequest(); }
    String preview() { return visible() ? suggestion.text() : ""; }

    boolean accept() {
        if (!visible()) { invalidate(); return false; }
        String text = suggestion.text(); invalidate(); accepting = true;
        try { insertion.accept(text); } finally { accepting = false; }
        return true;
    }

    boolean dismiss() { boolean hadRequest = suggestion != null || pending != null || timer.isRunning(); automaticEpoch++; invalidate(); return hadRequest; }

    boolean keyPressed(java.awt.event.KeyEvent event, boolean otherCompletionOwnsKeys) {
        if (event.getComponent() != area || event.isConsumed()) return false;
        if (otherCompletionOwnsKeys) { dismiss(); return false; }
        boolean plain = !event.isAltDown() && !event.isControlDown() && !event.isMetaDown() && !event.isShiftDown();
        if ((plain && event.getKeyCode() == java.awt.event.KeyEvent.VK_TAB && accept())
            || (plain && event.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE && dismiss())) {
            event.consume(); return true;
        }
        dismiss(); return false;
    }

    private void invalidate() {
        generation++; timer.stop(); suggestion = null;
        var previous = pending; pending = null; if (previous != null) previous.cancel(true);
        area.repaint();
    }

    /** Draw an insertion preview without changing text, selection, undo history or document geometry. */
    void paint(Graphics graphics) {
        if (!visible()) return;
        try {
            var anchor = area.modelToView2D(suggestion.caret()); if (anchor == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                var viewport = area.getVisibleRect(); g.clip(viewport); g.setFont(area.getFont());
                var metrics = g.getFontMetrics(); int lineHeight = metrics.getHeight();
                String[] lines = suggestion.text().split("\n", -1);
                int x = (int) anchor.getX(), y = (int) anchor.getY();
                int left = area.getInsets().left;
                Color foreground = area.getForeground(), background = area.getBackground();
                Color ghost = new Color((foreground.getRed() + background.getRed()) / 2,
                    (foreground.getGreen() + background.getGreen()) / 2, (foreground.getBlue() + background.getBlue()) / 2);
                for (int i = 0; i < lines.length && y < viewport.y + viewport.height; i++, y += lineHeight) {
                    int at = i == 0 ? x : left;
                    String line = lines[i].replace("\t", " ".repeat(Math.max(1, area.getTabSize())));
                    g.setColor(background); g.fillRect(at, y, Math.max(0, viewport.x + viewport.width - at), lineHeight);
                    g.setColor(ghost); g.drawString(line, at, y + metrics.getAscent());
                    if (i == lines.length - 1) {
                        int lineEnd = Math.min(document.getLength(), area.getLineEndOffset(area.getLineOfOffset(suggestion.caret())));
                        String suffix = document.getText(suggestion.caret(), lineEnd - suggestion.caret()).replace("\n", "");
                        g.setColor(foreground); g.drawString(suffix, at + metrics.stringWidth(line), y + metrics.getAscent());
                    }
                }
                g.setColor(area.getCaretColor()); g.drawLine(x, (int) anchor.getY(), x, (int) anchor.getY() + lineHeight - 1);
            } finally { g.dispose(); }
        } catch (BadLocationException ignored) { }
    }

    @Override public void close() {
        if (closed) return; closed = true; invalidate(); document.removeDocumentListener(changes);
        area.removeCaretListener(caret); area.removeFocusListener(focus); area.removePropertyChangeListener(binding);
    }
}
