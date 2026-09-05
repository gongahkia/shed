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
    void explicitlyStartingAnActivePythonFileSelectsTheBuiltInProfileWhenNoSelectionExists() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-python-context").toAbsolutePath();
        Path file = workspace.resolve("main.py");
        DebugAdapterRegistry.Validation validation = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));
        FakeConnection connection = new FakeConnection();

        DebugSessionService.Result result = service.start(workspace, file, validation, enabled(), "", Duration.ofSeconds(1),
            (plan, features, listener) -> connection);

        assertTrue(result.succeeded());
        assertEquals(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY, result.snapshot().configuration());
        assertEquals(List.of("initialize", "launch"), connection.commands);
    }

    @Test
    void explicitlyStartingAnActiveGoFileSelectsTheDelveProfileAndItsRequiredLaunchMode() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-go-context").toAbsolutePath();
        Path file = workspace.resolve("main.go");
        DebugAdapterRegistry.Validation validation = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));
        FakeConnection connection = new FakeConnection();

        DebugSessionService.Result result = service.start(workspace, file, validation, enabled(), "", Duration.ofSeconds(1),
            (plan, features, listener) -> connection);

        assertTrue(result.succeeded());
        assertEquals(BuiltInDebugAdapterSupport.GO_DELVE, result.snapshot().configuration());
        assertEquals("debug", connection.arguments.get(1).get("mode"));
        assertEquals(file.toString(), connection.arguments.get(1).get("program"));
    }

    @Test
    void sendsAValidatedImportedLaunchEnvironmentToTheAdapter() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-environment-context").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        DebugAdapterRegistry.Configuration imported = new DebugAdapterRegistry.Configuration("vscode:With environment", "java",
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "${file}", "", "", "${workspaceFolder}", List.of(), "", "127.0.0.1", 0,
            List.of(), Map.of("APP_MODE", "development", "PORT", "3000"), Map.of("type", "pwa-node", "sourceMaps", true));
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.withExternalConfigurations(validation(),
            Map.of(imported.name(), imported));
        FakeConnection connection = new FakeConnection();

        DebugSessionService.Result result = service.start(workspace, file, validation, enabled(), imported.name(), Duration.ofSeconds(1),
            (plan, features, listener) -> connection);

        assertTrue(result.succeeded());
        assertEquals(Map.of("APP_MODE", "development", "PORT", "3000"), connection.arguments.get(1).get("env"));
        assertEquals("pwa-node", connection.arguments.get(1).get("type"));
        assertEquals(true, connection.arguments.get(1).get("sourceMaps"));
    }

    @Test
    void runsAConfiguredPreLaunchTaskBeforeOpeningTheDebugAdapter() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-prelaunch").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<String> task = new AtomicReference<>();

        DebugSessionService.Result result = service.start(workspace, new DebugAdapterRegistry.LaunchContext(file, "", null),
            validationWithPreLaunch("build"), enabled(), "main", Duration.ofSeconds(1), (plan, features, listener) -> connection, null, null,
            plan -> {
                task.set(plan.configuration().prelaunchTask());
                return new DebugSessionService.PreLaunchResult(true, List.of("Debug pre-launch task 'build' completed."));
            });

        assertTrue(result.succeeded());
        assertEquals("build", task.get());
        assertEquals(List.of("initialize", "launch"), connection.commands);
        assertTrue(result.snapshot().diagnostics().contains("Debug pre-launch task 'build' completed."));
    }

    @Test
    void doesNotOpenTheDebugAdapterWhenTheConfiguredPreLaunchTaskFails() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-prelaunch-fail").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        List<DebugAdapterRegistry.Plan> started = new ArrayList<>();

        DebugSessionService.Result result = service.start(workspace, new DebugAdapterRegistry.LaunchContext(file, "", null),
            validationWithPreLaunch("build"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> { started.add(plan); return new FakeConnection(); }, null, null,
            plan -> new DebugSessionService.PreLaunchResult(false, List.of("Task 'build' exited 1.")));

        assertFalse(result.succeeded());
        assertTrue(started.isEmpty());
        assertTrue(result.snapshot().detail().contains("pre-launch task failed"));
        assertTrue(result.snapshot().diagnostics().contains("Task 'build' exited 1."));
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
    void sendsValidatedModuleAndInlineCodeTargetsWithoutInventingAProgramPath() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-module-session").toAbsolutePath();
        FakeConnection moduleConnection = new FakeConnection();

        assertTrue(service.start(workspace, (Path) null, validationWithTarget("module", "package.main"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> moduleConnection).succeeded());
        assertEquals("package.main", moduleConnection.arguments.get(1).get("module"));
        assertFalse(moduleConnection.arguments.get(1).containsKey("program"));
        service.stop(workspace);

        FakeConnection codeConnection = new FakeConnection();
        assertTrue(service.start(workspace, (Path) null, validationWithTarget("code", "print('Shed')"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> codeConnection).succeeded());
        assertEquals("print('Shed')", codeConnection.arguments.get(1).get("code"));
        assertFalse(codeConnection.arguments.get(1).containsKey("program"));
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
    void sendsDeclaredExecutionControlsToThePausedThreadAndUsesThreadsForPause() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-controls").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();
        connection.responses.put("threads", response("threads", true, Map.of("threads", List.of(Map.of("id", 11, "name", "worker"))), ""));

        assertTrue(service.start(workspace, file, validation("launch,threads,continue,next,step_in,step_out,pause"), enabled(), "main",
            Duration.ofSeconds(1), (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));

        DebugSessionService.ControlResult next = service.control(workspace, DebugSessionService.Control.NEXT, Duration.ofSeconds(1));
        assertTrue(next.succeeded());
        assertEquals("next", connection.commands.get(connection.commands.size() - 1));
        assertEquals(7, connection.arguments.get(connection.arguments.size() - 1).get("threadId"));
        listener.get().onEvent(new DebugAdapterTransport.Event(2, "stopped", Map.of("reason", "pause", "threadId", 7)));

        DebugSessionService.ControlResult pause = service.control(workspace, DebugSessionService.Control.PAUSE, Duration.ofSeconds(1));
        assertFalse(pause.succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(3, "continued", Map.of("threadId", 7)));
        pause = service.control(workspace, DebugSessionService.Control.PAUSE, Duration.ofSeconds(1));
        assertTrue(pause.succeeded());
        assertEquals("threads", connection.commands.get(connection.commands.size() - 2));
        assertEquals("pause", connection.commands.get(connection.commands.size() - 1));
        assertEquals(11, connection.arguments.get(connection.arguments.size() - 1).get("threadId"));
    }

    @Test
    void rejectsExecutionControlsTheAdapterDidNotDeclare() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-controls-missing").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();

        assertTrue(service.start(workspace, file, validation("launch"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));

        DebugSessionService.ControlResult result = service.control(workspace, DebugSessionService.Control.CONTINUE, Duration.ofSeconds(1));

        assertFalse(result.succeeded());
        assertTrue(result.snapshot().diagnostics().stream().anyMatch(value -> value.contains("does not declare support for continue")));
        assertFalse(connection.commands.contains("continue"));
    }

    @Test
    void sendsReverseControlsOnlyWhenTheAdapterDeclaresAndAdvertisesThem() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-reverse-controls").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsReverseContinue", true, "supportsStepBack", true), ""));

        assertTrue(service.start(workspace, file, validation("launch,reverse_continue,step_back"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));

        assertTrue(service.control(workspace, DebugSessionService.Control.REVERSE_CONTINUE, Duration.ofSeconds(1)).succeeded());
        assertEquals("reverseContinue", connection.commands.getLast());
        assertEquals(Map.of("threadId", 7), connection.arguments.getLast());
        listener.get().onEvent(new DebugAdapterTransport.Event(2, "stopped", Map.of("reason", "step", "threadId", 7)));
        assertTrue(service.control(workspace, DebugSessionService.Control.STEP_BACK, Duration.ofSeconds(1)).succeeded());
        assertEquals("stepBack", connection.commands.getLast());
    }

    @Test
    void restartsTheSelectedPausedFrameOnlyWhenTheAdapterAdvertisesSupport() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-restart-frame").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsRestartFrame", true), ""));
        connection.responses.put("stackTrace", response("stackTrace", true,
            Map.of("stackFrames", List.of(Map.of("id", 44, "name", "main"))), ""));

        assertTrue(service.start(workspace, file, validation("launch,stack_trace,restart_frame"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));
        assertTrue(service.refreshInspection(workspace, Duration.ofSeconds(1)).succeeded());

        assertTrue(service.restartFrame(workspace, Duration.ofSeconds(1)).succeeded());
        assertEquals("restartFrame", connection.commands.getLast());
        assertEquals(Map.of("frameId", 44), connection.arguments.getLast());
        assertFalse(service.inspection(workspace).paused());
    }

    @Test
    void loadsStandardExceptionDetailsForThePausedThreadWhenAdvertised() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-exception-details").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsExceptionInfoRequest", true), ""));
        connection.responses.put("exceptionInfo", response("exceptionInfo", true, Map.of("exceptionId", "java.lang.IllegalStateException",
            "breakMode", "always", "description", "broken", "details", Map.of("typeName", "IllegalStateException",
                "fullTypeName", "java.lang.IllegalStateException", "stackTrace", "at Main.main(Main.java:4)")), ""));

        assertTrue(service.start(workspace, file, validation("launch,exception_details"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "exception", "threadId", 7)));

        DebugSessionService.ExceptionDetailsResult result = service.exceptionDetails(workspace, Duration.ofSeconds(1));

        assertTrue(result.succeeded());
        assertEquals("java.lang.IllegalStateException", result.details().exceptionId());
        assertEquals("always", result.details().breakMode());
        assertEquals(Map.of("threadId", 7), connection.arguments.getLast());
    }

    @Test
    void loadsModulesAndSourcesOnlyWhenTheAdapterAdvertisesStandardRequests() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-runtime-metadata").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true,
            Map.of("supportsModulesRequest", true, "supportsLoadedSourcesRequest", true), ""));
        connection.responses.put("modules", response("modules", true, Map.of("modules", List.of(Map.of("id", 4, "name", "app",
            "path", "/tmp/app", "version", "1.0", "symbolStatus", "loaded"))), ""));
        connection.responses.put("loadedSources", response("loadedSources", true, Map.of("sources", List.of(Map.of("name", "Main.java",
            "path", file.toString(), "sourceReference", 0, "origin", "runtime", "presentationHint", "normal"))), ""));

        assertTrue(service.start(workspace, file, validation("launch,modules,loaded_sources"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> connection).succeeded());
        DebugSessionService.ModulesResult modules = service.modules(workspace, 0, 25, Duration.ofSeconds(1));
        DebugSessionService.LoadedSourcesResult sources = service.loadedSources(workspace, Duration.ofSeconds(1));

        assertTrue(modules.succeeded());
        assertEquals(new DebugSessionService.ModuleInfo("4", "app", "/tmp/app", "1.0", "loaded"), modules.modules().getFirst());
        assertEquals(Map.of("startModule", 0, "moduleCount", 25), connection.arguments.get(2));
        assertTrue(sources.succeeded());
        assertEquals("Main.java", sources.sources().getFirst().name());
        assertEquals("runtime", sources.sources().getFirst().origin());
    }

    @Test
    void sendsRunToCursorOnlyWhenDeclaredAndAdvertised() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-goto").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsGotoTargetsRequest", true), ""));
        connection.responses.put("gotoTargets", response("gotoTargets", true, Map.of("targets", List.of(Map.of("id", 91, "label", "line 12", "line", 12, "column", 4))), ""));

        assertTrue(service.start(workspace, file, validation("launch,goto"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));

        DebugSessionService.RunToCursorResult result = service.runToCursor(workspace, file, 12, 4, Duration.ofSeconds(1));

        assertTrue(result.succeeded());
        assertEquals("gotoTargets", connection.commands.get(connection.commands.size() - 2));
        assertEquals(Map.of("source", Map.of("path", file.toString()), "line", 12, "column", 4), connection.arguments.get(connection.arguments.size() - 2));
        assertEquals("goto", connection.commands.getLast());
        assertEquals(Map.of("threadId", 7, "targetId", 91), connection.arguments.getLast());
    }

    @Test
    void rejectsRunToCursorWhenTheAdapterDidNotAdvertiseGotoTargets() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-goto-unadvertised").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();

        assertTrue(service.start(workspace, file, validation("launch,goto"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));

        DebugSessionService.RunToCursorResult result = service.runToCursor(workspace, file, 12, 1, Duration.ofSeconds(1));

        assertFalse(result.succeeded());
        assertTrue(result.snapshot().diagnostics().stream().anyMatch(value -> value.contains("did not advertise gotoTargets")));
        assertFalse(connection.commands.contains("gotoTargets"));
    }

    @Test
    void refusesAmbiguousRunToCursorTargetsInsteadOfChoosingOne() {
        DebugSessionService service = new DebugSessionService();
        Path workspace = Path.of("build/debug-goto-ambiguous").toAbsolutePath();
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsGotoTargetsRequest", true), ""));
        connection.responses.put("gotoTargets", response("gotoTargets", true, Map.of("targets", List.of(
            Map.of("id", 14, "label", "first", "line", 12, "column", 2),
            Map.of("id", 15, "label", "second", "line", 12, "column", 8)
        )), ""));

        assertTrue(service.start(workspace, file, validation("launch,goto"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));

        DebugSessionService.RunToCursorResult result = service.runToCursor(workspace, file, 12, 4, Duration.ofSeconds(1));

        assertFalse(result.succeeded());
        assertTrue(result.snapshot().diagnostics().stream().anyMatch(value -> value.contains("no unambiguous target")));
        assertEquals("gotoTargets", connection.commands.getLast());
        assertFalse(connection.commands.contains("goto"));
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
        assertEquals(BreakpointStore.State.CHANGED, store.markers(workspace, file).get(3).state());
        assertTrue(result.snapshot().diagnostics().stream().anyMatch(value -> value.contains("moved to line 4")));
    }

    @Test
    void mapsLaunchAndBreakpointPathsForARemoteAdapter(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("mirror");
        Path file = workspace.resolve("src/Main.java");
        BreakpointStore store = new BreakpointStore(tempDir.resolve("state"));
        store.toggle(workspace, file, 3);
        FakeConnection connection = new FakeConnection(workspace, "/srv/project");
        connection.responses.put("setBreakpoints", response("setBreakpoints", true, Map.of("breakpoints", List.of(Map.of("verified", true))), ""));

        assertTrue(service.start(workspace, file, validation("launch,breakpoints"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> connection, store).succeeded());

        assertEquals("/srv/project/src/Main.java", connection.arguments.get(1).get("program"));
        assertEquals("/srv/project", connection.arguments.get(1).get("cwd"));
        assertEquals("/srv/project/src/Main.java", ((Map<?, ?>) connection.arguments.get(2).get("source")).get("path"));
    }

    @Test
    void sendsDeclaredConditionalHitAndLogBreakpointFields(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        BreakpointStore store = new BreakpointStore(tempDir.resolve("state"));
        store.toggle(workspace, file, 3);
        store.configure(workspace, file, 3, true, "value > 1", "5", "value={value}");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsConditionalBreakpoints", true,
            "supportsHitConditionalBreakpoints", true, "supportsLogPoints", true), ""));
        connection.responses.put("setBreakpoints", response("setBreakpoints", true, Map.of("breakpoints", List.of(Map.of("verified", true))), ""));

        DebugSessionService.Result result = service.start(workspace, file,
            validation("launch,breakpoints,conditional_breakpoints,hit_conditional_breakpoints,log_points"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> connection, store);

        assertTrue(result.succeeded());
        Map<?, ?> arguments = connection.arguments.get(2);
        List<?> requested = (List<?>) arguments.get("breakpoints");
        assertEquals(Map.of("line", 3, "condition", "value > 1", "hitCondition", "5", "logMessage", "value={value}"), requested.getFirst());
    }

    @Test
    void rejectsRichBreakpointSettingsWhenTheAdapterDoesNotDeclareThem(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        BreakpointStore store = new BreakpointStore(tempDir.resolve("state"));
        store.toggle(workspace, file, 3);
        store.configure(workspace, file, 3, true, "value > 1", "", "");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("setBreakpoints", response("setBreakpoints", true, Map.of("breakpoints", List.of()), ""));

        DebugSessionService.Result result = service.start(workspace, file, validation("launch,breakpoints"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> connection, store);

        assertTrue(result.succeeded());
        assertEquals(List.of(), ((List<?>) connection.arguments.get(2).get("breakpoints")));
        BreakpointStore.Breakpoint rejected = store.sources(workspace).get(file).getFirst();
        assertEquals(BreakpointStore.State.REJECTED, rejected.state());
        assertTrue(rejected.message().contains("condition"));
    }

    @Test
    void rejectsRichBreakpointSettingsWhenTheAdapterDoesNotAdvertiseThemAtInitialize(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        BreakpointStore store = new BreakpointStore(tempDir.resolve("state"));
        store.toggle(workspace, file, 3);
        store.configure(workspace, file, 3, true, "value > 1", "", "");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true, Map.of(), ""));
        connection.responses.put("setBreakpoints", response("setBreakpoints", true, Map.of("breakpoints", List.of()), ""));

        DebugSessionService.Result result = service.start(workspace, file,
            validation("launch,breakpoints,conditional_breakpoints"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> connection, store);

        assertTrue(result.succeeded());
        assertEquals(List.of(), ((List<?>) connection.arguments.get(2).get("breakpoints")));
        BreakpointStore.Breakpoint rejected = store.sources(workspace).get(file).getFirst();
        assertEquals(BreakpointStore.State.REJECTED, rejected.state());
        assertTrue(rejected.message().contains("initialize response"));
    }

    @Test
    void sendsAdapterDefaultExceptionBreakpointFiltersAndPersistsExplicitOverrides(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        ExceptionBreakpointStore store = new ExceptionBreakpointStore(tempDir.resolve("state"));
        store.configure(workspace, "raised", true);
        store.configure(workspace, "uncaught", false);
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true, Map.of("exceptionBreakpointFilters", List.of(
            Map.of("filter", "raised", "label", "Raised Exceptions", "default", false),
            Map.of("filter", "uncaught", "label", "Uncaught Exceptions", "default", true))), ""));
        connection.responses.put("setExceptionBreakpoints", response("setExceptionBreakpoints", true, Map.of(), ""));

        DebugSessionService.Result result = service.start(workspace, file, validation("launch,exception_breakpoints"), enabled(), "main",
            Duration.ofSeconds(1), (plan, features, listener) -> connection, null, store);

        assertTrue(result.succeeded());
        assertEquals(List.of("initialize", "launch", "setExceptionBreakpoints"), connection.commands);
        assertEquals(List.of("raised"), connection.arguments.get(2).get("filters"));
        assertEquals(List.of(new DebugSessionService.ExceptionFilter("raised", "Raised Exceptions", false),
            new DebugSessionService.ExceptionFilter("uncaught", "Uncaught Exceptions", true)), service.exceptionFilters(workspace));
    }

    @Test
    void synchronizesPersistedFunctionBreakpointsOnlyAfterTheAdapterAdvertisesSupport(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        FunctionBreakpointStore store = new FunctionBreakpointStore(tempDir.resolve("state"));
        store.add(workspace, "main");
        store.configure(workspace, "main", true, "count > 2", "5");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsFunctionBreakpoints", true,
            "supportsConditionalBreakpoints", true, "supportsHitConditionalBreakpoints", true), ""));
        connection.responses.put("setFunctionBreakpoints", response("setFunctionBreakpoints", true, Map.of("breakpoints", List.of(Map.of("verified", true))), ""));

        DebugSessionService.Result result = service.start(workspace, file,
            validation("launch,function_breakpoints,conditional_breakpoints,hit_conditional_breakpoints"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, listener) -> connection, null, null, store);

        assertTrue(result.succeeded());
        assertEquals(List.of("initialize", "launch", "setFunctionBreakpoints"), connection.commands);
        assertEquals(List.of(Map.of("name", "main", "condition", "count > 2", "hitCondition", "5")), connection.arguments.get(2).get("breakpoints"));
        assertEquals(FunctionBreakpointStore.State.VERIFIED, store.breakpoints(workspace).getFirst().state());
    }

    @Test
    void resolvesAndSynchronizesDataBreakpointsOnlyAfterTheAdapterAdvertisesSupport(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        DataBreakpointStore store = new DataBreakpointStore(tempDir.resolve("state"));
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsDataBreakpoints", true), ""));
        connection.responses.put("dataBreakpointInfo", response("dataBreakpointInfo", true, Map.of("dataId", "variable:counter",
            "description", "counter", "accessTypes", List.of("read", "write")), ""));
        connection.responses.put("setDataBreakpoints", response("setDataBreakpoints", true,
            Map.of("breakpoints", List.of(Map.of("verified", true))), ""));

        DebugSessionService.Result started = service.start(workspace, new DebugAdapterRegistry.LaunchContext(file, "", null),
            validation("launch,data_breakpoints"), enabled(), "main", Duration.ofSeconds(1), (plan, features, listener) -> connection,
            null, null, null, store, null);
        DebugSessionService.DataBreakpointResult added = service.addDataBreakpoint(workspace, 55, "counter", DataBreakpointStore.AccessType.WRITE,
            store, Duration.ofSeconds(1));

        assertTrue(started.succeeded());
        assertTrue(added.succeeded());
        assertEquals(List.of("initialize", "launch", "setDataBreakpoints", "dataBreakpointInfo", "setDataBreakpoints"), connection.commands);
        assertEquals(List.of(Map.of("dataId", "variable:counter", "accessType", "write")), connection.arguments.getLast().get("breakpoints"));
        assertEquals(DataBreakpointStore.State.VERIFIED, store.breakpoints(workspace).getFirst().state());
    }

    @Test
    void refusesDataBreakpointLookupWhenInitializeDoesNotAdvertiseSupport(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        DataBreakpointStore store = new DataBreakpointStore(tempDir.resolve("state"));
        FakeConnection connection = new FakeConnection();

        assertTrue(service.start(workspace, new DebugAdapterRegistry.LaunchContext(file, "", null), validation("launch,data_breakpoints"), enabled(),
            "main", Duration.ofSeconds(1), (plan, features, listener) -> connection, null, null, null, store, null).succeeded());
        DebugSessionService.DataBreakpointResult result = service.addDataBreakpoint(workspace, 55, "counter", DataBreakpointStore.AccessType.WRITE,
            store, Duration.ofSeconds(1));

        assertFalse(result.succeeded());
        assertTrue(result.snapshot().detail().contains("Data breakpoints are unavailable"));
        assertFalse(connection.commands.contains("dataBreakpointInfo"));
    }

    @Test
    void doesNotSynchronizeFunctionBreakpointsWithoutInitializeSupport(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        FunctionBreakpointStore store = new FunctionBreakpointStore(tempDir.resolve("state"));
        store.add(workspace, "main");
        FakeConnection connection = new FakeConnection();

        DebugSessionService.Result result = service.start(workspace, file, validation("launch,function_breakpoints"), enabled(), "main",
            Duration.ofSeconds(1), (plan, features, listener) -> connection, null, null, store);

        assertTrue(result.succeeded());
        assertEquals(List.of("initialize", "launch"), connection.commands);
        assertEquals(FunctionBreakpointStore.State.REQUESTED, store.breakpoints(workspace).getFirst().state());
    }

    @Test
    void sendsFunctionBreakpointsBeforeConfigurationDoneAfterInitialized(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.go");
        FunctionBreakpointStore store = new FunctionBreakpointStore(tempDir.resolve("state"));
        store.add(workspace, "main.main");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsFunctionBreakpoints", true,
            "supportsConfigurationDoneRequest", true), ""));
        connection.responses.put("setFunctionBreakpoints", response("setFunctionBreakpoints", true,
            Map.of("breakpoints", List.of(Map.of("verified", true))), ""));
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();
        connection.requestHook = command -> {
            if ("launch".equals(command)) listener.get().onEvent(new DebugAdapterTransport.Event(1, "initialized", Map.of()));
        };

        DebugSessionService.Result result = service.start(workspace, file, validation("launch,function_breakpoints,configuration_done"), enabled(),
            "main", Duration.ofSeconds(1), (plan, features, value) -> { listener.set(value); return connection; }, null, null, store);

        assertTrue(result.succeeded());
        assertEquals(List.of("initialize", "launch", "setFunctionBreakpoints", "configurationDone"), connection.commands);
    }

    @Test
    void clearsFunctionBreakpointsAtTheAdapterWhenEveryPersistedEntryIsDisabled(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.go");
        FunctionBreakpointStore store = new FunctionBreakpointStore(tempDir.resolve("state"));
        store.add(workspace, "main.main");
        store.configure(workspace, "main.main", false, "", "");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true, Map.of("supportsFunctionBreakpoints", true), ""));
        connection.responses.put("setFunctionBreakpoints", response("setFunctionBreakpoints", true, Map.of("breakpoints", List.of()), ""));

        DebugSessionService.Result result = service.start(workspace, file, validation("launch,function_breakpoints"), enabled(), "main",
            Duration.ofSeconds(1), (plan, features, listener) -> connection, null, null, store);

        assertTrue(result.succeeded());
        assertEquals(List.of(), connection.arguments.get(2).get("breakpoints"));
        assertFalse(store.breakpoints(workspace).getFirst().enabled());
    }

    @Test
    void doesNotSendExceptionBreakpointConfigurationWithoutAdapterFilters(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        ExceptionBreakpointStore store = new ExceptionBreakpointStore(tempDir.resolve("state"));
        store.configure(workspace, "uncaught", true);
        FakeConnection connection = new FakeConnection();
        connection.responses.put("initialize", response("initialize", true, Map.of("exceptionBreakpointFilters", List.of()), ""));

        DebugSessionService.Result result = service.start(workspace, file, validation("launch,exception_breakpoints"), enabled(), "main",
            Duration.ofSeconds(1), (plan, features, listener) -> connection, null, store);

        assertTrue(result.succeeded());
        assertEquals(List.of("initialize", "launch"), connection.commands);
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

    @Test
    void loadsPausedFrameScopesVariablesAndWatchesOnlyForDeclaredCapabilities(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("threads", response("threads", true, Map.of("threads", List.of(Map.of("id", 7, "name", "main"))), ""));
        connection.responses.put("stackTrace", response("stackTrace", true, Map.of("stackFrames", List.of(Map.of("id", 44, "name", "main",
            "source", Map.of("path", file.toString()), "line", 8, "column", 1))), ""));
        connection.responses.put("scopes", response("scopes", true, Map.of("scopes", List.of(Map.of("name", "Locals", "variablesReference", 55))), ""));
        connection.responses.put("variables", response("variables", true, Map.of("variables", List.of(Map.of("name", "count", "value", "2", "type", "int"))), ""));
        connection.responses.put("evaluate", response("evaluate", true, Map.of("result", "2", "type", "int"), ""));
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();

        assertTrue(service.start(workspace, file, validation("launch,threads,stack_trace,scopes,variables,evaluate"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));
        assertTrue(service.addWatch(workspace, "count").succeeded());

        DebugSessionService.InspectionResult result = service.refreshInspection(workspace, Duration.ofSeconds(1));

        assertTrue(result.succeeded());
        assertEquals(DebugInspection.State.READY, result.snapshot().state());
        assertEquals(44, result.snapshot().frameId());
        assertEquals("count", result.snapshot().scopes().getFirst().variables().getFirst().name());
        assertEquals(DebugInspection.WatchState.READY, result.snapshot().watches().getFirst().state());
        assertEquals(List.of("initialize", "launch", "threads", "stackTrace", "scopes", "variables", "evaluate"), connection.commands);
    }

    @Test
    void rejectsVariableMutationWhenTheAdapterDoesNotAdvertiseItAtInitialization(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("threads", response("threads", true, Map.of("threads", List.of(Map.of("id", 7, "name", "main"))), ""));
        connection.responses.put("stackTrace", response("stackTrace", true, Map.of("stackFrames", List.of(Map.of("id", 44, "name", "main"))), ""));
        connection.responses.put("scopes", response("scopes", true, Map.of("scopes", List.of(Map.of("name", "Locals", "variablesReference", 55))), ""));
        connection.responses.put("variables", response("variables", true,
            Map.of("variables", List.of(Map.of("name", "count", "value", "2", "type", "int"))), ""));
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();

        assertTrue(service.start(workspace, file, validation("launch,threads,stack_trace,scopes,variables,set_variable"), enabled(), "main",
            Duration.ofSeconds(1), (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));
        assertTrue(service.refreshInspection(workspace, Duration.ofSeconds(1)).succeeded());

        DebugSessionService.VariableMutationResult result = service.setVariable(workspace, 55, "count", "9", Duration.ofSeconds(1));

        assertFalse(result.succeeded());
        assertTrue(result.mutation().message().contains("did not advertise"));
        assertFalse(connection.commands.contains("setVariable"));
    }

    @Test
    void leavesPausedInspectionUnavailableWithoutUndeclaredRequests(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();

        assertTrue(service.start(workspace, file, validation("launch"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));
        service.addWatch(workspace, "count");

        DebugSessionService.InspectionResult result = service.refreshInspection(workspace, Duration.ofSeconds(1));

        assertTrue(result.succeeded());
        assertEquals(DebugInspection.State.UNAVAILABLE, result.snapshot().state());
        assertEquals(DebugInspection.WatchState.UNAVAILABLE, result.snapshot().watches().getFirst().state());
        assertEquals(List.of("initialize", "launch"), connection.commands);
    }

    @Test
    void stopsInspectionRequestsWhenThePausedStateChanges(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("threads", response("threads", true, Map.of("threads", List.of(Map.of("id", 7, "name", "main"))), ""));
        connection.responses.put("stackTrace", response("stackTrace", true, Map.of("stackFrames", List.of(Map.of("id", 44, "name", "main"))), ""));
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();
        connection.requestHook = command -> {
            if ("stackTrace".equals(command)) listener.get().onEvent(new DebugAdapterTransport.Event(2, "continued", Map.of("threadId", 7)));
        };

        assertTrue(service.start(workspace, file, validation("launch,threads,stack_trace,scopes,variables"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));

        assertFalse(service.refreshInspection(workspace, Duration.ofSeconds(1)).succeeded());
        assertFalse(service.inspection(workspace).paused());
        assertEquals(List.of("initialize", "launch", "threads", "stackTrace"), connection.commands);
    }

    @Test
    void reloadsScopesForOnlyTheSelectedActiveFrame(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        connection.responses.put("stackTrace", response("stackTrace", true, Map.of("stackFrames", List.of(
            Map.of("id", 44, "name", "main"), Map.of("id", 45, "name", "caller"))), ""));
        connection.responses.put("scopes", response("scopes", true, Map.of("scopes", List.of()), ""));
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();

        assertTrue(service.start(workspace, file, validation("launch,stack_trace,scopes"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "stopped", Map.of("reason", "breakpoint", "threadId", 7)));
        assertTrue(service.refreshInspection(workspace, Duration.ofSeconds(1)).succeeded());
        assertTrue(service.selectFrame(workspace, 45).succeeded());

        assertTrue(service.refreshInspection(workspace, Duration.ofSeconds(1)).succeeded());
        int scopesIndex = connection.commands.lastIndexOf("scopes");
        assertEquals(45, connection.arguments.get(scopesIndex).get("frameId"));
    }

    @Test
    void retainsOrderedDapOutputAndReportsAConsoleDisconnect(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();
        AtomicReference<DebugAdapterTransport.Listener> listener = new AtomicReference<>();

        assertTrue(service.start(workspace, file, validation("launch"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> { listener.set(value); return connection; }).succeeded());
        listener.get().onEvent(new DebugAdapterTransport.Event(1, "output", Map.of("category", "stdout", "output", "one\n")));
        listener.get().onEvent(new DebugAdapterTransport.Event(2, "output", Map.of("category", "stderr", "output", "two\n")));
        listener.get().onEvent(new DebugAdapterTransport.Event(3, "terminated", Map.of()));

        DebugConsole.Snapshot snapshot = service.console(workspace);
        assertEquals(DebugConsole.State.DISCONNECTED, snapshot.state());
        assertEquals("[stdout] one\n[stderr] two\n", snapshot.output());
        assertEquals(2, snapshot.events());
    }

    @Test
    void consoleDetectsTransportDisconnectionWhenOpened(@TempDir Path tempDir) throws Exception {
        DebugSessionService service = new DebugSessionService();
        Path workspace = tempDir.resolve("workspace");
        Path file = workspace.resolve("Main.java");
        FakeConnection connection = new FakeConnection();

        assertTrue(service.start(workspace, file, validation("launch"), enabled(), "main", Duration.ofSeconds(1),
            (plan, features, value) -> connection).succeeded());
        connection.close();

        assertEquals(DebugConsole.State.DISCONNECTED, service.console(workspace).state());
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

    private static DebugAdapterRegistry.Validation validationWithPreLaunch(String task) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("debug.adapter.java.command", "java-debug-adapter");
        values.put("debug.adapter.java.capabilities", "launch");
        values.put("debug.configuration.main.adapter", "java");
        values.put("debug.configuration.main.request", "launch");
        values.put("debug.configuration.main.scope", "workspace");
        values.put("debug.configuration.main.program", "${file}");
        values.put("debug.configuration.main.cwd", "${workspaceFolder}");
        values.put("debug.configuration.main.prelaunch_task", task);
        return DebugAdapterRegistry.validate(values);
    }

    private static DebugAdapterRegistry.Validation validationWithTarget(String target, String value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("debug.adapter.java.command", "java-debug-adapter");
        values.put("debug.adapter.java.capabilities", "launch");
        values.put("debug.configuration.main.adapter", "java");
        values.put("debug.configuration.main.request", "launch");
        values.put("debug.configuration.main.scope", "workspace");
        values.put("debug.configuration.main." + target, value);
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
        private final Path localRoot;
        private final String remoteRoot;

        FakeConnection() { this(null, ""); }
        FakeConnection(Path localRoot, String remoteRoot) {
            this.localRoot = localRoot == null ? null : localRoot.toAbsolutePath().normalize();
            this.remoteRoot = remoteRoot == null ? "" : remoteRoot;
        }

        @Override public DebugAdapterTransport.Response request(String command, Map<String, Object> arguments, Duration timeout) {
            commands.add(command); this.arguments.add(arguments);
            if (requestHook != null) requestHook.accept(command);
            return responses.getOrDefault(command, response(command, true, ""));
        }
        @Override public DebugAdapterTransport.State state() { return closed ? DebugAdapterTransport.State.CLOSED : DebugAdapterTransport.State.RUNNING; }
        @Override public String adapterPath(Path localPath) {
            if (localRoot == null || localPath == null) return DebugSessionService.Connection.super.adapterPath(localPath);
            Path path = localPath.toAbsolutePath().normalize();
            return path.startsWith(localRoot) ? remoteRoot + (localRoot.relativize(path).toString().isEmpty() ? "" : "/" + localRoot.relativize(path).toString().replace('\\', '/')) : null;
        }
        @Override public Path localPath(String adapterPath) {
            if (localRoot == null || adapterPath == null || !adapterPath.startsWith(remoteRoot)) return null;
            String suffix = adapterPath.substring(remoteRoot.length()).replaceFirst("^/", "");
            return localRoot.resolve(suffix).normalize();
        }
        @Override public void close() { closed = true; }
    }
}
