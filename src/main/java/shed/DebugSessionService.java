package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

final class DebugSessionService {
    enum Lifecycle { IDLE, STARTING, RUNNING, STOPPED, FAILED }
    private record BreakpointSynchronization(boolean attempted, List<String> diagnostics) { }
    record InspectionResult(DebugInspection.Snapshot snapshot, boolean succeeded) { }
    record ConsoleResult(DebugConsole.Snapshot snapshot, boolean succeeded) { }
    private record InspectionPayload(List<DebugInspection.ThreadInfo> threads, List<DebugInspection.Frame> frames, int frameId,
        List<DebugInspection.Scope> scopes, List<DebugInspection.Watch> watches, String detail, DebugInspection.State state) { }

    interface Connection extends AutoCloseable {
        DebugAdapterTransport.Response request(String command, Map<String, Object> arguments, Duration timeout)
            throws IOException, TimeoutException, InterruptedException;
        DebugAdapterTransport.State state();
        @Override void close();
    }

    interface Starter {
        Connection start(DebugAdapterRegistry.Plan plan, DebugFeatureSettings features, DebugAdapterTransport.Listener listener) throws IOException;
    }

    record Snapshot(Path workspace, String configuration, Lifecycle lifecycle, String detail, List<String> diagnostics) {
        Snapshot {
            workspace = workspace == null ? null : workspace.toAbsolutePath().normalize();
            configuration = configuration == null ? "" : configuration;
            lifecycle = lifecycle == null ? Lifecycle.IDLE : lifecycle;
            detail = detail == null ? "" : detail;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    record Result(Snapshot snapshot, boolean succeeded) { }

    private static final class Session {
        private String configuration = "";
        private Lifecycle lifecycle = Lifecycle.IDLE;
        private String detail = "No debug session selected.";
        private final List<String> diagnostics = new ArrayList<>();
        private Connection connection;
        private DebugAdapterRegistry.Plan plan;
        private DebugFeatureSettings features;
        private final DebugInspection inspection = new DebugInspection();
        private final DebugConsole console = new DebugConsole();
        private long generation;
    }

    private final Map<Path, Session> sessions = new LinkedHashMap<>();

    synchronized Result select(Path workspace, DebugAdapterRegistry.Validation validation, String configuration) {
        Path root = root(workspace);
        Session session = session(root);
        if (validation == null || !validation.valid()) return fail(root, session, "Debug configuration is invalid", validationErrors(validation));
        String name = configuration == null ? "" : configuration.trim();
        if (!validation.configurations().containsKey(name)) return fail(root, session, "Debug configuration is unavailable: " + name, List.of());
        session.configuration = name;
        if (session.lifecycle != Lifecycle.RUNNING && session.lifecycle != Lifecycle.STARTING) {
            session.lifecycle = Lifecycle.IDLE;
            session.detail = "Selected debug configuration '" + name + "'. Start remains explicit.";
        }
        return new Result(snapshot(root, session), true);
    }

    Result start(Path workspace, Path activeFile, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter) {
        return start(workspace, new DebugAdapterRegistry.LaunchContext(activeFile, "", null), validation, features, requestedConfiguration, timeout, starter, null);
    }

    Result start(Path workspace, Path activeFile, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore) {
        return start(workspace, new DebugAdapterRegistry.LaunchContext(activeFile, "", null), validation, features, requestedConfiguration, timeout, starter, breakpointStore);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore) {
        Path root = root(workspace);
        DebugFeatureSettings settings = features == null ? DebugFeatureSettings.defaults() : features;
        String name;
        DebugAdapterRegistry.Plan plan;
        Session session;
        long generation;
        synchronized (this) {
            session = session(root);
            name = requestedConfiguration == null || requestedConfiguration.isBlank() ? session.configuration : requestedConfiguration.trim();
            if (!settings.enabled()) return fail(root, session, "Debugging is disabled by settings", List.of("Set debug.enabled=true before launch."));
            if (validation == null || !validation.valid()) return fail(root, session, "Debug configuration is invalid", validationErrors(validation));
            if (name.isBlank()) return fail(root, session, "Select a debug configuration before launch", List.of("Use :debug select <name>."));
            DebugAdapterRegistry.PlanResult planned = DebugAdapterRegistry.plan(validation, name, root, context);
            if (!planned.launchable()) return fail(root, session, planned.error(), List.of(planned.error()));
            if (session.lifecycle == Lifecycle.RUNNING || session.lifecycle == Lifecycle.STARTING) {
                return fail(root, session, "A debug session is already active; use :debug restart or :debug stop", List.of());
            }
            session.configuration = name;
            session.lifecycle = Lifecycle.STARTING;
            session.detail = "Starting debug configuration '" + name + "'.";
            session.diagnostics.clear();
            session.console.start();
            generation = ++session.generation;
            plan = planned.plan();
        }
        CompletableFuture<Void> initialized = new CompletableFuture<>();
        Connection connection = null;
        try {
            Connection started = Objects.requireNonNull(starter, "starter").start(plan, settings, new DebugAdapterTransport.Listener() {
                @Override public void onEvent(DebugAdapterTransport.Event event) {
                    if (event != null && "initialized".equals(event.event())) initialized.complete(null);
                    handleEvent(root, event);
                }
                @Override public void onDiagnostic(DebugAdapterTransport.Diagnostic diagnostic) { addDiagnostic(root, diagnostic.code() + ": " + diagnostic.message()); }
            });
            connection = started;
            DebugAdapterTransport.Response initialize = connection.request("initialize", initializeArguments(plan), timeout);
            if (!initialize.success()) throw new IOException(responseFailure("initialize", initialize));
            String command = plan.configuration().request() == DebugAdapterRegistry.Request.LAUNCH ? "launch" : "attach";
            Connection activeConnection = connection;
            CompletableFuture<DebugAdapterTransport.Response> startRequest = CompletableFuture.supplyAsync(() -> {
                try {
                    return activeConnection.request(command, startArguments(plan), timeout);
                } catch (IOException | TimeoutException | InterruptedException error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CompletionException(error);
                }
            });
            boolean configurationReady = waitForInitializationOrStart(initialized, startRequest, timeout);
            List<String> synchronizationDiagnostics = new ArrayList<>();
            if (configurationReady) {
                synchronizationDiagnostics.addAll(synchronizeBreakpoints(plan, settings, connection, breakpointStore, timeout).diagnostics());
                if (supportsConfigurationDone(plan, initialize)) {
                    DebugAdapterTransport.Response configurationDone = connection.request("configurationDone", Map.of(), timeout);
                    if (!configurationDone.success()) throw new IOException(responseFailure("configurationDone", configurationDone));
                }
            }
            DebugAdapterTransport.Response request = await(startRequest, timeout);
            if (!request.success()) throw new IOException(responseFailure(command, request));
            if (!configurationReady) {
                BreakpointSynchronization synchronization = synchronizeBreakpoints(plan, settings, connection, breakpointStore, timeout);
                if (synchronization.attempted()) {
                    synchronizationDiagnostics.add("Debug adapter did not emit initialized; source breakpoints synchronized after " + command + ".");
                }
                synchronizationDiagnostics.addAll(synchronization.diagnostics());
            }
            synchronized (this) {
                if (session.generation != generation || session.lifecycle != Lifecycle.STARTING) {
                    connection.close();
                    return new Result(snapshot(root, session), false);
                }
                session.connection = connection;
                session.plan = plan;
                session.features = settings;
                session.lifecycle = Lifecycle.RUNNING;
                session.detail = "Debug " + command + " request succeeded for '" + name + "'.";
                session.diagnostics.addAll(synchronizationDiagnostics);
                return new Result(snapshot(root, session), true);
            }
        } catch (IOException | TimeoutException error) {
            if (connection != null) connection.close();
            synchronized (this) { return fail(root, session, "Debug start failed: " + message(error), List.of(message(error))); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (connection != null) connection.close();
            synchronized (this) { return fail(root, session, "Debug start interrupted", List.of("Debug start interrupted.")); }
        } catch (RuntimeException error) {
            if (connection != null) connection.close();
            synchronized (this) { return fail(root, session, "Debug start failed: " + message(error), List.of(message(error))); }
        }
    }

    synchronized Result stop(Path workspace) {
        Path root = root(workspace);
        Session session = session(root);
        Connection connection = session.connection;
        session.connection = null;
        session.plan = null;
        session.features = null;
        session.inspection.invalidated("Debug session stopped.");
        session.console.stopped();
        session.generation++;
        if (connection != null) connection.close();
        session.lifecycle = Lifecycle.STOPPED;
        session.detail = "Debug session stopped.";
        return new Result(snapshot(root, session), true);
    }

    synchronized Snapshot snapshot(Path workspace) {
        Path root = root(workspace);
        Session session = session(root);
        reconcileTransport(session);
        return snapshot(root, session);
    }

    synchronized List<Snapshot> snapshots() {
        List<Snapshot> result = new ArrayList<>();
        for (Map.Entry<Path, Session> entry : sessions.entrySet()) result.add(snapshot(entry.getKey(), entry.getValue()));
        result.sort(Comparator.comparing(snapshot -> snapshot.workspace() == null ? "" : snapshot.workspace().toString()));
        return result;
    }

    Result synchronizeBreakpoints(Path workspace, BreakpointStore breakpointStore, Duration timeout) {
        Path root = root(workspace);
        Connection connection;
        DebugAdapterRegistry.Plan plan;
        DebugFeatureSettings settings;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null) {
                return new Result(snapshot(root, session), false);
            }
            connection = session.connection;
            plan = session.plan;
            settings = session.features;
        }
        List<String> diagnostics = synchronizeBreakpoints(plan, settings, connection, breakpointStore, timeout).diagnostics();
        synchronized (this) {
            Session session = session(root);
            if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING) return new Result(snapshot(root, session), false);
            session.diagnostics.addAll(diagnostics);
            return new Result(snapshot(root, session), true);
        }
    }

    synchronized DebugInspection.Snapshot inspection(Path workspace) {
        return session(root(workspace)).inspection.snapshot();
    }

    synchronized DebugConsole.Snapshot console(Path workspace) {
        Path root = root(workspace);
        Session session = session(root);
        reconcileTransport(session);
        return session.console.snapshot();
    }

    synchronized ConsoleResult clearConsole(Path workspace) {
        Session session = session(root(workspace));
        session.console.clear();
        return new ConsoleResult(session.console.snapshot(), true);
    }

    synchronized InspectionResult addWatch(Path workspace, String expression) {
        DebugInspection.Result result = session(root(workspace)).inspection.addWatch(expression);
        return new InspectionResult(result.snapshot(), result.succeeded());
    }

    synchronized InspectionResult removeWatch(Path workspace, String expression) {
        DebugInspection.Result result = session(root(workspace)).inspection.removeWatch(expression);
        return new InspectionResult(result.snapshot(), result.succeeded());
    }

    synchronized InspectionResult clearWatches(Path workspace) {
        DebugInspection.Result result = session(root(workspace)).inspection.clearWatches();
        return new InspectionResult(result.snapshot(), result.succeeded());
    }

    synchronized InspectionResult selectFrame(Path workspace, int frameId) {
        DebugInspection.Result result = session(root(workspace)).inspection.selectFrame(frameId);
        return new InspectionResult(result.snapshot(), result.succeeded());
    }

    InspectionResult refreshInspection(Path workspace, Duration timeout) {
        Path root = root(workspace);
        Connection connection;
        DebugAdapterRegistry.Plan plan;
        DebugFeatureSettings settings;
        DebugInspection.Load load;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null) {
                return new InspectionResult(session.inspection.snapshot(), false);
            }
            load = session.inspection.beginLoad();
            if (load == null) return new InspectionResult(session.inspection.snapshot(), false);
            connection = session.connection;
            plan = session.plan;
            settings = session.features;
        }
        try {
            InspectionPayload payload = loadInspection(plan, settings, connection, load, timeout,
                () -> inspectionLoading(root, connection, load.generation()));
            synchronized (this) {
                Session session = session(root);
                boolean applied = session.connection == connection && session.inspection.complete(load.generation(), payload.threads(), payload.frames(),
                    payload.frameId(), payload.scopes(), payload.watches(), payload.detail(), payload.state());
                return new InspectionResult(session.inspection.snapshot(), applied);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) {
                Session session = session(root);
                boolean applied = session.connection == connection && session.inspection.failed(load.generation(), "Paused-frame inspection failed: " + message(error),
                    DebugInspection.State.ERROR);
                if (applied) session.diagnostics.add("Paused-frame inspection failed: " + message(error));
                return new InspectionResult(session.inspection.snapshot(), false);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                Session session = session(root);
                boolean applied = session.connection == connection && session.inspection.failed(load.generation(), "Paused-frame inspection interrupted.",
                    DebugInspection.State.ERROR);
                if (applied) session.diagnostics.add("Paused-frame inspection interrupted.");
                return new InspectionResult(session.inspection.snapshot(), false);
            }
        }
    }

    private synchronized void addDiagnostic(Path workspace, String diagnostic) {
        Session session = session(root(workspace));
        session.diagnostics.add(diagnostic == null ? "Debug adapter diagnostic" : diagnostic);
    }

    private static void reconcileTransport(Session session) {
        if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.connection.state() == DebugAdapterTransport.State.RUNNING) return;
        session.lifecycle = Lifecycle.FAILED;
        session.detail = "Debug adapter transport stopped unexpectedly.";
        session.diagnostics.add(session.detail);
        session.connection = null;
        session.inspection.invalidated(session.detail);
        session.console.disconnected(session.detail);
    }

    private synchronized void handleEvent(Path workspace, DebugAdapterTransport.Event event) {
        if (event == null) return;
        Session session = session(root(workspace));
        if ("stopped".equals(event.event())) {
            Map<String, Object> body = MiniJson.asObject(event.body());
            int threadId = integer(body == null ? null : body.get("threadId"));
            String reason = string(body == null ? null : body.get("reason"));
            String description = string(body == null ? null : body.get("description"));
            session.inspection.stopped(threadId, reason, description);
        } else if ("output".equals(event.event())) {
            Map<String, Object> body = MiniJson.asObject(event.body());
            String output = body == null ? null : MiniJson.asString(body.get("output"));
            if (output == null) session.diagnostics.add("DAP output event is missing text.");
            else session.console.append(string(body.get("category")), output);
        } else if ("continued".equals(event.event()) || "terminated".equals(event.event()) || "exited".equals(event.event())) {
            session.inspection.invalidated("Debug execution " + event.event() + ".");
            if ("terminated".equals(event.event()) || "exited".equals(event.event())) session.console.disconnected("Debug adapter " + event.event() + ".");
        }
    }

    private synchronized boolean inspectionLoading(Path workspace, Connection connection, long generation) {
        Session session = session(root(workspace));
        return session.connection == connection && session.inspection.loading(generation);
    }

    private static InspectionPayload loadInspection(DebugAdapterRegistry.Plan plan, DebugFeatureSettings settings, Connection connection,
        DebugInspection.Load load, Duration timeout, BooleanSupplier active) throws IOException, TimeoutException, InterruptedException {
        requireActive(active);
        List<DebugInspection.ThreadInfo> threads = supports(plan, settings, DebugAdapterRegistry.Capability.THREADS)
            ? threads(connection, timeout, active) : List.of();
        requireActive(active);
        if (!supports(plan, settings, DebugAdapterRegistry.Capability.STACK_TRACE)) {
            return new InspectionPayload(threads, List.of(), 0, List.of(), unavailableWatches(load.watches(), "No paused frame is available."),
                "Stack traces are unavailable for the active adapter.", DebugInspection.State.UNAVAILABLE);
        }
        int threadId = load.threadId();
        if (threadId < 1 && !threads.isEmpty()) threadId = threads.getFirst().id();
        if (threadId < 1) {
            return new InspectionPayload(threads, List.of(), 0, List.of(), unavailableWatches(load.watches(), "No paused frame is available."),
                "The stopped event did not identify a thread.", DebugInspection.State.UNAVAILABLE);
        }
        List<DebugInspection.Frame> frames = frames(connection, threadId, timeout, active);
        requireActive(active);
        if (frames.isEmpty()) {
            return new InspectionPayload(threads, frames, 0, List.of(), unavailableWatches(load.watches(), "No paused frame is available."),
                "The adapter returned no stack frames.", DebugInspection.State.UNAVAILABLE);
        }
        int frameId = frames.stream().anyMatch(frame -> frame.id() == load.frameId()) ? load.frameId() : frames.getFirst().id();
        List<DebugInspection.Scope> scopes = supports(plan, settings, DebugAdapterRegistry.Capability.SCOPES)
            ? scopes(connection, frameId, supports(plan, settings, DebugAdapterRegistry.Capability.VARIABLES), timeout, active) : List.of();
        requireActive(active);
        List<DebugInspection.Watch> watches = supports(plan, settings, DebugAdapterRegistry.Capability.EVALUATE)
            ? watches(connection, load.watches(), frameId, timeout, active) : unavailableWatches(load.watches(), "Evaluate is unavailable for this adapter.");
        List<String> unavailable = new ArrayList<>();
        if (!supports(plan, settings, DebugAdapterRegistry.Capability.THREADS)) unavailable.add("thread list unavailable");
        if (!supports(plan, settings, DebugAdapterRegistry.Capability.SCOPES)) unavailable.add("scopes unavailable");
        else if (!supports(plan, settings, DebugAdapterRegistry.Capability.VARIABLES)) unavailable.add("variables unavailable");
        if (!supports(plan, settings, DebugAdapterRegistry.Capability.EVALUATE)) unavailable.add("watch evaluation unavailable");
        String detail = "Paused thread " + threadId + ", frame " + frameId + "." + (unavailable.isEmpty() ? "" : " " + String.join("; ", unavailable) + ".");
        return new InspectionPayload(threads, frames, frameId, scopes, watches, detail, DebugInspection.State.READY);
    }

    private static List<DebugInspection.ThreadInfo> threads(Connection connection, Duration timeout, BooleanSupplier active)
        throws IOException, TimeoutException, InterruptedException {
        requireActive(active);
        Map<String, Object> body = body(connection.request("threads", Map.of(), timeout), "threads");
        List<Object> values = MiniJson.asArray(body.get("threads"));
        if (values == null) throw new IOException("DAP threads response is missing threads");
        List<DebugInspection.ThreadInfo> threads = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> thread = MiniJson.asObject(value);
            int id = integer(thread == null ? null : thread.get("id"));
            if (id < 1) throw new IOException("DAP threads response contains an invalid thread id");
            threads.add(new DebugInspection.ThreadInfo(id, string(thread.get("name"))));
        }
        return List.copyOf(threads);
    }

    private static List<DebugInspection.Frame> frames(Connection connection, int threadId, Duration timeout, BooleanSupplier active)
        throws IOException, TimeoutException, InterruptedException {
        requireActive(active);
        Map<String, Object> body = body(connection.request("stackTrace", Map.of("threadId", threadId), timeout), "stackTrace");
        List<Object> values = MiniJson.asArray(body.get("stackFrames"));
        if (values == null) throw new IOException("DAP stackTrace response is missing stackFrames");
        List<DebugInspection.Frame> frames = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> frame = MiniJson.asObject(value);
            int id = integer(frame == null ? null : frame.get("id"));
            if (id < 1) throw new IOException("DAP stackTrace response contains an invalid frame id");
            Map<String, Object> source = MiniJson.asObject(frame.get("source"));
            String path = source == null ? "" : string(source.get("path"));
            if (path.isBlank() && source != null) path = string(source.get("name"));
            frames.add(new DebugInspection.Frame(id, string(frame.get("name")), path, integer(frame.get("line")), integer(frame.get("column"))));
        }
        return List.copyOf(frames);
    }

    private static List<DebugInspection.Scope> scopes(Connection connection, int frameId, boolean variablesEnabled, Duration timeout, BooleanSupplier active)
        throws IOException, TimeoutException, InterruptedException {
        requireActive(active);
        Map<String, Object> body = body(connection.request("scopes", Map.of("frameId", frameId), timeout), "scopes");
        List<Object> values = MiniJson.asArray(body.get("scopes"));
        if (values == null) throw new IOException("DAP scopes response is missing scopes");
        List<DebugInspection.Scope> scopes = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> scope = MiniJson.asObject(value);
            if (scope == null) throw new IOException("DAP scopes response contains an invalid scope");
            int reference = integer(scope.get("variablesReference"));
            List<DebugInspection.Variable> variables = variablesEnabled && reference > 0 ? variables(connection, reference, timeout, active) : List.of();
            scopes.add(new DebugInspection.Scope(string(scope.get("name")), reference, Boolean.TRUE.equals(scope.get("expensive")), variables));
        }
        return List.copyOf(scopes);
    }

    private static List<DebugInspection.Variable> variables(Connection connection, int reference, Duration timeout, BooleanSupplier active)
        throws IOException, TimeoutException, InterruptedException {
        requireActive(active);
        Map<String, Object> body = body(connection.request("variables", Map.of("variablesReference", reference), timeout), "variables");
        List<Object> values = MiniJson.asArray(body.get("variables"));
        if (values == null) throw new IOException("DAP variables response is missing variables");
        List<DebugInspection.Variable> variables = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> variable = MiniJson.asObject(value);
            if (variable == null) throw new IOException("DAP variables response contains an invalid variable");
            variables.add(new DebugInspection.Variable(string(variable.get("name")), string(variable.get("value")), string(variable.get("type")),
                integer(variable.get("variablesReference"))));
        }
        return List.copyOf(variables);
    }

    private static List<DebugInspection.Watch> watches(Connection connection, List<String> expressions, int frameId, Duration timeout, BooleanSupplier active)
        throws IOException, TimeoutException, InterruptedException {
        List<DebugInspection.Watch> watches = new ArrayList<>();
        for (String expression : expressions) {
            requireActive(active);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("expression", expression);
            arguments.put("context", "watch");
            arguments.put("frameId", frameId);
            DebugAdapterTransport.Response response = connection.request("evaluate", arguments, timeout);
            if (!response.success()) {
                watches.add(new DebugInspection.Watch(expression, DebugInspection.WatchState.ERROR, "", "", response.message()));
                continue;
            }
            Map<String, Object> body = MiniJson.asObject(response.body());
            String result = body == null ? null : MiniJson.asString(body.get("result"));
            if (result == null) watches.add(new DebugInspection.Watch(expression, DebugInspection.WatchState.ERROR, "", "", "DAP evaluate response is missing result"));
            else watches.add(new DebugInspection.Watch(expression, DebugInspection.WatchState.READY, result, string(body.get("type")), ""));
        }
        return List.copyOf(watches);
    }

    private static List<DebugInspection.Watch> unavailableWatches(List<String> expressions, String detail) {
        return expressions.stream().map(expression -> new DebugInspection.Watch(expression, DebugInspection.WatchState.UNAVAILABLE, "", "", detail)).toList();
    }

    private static void requireActive(BooleanSupplier active) throws IOException {
        if (active == null || !active.getAsBoolean()) throw new IOException("Paused-frame state changed before inspection completed");
    }

    private static Map<String, Object> body(DebugAdapterTransport.Response response, String command) throws IOException {
        if (response == null || !response.success()) throw new IOException(responseFailure(command, response));
        Map<String, Object> body = MiniJson.asObject(response.body());
        if (body == null) throw new IOException("DAP " + command + " response body is invalid");
        return body;
    }

    private static boolean supports(DebugAdapterRegistry.Plan plan, DebugFeatureSettings settings, DebugAdapterRegistry.Capability capability) {
        if (plan == null || settings == null || !plan.adapter().capabilities().contains(capability)) return false;
        return switch (capability) {
            case THREADS -> settings.threads();
            case STACK_TRACE -> settings.stackTrace();
            case SCOPES -> settings.scopes();
            case VARIABLES -> settings.variables();
            case EVALUATE -> settings.evaluate();
            default -> true;
        };
    }

    private static int integer(Object value) {
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue() || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE) return 0;
        return (int) number.longValue();
    }

    private static String string(Object value) {
        String string = MiniJson.asString(value);
        return string == null ? "" : string;
    }

    private static BreakpointSynchronization synchronizeBreakpoints(DebugAdapterRegistry.Plan plan, DebugFeatureSettings settings, Connection connection,
        BreakpointStore breakpointStore, Duration timeout) {
        if (breakpointStore == null || settings == null || !settings.breakpoints() || plan == null || connection == null
            || !plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.BREAKPOINTS)) return new BreakpointSynchronization(false, List.of());
        List<String> diagnostics = new ArrayList<>();
        try {
            Map<Path, List<BreakpointStore.Breakpoint>> sources = breakpointStore.sources(plan.workspace());
            if (sources.isEmpty()) return new BreakpointSynchronization(false, diagnostics);
            for (Map.Entry<Path, List<BreakpointStore.Breakpoint>> entry : sources.entrySet()) {
                List<Map<String, Object>> requested = entry.getValue().stream().map(breakpoint -> Map.<String, Object>of("line", breakpoint.line())).toList();
                DebugAdapterTransport.Response response = connection.request("setBreakpoints",
                    Map.of("source", Map.of("path", entry.getKey().toString()), "breakpoints", requested), timeout);
                if (!response.success()) {
                    diagnostics.add("DAP setBreakpoints failed for " + entry.getKey() + (response.message().isBlank() ? "." : ": " + response.message()));
                    continue;
                }
                diagnostics.addAll(breakpointStore.apply(plan.workspace(), entry.getKey(), entry.getValue(), response.body()).diagnostics());
            }
        } catch (IOException | TimeoutException error) {
            diagnostics.add("Source breakpoint synchronization failed: " + message(error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            diagnostics.add("Source breakpoint synchronization interrupted.");
        }
        return new BreakpointSynchronization(true, diagnostics);
    }

    private static boolean waitForInitializationOrStart(CompletableFuture<Void> initialized, CompletableFuture<DebugAdapterTransport.Response> start,
        Duration timeout) throws IOException, TimeoutException, InterruptedException {
        try {
            CompletableFuture.anyOf(initialized, start).get(Math.max(1L, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
            return initialized.isDone();
        } catch (ExecutionException error) {
            throw executionFailure(error);
        }
    }

    private static DebugAdapterTransport.Response await(CompletableFuture<DebugAdapterTransport.Response> response, Duration timeout)
        throws IOException, TimeoutException, InterruptedException {
        try {
            return response.get(Math.max(1L, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            throw executionFailure(error);
        }
    }

    private static IOException executionFailure(ExecutionException error) throws InterruptedException {
        Throwable cause = error.getCause();
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof InterruptedException interrupted) throw interrupted;
        if (cause instanceof IOException io) return io;
        if (cause instanceof TimeoutException timeout) return new IOException(message(timeout), timeout);
        return new IOException("Debug adapter request failed", cause);
    }

    private static boolean supportsConfigurationDone(DebugAdapterRegistry.Plan plan, DebugAdapterTransport.Response initialize) {
        if (!plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.CONFIGURATION_DONE)) return false;
        Map<String, Object> capabilities = initialize == null ? null : MiniJson.asObject(initialize.body());
        return Boolean.TRUE.equals(capabilities == null ? null : capabilities.get("supportsConfigurationDoneRequest"));
    }

    private Result fail(Path root, Session session, String detail, List<String> diagnostics) {
        session.connection = null;
        session.lifecycle = Lifecycle.FAILED;
        session.detail = detail == null ? "Debug session failed." : detail;
        if (session.console.snapshot().state() == DebugConsole.State.CONNECTED) session.console.failed(session.detail);
        if (diagnostics != null) for (String diagnostic : diagnostics) if (diagnostic != null && !diagnostic.isBlank()) session.diagnostics.add(diagnostic);
        return new Result(snapshot(root, session), false);
    }

    private Session session(Path workspace) { return sessions.computeIfAbsent(workspace, ignored -> new Session()); }
    private static Path root(Path workspace) { return (workspace == null ? Path.of(".") : workspace).toAbsolutePath().normalize(); }
    private static Snapshot snapshot(Path workspace, Session session) { return new Snapshot(workspace, session.configuration, session.lifecycle, session.detail, session.diagnostics); }
    private static List<String> validationErrors(DebugAdapterRegistry.Validation validation) {
        return validation == null ? List.of("Debug configuration has not been loaded.") : validation.errors().stream().map(DebugAdapterRegistry.Error::message).toList();
    }
    private static String message(Throwable error) { return error == null || error.getMessage() == null ? "Unknown debug adapter failure" : error.getMessage(); }
    private static String responseFailure(String command, DebugAdapterTransport.Response response) {
        String detail = response == null ? "no response" : response.message();
        return "DAP " + command + " failed" + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
    private static Map<String, Object> initializeArguments(DebugAdapterRegistry.Plan plan) {
        return Map.of("clientID", "shed", "clientName", "Shed", "adapterID", plan.adapter().id(), "pathFormat", "path",
            "linesStartAt1", true, "columnsStartAt1", true);
    }
    private static Map<String, Object> startArguments(DebugAdapterRegistry.Plan plan) {
        DebugAdapterRegistry.Configuration configuration = plan.configuration();
        if (configuration.request() == DebugAdapterRegistry.Request.ATTACH) {
            return Map.of("host", configuration.host(), "port", configuration.port(), "cwd", plan.cwd().toString(), "args", plan.args());
        }
        return Map.of("program", plan.program().toString(), "cwd", plan.cwd().toString(), "args", plan.args());
    }
}
