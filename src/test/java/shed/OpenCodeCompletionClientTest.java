package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenCodeCompletionClientTest {
    @TempDir Path temporary;

    @Test void reusesTheProcessWithFreshSessionsAndOnlyTheCapturedPrefixAndSuffix() throws Exception {
        Path log = temporary.resolve("requests");
        try (var client = new OpenCodeCompletionClient(settings(true, 15000, log))) {
            assertEquals("a + b", client.complete("before🙂", "after").get(20, TimeUnit.SECONDS));
            assertEquals("a + b", client.complete("next", "suffix").get(20, TimeUnit.SECONDS));
            var requests = Files.readAllLines(log).stream().map(line -> MiniJson.asObject(MiniJson.parseStrict(line))).toList();
            assertEquals(2, requests.size());
            assertEquals(Map.of("prefix", "before🙂", "suffix", "after"), requests.getFirst().get("context"));
            assertNotEquals(requests.get(0).get("session"), requests.get(1).get("session"));
            assertEquals(requests.get(0).get("pid"), requests.get(1).get("pid"));
        }
    }

    @Test void noProcessStartsForDisabledOrOversizedInput() throws Exception {
        Path log = temporary.resolve("requests");
        try (var disabled = new OpenCodeCompletionClient(settings(false, 1000, log));
             var enabled = new OpenCodeCompletionClient(settings(true, 1000, log))) {
            assertThrows(Exception.class, () -> disabled.complete("a", "b").get());
            assertThrows(Exception.class, () -> enabled.complete("x".repeat(8193), "").get());
            assertThrows(Exception.class, () -> enabled.complete("", "x".repeat(4097)).get());
            assertFalse(Files.exists(log));
        }
    }

    @Test void refusesToolReadsWritesMalformedOversizedAndIncompleteResults() throws Exception {
        try (var client = new OpenCodeCompletionClient(settings(true, 15000, temporary.resolve("requests")))) {
            for (String prefix : List.of("read", "write", "permission", "invalid", "oversized", "incomplete")) {
                assertThrows(java.util.concurrent.ExecutionException.class, () -> client.complete(prefix, "").get(20, TimeUnit.SECONDS), prefix);
            }
            assertEquals("a + b", client.complete("retry", "").get(20, TimeUnit.SECONDS));
        }
    }

    @Test void cancellationStopsOnlyItsRequestAndTheNextRequestCanConnectAgain() throws Exception {
        Path log = temporary.resolve("requests");
        try (var client = new OpenCodeCompletionClient(settings(true, 15000, log))) {
            var request = client.complete("stall", ""); AcpConnectionTest.await(() -> Files.exists(log));
            assertTrue(request.cancel(true));
            assertEquals("a + b", client.complete("next", "").get(20, TimeUnit.SECONDS));
            assertTrue(request.isCancelled());
        }
    }

    @Test void deadlineAndCloseStopPendingWork() throws Exception {
        Path log = temporary.resolve("requests");
        try (var client = new OpenCodeCompletionClient(settings(true, 3000, log))) {
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, () -> client.complete("stall", "").get(8, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, failed.getCause());
        }
        var client = new OpenCodeCompletionClient(settings(true, 15000, log));
        var pending = client.complete("stall", ""); client.close();
        assertTrue(pending.isDone());
        assertThrows(Exception.class, () -> client.complete("next", "").get());
    }

    @Test void parsingPreservesWhitespaceAndRejectsUnexpectedOutput() {
        assertEquals("  value;\n\t", OpenCodeCompletionClient.parse("{\"completion\":\"  value;\\r\\n\\t\"}"));
        for (String invalid : List.of("{}", "{\"completion\":42}", "{\"completion\":\"a\",\"completion\":\"b\"}",
            "{\"completion\":\"x\",\"path\":\"other\"}", "{\"completion\":\"\\u001b\"}"))
            assertThrows(IllegalArgumentException.class, () -> OpenCodeCompletionClient.parse(invalid));
    }

    private InlineCompletionSettings settings(boolean enabled, int timeout, Path log) {
        var provider = new AgentProvider("fixture", AcpConnectionTest.javaCommand(CompletionAcpFixture.class.getName(), "single", log.toString()),
            Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(20));
        return new InlineCompletionSettings(enabled, provider, 500, Duration.ofMillis(timeout));
    }
}
