package shed;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OllamaCompletionClientTest {
    @Test void sendsOnlyBoundedPrefixAndSuffixAndPreservesInsertionWhitespace() throws Exception {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        try (var fixture = new Fixture(exchange -> {
            received.set(MiniJson.asObject(MiniJson.parseStrict(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
            reply(exchange, 200, MiniJson.stringify(Map.of("response", "  answer;\r\n\t}", "done", true)));
        }); var client = new OllamaCompletionClient(fixture.settings(true, 3000))) {
            assertEquals("  answer;\n\t}", client.complete("before🙂", "after").get(5, TimeUnit.SECONDS));
            assertEquals("before🙂", received.get().get("prompt")); assertEquals("after", received.get().get("suffix"));
            assertEquals(false, received.get().get("stream")); assertEquals("fixture-fim", received.get().get("model"));
            assertEquals(java.util.Set.of("model", "prompt", "suffix", "stream", "options"), received.get().keySet());
        }
    }

    @Test void disabledAndOversizedRequestsNeverReachTheServer() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        try (var fixture = new Fixture(exchange -> { requests.incrementAndGet(); reply(exchange, 500, ""); });
             var disabled = new OllamaCompletionClient(fixture.settings(false, 3000));
             var enabled = new OllamaCompletionClient(fixture.settings(true, 3000))) {
            assertThrows(Exception.class, () -> disabled.complete("code", "").get());
            assertThrows(Exception.class, () -> enabled.complete("x".repeat(OllamaCompletionClient.MAX_PREFIX + 1), "").get());
            assertThrows(Exception.class, () -> enabled.complete("", "x".repeat(OllamaCompletionClient.MAX_SUFFIX + 1)).get());
            assertEquals(0, requests.get());
        }
    }

    @Test void refusesRedirectsErrorsIncompleteMalformedAndExcessiveResponses() throws Exception {
        for (String body : new String[] {"not json", "{\"done\":false,\"response\":\"x\"}",
            "{\"done\":true,\"response\":\"x\",\"response\":\"y\"}",
            MiniJson.stringify(Map.of("done", true, "response", "x".repeat(4097))),
            MiniJson.stringify(Map.of("done", true, "response", "x\u001b[31m")), "x".repeat(65537)}) {
            try (var fixture = new Fixture(exchange -> reply(exchange, 200, body));
                 var client = new OllamaCompletionClient(fixture.settings(true, 3000))) {
                assertThrows(java.util.concurrent.ExecutionException.class, () -> client.complete("a", "b").get(5, TimeUnit.SECONDS));
            }
        }
        for (int status : new int[] {302, 404, 500}) {
            try (var fixture = new Fixture(exchange -> { exchange.getResponseHeaders().add("Location", "http://example.invalid/"); reply(exchange, status, "{}"); });
                 var client = new OllamaCompletionClient(fixture.settings(true, 3000))) {
                assertThrows(java.util.concurrent.ExecutionException.class, () -> client.complete("a", "b").get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void rejectsMalformedUtf8() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(new byte[] {(byte) 0xc3, 0x28}); exchange.close();
        }); var client = new OllamaCompletionClient(fixture.settings(true, 3000))) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> client.complete("a", "b").get(5, TimeUnit.SECONDS));
        }
    }

    @Test void cancellationAndDeadlineHandleAStalledBody() throws Exception {
        for (boolean cancel : new boolean[] {true, false}) {
            CountDownLatch headers = new CountDownLatch(1), release = new CountDownLatch(1);
            try (var fixture = new Fixture(exchange -> {
                exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); headers.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.close();
            }); var client = new OllamaCompletionClient(fixture.settings(true, cancel ? 3000 : 400))) {
                var request = client.complete("code", ""); assertTrue(headers.await(3, TimeUnit.SECONDS));
                if (cancel) { assertTrue(request.cancel(true)); assertTrue(request.isCancelled()); }
                else assertThrows(java.util.concurrent.ExecutionException.class, () -> request.get(3, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    @Test void settingsAllowOnlyExplicitLocalGenerationEndpoints() {
        for (String uri : new String[] {"https://example.com/api/generate", "http://127.0.0.1.evil/api/generate",
            "http://user@127.0.0.1/api/generate", "http://127.0.0.1/api/generate?q=x", "http://127.0.0.1/api/pull"}) {
            assertThrows(IllegalArgumentException.class, () -> new InlineCompletionSettings(true, URI.create(uri), "fim", 500, Duration.ofSeconds(2)));
        }
        var local = new InlineCompletionSettings(true, URI.create("http://localhost:11434/api/generate"), "fim", 500, Duration.ofSeconds(2));
        assertEquals("127.0.0.1", local.endpoint().getHost());
        assertThrows(IllegalArgumentException.class, () -> new InlineCompletionSettings(true, local.endpoint(), "", 500, Duration.ofSeconds(2)));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String text) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body); exchange.close();
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Fixture(com.sun.net.httpserver.HttpHandler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/generate", handler); server.setExecutor(executor); server.start();
        }
        InlineCompletionSettings settings(boolean enabled, int timeout) {
            return new InlineCompletionSettings(enabled, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/generate"),
                "fixture-fim", 500, Duration.ofMillis(timeout));
        }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
