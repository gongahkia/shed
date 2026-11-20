package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LspTransportConcurrencyTest {
    @Test void cancellationDoesNotWaitForAnotherFeaturesResponse() throws Exception {
        try (var peer = new InProcessLspFixture()) {
            LspClient client = peer.client();
            Thread first = new Thread(() -> client.hover("file:///source.java", 0, 0), "b1a-first-request");
            CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
            Thread second = new Thread(() -> {
                entered.countDown();
                client.definition("file:///source.java", 0, 0);
                cancelled.countDown();
            }, "b1a-cancelled-request");
            first.start();
            var firstRequest = peer.await("textDocument/hover");
            try {
                second.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (second.getState() == Thread.State.RUNNABLE && System.nanoTime() < deadline) Thread.onSpinWait();
                second.interrupt();
                assertTrue(cancelled.await(1, TimeUnit.SECONDS), "cancelled definition still waits for unrelated transport owner");
                assertTrue(first.isAlive(), "the held peer response must still be outstanding");
                assertTrue(peer.requests.stream().noneMatch(request -> "textDocument/definition".equals(request.get("method"))));
            } finally {
                peer.respond(firstRequest, java.util.Map.of("contents", "hover"));
                first.join(3000); second.interrupt(); second.join(3000); client.stop();
                assertFalse(first.isAlive()); assertFalse(second.isAlive());
            }
        }
    }
    @Test void notificationPollingAndDocumentChangesDoNotWaitForDefinition() throws Exception {
        try (var peer = new InProcessLspFixture()) {
            LspClient client = peer.client();
            var result = new java.util.concurrent.atomic.AtomicReference<LspClient.Location>();
            Thread requestThread = new Thread(() -> result.set(client.definition("file:///source.java", 0, 0)), "b1a-held-definition");
            requestThread.start(); var definition = peer.await("textDocument/definition");
            CountDownLatch input = new CountDownLatch(1);
            javax.swing.SwingUtilities.invokeLater(() -> {
                client.drainNotifications();
                client.didChange("file:///source.java", 2, java.util.List.of(new LspDocumentChange(0, 0, 0, 0, "human")), null);
                input.countDown();
            });
            try {
                assertTrue(input.await(2, TimeUnit.SECONDS), "UI polling or notification write waited for the response");
                var change = MiniJson.asObject(peer.await("textDocument/didChange").get("params"));
                assertEquals(2, MiniJson.asInt(MiniJson.asObject(change.get("textDocument")).get("version")));
                peer.respond(definition, InProcessLspFixture.location("file:///target.java")); requestThread.join(2000);
                assertNotNull(result.get());
            } finally {
                peer.respond(definition, InProcessLspFixture.location("file:///target.java"));
                requestThread.interrupt(); requestThread.join(3000); client.stop();
                assertFalse(requestThread.isAlive());
            }
        }
    }

}
