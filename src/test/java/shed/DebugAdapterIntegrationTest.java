package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DebugAdapterIntegrationTest {
    @Test
    void referenceAdapterCoversLaunchBreakpointsPauseInspectEvaluateAndStop(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path source = workspace.resolve("Main.java");
        BreakpointStore breakpoints = new BreakpointStore(tempDir.resolve("state"));
        breakpoints.toggle(workspace, source, 8);
        DebugSessionService service = new DebugSessionService();
        try (ReferenceDebugAdapter adapter = new ReferenceDebugAdapter(ReferenceDebugAdapter.Mode.NORMAL)) {
            DebugSessionService.Result started = service.start(workspace, source, validation(), enabled(), "main", Duration.ofSeconds(1),
                starter(adapter), breakpoints);

            assertTrue(started.succeeded());
            await(() -> service.inspection(workspace).paused());
            assertTrue(service.addWatch(workspace, "count").succeeded());
            DebugSessionService.InspectionResult inspection = service.refreshInspection(workspace, Duration.ofSeconds(1));
            assertTrue(inspection.succeeded());
            assertEquals(DebugInspection.State.READY, inspection.snapshot().state());
            assertEquals("count", inspection.snapshot().scopes().getFirst().variables().getFirst().name());
            assertEquals(DebugInspection.WatchState.READY, inspection.snapshot().watches().getFirst().state());
            assertEquals(List.of("initialize", "launch", "setBreakpoints", "configurationDone", "threads", "stackTrace", "scopes", "variables", "evaluate"),
                adapter.commands());

            assertTrue(service.stop(workspace).succeeded());
            await(() -> adapter.commands().contains("disconnect"));
            assertTrue(adapter.destroyed());
        }
    }

    @Test
    void timeoutSendsCancellationAndTheReferenceAdapterRecovers() throws Exception {
        try (ReferenceDebugAdapter adapter = new ReferenceDebugAdapter(ReferenceDebugAdapter.Mode.TIMEOUT_THREADS)) {
            DebugAdapterTransport transport = DebugAdapterTransport.forStreams(adapter.clientInput(), adapter.clientOutput(), adapter.process(), null);
            try {
                assertTrue(transport.request("initialize", Map.of(), Duration.ofSeconds(1)).success());
                assertThrows(TimeoutException.class, () -> transport.request("threads", Map.of(), Duration.ofMillis(80)));
                await(() -> adapter.commands().contains("cancel"));
                adapter.mode(ReferenceDebugAdapter.Mode.NORMAL);

                assertTrue(transport.request("threads", Map.of(), Duration.ofSeconds(1)).success());
                assertEquals(DebugAdapterTransport.State.RUNNING, transport.state());
            } finally {
                transport.close();
            }
            assertTrue(adapter.destroyed());
        }
    }

    @Test
    void malformedReferenceOutputFailsTheTransportAndDestroysItsProcess() throws Exception {
        List<DebugAdapterTransport.Diagnostic> diagnostics = new CopyOnWriteArrayList<>();
        try (ReferenceDebugAdapter adapter = new ReferenceDebugAdapter(ReferenceDebugAdapter.Mode.MALFORMED_OUTPUT)) {
            DebugAdapterTransport transport = DebugAdapterTransport.forStreams(adapter.clientInput(), adapter.clientOutput(), adapter.process(),
                new DebugAdapterTransport.Listener() {
                    @Override public void onDiagnostic(DebugAdapterTransport.Diagnostic diagnostic) { diagnostics.add(diagnostic); }
                });
            try {
                await(() -> transport.state() == DebugAdapterTransport.State.FAILED && adapter.destroyed());
                assertFalse(diagnostics.isEmpty());
                assertEquals("malformed-adapter-output", diagnostics.getFirst().code());
                assertThrows(IOException.class, () -> transport.request("initialize", Map.of(), Duration.ofSeconds(1)));
            } finally {
                transport.close();
            }
        }
    }

    private static DebugSessionService.Starter starter(ReferenceDebugAdapter adapter) {
        return (plan, features, listener) -> {
            DebugAdapterTransport transport = DebugAdapterTransport.forStreams(adapter.clientInput(), adapter.clientOutput(), adapter.process(), listener);
            return new DebugSessionService.Connection() {
                @Override public DebugAdapterTransport.Response request(String command, Map<String, Object> arguments, Duration timeout)
                    throws IOException, TimeoutException, InterruptedException { return transport.request(command, arguments, timeout); }
                @Override public DebugAdapterTransport.State state() { return transport.state(); }
                @Override public void close() { transport.close(); }
            };
        };
    }

    private static DebugAdapterRegistry.Validation validation() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("debug.adapter.reference.command", "reference-adapter");
        values.put("debug.adapter.reference.capabilities", "launch,breakpoints,configuration_done,threads,stack_trace,scopes,variables,evaluate");
        values.put("debug.configuration.main.adapter", "reference");
        values.put("debug.configuration.main.request", "launch");
        values.put("debug.configuration.main.scope", "workspace");
        values.put("debug.configuration.main.program", "${file}");
        values.put("debug.configuration.main.cwd", "${workspaceFolder}");
        return DebugAdapterRegistry.validate(values);
    }

    private static DebugFeatureSettings enabled() { return new DebugFeatureSettings(true, true, true, true, true, true, true, true); }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("condition was not met");
            Thread.sleep(10);
        }
    }
}
