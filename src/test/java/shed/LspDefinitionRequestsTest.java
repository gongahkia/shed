package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class LspDefinitionRequestsTest {
    static <T> T edt(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(action.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    static EditorPane pane(FileBuffer buffer) {
        JTextArea area = new JTextArea(); area.setDocument(buffer.getDocument());
        return new EditorPane(area, new LineNumberPanel(area), new JScrollPane(area), new SearchManager(area));
    }

    static final class Harness implements AutoCloseable {
        final InProcessLspFixture peer = new InProcessLspFixture();
        final LspClient client;
        final AsyncJobService jobs;
        final FileBuffer buffer;
        final EditorPane pane;
        final List<LspClient.Location> navigated = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final NavigationHistory<String> history = new NavigationHistory<>(10);
        final LspDefinitionRequests requests;
        final CountDownLatch completed = new CountDownLatch(1);
        FileBuffer activeBuffer;
        EditorPane activePane;
        LspClient boundClient;
        String uri = "file:///synthetic/source.java";
        Integer version = 1;
        boolean closing;
        boolean valid = true;
        Runnable onNavigation = () -> { };

        Harness() throws Exception { this(null, "alpha\ne\u0301 😀 क्ष\nlast\n"); }
        Harness(java.util.concurrent.ExecutorService executor) throws Exception { this(executor, "alpha\ne\u0301 😀 क्ष\nlast\n"); }
        Harness(String source) throws Exception { this(null, source); }

        Harness(java.util.concurrent.ExecutorService executor, String source) throws Exception {
            jobs = executor == null ? new AsyncJobService(20) : new AsyncJobService(executor, 20, null);
            client = peer.client();
            buffer = edt(() -> {
                var b = FileBuffer.createScratch("source", source);
                b.retargetFile(Path.of(System.getProperty("java.io.tmpdir"), "source.java").toFile());
                return b;
            });
            pane = edt(() -> { var p = pane(buffer); p.setBuffer(buffer); p.getTextArea().setCaretPosition(7); return p; });
            activeBuffer = buffer; activePane = pane; boundClient = client;
            requests = edt(() -> new LspDefinitionRequests(jobs, location -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                history.record("source:7"); navigated.add(location); onNavigation.run(); completed.countDown();
            }, message -> {
                assertTrue(SwingUtilities.isEventDispatchThread()); messages.add(message); completed.countDown();
            }));
        }

        String request() throws Exception {
            return edt(() -> {
                var context = LspDefinitionRequests.Context.capture(buffer, pane, client, uri, version);
                return requests.request(context, () -> valid && context.current(activeBuffer, activePane, boundClient, uri, version, closing));
            });
        }

        void idle() throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < end) {
                boolean retired = edt(() -> {
                    var field = LspDefinitionRequests.class.getDeclaredField("jobId"); field.setAccessible(true);
                    return field.getInt(requests) < 0;
                });
                if (retired && jobs.list().stream().noneMatch(job -> job.getStatus() == AsyncJobService.Status.RUNNING)) return;
                TimeUnit.MILLISECONDS.sleep(1);
            }
            fail("definition work did not retire");
        }

        @Override public void close() throws Exception {
            edt(() -> { requests.close(); return null; });
            jobs.shutdownNow(); idle(); client.stop(); peer.close();
            assertTrue(peer.readerClosed.await(2, TimeUnit.SECONDS), "in-process reader did not close");
        }
    }

    @Test void heldResponseDoesNotDelayQueuedTypingAndNavigationIsReadOnly() throws Exception {
        try (Harness h = new Harness()) {
            long revision = edt(h.buffer::documentRevision);
            String source = edt(h.buffer::getContent);
            assertTrue(h.request().contains("Loading"));
            var request = h.peer.await("textDocument/definition");
            assertEquals(1, MiniJson.asInt(MiniJson.asObject(MiniJson.asObject(request.get("params")).get("position")).get("line")));
            assertEquals(1, MiniJson.asInt(MiniJson.asObject(MiniJson.asObject(request.get("params")).get("position")).get("character")));
            // This barrier must run before the held response is released; no timing threshold is needed.
            edt(() -> { assertTrue(h.navigated.isEmpty()); assertFalse(h.buffer.getUndoManager().canUndo()); return null; });
            h.peer.respond(request, InProcessLspFixture.location("file:///synthetic/target.java"));
            assertTrue(h.completed.await(2, TimeUnit.SECONDS)); h.idle();
            edt(() -> {
                assertEquals(1, h.navigated.size()); assertEquals("source:7", h.history.back("target:2", x -> true));
                assertEquals(source, h.buffer.getContent()); assertEquals(revision, h.buffer.documentRevision());
                assertFalse(h.buffer.isModified()); assertFalse(h.buffer.getUndoManager().canUndo());
                assertEquals(7, h.pane.getTextArea().getCaretPosition()); return null;
            });
        }
    }

    @Test void emptyAndErrorResponsesReportWithoutNavigation() throws Exception {
        for (boolean error : new boolean[]{false, true}) try (Harness h = new Harness()) {
            h.request(); var request = h.peer.await("textDocument/definition");
            if (error) h.peer.error(request); else h.peer.respond(request, List.of());
            assertTrue(h.completed.await(2, TimeUnit.SECONDS)); h.idle();
            edt(() -> { assertEquals(List.of("No definition found"), h.messages); assertTrue(h.navigated.isEmpty()); return null; });
        }
    }

    @Test void multipleTargetsPreserveExistingFirstLocationPolicy() throws Exception {
        try (Harness h = new Harness()) {
            h.request(); var request = h.peer.await("textDocument/definition");
            List<Object> result = new ArrayList<>(InProcessLspFixture.location("file:///first.java"));
            result.addAll(InProcessLspFixture.location("file:///second.java")); h.peer.respond(request, result);
            assertTrue(h.completed.await(2, TimeUnit.SECONDS)); h.idle();
            edt(() -> { assertEquals("file:///first.java", h.navigated.getFirst().getUri()); return null; });
        }
    }

    @Test void editsBeforeDebouncedLspVersionUpdateRejectOldResultsAndPreserveUndo() throws Exception {
        try (Harness h = new Harness()) {
            String before = edt(h.buffer::getContent);
            h.request(); var request = h.peer.await("textDocument/definition");
            edt(() -> { h.buffer.getDocument().insertString(0, "human ", null); return null; });
            h.peer.respond(request, InProcessLspFixture.location("file:///target.java")); h.idle();
            edt(() -> {
                assertTrue(h.navigated.isEmpty()); assertEquals("human " + before, h.buffer.getContent());
                h.buffer.getUndoManager().undo(); assertEquals(before, h.buffer.getContent());
                h.buffer.getUndoManager().redo(); assertEquals("human " + before, h.buffer.getContent()); return null;
            });
        }
    }

    @Test void contextChangesRejectResultsIncludingAwayAndBackRevisionChanges() throws Exception {
        for (String scenario : List.of("buffer", "pane", "caret", "selection", "version", "uri", "path", "closing", "disposed", "disconnect", "restart", "editUndo")) {
            try (Harness h = new Harness(); var replacement = new InProcessLspFixture()) {
                LspClient other = replacement.client();
                try {
                    h.request(); var request = h.peer.await("textDocument/definition");
                    edt(() -> {
                        switch (scenario) {
                            case "buffer" -> h.activeBuffer = FileBuffer.createScratch("other", "other");
                            case "pane" -> { h.activePane = pane(h.buffer); h.activePane.setBuffer(h.buffer); }
                            case "caret" -> h.pane.getTextArea().setCaretPosition(1);
                            case "selection" -> h.pane.getTextArea().moveCaretPosition(8);
                            case "version" -> h.version++;
                            case "uri" -> h.uri = "file:///changed.java";
                            case "path" -> h.buffer.retargetFile(Path.of(System.getProperty("java.io.tmpdir"), "renamed.java").toFile());
                            case "closing" -> h.closing = true;
                            case "disposed" -> h.pane.disposeSourceView();
                            case "disconnect" -> h.peer.destroy();
                            case "restart" -> h.boundClient = other;
                            case "editUndo" -> { h.buffer.getDocument().insertString(0, "x", null); h.buffer.getUndoManager().undo(); }
                            default -> throw new AssertionError();
                        }
                        return null;
                    });
                    h.peer.respond(request, InProcessLspFixture.location("file:///target.java")); h.idle();
                    edt(() -> { assertTrue(h.navigated.isEmpty(), scenario); return null; });
                } finally { other.stop(); }
            }
        }
    }

    @Test void cancellationEmitsProtocolCancelAndLateResponseCannotNavigate() throws Exception {
        try (Harness h = new Harness()) {
            h.request(); var old = h.peer.await("textDocument/definition");
            edt(() -> { h.requests.cancel(); return null; });
            assertEquals(old.get("id"), MiniJson.asObject(h.peer.await("$/cancelRequest").get("params")).get("id"));
            h.peer.respond(old, InProcessLspFixture.location("file:///obsolete.java")); h.idle();
            h.request(); var current = h.peer.await("textDocument/definition");
            h.peer.respond(current, InProcessLspFixture.location("file:///current.java"));
            assertTrue(h.completed.await(2, TimeUnit.SECONDS)); h.idle();
            edt(() -> { assertEquals(1, h.navigated.size()); assertEquals("file:///current.java", h.navigated.getFirst().getUri()); return null; });
        }
    }

    @Test void rapidRequestsCoalesceToOneLatestReplacement() throws Exception {
        try (Harness h = new Harness()) {
            h.request(); var old = h.peer.await("textDocument/definition");
            edt(() -> {
                for (int i = 0; i < 50; i++) {
                    var context = LspDefinitionRequests.Context.capture(h.buffer, h.pane, h.client, h.uri, h.version);
                    h.requests.request(context, () -> true);
                }
                assertEquals(1, h.jobs.list().size(), "replacement requests must not grow the job queue");
                return null;
            });
            h.peer.await("$/cancelRequest");
            h.peer.respond(old, InProcessLspFixture.location("file:///obsolete.java"));
            var latest = h.peer.await("textDocument/definition");
            h.peer.respond(latest, InProcessLspFixture.location("file:///latest.java"));
            assertTrue(h.completed.await(2, TimeUnit.SECONDS)); h.idle();
            edt(() -> { assertEquals(1, h.navigated.size()); assertEquals("file:///latest.java", h.navigated.getFirst().getUri()); return null; });
        }
    }

    @Test void queuedCancellationAndWindowCloseRetireCallbacks() throws Exception {
        try (Harness h = new Harness()) {
            h.request(); h.peer.await("textDocument/definition");
            edt(() -> { h.requests.close(); h.requests.close(); assertEquals("LSP unavailable", h.requests.request(null, () -> true)); return null; });
            h.idle();
            edt(() -> { assertTrue(h.navigated.isEmpty()); assertTrue(h.messages.isEmpty()); return null; });
        }
    }
    @Test void transportFailureDoesNotBlockLaterDefinition() throws Exception {
        try (Harness h = new Harness()) {
            h.peer.failDefinition = true; h.request();
            assertTrue(h.completed.await(2, TimeUnit.SECONDS)); h.idle();
            edt(() -> { assertTrue(h.messages.getFirst().contains("synthetic transport failure")); assertTrue(h.navigated.isEmpty()); return null; });
            h.peer.failDefinition = false; h.request(); var request = h.peer.await("textDocument/definition");
            h.peer.respond(request, InProcessLspFixture.location("file:///recovered.java")); h.idle();
            edt(() -> { assertEquals(1, h.navigated.size()); return null; });
        }
    }

    @Test void cancellationBeforeWorkerStartsAndRepeatedCloseReleaseOwnedReferences() throws Exception {
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        workers.submit(() -> { occupied.countDown(); try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
        assertTrue(occupied.await(2, TimeUnit.SECONDS));
        try (Harness h = new Harness(workers)) {
            h.request();
            edt(() -> { h.requests.close(); h.requests.close(); return null; });
            release.countDown(); h.idle();
            edt(() -> {
                for (String name : List.of("jobs", "navigate", "message", "active", "pending")) {
                    var field = LspDefinitionRequests.class.getDeclaredField(name); field.setAccessible(true);
                    assertNull(field.get(h.requests), "closed coordinator retained " + name);
                }
                assertTrue(h.navigated.isEmpty()); assertTrue(h.messages.isEmpty()); return null;
            });
            assertTrue(h.peer.requests.stream().noneMatch(request -> "textDocument/definition".equals(request.get("method"))));
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void workspaceEditReviewFromBackgroundResponseIsMarshalledToEdtAndNotApplied() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch review = new CountDownLatch(1);
            h.client.setWorkspaceEditHandler((label, operations) -> LspController.requestWorkspaceEditReview(() -> {
                assertTrue(SwingUtilities.isEventDispatchThread()); review.countDown();
            }));
            String source = edt(h.buffer::getContent);
            h.request(); var definition = h.peer.await("textDocument/definition");
            h.peer.send(Map.of("jsonrpc", "2.0", "id", "server-edit", "method", "workspace/applyEdit",
                "params", Map.of("label", "synthetic", "edit", Map.of("changes", Map.of()))));
            var response = h.peer.awaitResponse("server-edit");
            assertEquals(false, MiniJson.asObject(response.get("result")).get("applied"));
            assertTrue(review.await(2, TimeUnit.SECONDS));
            h.peer.respond(definition, InProcessLspFixture.location("file:///target.java")); h.idle();
            edt(() -> { assertEquals(source, h.buffer.getContent()); assertFalse(h.buffer.isModified()); return null; });
        }
    }

    @Test void onlyChangesToTheOriginCancelThePendingRequest() throws Exception {
        try (Harness h = new Harness()) {
            h.request(); h.peer.await("textDocument/definition");
            edt(() -> {
                h.requests.sourceChanged(FileBuffer.createScratch("other", "other"));
                assertTrue(h.jobs.list().stream().anyMatch(job -> job.getStatus() == AsyncJobService.Status.RUNNING));
                h.requests.sourceChanged(h.buffer); return null;
            });
            h.peer.await("$/cancelRequest"); h.idle();
            edt(() -> { assertTrue(h.navigated.isEmpty()); return null; });
        }
    }

    @Test void immediateResponseCannotOvertakeEdtRequestOwnership() throws Exception {
        try (Harness h = new Harness()) {
            h.peer.immediateDefinition = true; h.request();
            assertTrue(h.completed.await(2, TimeUnit.SECONDS)); h.idle();
            edt(() -> { assertEquals("file:///immediate.java", h.navigated.getFirst().getUri()); return null; });
        }
    }

    @Test void splitClosureWhileDefinitionIsPendingPreservesSharedSourceAndUndo() throws Exception {
        for (boolean closeOrigin : new boolean[]{true, false}) try (Harness h = new Harness()) {
            String before = edt(h.buffer::getContent);
            EditorPane other = edt(() -> { var p = pane(h.buffer); p.setBuffer(h.buffer); return p; });
            h.request(); var request = h.peer.await("textDocument/definition");
            edt(() -> {
                h.pane.surface().replace(0, 0, "A"); other.surface().replace(0, 0, "B");
                EditorPane closed = closeOrigin ? h.pane : other;
                EditorPane survivor = closeOrigin ? other : h.pane;
                closed.disposeSourceView(); h.activePane = survivor;
                assertEquals("BA" + before, h.buffer.getContent());
                survivor.surface().undo(); assertEquals("A" + before, h.buffer.getContent());
                survivor.surface().undo(); assertEquals(before, h.buffer.getContent());
                survivor.surface().redo(); survivor.surface().redo(); assertEquals("BA" + before, h.buffer.getContent());
                return null;
            });
            h.peer.respond(request, InProcessLspFixture.location("file:///obsolete.java")); h.idle();
            edt(() -> { assertTrue(h.navigated.isEmpty()); return null; });
        }
    }

    @Test void independentClientsDoNotCrossNavigateOrCrossCancel() throws Exception {
        try (Harness first = new Harness(); Harness second = new Harness()) {
            first.request(); var a = first.peer.await("textDocument/definition");
            second.request(); var b = second.peer.await("textDocument/definition");
            edt(() -> { first.requests.cancel(); return null; }); first.peer.await("$/cancelRequest");
            first.peer.respond(a, InProcessLspFixture.location("file:///first-obsolete.java"));
            second.peer.respond(b, InProcessLspFixture.location("file:///second-current.java"));
            first.idle(); second.idle();
            edt(() -> { assertTrue(first.navigated.isEmpty()); assertEquals("file:///second-current.java", second.navigated.getFirst().getUri()); return null; });
        }
    }

    @Test void closingWithACompletedResultStillQueuedOnEdtDoesNotResurrectNavigation() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch entered = new CountDownLatch(1), close = new CountDownLatch(1), closed = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                try {
                    var context = LspDefinitionRequests.Context.capture(h.buffer, h.pane, h.client, h.uri, h.version);
                    h.requests.request(context, () -> true); entered.countDown();
                    assertTrue(close.await(3, TimeUnit.SECONDS)); h.requests.close(); closed.countDown();
                } catch (Exception error) { throw new AssertionError(error); }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS)); var request = h.peer.await("textDocument/definition");
                h.peer.respond(request, InProcessLspFixture.location("file:///obsolete.java"));
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (h.jobs.list().stream().anyMatch(job -> job.getFinishedAtMillis() == null) && System.nanoTime() < end) Thread.onSpinWait();
                assertTrue(h.jobs.list().stream().allMatch(job -> job.getFinishedAtMillis() != null));
            } finally { close.countDown(); }
            assertTrue(closed.await(2, TimeUnit.SECONDS)); h.idle();
            edt(() -> { assertTrue(h.navigated.isEmpty()); assertTrue(h.messages.isEmpty()); return null; });
        }
    }

}
