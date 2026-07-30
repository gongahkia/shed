package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DebugSessionServiceTest {
    @Test
    void requiresExplicitValidConfigurationBeforeStartingAnAdapter() {
        DebugSessionService service = new DebugSessionService();
        List<DebugAdapterRegistry.Plan> started = new ArrayList<>();
        Path workspace = Path.of("build/debug-session").toAbsolutePath();

        DebugSessionService.Result result = service.start(workspace, null, validation(), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> { started.add(plan); return new FakeConnection(); });

        assertFalse(result.succeeded());
        assertEquals(DebugSessionService.Lifecycle.FAILED, result.snapshot().lifecycle());
        assertTrue(result.snapshot().detail().contains("no process will be launched"));
        assertTrue(started.isEmpty());
    }

    @Test
    void launchesOnlyWhenExplicitlyStartedThenStopsAndRestarts() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-session").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        List<FakeConnection> connections = new ArrayList<>();
        DebugSessionService.Starter starter = (plan, features, listener) -> {
            FakeConnection connection = new FakeConnection();
            connections.add(connection);
            return connection;
        };

        assertTrue(service.select(workspace, validation(), "main").succeeded());
        DebugSessionService.Result launched = service.start(workspace, file, validation(), enabled(), "", Duration.ofSeconds(1), starter);

        assertTrue(launched.succeeded());
        assertEquals(DebugSessionService.Lifecycle.RUNNING, launched.snapshot().lifecycle());
        assertEquals(List.of("initialize", "launch"), connections.getFirst().commands);
        assertEquals(file.toString(), connections.getFirst().arguments.get(1).get("program"));
        assertEquals(DebugSessionService.Lifecycle.STOPPED, service.stop(workspace).snapshot().lifecycle());
        assertTrue(connections.getFirst().closed);

        DebugSessionService.Result restarted = service.start(workspace, file, validation(), enabled(), "", Duration.ofSeconds(1), starter);

        assertTrue(restarted.succeeded());
        assertEquals(2, connections.size());
        assertEquals(DebugSessionService.Lifecycle.RUNNING, service.snapshot(workspace).lifecycle());
    }

    @Test
    void preservesAdapterFailureDiagnostics() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-session").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("launch", response("launch", false, "adapter rejected program"));

        DebugSessionService.Result result = service.start(workspace, file, validation(), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> connection);

        assertFalse(result.succeeded());
        assertEquals(DebugSessionService.Lifecycle.FAILED, result.snapshot().lifecycle());
        assertTrue(connection.closed);
        assertTrue(result.snapshot().diagnostics().stream().anyMatch(value -> value.contains("adapter rejected program")));
    }

    @Test
    void blocksDisabledDebuggingWithoutStartingAnAdapter() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-session").toAbsolutePath();
        List<DebugAdapterRegistry.Plan> started = new ArrayList<>();

        DebugSessionService.Result result = service.start(workspace, workspace.resolve("Main.java"), validation(), DebugFeatureSettings.defaults(), "main",
            Duration.ofSeconds(1), (plan, features, listener) -> { started.add(plan); return new FakeConnection(); });

        assertFalse(result.succeeded());
        assertTrue(result.snapshot().detail().contains("disabled"));
        assertTrue(started.isEmpty());
    }

    @Test
    void synchronizesPersistedSourceBreakpointsOnlyForDeclaredAdapterCapability(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        BreakpointStore store = new BreakpointStore(tempDir.resolve("state"));
        store.toggle(workspace, file, 3);
        FakeConnection connection = new FakeConnection();
        connection.responses.put("setBreakpoints", response("setBreakpoints", true, Map.of("breakpoints", List.of(Map.of("verified", true, "line", 4))), ""));

        DebugSessionService.Result result = service.start(workspace, file, validation("launch,breakpoints"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> connection, store);

        assertTrue(result.succeeded());
        assertEquals(List.of("initialize", "launch", "setBreakpoints"), connection.commands);
        assertEquals(file.toString(), ((Map<?, ?>) connection.arguments.get(2).get("source")).get("path"));
        assertEquals(BreakpointStore.State.CHANGED, store.markers(workspace, file).get(3));
        assertTrue(result.snapshot().diagnostics().stream().anyMatch(value -> value.contains("moved to line 4")));
    }

    @Test
    void doesNotSynchronizeWhenBreakpointCapabilityIsUndeclared(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        BreakpointStore store = new BreakpointStore(tempDir.resolve("state"));
        store.toggle(workspace, file, 3);
        FakeConnection connection = new FakeConnection();

        DebugSessionService.Result result = service.start(workspace, file, validation(), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> connection, store);

        assertTrue(result.succeeded());
        assertEquals(List.of("initialize", "launch"), connection.commands);
    }

    @Test
    void sendsBreakpointConfigurationAfterInitializedWhenSupported(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        BreakpointStore store = new BreakpointStore(tempDir.resolve("state"));
        store.toggle(workspace, file, 3);
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsConfigurationDoneRequest", true), ""));
        connection.responses.put("setBreakpoints", response("setBreakpoints", true, Map.of("breakpoints", List.of(Map.of("verified", true))), ""));
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();
        connection.requestHook = command -> {
            if ("launch".equals(command)) listener.get().onEvent(new DebugAdapterTransport.Event(1, "initialized", Map.of()));
        };

        DebugSessionService.Result result = service.start(workspace, file, validation("launch,breakpoints,configuration_done"), enabled(), "main",
            Duration.ofSeconds(1), (plan, features, value) -> { listener.set(value); return connection; }, store);

        assertTrue(result.succeeded());
        assertEquals(List.of("initialize", "launch", "setBreakpoints", "configurationDone"), connection.commands);
        assertFalse(result.snapshot().diagnostics().stream().anyMatch(value -> value.contains("did not emit initialized")));
    }

    private static DebugAdapterRegistry.Validation validation() {
        return validation("launch,attach");
    }

    private static DebugAdapterRegistry.Validation validation(String capabilities) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("debug.adapter.java.command", "java-debug-adapter");
        values.put("debug.adapter.java.capabilities", capabilities);
        values.put("debug.configuration.main.adapter", "java");
        values.put("debug.configuration.main.request", "launch");
        values.put("debug.configuration.main.scope", "workspace");
        values.put("debug.configuration.main.program", "${file}");
        values.put("debug.configuration.main.cwd", "${workspaceFolder}");
        return DebugAdapterRegistry.validate(values);
    }

    private static DebugFeatureSettings enabled() { return new DebugFeatureSettings(true, true, true, true, true, true, true, true); }
    private static DebugAdapterTransport.Response response(String command, boolean success, String message) {
        return new DebugAdapterTransport.Response(1, 1, command, success, Map.of(), message);
    }
    private static DebugAdapterTransport.Response response(String command, boolean success, Object body, String message) {
        return new DebugAdapterTransport.Response(1, 1, command, success, body, message);
    }

    private static final class FakeConnection implements DebugSessionService.Connection {
        private final List<String> commands = new ArrayList<>();
        private final List<Map<String, Object>> arguments = new ArrayList<>();
        private final Map<String, DebugAdapterTransport.Response> responses = new LinkedHashMap<>();
        private Consumer<String> requestHook;
        private boolean closed;

        @Override public DebugAdapterTransport.Response request(String command, Map<String, Object> arguments, Duration timeout) {
            commands.add(command); this.arguments.add(arguments);
            if (requestHook != null) requestHook.accept(command);
            return responses.getOrDefault(command, response(command, true, ""));
        }
        @Override public DebugAdapterTransport.State state() { return closed ? DebugAdapterTransport.State.CLOSED : DebugAdapterTransport.State.RUNNING; }
        @Override public void close() { closed = true; }
    }
}
