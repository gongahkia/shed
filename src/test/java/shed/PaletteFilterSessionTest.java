package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class PaletteFilterSessionTest {
    private static final class Fixture implements AutoCloseable {
        final JTextField field = new JTextField();
        final DefaultListModel<String> model = new DefaultListModel<>();
        final JList<String> list = new JList<>(model);
        final JLabel status = new JLabel();
        final CompletableFuture<String> accepted = new CompletableFuture<>();
        final int documentListeners;
        final int actionListeners;
        final int mouseListeners;
        final PaletteFilterSession controller;

        Fixture(Function<String, List<String>> matcher) {
            model.addElement("old result");
            list.setSelectedIndex(0);
            documentListeners = ((javax.swing.text.AbstractDocument) field.getDocument()).getDocumentListeners().length;
            actionListeners = field.getActionListeners().length;
            mouseListeners = list.getMouseListeners().length;
            controller = new PaletteFilterSession(field, list, status, matcher,
                (value, query) -> {
                    assertTrue(SwingUtilities.isEventDispatchThread(), "acceptance must retain EDT delivery");
                    accepted.complete(value + "|" + query);
                });
        }

        public void close() { controller.close(); }
    }

    @Test
    void closingDetachesOwnedListenersAndLeavesOtherSubscriptionsIntact() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture(query -> { fail("a disposed picker must not start filtering"); return List.of(); });
            java.util.concurrent.atomic.AtomicInteger otherActions = new java.util.concurrent.atomic.AtomicInteger();
            java.awt.event.ActionListener otherListener = event -> otherActions.incrementAndGet();
            f.field.addActionListener(otherListener);
            f.field.setText("queued");
            f.close();
            f.close();
            assertEquals(f.documentListeners,
                ((javax.swing.text.AbstractDocument) f.field.getDocument()).getDocumentListeners().length);
            assertEquals(f.actionListeners + 1, f.field.getActionListeners().length);
            assertEquals(f.mouseListeners, f.list.getMouseListeners().length);
            assertTrue(List.of(f.field.getActionListeners()).contains(otherListener));
            String status = f.status.getText();
            f.field.setText("after close");
            f.field.postActionEvent();
            f.controller.acceptSelection();
            assertEquals(1, otherActions.get());
            assertEquals(status, f.status.getText());
            assertFalse(f.accepted.isDone());
        });
    }

    @Test
    void enterDuringDebounceAcceptsOnlyTheNewQuery() throws Exception {
        Fixture[] fixture = new Fixture[1];
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture[0] = new Fixture(query -> List.of("match " + query));
            f.field.setText("new");
            f.field.postActionEvent();
            assertFalse(f.accepted.isDone(), "must not accept the old selected row");
            assertTrue(f.model.isEmpty());
        });
        try {
            assertEquals("match new|new", fixture[0].accepted.get(5, TimeUnit.SECONDS));
        } finally { SwingUtilities.invokeAndWait(fixture[0]::close); }
    }

    @Test
    void supersededSlowQueryCannotReplaceOrAcceptTheLatestResults() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Fixture[] fixture = new Fixture[1];
        SwingUtilities.invokeAndWait(() -> {
            fixture[0] = new Fixture(query -> {
                if (query.equals("slow")) {
                    started.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { /* simulate an uncooperative matcher */ }
                }
                return List.of(query);
            });
            fixture[0].field.setText("slow");
            fixture[0].field.postActionEvent();
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                fixture[0].field.setText("latest");
                fixture[0].field.postActionEvent();
            });
            release.countDown();
            assertEquals("latest|latest", fixture[0].accepted.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(fixture[0]::close);
        }
    }

    @Test
    void emptyResultsRemainOpenAndClosingCancelsQueuedAcceptance() throws Exception {
        Fixture[] fixture = new Fixture[1];
        SwingUtilities.invokeAndWait(() -> {
            fixture[0] = new Fixture(query -> List.of());
            fixture[0].field.setText("missing");
            fixture[0].field.postActionEvent();
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean[] finished = {false};
            while (!finished[0] && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> finished[0] = fixture[0].status.getText().startsWith("No matches"));
                if (!finished[0]) Thread.sleep(20);
            }
            assertTrue(finished[0]);
            assertFalse(fixture[0].accepted.isDone());
            SwingUtilities.invokeAndWait(() -> {
                fixture[0].field.setText("another");
                fixture[0].field.postActionEvent();
                fixture[0].close();
                fixture[0].controller.acceptSelection();
                assertFalse(fixture[0].accepted.isDone());
            });
        } finally { SwingUtilities.invokeAndWait(fixture[0]::close); }
    }
}
