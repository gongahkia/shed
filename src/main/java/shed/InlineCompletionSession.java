package shed;

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
    static final int MAX_PREVIEW_CHARACTERS = 1_000_000, MAX_WRAPPED_PREVIEW_CHARACTERS = 100_000;
    private final JTextArea area;
    private final InlineCompletionDisplay display;
    private final BooleanSupplier eligible;
    private final BiFunction<String, String, CompletableFuture<String>> provider;
    private final Consumer<String> insertion, errors;
    private final IntSupplier delay;
    private final Timer timer;
    private Document document;
    private long revision, generation, automaticEpoch, retryAfter;
    private CompletableFuture<String> pending;
    private Suggestion suggestion;
    private boolean closed, accepting;
    private java.util.function.BiConsumer<String, Long> timings = (stage, nanos) -> { };
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
        display = new InlineCompletionDisplay(area);
        timer = new Timer(500, event -> { if (System.nanoTime() >= retryAfter) request(); }); timer.setRepeats(false);
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
        return !closed && area.isEditable() && area.isEnabled() && area.getSelectionStart() == area.getSelectionEnd()
            && sizeSupported(area) && eligible.getAsBoolean();
    }
    static boolean sizeSupported(JTextArea area) {
        return area.getDocument().getLength() <= (area.getLineWrap() ? MAX_WRAPPED_PREVIEW_CHARACTERS : MAX_PREVIEW_CHARACTERS);
    }
    void onTiming(java.util.function.BiConsumer<String, Long> listener) { timings = java.util.Objects.requireNonNull(listener); }

    void request() {
        dismiss(); if (!canRequest()) return;
        int offset = area.getCaretPosition();
        try {
            int start = Math.max(0, offset - OpenCodeCompletionClient.MAX_PREFIX);
            int end = Math.min(document.getLength(), offset + OpenCodeCompletionClient.MAX_SUFFIX);
            String context = document.getText(start, end - start);
            int relative = offset - start;
            if (!GraphemeBoundary.isBoundary(context, relative)) return;
            // Bounded slices must not send an isolated half of a surrogate pair.
            if (!context.isEmpty() && Character.isLowSurrogate(context.charAt(0))) { context = context.substring(1); relative--; }
            if (!context.isEmpty() && Character.isHighSurrogate(context.charAt(context.length() - 1))) context = context.substring(0, context.length() - 1);
            if (relative < 0 || relative > context.length()) return;
            long request = generation, capturedRevision = revision; Document capturedDocument = document;
            long started = System.nanoTime();
            pending = provider.apply(context.substring(0, relative), context.substring(relative));
            pending.whenComplete((text, error) -> SwingUtilities.invokeLater(() -> {
                if (closed || request != generation || capturedDocument != document || capturedRevision != revision
                    || offset != area.getCaretPosition() || !canRequest()) return;
                pending = null;
                if (error != null) {
                    if (!(error instanceof java.util.concurrent.CancellationException)) {
                        retryAfter = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                        errors.accept("Inline suggestion unavailable. Check OpenCode authentication, credits and model settings. AI: Complete retries immediately.");
                    }
                    return;
                }
                retryAfter = 0;
                if (text == null || text.isEmpty() || text.length() > OpenCodeCompletionClient.MAX_COMPLETION) return;
                try {
                    long layoutStarted = System.nanoTime();
                    display.show(offset, text); suggestion = new Suggestion(document, revision, offset, text); area.repaint();
                    timings.accept("layout", System.nanoTime() - layoutStarted);
                    timings.accept("ready", System.nanoTime() - started);
                } catch (BadLocationException unavailable) { display.clear(); }
            }));
        } catch (BadLocationException | RuntimeException unavailable) { errors.accept("Inline suggestion unavailable. Check OpenCode authentication, credits and model settings."); }
    }

    boolean visible() {
        boolean valid = suggestion != null && suggestion.document() == document && suggestion.revision() == revision
            && suggestion.caret() == area.getCaretPosition() && canRequest();
        if (!valid) display.clear();
        return valid;
    }
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
        display.clear();
        var previous = pending; pending = null; if (previous != null) previous.cancel(true);
        area.repaint();
    }

    @Override public void close() {
        if (closed) return; closed = true; invalidate(); document.removeDocumentListener(changes);
        area.removeCaretListener(caret); area.removeFocusListener(focus); area.removePropertyChangeListener(binding);
    }
}
