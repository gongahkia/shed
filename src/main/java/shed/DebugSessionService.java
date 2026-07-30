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

final class DebugSessionService {
    enum Lifecycle { IDLE, STARTING, RUNNING, STOPPED, FAILED }
    private record BreakpointSynchronization(boolean attempted, List<String> diagnostics) { }

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
        return start(workspace, activeFile, validation, features, requestedConfiguration, timeout, starter, null);
    }

    Result start(Path workspace, Path activeFile, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
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
            DebugAdapterRegistry.PlanResult planned = DebugAdapterRegistry.plan(validation, name, root, activeFile);
            if (!planned.launchable()) return fail(root, session, planned.error(), List.of(planned.error()));
            if (session.lifecycle == Lifecycle.RUNNING || session.lifecycle == Lifecycle.STARTING) {
                return fail(root, session, "A debug session is already active; use :debug restart or :debug stop", List.of());
            }
            session.configuration = name;
            session.lifecycle = Lifecycle.STARTING;
            session.detail = "Starting debug configuration '" + name + "'.";
            session.diagnostics.clear();
            generation = ++session.generation;
            plan = planned.plan();
        }
        CompletableFuture<Void> initialized = new CompletableFuture<>();
        Connection connection = null;
        try {
            Connection started = Objects.requireNonNull(starter, "starter").start(plan, settings, new DebugAdapterTransport.Listener() {
                @Override public void onEvent(DebugAdapterTransport.Event event) {
                    if (event != null && "initialized".equals(event.event())) initialized.complete(null);
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
        session.generation++;
        if (connection != null) connection.close();
        session.lifecycle = Lifecycle.STOPPED;
        session.detail = "Debug session stopped.";
        return new Result(snapshot(root, session), true);
    }

    synchronized Snapshot snapshot(Path workspace) {
        Path root = root(workspace);
        Session session = session(root);
        if (session.lifecycle == Lifecycle.RUNNING && session.connection != null && session.connection.state() != DebugAdapterTransport.State.RUNNING) {
            session.lifecycle = Lifecycle.FAILED;
            session.detail = "Debug adapter transport stopped unexpectedly.";
            session.diagnostics.add(session.detail);
            session.connection = null;
        }
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

    private synchronized void addDiagnostic(Path workspace, String diagnostic) {
        Session session = session(root(workspace));
        session.diagnostics.add(diagnostic == null ? "Debug adapter diagnostic" : diagnostic);
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
            return Map.of("host", configuration.host(), "port", configuration.port(), "cwd", plan.cwd().toString(), "args", configuration.args());
        }
        return Map.of("program", plan.program().toString(), "cwd", plan.cwd().toString(), "args", configuration.args());
    }
}
