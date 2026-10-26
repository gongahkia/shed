package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.PlainDocument;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InlineCompletionSessionTest {
    private JTextArea area;
    private InlineCompletionSession session;
    private final List<CompletableFuture<String>> requests = new ArrayList<>();
    private final List<String> contexts = new ArrayList<>(), errors = new ArrayList<>();
    private final AtomicBoolean eligible = new AtomicBoolean(true);
    private final java.util.concurrent.atomic.AtomicInteger delay = new java.util.concurrent.atomic.AtomicInteger(5000);

    private void open(String text, int caret) throws Exception {
        edt(() -> {
            area = new JTextArea(text); area.setCaretPosition(caret);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14)); area.setForeground(Color.BLACK); area.setBackground(Color.WHITE);
            area.setSize(600, 300);
            session = new InlineCompletionSession(area, eligible::get, delay::get, (prefix, suffix) -> {
                contexts.add(prefix + "|" + suffix); var future = new CompletableFuture<String>(); requests.add(future); return future;
            }, area::replaceSelection, errors::add);
        });
    }
    @AfterEach void close() throws Exception { if (session != null) edt(session::close); }

    @Test void previewDoesNotEditTheDocumentAndAcceptanceIsOneUndoableInsertion() throws Exception {
        open("return ;", 7); UndoManager undo = new UndoManager();
        edt(() -> { area.getDocument().addUndoableEditListener(undo); session.request(); });
        assertEquals(List.of("return |;"), contexts);
        var before = new BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB);
        edt(() -> { var graphics = before.createGraphics(); area.paint(graphics); graphics.dispose(); });
        requests.getFirst().complete("answer\n    + 1"); flush();
        edt(() -> {
            assertEquals("answer\n    + 1", session.preview()); assertEquals("return ;", area.getText()); assertFalse(undo.canUndo());
            var after = new BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB);
            var graphics = after.createGraphics(); area.paint(graphics); graphics.dispose();
            assertFalse(java.util.Arrays.equals(before.getRGB(0, 0, 600, 300, null, 0, 600), after.getRGB(0, 0, 600, 300, null, 0, 600)));
            assertEquals("return ;", area.getText()); assertEquals(7, area.getCaretPosition());
            assertTrue(session.accept()); assertEquals("return answer\n    + 1;", area.getText());
            assertFalse(session.visible()); undo.undo(); assertEquals("return ;", area.getText()); assertFalse(undo.canUndo());
        });
    }

    @Test void caretMovementEditingAndBufferReplacementCancelObsoleteRequests() throws Exception {
        open("abc", 3);
        edt(() -> { session.request(); area.setCaretPosition(2); assertTrue(requests.get(0).isCancelled()); session.request(); area.insert("x", 0); assertTrue(requests.get(1).isCancelled()); });
        edt(() -> { session.request(); area.setDocument(new PlainDocument()); assertTrue(requests.get(2).isCancelled()); });
        requests.forEach(request -> request.complete("stale")); flush();
        edt(() -> { assertFalse(session.visible()); assertFalse(session.accept()); assertEquals("", area.getText()); });
    }

    @Test void lateUncancellableResponseCannotReviveDismissedPreview() throws Exception {
        open("code", 4); edt(session::close);
        var late = new CompletableFuture<String>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        edt(() -> {
            session = new InlineCompletionSession(area, eligible::get, () -> 5000, (prefix, suffix) -> late, area::replaceSelection, errors::add);
            session.request(); assertTrue(session.dismiss());
        });
        late.complete(" should never appear"); flush();
        edt(() -> { assertEquals("", session.preview()); assertEquals("code", area.getText()); });
    }

    @Test void selectionEligibilityAndFocusLossPreserveOrdinaryEditing() throws Exception {
        open("abc", 3);
        edt(() -> { area.select(0, 2); session.request(); assertTrue(requests.isEmpty()); area.setCaretPosition(3); eligible.set(false); session.request(); assertTrue(requests.isEmpty()); eligible.set(true); session.request(); });
        requests.getFirst().complete("def"); flush();
        edt(() -> { assertTrue(session.visible()); eligible.set(false); assertFalse(session.accept()); assertEquals("abc", area.getText()); eligible.set(true); session.request();
            for (var listener : area.getFocusListeners()) listener.focusLost(new java.awt.event.FocusEvent(area, java.awt.event.FocusEvent.FOCUS_LOST));
            assertTrue(requests.get(1).isCancelled()); assertFalse(session.dismiss()); });
    }

    @Test void boundedContextUsesTheUnsavedDocumentAndDoesNotSplitSurrogates() throws Exception {
        String prefix = "x".repeat(9000) + "🙂", suffix = "suffix".repeat(1000);
        open(prefix + suffix, prefix.length()); edt(session::request);
        String[] sent = contexts.getFirst().split("\\|", -1);
        assertEquals(8192, sent[0].length()); assertTrue(sent[0].endsWith("🙂")); assertEquals(4096, sent[1].length());
        edt(() -> { area.setCaretPosition(prefix.length() - 1); session.request(); assertEquals(1, requests.size()); });
    }

    @Test void emptyFailedAndClosedRequestsLeaveNoGhostText() throws Exception {
        open("code", 4); edt(session::request); requests.getFirst().complete(""); flush();
        edt(() -> { assertFalse(session.visible()); session.request(); }); requests.get(1).completeExceptionally(new java.io.IOException("private response")); flush();
        edt(() -> { assertFalse(session.visible()); assertEquals(1, errors.size()); assertFalse(errors.getFirst().contains("private")); session.request(); session.close(); assertTrue(requests.get(2).isCancelled()); });
    }

    @Test void tabAndEscapeConsumeOnlyAiActionsAndYieldToExistingCompletions() throws Exception {
        open("code", 4);
        edt(() -> {
            var tab = key(java.awt.event.KeyEvent.VK_TAB, 0); assertFalse(session.keyPressed(tab, false)); assertFalse(tab.isConsumed());
            session.request();
        });
        requests.getFirst().complete(" suggestion"); flush();
        edt(() -> {
            var tab = key(java.awt.event.KeyEvent.VK_TAB, 0); assertFalse(session.keyPressed(tab, true)); assertFalse(tab.isConsumed()); assertEquals("code", area.getText());
            session.request();
        });
        requests.get(1).complete(" suggestion"); flush();
        edt(() -> {
            var shifted = key(java.awt.event.KeyEvent.VK_TAB, java.awt.event.InputEvent.SHIFT_DOWN_MASK);
            assertFalse(session.keyPressed(shifted, false)); assertFalse(shifted.isConsumed()); assertEquals("code", area.getText()); session.request();
        });
        requests.get(2).complete(" suggestion"); flush();
        edt(() -> {
            var escape = key(java.awt.event.KeyEvent.VK_ESCAPE, 0); assertTrue(session.keyPressed(escape, false)); assertTrue(escape.isConsumed());
            assertEquals("code", area.getText()); assertFalse(session.keyPressed(key(java.awt.event.KeyEvent.VK_ESCAPE, 0), false)); session.request();
        });
        requests.get(3).complete(" suggestion"); flush();
        edt(() -> {
            var tab = key(java.awt.event.KeyEvent.VK_TAB, 0); assertTrue(session.keyPressed(tab, false)); assertTrue(tab.isConsumed()); assertEquals("code suggestion", area.getText());
        });
    }

    @Test void typingDebouncesAndAcceptingDoesNotGenerateAnotherRequest() throws Exception {
        open("code", 4); delay.set(100);
        edt(() -> { area.append("1"); area.append("2"); area.setCaretPosition(6); });
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        do { Thread.sleep(20); edt(() -> count.set(requests.size())); } while (count.get() == 0 && System.nanoTime() < deadline);
        assertEquals(1, count.get()); assertEquals("code12|", contexts.getFirst());
        requests.getFirst().complete("3"); flush(); edt(() -> assertTrue(session.accept()));
        Thread.sleep(250); edt(() -> { assertEquals(1, requests.size()); assertEquals("code123", area.getText()); });
    }

    @Test void dismissalBeforeTheDeferredTypingCallbackDoesNotRestartCompletion() throws Exception {
        open("code", 4); delay.set(100);
        edt(() -> { area.append("1"); area.setCaretPosition(5); session.dismiss(); });
        Thread.sleep(250); edt(() -> assertTrue(requests.isEmpty()));
        edt(() -> { area.append("2"); area.setCaretPosition(0); });
        Thread.sleep(250); edt(() -> assertTrue(requests.isEmpty()));
    }

    @Test void expensivePreviewSizesDoNotRequestOrDisplaySuggestions() throws Exception {
        open("x".repeat(100001), 1);
        edt(() -> {
            area.setLineWrap(true); session.request(); assertTrue(requests.isEmpty());
            area.setLineWrap(false); session.request(); assertEquals(1, requests.size());
            area.setLineWrap(true);
        });
        requests.getFirst().complete("ghost"); flush();
        edt(() -> {
            assertFalse(session.visible());
            area.setLineWrap(false); area.setText("x".repeat(1000001)); session.request();
            assertEquals(1, requests.size());
        });
    }

    @Test void automaticFailuresBackOffButManualRetryRemainsAvailable() throws Exception {
        open("code", 4); delay.set(100);
        edt(session::request); requests.getFirst().completeExceptionally(new java.io.IOException("provider unavailable")); flush();
        edt(() -> { area.append("1"); area.setCaretPosition(5); }); Thread.sleep(250);
        edt(() -> { assertEquals(1, requests.size()); session.request(); assertEquals(2, requests.size()); });
    }

    private java.awt.event.KeyEvent key(int code, int modifiers) {
        return new java.awt.event.KeyEvent(area, java.awt.event.KeyEvent.KEY_PRESSED, 0, modifiers, code, java.awt.event.KeyEvent.CHAR_UNDEFINED);
    }

    private static void flush() throws Exception { edt(() -> { }); }
    private static void edt(Runnable action) throws Exception {
        try { SwingUtilities.invokeAndWait(action); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof AssertionError assertion) throw assertion;
            throw error;
        }
    }
}
