package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

public class DebugAdapterTransportTest {
    @Test
    void framesRequestsAndRoutesResponsesAndEvents() throws Exception {
        java.io.PipedInputStream adapterOutput = new java.io.PipedInputStream();
        java.io.PipedOutputStream adapterWriter = new java.io.PipedOutputStream(adapterOutput);
        ByteArrayOutputStream clientOutput = new ByteArrayOutputStream();
        List<DebugAdapterTransport.Event> events = new CopyOnWriteArrayList<>();
        DebugAdapterTransport transport = DebugAdapterTransport.forStreams(adapterOutput, clientOutput, new DebugAdapterTransport.Listener() {
            @Override public void onEvent(DebugAdapterTransport.Event event) { events.add(event); }
        });
        try {
            CompletableFuture<DebugAdapterTransport.Response> response = CompletableFuture.supplyAsync(() -> {
                try { return transport.request("initialize", Map.of("clientID", "shed"), Duration.ofSeconds(1)); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            });
            await(() -> clientOutput.size() > 0);
            Map<String, Object> outbound = DebugAdapterTransport.readMessage(new ByteArrayInputStream(clientOutput.toByteArray()));
            assertEquals("request", MiniJson.asString(outbound.get("type")));
            assertEquals("initialize", MiniJson.asString(outbound.get("command")));
            DebugAdapterTransport.writeMessage(adapterWriter, Map.of("seq", 1, "type", "response", "request_seq", 1, "success", true,
                "command", "initialize", "body", Map.of("supportsCancelRequest", true)));
            assertTrue(response.get(1, TimeUnit.SECONDS).success());
            DebugAdapterTransport.writeMessage(adapterWriter, Map.of("seq", 2, "type", "event", "event", "initialized", "body", List.of("ready")));
            await(() -> events.size() == 1);
            assertEquals("initialized", events.get(0).event());
            assertEquals(List.of("ready"), events.get(0).body());
            int initialBytes = clientOutput.size();
            CompletableFuture<DebugAdapterTransport.Response> threads = CompletableFuture.supplyAsync(() -> {
                try { return transport.request("threads", Map.of(), Duration.ofSeconds(1)); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            });
            await(() -> clientOutput.size() > initialBytes);
            DebugAdapterTransport.writeMessage(adapterWriter, Map.of("seq", 3, "type", "response", "request_seq", 2, "success", true,
                "command", "threads", "body", List.of("thread")));
            assertEquals(List.of("thread"), threads.get(1, TimeUnit.SECONDS).body());
        } finally {
            transport.close();
            adapterWriter.close();
        }
    }

    @Test
    void timesOutThenSendsDapCancellationAfterInitializeCapability() throws Exception {
        java.io.PipedInputStream adapterOutput = new java.io.PipedInputStream();
        java.io.PipedOutputStream adapterWriter = new java.io.PipedOutputStream(adapterOutput);
        ByteArrayOutputStream clientOutput = new ByteArrayOutputStream();
        DebugAdapterTransport transport = DebugAdapterTransport.forStreams(adapterOutput, clientOutput, null);
        try {
            CompletableFuture<DebugAdapterTransport.Response> initialize = CompletableFuture.supplyAsync(() -> {
                try { return transport.request("initialize", Map.of(), Duration.ofSeconds(1)); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            });
            await(() -> clientOutput.size() > 0);
            DebugAdapterTransport.readMessage(new ByteArrayInputStream(clientOutput.toByteArray()));
            clientOutput.reset();
            DebugAdapterTransport.writeMessage(adapterWriter, Map.of("seq", 1, "type", "response", "request_seq", 1, "success", true,
                "command", "initialize", "body", Map.of("supportsCancelRequest", true)));
            assertTrue(initialize.get(1, TimeUnit.SECONDS).success());
            assertThrows(TimeoutException.class, () -> transport.request("threads", Map.of(), Duration.ofMillis(80)));
            await(() -> clientOutput.size() > 0);
            ByteArrayInputStream frames = new ByteArrayInputStream(clientOutput.toByteArray());
            Map<String, Object> request = DebugAdapterTransport.readMessage(frames);
            Map<String, Object> cancel = DebugAdapterTransport.readMessage(frames);
            assertEquals("threads", MiniJson.asString(request.get("command")));
            assertEquals("cancel", MiniJson.asString(cancel.get("command")));
            assertEquals(1, MiniJson.asInt(MiniJson.asObject(cancel.get("arguments")).get("requestId")));
        } finally {
            transport.close();
            adapterWriter.close();
        }
    }

    @Test
    void timeoutWithoutCancelCapabilityIgnoresLateResponseWithoutSendingCancel() throws Exception {
        java.io.PipedInputStream adapterOutput = new java.io.PipedInputStream();
        java.io.PipedOutputStream adapterWriter = new java.io.PipedOutputStream(adapterOutput);
        ByteArrayOutputStream clientOutput = new ByteArrayOutputStream();
        DebugAdapterTransport transport = DebugAdapterTransport.forStreams(adapterOutput, clientOutput, null);
        try {
            assertThrows(TimeoutException.class, () -> transport.request("threads", Map.of(), Duration.ofMillis(80)));
            ByteArrayInputStream frames = new ByteArrayInputStream(clientOutput.toByteArray());
            Map<String, Object> request = DebugAdapterTransport.readMessage(frames);
            assertEquals("threads", MiniJson.asString(request.get("command")));
            assertNull(DebugAdapterTransport.readMessage(frames));
        } finally {
            transport.close();
            adapterWriter.close();
        }
    }

    @Test
    void isolatesMalformedAdapterOutputAndDestroysItsProcess() throws Exception {
        java.io.PipedInputStream adapterOutput = new java.io.PipedInputStream();
        java.io.PipedOutputStream adapterWriter = new java.io.PipedOutputStream(adapterOutput);
        ByteArrayOutputStream clientOutput = new ByteArrayOutputStream();
        FakeProcess process = new FakeProcess();
        List<DebugAdapterTransport.Diagnostic> diagnostics = new CopyOnWriteArrayList<>();
        DebugAdapterTransport transport = DebugAdapterTransport.forStreams(adapterOutput, clientOutput, process, new DebugAdapterTransport.Listener() {
            @Override public void onDiagnostic(DebugAdapterTransport.Diagnostic diagnostic) { diagnostics.add(diagnostic); }
        });
        try {
            adapterWriter.write("Content-Length: nope\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            adapterWriter.flush();
            await(() -> transport.state() == DebugAdapterTransport.State.FAILED);
            assertTrue(process.destroyed);
            assertFalse(diagnostics.isEmpty());
            assertEquals("malformed-adapter-output", diagnostics.get(0).code());
            assertThrows(java.io.IOException.class, () -> transport.request("initialize", Map.of(), Duration.ofSeconds(1)));
        } finally {
            transport.close();
            adapterWriter.close();
        }
    }

    @Test
    void rejectsMalformedJsonFixtures() throws Exception {
        byte[] body = "{\"seq\":1,\"type\":\"event\",}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] frame = new byte[header.length + body.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(body, 0, frame, header.length, body.length);
        assertThrows(java.io.IOException.class, () -> DebugAdapterTransport.readMessage(new ByteArrayInputStream(frame)));
    }

    @Test
    void refusesDisabledDebuggingBeforeItCanOpenATransport() {
        DebugAdapterRegistry.Adapter adapter = new DebugAdapterRegistry.Adapter("test", DebugAdapterRegistry.Transport.STDIO, "unreachable-adapter", List.of(), java.util.Set.of(DebugAdapterRegistry.Capability.LAUNCH));
        DebugAdapterRegistry.Configuration configuration = new DebugAdapterRegistry.Configuration("main", "test", DebugAdapterRegistry.Request.LAUNCH,
            "workspace", "${workspaceFolder}/Main.java", "${workspaceFolder}", List.of(), "127.0.0.1", 0);
        DebugAdapterRegistry.Plan plan = new DebugAdapterRegistry.Plan(adapter, configuration, java.nio.file.Path.of("."), java.nio.file.Path.of("."), java.nio.file.Path.of("Main.java"));

        java.io.IOException error = assertThrows(java.io.IOException.class,
            () -> DebugAdapterTransport.start(plan, DebugFeatureSettings.defaults(), null));

        assertEquals("Debugging is disabled by settings", error.getMessage());
    }

    @Test
    void closesWithDisconnectAndStopsTheAdapterProcess() throws Exception {
        java.io.PipedInputStream adapterOutput = new java.io.PipedInputStream();
        java.io.PipedOutputStream adapterWriter = new java.io.PipedOutputStream(adapterOutput);
        ByteArrayOutputStream clientOutput = new ByteArrayOutputStream();
        FakeProcess process = new FakeProcess();
        DebugAdapterTransport transport = DebugAdapterTransport.forStreams(adapterOutput, clientOutput, process, null);
        try {
            transport.close();
            Map<String, Object> disconnect = DebugAdapterTransport.readMessage(new ByteArrayInputStream(clientOutput.toByteArray()));
            assertEquals("disconnect", MiniJson.asString(disconnect.get("command")));
            assertTrue(process.destroyed);
            assertEquals(DebugAdapterTransport.State.CLOSED, transport.state());
        } finally {
            adapterWriter.close();
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("condition was not met");
            Thread.sleep(10);
        }
    }

    private static final class FakeProcess extends Process {
        private volatile boolean destroyed;

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
        @Override public boolean isAlive() { return !destroyed; }
    }
}
