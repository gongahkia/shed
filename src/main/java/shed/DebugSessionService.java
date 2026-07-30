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
import java.util.concurrent.TimeoutException;

final class DebugSessionService {
    enum Lifecycle { IDLE, STARTING, RUNNING, STOPPED, FAILED }

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
        Connection connection = null;
        try {
            Connection started = Objects.requireNonNull(starter, "starter").start(plan, settings, new DebugAdapterTransport.Listener() {
                @Override public void onDiagnostic(DebugAdapterTransport.Diagnostic diagnostic) { addDiagnostic(root, diagnostic.code() + ": " + diagnostic.message()); }
            });
            connection = started;
            DebugAdapterTransport.Response initialize = connection.request("initialize", initializeArguments(plan), timeout);
            if (!initialize.success()) throw new IOException(responseFailure("initialize", initialize));
            String command = plan.configuration().request() == DebugAdapterRegistry.Request.LAUNCH ? "launch" : "attach";
            DebugAdapterTransport.Response request = connection.request(command, startArguments(plan), timeout);
            if (!request.success()) throw new IOException(responseFailure(command, request));
            synchronized (this) {
                if (session.generation != generation || session.lifecycle != Lifecycle.STARTING) {
                    connection.close();
                    return new Result(snapshot(root, session), false);
                }
                session.connection = connection;
                session.lifecycle = Lifecycle.RUNNING;
                session.detail = "Debug " + command + " request succeeded for '" + name + "'.";
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

    private synchronized void addDiagnostic(Path workspace, String diagnostic) {
        Session session = session(root(workspace));
        session.diagnostics.add(diagnostic == null ? "Debug adapter diagnostic" : diagnostic);
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
