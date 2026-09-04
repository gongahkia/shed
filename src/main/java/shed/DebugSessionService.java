package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

final class DebugSessionService {
    enum Lifecycle { IDLE, STARTING, RUNNING, STOPPED, FAILED }
    enum Control {
        CONTINUE("continue", DebugAdapterRegistry.Capability.CONTINUE, true),
        NEXT("next", DebugAdapterRegistry.Capability.NEXT, true),
        STEP_IN("stepIn", DebugAdapterRegistry.Capability.STEP_IN, true),
        STEP_OUT("stepOut", DebugAdapterRegistry.Capability.STEP_OUT, true),
        PAUSE("pause", DebugAdapterRegistry.Capability.PAUSE, false);

        private final String dapCommand;
        private final DebugAdapterRegistry.Capability capability;
        private final boolean requiresPause;

        Control(String dapCommand, DebugAdapterRegistry.Capability capability, boolean requiresPause) {
            this.dapCommand = dapCommand;
            this.capability = capability;
            this.requiresPause = requiresPause;
        }
    }
    private record BreakpointSynchronization(boolean attempted, List<String> diagnostics) { }
    private record SourceBreakpointRequest(List<BreakpointStore.Breakpoint> breakpoints, List<Map<String, Object>> arguments) { }
    record ExceptionFilter(String id, String label, boolean defaultEnabled) {
        ExceptionFilter {
            id = id == null ? "" : id.trim();
            label = label == null ? "" : label.trim();
            if (!id.matches("[A-Za-z0-9._-]{1,128}") || label.isEmpty() || label.length() > 512) {
                throw new IllegalArgumentException("exception breakpoint filter is invalid");
            }
        }
    }
    record InspectionResult(DebugInspection.Snapshot snapshot, boolean succeeded) { }
    record ConsoleResult(DebugConsole.Snapshot snapshot, boolean succeeded) { }
    record ControlResult(Snapshot snapshot, boolean succeeded) { }
    record RunToCursorResult(Snapshot snapshot, boolean succeeded) { }
    private record InspectionPayload(List<DebugInspection.ThreadInfo> threads, List<DebugInspection.Frame> frames, int frameId,
        List<DebugInspection.Scope> scopes, List<DebugInspection.Watch> watches, String detail, DebugInspection.State state) { }

    interface Connection extends AutoCloseable {
        DebugAdapterTransport.Response request(String command, Map<String, Object> arguments, Duration timeout)
            throws IOException, TimeoutException, InterruptedException;
        DebugAdapterTransport.State state();
        default String adapterPath(Path localPath) { return localPath == null ? null : localPath.toAbsolutePath().normalize().toString(); }
        default Path localPath(String adapterPath) { return null; }
        @Override void close();
    }

    interface Starter {
        Connection start(DebugAdapterRegistry.Plan plan, DebugFeatureSettings features, DebugAdapterTransport.Listener listener) throws IOException;
    }

    interface PreLaunch {
        PreLaunchResult run(DebugAdapterRegistry.Plan plan) throws IOException;
    }

    record PreLaunchResult(boolean succeeded, List<String> diagnostics) {
        PreLaunchResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
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
        private Set<DebugAdapterRegistry.Capability> runtimeCapabilities = Set.of();
        private List<ExceptionFilter> exceptionFilters = List.of();
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

    Result start(Path workspace, Path activeFile, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore) {
        return start(workspace, new DebugAdapterRegistry.LaunchContext(activeFile, "", null), validation, features, requestedConfiguration, timeout,
            starter, breakpointStore, exceptionBreakpointStore);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore) {
        return start(workspace, context, validation, features, requestedConfiguration, timeout, starter, breakpointStore, null);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore) {
        return start(workspace, context, validation, features, requestedConfiguration, timeout, starter, breakpointStore, exceptionBreakpointStore, null);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore,
        PreLaunch preLaunch) {
        Path root = root(workspace);
        DebugFeatureSettings settings = features == null ? DebugFeatureSettings.defaults() : features;
        String name;
        DebugAdapterRegistry.Plan plan;
        Session session;
        long generation;
        synchronized (this) {
            session = session(root);
            name = requestedConfiguration == null || requestedConfiguration.isBlank() ? session.configuration : requestedConfiguration.trim();
            if (name.isBlank()) name = contextualConfiguration(validation, context);
            if (!settings.enabled()) return fail(root, session, "Debugging is disabled by settings", List.of("Set debug.enabled=true before launch."));
            if (validation == null || !validation.valid()) return fail(root, session, "Debug configuration is invalid", validationErrors(validation));
            if (name.isBlank()) return fail(root, session, "Select a debug configuration before launch", List.of("Use :debug select <name>, or open a .py/.pyw file for the built-in Python profile."));
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
            session.runtimeCapabilities = Set.of();
            generation = ++session.generation;
            plan = planned.plan();
        }
        CompletableFuture<Void> initialized = new CompletableFuture<>();
        Connection connection = null;
        try {
            List<String> preLaunchDiagnostics = List.of();
            if (!plan.configuration().prelaunchTask().isBlank()) {
                if (preLaunch == null) {
                    synchronized (this) {
                        return fail(root, session, "Debug pre-launch task is unavailable", List.of("Debug configuration requires prelaunch task '"
                            + plan.configuration().prelaunchTask() + "'."));
                    }
                }
                PreLaunchResult result = preLaunch.run(plan);
                if (result == null || !result.succeeded()) {
                    List<String> diagnostics = result == null ? List.of("Debug pre-launch task returned no result.") : result.diagnostics();
                    synchronized (this) {
                        return fail(root, session, "Debug pre-launch task failed: " + plan.configuration().prelaunchTask(), diagnostics);
                    }
                }
                preLaunchDiagnostics = result.diagnostics();
            }
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
            Set<DebugAdapterRegistry.Capability> runtimeCapabilities = runtimeCapabilities(plan, initialize);
            List<ExceptionFilter> exceptionFilters = exceptionFilters(plan, initialize);
            String command = plan.configuration().request() == DebugAdapterRegistry.Request.LAUNCH ? "launch" : "attach";
            Connection activeConnection = connection;
            CompletableFuture<DebugAdapterTransport.Response> startRequest = CompletableFuture.supplyAsync(() -> {
                try {
                    return activeConnection.request(command, startArguments(plan, activeConnection), timeout);
                } catch (IOException | TimeoutException | InterruptedException error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CompletionException(error);
                }
            });
            boolean configurationReady = waitForInitializationOrStart(initialized, startRequest, timeout);
            List<String> synchronizationDiagnostics = new ArrayList<>(preLaunchDiagnostics);
            if (configurationReady) {
                synchronizationDiagnostics.addAll(synchronizeBreakpoints(plan, settings, runtimeCapabilities, connection, breakpointStore, timeout).diagnostics());
                synchronizationDiagnostics.addAll(synchronizeExceptionBreakpoints(plan, settings, exceptionFilters, connection,
                    exceptionBreakpointStore, timeout).diagnostics());
                if (supportsConfigurationDone(plan, initialize)) {
                    DebugAdapterTransport.Response configurationDone = connection.request("configurationDone", Map.of(), timeout);
                    if (!configurationDone.success()) throw new IOException(responseFailure("configurationDone", configurationDone));
                }
            }
            DebugAdapterTransport.Response request = await(startRequest, timeout);
            if (!request.success()) throw new IOException(responseFailure(command, request));
            if (!configurationReady) {
                BreakpointSynchronization synchronization = synchronizeBreakpoints(plan, settings, runtimeCapabilities, connection, breakpointStore, timeout);
                if (synchronization.attempted()) {
                    synchronizationDiagnostics.add("Debug adapter did not emit initialized; source breakpoints synchronized after " + command + ".");
                }
                synchronizationDiagnostics.addAll(synchronization.diagnostics());
                synchronizationDiagnostics.addAll(synchronizeExceptionBreakpoints(plan, settings, exceptionFilters, connection,
                    exceptionBreakpointStore, timeout).diagnostics());
            }
            synchronized (this) {
                if (session.generation != generation || session.lifecycle != Lifecycle.STARTING) {
                    connection.close();
                    return new Result(snapshot(root, session), false);
                }
                session.connection = connection;
                session.plan = plan;
                session.features = settings;
                session.runtimeCapabilities = runtimeCapabilities;
                session.exceptionFilters = exceptionFilters;
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
        session.runtimeCapabilities = Set.of();
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

    synchronized List<ExceptionFilter> exceptionFilters(Path workspace) {
        return session(root(workspace)).exceptionFilters;
    }

    synchronized List<Snapshot> snapshots() {
        List<Snapshot> result = new ArrayList<>();
        for (Map.Entry<Path, Session> entry : sessions.entrySet()) result.add(snapshot(entry.getKey(), entry.getValue()));
        result.sort(Comparator.comparing(snapshot -> snapshot.workspace() == null ? "" : snapshot.workspace().toString()));
        return result;
    }

    Result synchronizeBreakpoints(Path workspace, BreakpointStore breakpointStore, Duration timeout) {
        return synchronizeBreakpoints(workspace, breakpointStore, null, timeout);
    }

    Result synchronizeBreakpoints(Path workspace, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore, Duration timeout) {
        Path root = root(workspace);
        Connection connection;
        DebugAdapterRegistry.Plan plan;
        DebugFeatureSettings settings;
        Set<DebugAdapterRegistry.Capability> runtimeCapabilities;
        List<ExceptionFilter> exceptionFilters;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null) {
                return new Result(snapshot(root, session), false);
            }
            connection = session.connection;
            plan = session.plan;
            settings = session.features;
            runtimeCapabilities = session.runtimeCapabilities;
            exceptionFilters = session.exceptionFilters;
        }
        List<String> diagnostics = new ArrayList<>(synchronizeBreakpoints(plan, settings, runtimeCapabilities, connection, breakpointStore, timeout).diagnostics());
        diagnostics.addAll(synchronizeExceptionBreakpoints(plan, settings, exceptionFilters, connection, exceptionBreakpointStore, timeout).diagnostics());
        synchronized (this) {
            Session session = session(root);
            if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING) return new Result(snapshot(root, session), false);
            session.diagnostics.addAll(diagnostics);
            return new Result(snapshot(root, session), true);
        }
    }

    ControlResult control(Path workspace, Control control, Duration timeout) {
        Path root = root(workspace);
        Control requested = control;
        Connection connection;
        DebugAdapterRegistry.Plan plan;
        DebugInspection.Snapshot inspection;
        synchronized (this) {
            Session session = session(root);
            if (requested == null) return controlFailure(root, session, "Debug control is unavailable.");
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null) {
                return controlFailure(root, session, "No running debug session is available.");
            }
            if (!session.plan.adapter().capabilities().contains(requested.capability)) {
                return controlFailure(root, session, "Debug adapter does not declare support for " + requested.dapCommand + ".");
            }
            inspection = session.inspection.snapshot();
            if (requested.requiresPause && (!inspection.paused() || inspection.threadId() < 1)) {
                return controlFailure(root, session, "Debug " + requested.dapCommand + " requires a paused thread.");
            }
            if (requested == Control.PAUSE && inspection.paused()) {
                return controlFailure(root, session, "Debug session is already paused.");
            }
            if (requested == Control.PAUSE && !session.plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.THREADS)) {
                return controlFailure(root, session, "Debug pause requires the adapter to declare threads support.");
            }
            connection = session.connection;
            plan = session.plan;
        }
        try {
            int threadId = requested == Control.PAUSE ? firstThreadId(connection, timeout) : inspection.threadId();
            if (threadId < 1) {
                synchronized (this) {
                    return controlFailure(root, session(root), "Debug " + requested.dapCommand + " requires an available thread.");
                }
            }
            DebugAdapterTransport.Response response = connection.request(requested.dapCommand, Map.of("threadId", threadId), timeout);
            if (!response.success()) {
                synchronized (this) {
                    return controlFailure(root, session(root), responseFailure(requested.dapCommand, response));
                }
            }
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING || session.plan != plan) {
                    return new ControlResult(snapshot(root, session), false);
                }
                if (requested != Control.PAUSE) session.inspection.invalidated("Debug " + requested.dapCommand + " requested.");
                session.detail = "Debug " + requested.dapCommand + " requested.";
                return new ControlResult(snapshot(root, session), true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) {
                return controlFailure(root, session(root), "Debug " + requested.dapCommand + " failed: " + message(error));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                return controlFailure(root, session(root), "Debug " + requested.dapCommand + " interrupted.");
            }
        }
    }

    RunToCursorResult runToCursor(Path workspace, Path source, int line, int column, Duration timeout) {
        Path root = root(workspace);
        Path requestedSource = source == null ? null : source.toAbsolutePath().normalize();
        Connection connection;
        DebugAdapterRegistry.Plan plan;
        DebugInspection.Snapshot inspection;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null) {
                return runToCursorFailure(root, session, "No running debug session is available.");
            }
            if (!session.plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.GOTO)) {
                return runToCursorFailure(root, session, "Debug adapter does not declare support for run to cursor.");
            }
            if (!session.runtimeCapabilities.contains(DebugAdapterRegistry.Capability.GOTO)) {
                return runToCursorFailure(root, session, "Debug adapter did not advertise gotoTargets support during initialization.");
            }
            if (requestedSource == null || !requestedSource.startsWith(root) || line < 1 || column < 1) {
                return runToCursorFailure(root, session, "Run to cursor requires a positive location in the active workspace file.");
            }
            inspection = session.inspection.snapshot();
            if (!inspection.paused() || inspection.threadId() < 1) {
                return runToCursorFailure(root, session, "Run to cursor requires a paused thread.");
            }
            connection = session.connection;
            plan = session.plan;
        }
        try {
            String adapterSource = requireAdapterPath(connection, requestedSource);
            DebugAdapterTransport.Response targets = connection.request("gotoTargets", Map.of("source", Map.of("path", adapterSource),
                "line", line, "column", column), timeout);
            int targetId = selectGotoTarget(targets, line, column);
            if (targetId < 1) {
                synchronized (this) {
                    return runToCursorFailure(root, session(root), "DAP gotoTargets returned no unambiguous target for the requested location.");
                }
            }
            DebugAdapterTransport.Response response = connection.request("goto", Map.of("threadId", inspection.threadId(), "targetId", targetId), timeout);
            if (!response.success()) {
                synchronized (this) {
                    return runToCursorFailure(root, session(root), responseFailure("goto", response));
                }
            }
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING || session.plan != plan) {
                    return new RunToCursorResult(snapshot(root, session), false);
                }
                session.inspection.invalidated("Debug run to cursor requested.");
                session.detail = "Debug run to cursor requested.";
                return new RunToCursorResult(snapshot(root, session), true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) {
                return runToCursorFailure(root, session(root), "Debug run to cursor failed: " + message(error));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                return runToCursorFailure(root, session(root), "Debug run to cursor interrupted.");
            }
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
            Path localPath = connection.localPath(path);
            if (localPath != null) path = localPath.toString();
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

    private static BreakpointSynchronization synchronizeBreakpoints(DebugAdapterRegistry.Plan plan, DebugFeatureSettings settings,
        Set<DebugAdapterRegistry.Capability> runtimeCapabilities, Connection connection, BreakpointStore breakpointStore, Duration timeout) {
        if (breakpointStore == null || settings == null || !settings.breakpoints() || plan == null || connection == null
            || !plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.BREAKPOINTS)) return new BreakpointSynchronization(false, List.of());
        List<String> diagnostics = new ArrayList<>();
        try {
            Map<Path, List<BreakpointStore.Breakpoint>> sources = breakpointStore.sources(plan.workspace());
            if (sources.isEmpty()) return new BreakpointSynchronization(false, diagnostics);
            for (Map.Entry<Path, List<BreakpointStore.Breakpoint>> entry : sources.entrySet()) {
                SourceBreakpointRequest requested = sourceBreakpointRequest(plan, runtimeCapabilities, breakpointStore, entry.getKey(), entry.getValue(), diagnostics);
                String adapterPath = requireAdapterPath(connection, entry.getKey());
                DebugAdapterTransport.Response response = connection.request("setBreakpoints",
                    Map.of("source", Map.of("path", adapterPath), "breakpoints", requested.arguments()), timeout);
                if (!response.success()) {
                    diagnostics.add("DAP setBreakpoints failed for " + entry.getKey() + (response.message().isBlank() ? "." : ": " + response.message()));
                    continue;
                }
                diagnostics.addAll(breakpointStore.apply(plan.workspace(), entry.getKey(), requested.breakpoints(), response.body()).diagnostics());
            }
        } catch (IOException | TimeoutException error) {
            diagnostics.add("Source breakpoint synchronization failed: " + message(error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            diagnostics.add("Source breakpoint synchronization interrupted.");
        }
        return new BreakpointSynchronization(true, diagnostics);
    }

    private static BreakpointSynchronization synchronizeExceptionBreakpoints(DebugAdapterRegistry.Plan plan, DebugFeatureSettings settings,
        List<ExceptionFilter> filters, Connection connection, ExceptionBreakpointStore exceptionBreakpointStore, Duration timeout) {
        if (exceptionBreakpointStore == null || settings == null || !settings.breakpoints() || plan == null || connection == null
            || !plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.EXCEPTION_BREAKPOINTS)) {
            return new BreakpointSynchronization(false, List.of());
        }
        List<ExceptionFilter> available = filters == null ? List.of() : filters;
        if (available.isEmpty()) return new BreakpointSynchronization(false, List.of());
        try {
            Map<String, ExceptionBreakpointStore.Setting> settingsByFilter = exceptionBreakpointStore.settings(plan.workspace());
            List<String> requested = new ArrayList<>();
            for (ExceptionFilter filter : available) {
                ExceptionBreakpointStore.Setting setting = settingsByFilter.get(filter.id());
                if (setting == null ? filter.defaultEnabled() : setting.enabled()) requested.add(filter.id());
            }
            DebugAdapterTransport.Response response = connection.request("setExceptionBreakpoints", Map.of("filters", List.copyOf(requested)), timeout);
            if (!response.success()) {
                return new BreakpointSynchronization(true, List.of("DAP setExceptionBreakpoints failed"
                    + (response.message().isBlank() ? "." : ": " + response.message())));
            }
            return new BreakpointSynchronization(true, exceptionBreakpointDiagnostics(response.body(), requested));
        } catch (IOException | TimeoutException error) {
            return new BreakpointSynchronization(true, List.of("Exception breakpoint synchronization failed: " + message(error)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new BreakpointSynchronization(true, List.of("Exception breakpoint synchronization interrupted."));
        }
    }

    private static List<String> exceptionBreakpointDiagnostics(Object responseBody, List<String> requested) {
        Map<String, Object> body = MiniJson.asObject(responseBody);
        List<Object> returned = MiniJson.asArray(body == null ? null : body.get("breakpoints"));
        if (returned == null) return List.of();
        List<String> diagnostics = new ArrayList<>();
        for (int index = 0; index < returned.size() && index < (requested == null ? 0 : requested.size()); index++) {
            Map<String, Object> breakpoint = MiniJson.asObject(returned.get(index));
            if (breakpoint == null || !Boolean.FALSE.equals(breakpoint.get("verified"))) continue;
            String detail = string(breakpoint.get("message"));
            diagnostics.add("Exception breakpoint '" + requested.get(index) + "' was rejected" + (detail.isBlank() ? "." : ": " + detail));
        }
        return List.copyOf(diagnostics);
    }

    private static SourceBreakpointRequest sourceBreakpointRequest(DebugAdapterRegistry.Plan plan, Set<DebugAdapterRegistry.Capability> runtimeCapabilities,
        BreakpointStore store, Path source, List<BreakpointStore.Breakpoint> breakpoints, List<String> diagnostics) throws IOException {
        List<BreakpointStore.Breakpoint> routed = new ArrayList<>();
        List<Map<String, Object>> arguments = new ArrayList<>();
        for (BreakpointStore.Breakpoint breakpoint : breakpoints == null ? List.<BreakpointStore.Breakpoint>of() : breakpoints) {
            if (!breakpoint.enabled()) continue;
            String unsupported = unsupportedBreakpointOption(plan.adapter(), runtimeCapabilities, breakpoint);
            if (unsupported != null) {
                String message = "Breakpoint option is unsupported by adapter " + plan.adapter().id() + ": " + unsupported + ".";
                store.reject(plan.workspace(), source, breakpoint, message);
                diagnostics.add(source + ":" + breakpoint.line() + " " + message);
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("line", breakpoint.line());
            if (!breakpoint.condition().isBlank()) value.put("condition", breakpoint.condition());
            if (!breakpoint.hitCondition().isBlank()) value.put("hitCondition", breakpoint.hitCondition());
            if (!breakpoint.logMessage().isBlank()) value.put("logMessage", breakpoint.logMessage());
            routed.add(breakpoint);
            arguments.add(Map.copyOf(value));
        }
        return new SourceBreakpointRequest(List.copyOf(routed), List.copyOf(arguments));
    }

    private static String unsupportedBreakpointOption(DebugAdapterRegistry.Adapter adapter, Set<DebugAdapterRegistry.Capability> runtimeCapabilities,
        BreakpointStore.Breakpoint breakpoint) {
        if (adapter == null || breakpoint == null) return "source breakpoint";
        if (!breakpoint.condition().isBlank()) {
            String unsupported = unsupportedCapability(adapter, runtimeCapabilities, DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS, "condition");
            if (unsupported != null) return unsupported;
        }
        if (!breakpoint.hitCondition().isBlank()) {
            String unsupported = unsupportedCapability(adapter, runtimeCapabilities, DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, "hit condition");
            if (unsupported != null) return unsupported;
        }
        if (!breakpoint.logMessage().isBlank()) {
            String unsupported = unsupportedCapability(adapter, runtimeCapabilities, DebugAdapterRegistry.Capability.LOG_POINTS, "log message");
            if (unsupported != null) return unsupported;
        }
        return null;
    }

    private static String unsupportedCapability(DebugAdapterRegistry.Adapter adapter, Set<DebugAdapterRegistry.Capability> runtimeCapabilities,
        DebugAdapterRegistry.Capability capability, String label) {
        if (!adapter.capabilities().contains(capability)) return label + " (not declared in adapter configuration)";
        if (runtimeCapabilities == null || !runtimeCapabilities.contains(capability)) return label + " (not advertised by adapter initialize response)";
        return null;
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

    private static String contextualConfiguration(DebugAdapterRegistry.Validation validation, DebugAdapterRegistry.LaunchContext context) {
        if (validation == null || !validation.valid() || context == null || context.activeFile() == null) return "";
        DebugAdapterRegistry.Configuration python = validation.configurations().get(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY);
        if (python == null || python.request() != DebugAdapterRegistry.Request.LAUNCH
            || !BuiltInDebugAdapterSupport.PYTHON_DEBUGPY.equals(python.adapter())) return "";
        String fileName = context.activeFile().getFileName() == null ? "" : context.activeFile().getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String extension : python.fileExtensions()) {
            if (extension != null && !extension.isBlank() && fileName.endsWith(extension.toLowerCase(java.util.Locale.ROOT))) return python.name();
        }
        return "";
    }

    private static List<ExceptionFilter> exceptionFilters(DebugAdapterRegistry.Plan plan, DebugAdapterTransport.Response initialize) throws IOException {
        if (plan == null || plan.adapter() == null || !plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.EXCEPTION_BREAKPOINTS)) {
            return List.of();
        }
        Map<String, Object> capabilities = initialize == null ? null : MiniJson.asObject(initialize.body());
        List<Object> values = MiniJson.asArray(capabilities == null ? null : capabilities.get("exceptionBreakpointFilters"));
        if (values == null || values.isEmpty()) return List.of();
        Map<String, ExceptionFilter> filters = new LinkedHashMap<>();
        for (Object value : values) {
            Map<String, Object> fields = MiniJson.asObject(value);
            String id = fields == null ? null : MiniJson.asString(fields.get("filter"));
            String label = fields == null ? null : MiniJson.asString(fields.get("label"));
            if (id == null || label == null) throw new IOException("DAP initialize returned an invalid exception breakpoint filter");
            ExceptionFilter filter;
            try {
                filter = new ExceptionFilter(id, label, Boolean.TRUE.equals(fields.get("default")));
            } catch (IllegalArgumentException error) {
                throw new IOException("DAP initialize returned an invalid exception breakpoint filter", error);
            }
            if (filters.put(filter.id(), filter) != null) throw new IOException("DAP initialize returned duplicate exception breakpoint filters");
        }
        return List.copyOf(filters.values());
    }

    private static Set<DebugAdapterRegistry.Capability> runtimeCapabilities(DebugAdapterRegistry.Plan plan, DebugAdapterTransport.Response initialize) {
        EnumSet<DebugAdapterRegistry.Capability> result = EnumSet.noneOf(DebugAdapterRegistry.Capability.class);
        if (plan == null || plan.adapter() == null) return Set.of();
        result.addAll(plan.adapter().capabilities());
        Map<String, Object> capabilities = initialize == null ? null : MiniJson.asObject(initialize.body());
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS, "supportsConditionalBreakpoints");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, "supportsHitConditionalBreakpoints");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.LOG_POINTS, "supportsLogPoints");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.GOTO, "supportsGotoTargetsRequest");
        return Set.copyOf(result);
    }

    private static void retainAdvertised(EnumSet<DebugAdapterRegistry.Capability> capabilities, Map<String, Object> initialize,
        DebugAdapterRegistry.Capability capability, String key) {
        if (capabilities.contains(capability) && !Boolean.TRUE.equals(initialize == null ? null : initialize.get(key))) capabilities.remove(capability);
    }

    private Result fail(Path root, Session session, String detail, List<String> diagnostics) {
        session.connection = null;
        session.runtimeCapabilities = Set.of();
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
    private static int firstThreadId(Connection connection, Duration timeout) throws IOException, TimeoutException, InterruptedException {
        DebugAdapterTransport.Response response = connection.request("threads", Map.of(), timeout);
        if (!response.success()) throw new IOException(responseFailure("threads", response));
        Map<String, Object> body = MiniJson.asObject(response.body());
        List<Object> values = MiniJson.asArray(body == null ? null : body.get("threads"));
        if (values == null) return 0;
        for (Object value : values) {
            Map<String, Object> thread = MiniJson.asObject(value);
            int id = integer(thread == null ? null : thread.get("id"));
            if (id > 0) return id;
        }
        return 0;
    }
    private static ControlResult controlFailure(Path root, Session session, String detail) {
        String message = detail == null || detail.isBlank() ? "Debug control failed." : detail;
        session.diagnostics.add(message);
        return new ControlResult(snapshot(root, session), false);
    }
    private static RunToCursorResult runToCursorFailure(Path root, Session session, String detail) {
        String message = detail == null || detail.isBlank() ? "Debug run to cursor failed." : detail;
        session.diagnostics.add(message);
        return new RunToCursorResult(snapshot(root, session), false);
    }
    private static int selectGotoTarget(DebugAdapterTransport.Response response, int line, int column) throws IOException {
        Map<String, Object> body = body(response, "gotoTargets");
        List<Object> values = MiniJson.asArray(body.get("targets"));
        if (values == null || values.isEmpty()) return 0;
        List<Integer> valid = new ArrayList<>();
        List<Integer> exactLine = new ArrayList<>();
        List<Integer> exactLocation = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> target = MiniJson.asObject(value);
            int id = integer(target == null ? null : target.get("id"));
            int targetLine = integer(target == null ? null : target.get("line"));
            int targetColumn = integer(target == null ? null : target.get("column"));
            if (id < 1 || targetLine < 1) continue;
            valid.add(id);
            if (targetLine == line) exactLine.add(id);
            if (targetLine == line && (targetColumn == 0 || targetColumn == column)) exactLocation.add(id);
        }
        if (exactLocation.size() == 1) return exactLocation.getFirst();
        if (exactLine.size() == 1) return exactLine.getFirst();
        return valid.size() == 1 ? valid.getFirst() : 0;
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
    private static Map<String, Object> startArguments(DebugAdapterRegistry.Plan plan, Connection connection) throws IOException {
        DebugAdapterRegistry.Configuration configuration = plan.configuration();
        if (configuration.request() == DebugAdapterRegistry.Request.ATTACH) {
            return Map.of("host", configuration.host(), "port", configuration.port(), "cwd", requireAdapterPath(connection, plan.cwd()), "args", plan.args());
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (!configuration.program().isBlank()) arguments.put("program", requireAdapterPath(connection, plan.program()));
        else if (!plan.module().isBlank()) arguments.put("module", plan.module());
        else if (!plan.code().isBlank()) arguments.put("code", plan.code());
        else throw new IOException("Debug launch target is unavailable");
        arguments.put("cwd", requireAdapterPath(connection, plan.cwd()));
        arguments.put("args", plan.args());
        return Map.copyOf(arguments);
    }

    private static String requireAdapterPath(Connection connection, Path localPath) throws IOException {
        String path = connection == null ? null : connection.adapterPath(localPath);
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IOException("Debug source path cannot be mapped into the adapter environment");
        }
        return path;
    }
}
