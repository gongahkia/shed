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
import org.junit.jupiter.api.Test;

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

    private static DebugAdapterRegistry.Validation validation() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("debug.adapter.java.command", "java-debug-adapter");
        values.put("debug.adapter.java.capabilities", "launch,attach");
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

    private static final class FakeConnection implements DebugSessionService.Connection {
        private final List<String> commands = new ArrayList<>();
        private final List<Map<String, Object>> arguments = new ArrayList<>();
        private final Map<String, DebugAdapterTransport.Response> responses = new LinkedHashMap<>();
        private boolean closed;

        @Override public DebugAdapterTransport.Response request(String command, Map<String, Object> arguments, Duration timeout) {
            commands.add(command); this.arguments.add(arguments);
            return responses.getOrDefault(command, response(command, true, ""));
        }
        @Override public DebugAdapterTransport.State state() { return closed ? DebugAdapterTransport.State.CLOSED : DebugAdapterTransport.State.RUNNING; }
        @Override public void close() { closed = true; }
    }
}
