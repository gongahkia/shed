package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
        PAUSE("pause", DebugAdapterRegistry.Capability.PAUSE, false),
        REVERSE_CONTINUE("reverseContinue", DebugAdapterRegistry.Capability.REVERSE_CONTINUE, true),
        STEP_BACK("stepBack", DebugAdapterRegistry.Capability.STEP_BACK, true);

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
    record Evaluation(String expression, String result, String type, int variablesReference, String message) {
        Evaluation {
            expression = expression == null ? "" : expression;
            result = result == null ? "" : result;
            type = type == null ? "" : type;
            message = message == null ? "" : message;
        }
    }
    record EvaluationResult(DebugConsole.Snapshot console, Evaluation evaluation, boolean succeeded) { }
    record VariableMutation(String name, String value, String type, int variablesReference, String message) {
        VariableMutation {
            name = name == null ? "" : name;
            value = value == null ? "" : value;
            type = type == null ? "" : type;
            message = message == null ? "" : message;
        }
    }
    record VariableMutationResult(DebugConsole.Snapshot console, VariableMutation mutation, boolean succeeded) { }
    record ControlResult(Snapshot snapshot, boolean succeeded) { }
    record RunToCursorResult(Snapshot snapshot, boolean succeeded) { }
    record DataBreakpointInfo(String dataId, String description, List<DataBreakpointStore.AccessType> accessTypes) {
        DataBreakpointInfo {
            dataId = dataId == null ? "" : dataId;
            description = description == null ? "" : description;
            accessTypes = accessTypes == null ? List.of() : List.copyOf(accessTypes);
        }
    }
    record DataBreakpointResult(Snapshot snapshot, DataBreakpointInfo info, boolean succeeded) { }
    record ExceptionDetails(String exceptionId, String description, String breakMode, String typeName, String fullTypeName, String stackTrace) {
        ExceptionDetails {
            exceptionId = exceptionId == null ? "" : exceptionId;
            description = description == null ? "" : description;
            breakMode = breakMode == null ? "" : breakMode;
            typeName = typeName == null ? "" : typeName;
            fullTypeName = fullTypeName == null ? "" : fullTypeName;
            stackTrace = stackTrace == null ? "" : stackTrace;
        }
    }
    record ModuleInfo(String id, String name, String path, String version, String symbolStatus) {
        ModuleInfo {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            path = path == null ? "" : path;
            version = version == null ? "" : version;
            symbolStatus = symbolStatus == null ? "" : symbolStatus;
        }
    }
    record LoadedSource(String name, String path, int sourceReference, String origin, String presentationHint) {
        LoadedSource {
            name = name == null ? "" : name;
            path = path == null ? "" : path;
            origin = origin == null ? "" : origin;
            presentationHint = presentationHint == null ? "" : presentationHint;
        }
    }
    record ExceptionDetailsResult(Snapshot snapshot, ExceptionDetails details, boolean succeeded) { }
    record ModulesResult(Snapshot snapshot, List<ModuleInfo> modules, boolean succeeded) { }
    record LoadedSourcesResult(Snapshot snapshot, List<LoadedSource> sources, boolean succeeded) { }
    record MemoryRead(String address, int unreadableBytes, byte[] data) {
        MemoryRead {
            address = address == null ? "" : address;
            if (unreadableBytes < 0) throw new IllegalArgumentException("unreadable byte count is invalid");
            data = data == null ? new byte[0] : data.clone();
        }

        @Override public byte[] data() { return data.clone(); }
    }
    record MemoryReadResult(Snapshot snapshot, MemoryRead memory, boolean succeeded) { }
    record DisassembledInstruction(String address, String instructionBytes, String instruction, String symbol) {
        DisassembledInstruction {
            address = address == null ? "" : address;
            instructionBytes = instructionBytes == null ? "" : instructionBytes;
            instruction = instruction == null ? "" : instruction;
            symbol = symbol == null ? "" : symbol;
        }
    }
    record DisassemblyResult(Snapshot snapshot, List<DisassembledInstruction> instructions, boolean succeeded) {
        DisassemblyResult { instructions = instructions == null ? List.of() : List.copyOf(instructions); }
    }
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
            starter, breakpointStore, exceptionBreakpointStore, null);
    }

    Result start(Path workspace, Path activeFile, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore,
        FunctionBreakpointStore functionBreakpointStore) {
        return start(workspace, new DebugAdapterRegistry.LaunchContext(activeFile, "", null), validation, features, requestedConfiguration, timeout,
            starter, breakpointStore, exceptionBreakpointStore, functionBreakpointStore, null);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore) {
        return start(workspace, context, validation, features, requestedConfiguration, timeout, starter, breakpointStore, null);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore) {
        return start(workspace, context, validation, features, requestedConfiguration, timeout, starter, breakpointStore, exceptionBreakpointStore, null, null);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore,
        PreLaunch preLaunch) {
        return start(workspace, context, validation, features, requestedConfiguration, timeout, starter, breakpointStore, exceptionBreakpointStore,
            null, preLaunch);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore,
        FunctionBreakpointStore functionBreakpointStore, PreLaunch preLaunch) {
        return start(workspace, context, validation, features, requestedConfiguration, timeout, starter, breakpointStore, exceptionBreakpointStore,
            functionBreakpointStore, null, preLaunch);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore,
        FunctionBreakpointStore functionBreakpointStore, DataBreakpointStore dataBreakpointStore, PreLaunch preLaunch) {
        return start(workspace, context, validation, features, requestedConfiguration, timeout, starter, breakpointStore, exceptionBreakpointStore,
            functionBreakpointStore, dataBreakpointStore, null, preLaunch);
    }

    Result start(Path workspace, DebugAdapterRegistry.LaunchContext context, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features,
        String requestedConfiguration, Duration timeout, Starter starter, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore,
        FunctionBreakpointStore functionBreakpointStore, DataBreakpointStore dataBreakpointStore, InstructionBreakpointStore instructionBreakpointStore,
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
            if (name.isBlank()) return fail(root, session, "Select a debug configuration before launch", List.of(
                "Use :debug select <name>, or open a matching file for an available built-in profile."));
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
                synchronizationDiagnostics.addAll(synchronizeFunctionBreakpoints(plan, settings, runtimeCapabilities, connection,
                    functionBreakpointStore, timeout).diagnostics());
                synchronizationDiagnostics.addAll(synchronizeDataBreakpoints(plan, settings, runtimeCapabilities, connection,
                    dataBreakpointStore, timeout).diagnostics());
                synchronizationDiagnostics.addAll(synchronizeInstructionBreakpoints(plan, settings, runtimeCapabilities, connection,
                    instructionBreakpointStore, timeout).diagnostics());
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
                synchronizationDiagnostics.addAll(synchronizeFunctionBreakpoints(plan, settings, runtimeCapabilities, connection,
                    functionBreakpointStore, timeout).diagnostics());
                synchronizationDiagnostics.addAll(synchronizeDataBreakpoints(plan, settings, runtimeCapabilities, connection,
                    dataBreakpointStore, timeout).diagnostics());
                synchronizationDiagnostics.addAll(synchronizeInstructionBreakpoints(plan, settings, runtimeCapabilities, connection,
                    instructionBreakpointStore, timeout).diagnostics());
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
        return synchronizeBreakpoints(workspace, breakpointStore, null, null, timeout);
    }

    Result synchronizeBreakpoints(Path workspace, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore, Duration timeout) {
        return synchronizeBreakpoints(workspace, breakpointStore, exceptionBreakpointStore, null, timeout);
    }

    Result synchronizeBreakpoints(Path workspace, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore,
        FunctionBreakpointStore functionBreakpointStore, Duration timeout) {
        return synchronizeBreakpoints(workspace, breakpointStore, exceptionBreakpointStore, functionBreakpointStore, null, timeout);
    }

    Result synchronizeBreakpoints(Path workspace, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore,
        FunctionBreakpointStore functionBreakpointStore, DataBreakpointStore dataBreakpointStore, Duration timeout) {
        return synchronizeBreakpoints(workspace, breakpointStore, exceptionBreakpointStore, functionBreakpointStore, dataBreakpointStore, null, timeout);
    }

    Result synchronizeBreakpoints(Path workspace, BreakpointStore breakpointStore, ExceptionBreakpointStore exceptionBreakpointStore,
        FunctionBreakpointStore functionBreakpointStore, DataBreakpointStore dataBreakpointStore, InstructionBreakpointStore instructionBreakpointStore,
        Duration timeout) {
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
        diagnostics.addAll(synchronizeFunctionBreakpoints(plan, settings, runtimeCapabilities, connection, functionBreakpointStore, timeout).diagnostics());
        diagnostics.addAll(synchronizeDataBreakpoints(plan, settings, runtimeCapabilities, connection, dataBreakpointStore, timeout).diagnostics());
        diagnostics.addAll(synchronizeInstructionBreakpoints(plan, settings, runtimeCapabilities, connection, instructionBreakpointStore, timeout).diagnostics());
        diagnostics.addAll(synchronizeExceptionBreakpoints(plan, settings, exceptionFilters, connection, exceptionBreakpointStore, timeout).diagnostics());
        synchronized (this) {
            Session session = session(root);
            if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING) return new Result(snapshot(root, session), false);
            session.diagnostics.addAll(diagnostics);
            return new Result(snapshot(root, session), true);
        }
    }

    DataBreakpointResult addDataBreakpoint(Path workspace, int variablesReference, String name, DataBreakpointStore.AccessType preferredAccess,
        DataBreakpointStore dataBreakpointStore, Duration timeout) {
        Path root = root(workspace);
        Connection connection;
        DebugAdapterRegistry.Plan plan;
        DebugFeatureSettings settings;
        Set<DebugAdapterRegistry.Capability> runtimeCapabilities;
        String requestedName = name == null ? "" : name.trim();
        synchronized (this) {
            Session session = session(root);
            if (variablesReference < 1 || !validVariableText(requestedName, 512)) {
                return dataBreakpointFailure(root, session, "Data breakpoints require a displayed variable reference and name.");
            }
            if (dataBreakpointStore == null || session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null
                || !supports(session.plan, session.features, DebugAdapterRegistry.Capability.DATA_BREAKPOINTS)
                || !session.runtimeCapabilities.contains(DebugAdapterRegistry.Capability.DATA_BREAKPOINTS)) {
                return dataBreakpointFailure(root, session, "Data breakpoints are unavailable for the active adapter.");
            }
            connection = session.connection;
            plan = session.plan;
            settings = session.features;
            runtimeCapabilities = session.runtimeCapabilities;
        }
        try {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("variablesReference", variablesReference);
            arguments.put("name", requestedName);
            DebugAdapterTransport.Response response = connection.request("dataBreakpointInfo", Map.copyOf(arguments), timeout);
            if (!response.success()) {
                synchronized (this) { return dataBreakpointFailure(root, session(root), responseFailure("dataBreakpointInfo", response)); }
            }
            DataBreakpointInfo info = dataBreakpointInfo(response.body(), requestedName, preferredAccess);
            dataBreakpointStore.add(root, info.dataId(), info.description(), selectDataBreakpointAccess(info.accessTypes(), preferredAccess));
            BreakpointSynchronization synchronization = synchronizeDataBreakpoints(plan, settings, runtimeCapabilities, connection, dataBreakpointStore, timeout);
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING || session.plan != plan) {
                    return new DataBreakpointResult(snapshot(root, session), info, false);
                }
                session.diagnostics.addAll(synchronization.diagnostics());
                session.detail = "Data breakpoint '" + info.description() + "' added.";
                return new DataBreakpointResult(snapshot(root, session), info, true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) { return dataBreakpointFailure(root, session(root), "Data breakpoint lookup failed: " + message(error)); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) { return dataBreakpointFailure(root, session(root), "Data breakpoint lookup interrupted."); }
        } catch (IllegalArgumentException error) {
            synchronized (this) { return dataBreakpointFailure(root, session(root), "Data breakpoint lookup returned invalid metadata: " + message(error)); }
        }
    }

    ExceptionDetailsResult exceptionDetails(Path workspace, Duration timeout) {
        Path root = root(workspace);
        Connection connection;
        DebugInspection.Snapshot inspection;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null
                || !session.plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.EXCEPTION_DETAILS)
                || !session.runtimeCapabilities.contains(DebugAdapterRegistry.Capability.EXCEPTION_DETAILS)) {
                return exceptionDetailsFailure(root, session, "Exception details are unavailable for the active adapter.");
            }
            inspection = session.inspection.snapshot();
            if (!inspection.paused() || inspection.threadId() < 1) {
                return exceptionDetailsFailure(root, session, "Exception details require a paused thread.");
            }
            connection = session.connection;
        }
        try {
            Map<String, Object> body = body(connection.request("exceptionInfo", Map.of("threadId", inspection.threadId()), timeout), "exceptionInfo");
            ExceptionDetails details = exceptionDetails(body);
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING) {
                    return new ExceptionDetailsResult(snapshot(root, session), details, false);
                }
                session.detail = "Loaded exception details.";
                return new ExceptionDetailsResult(snapshot(root, session), details, true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) { return exceptionDetailsFailure(root, session(root), "Exception details failed: " + message(error)); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) { return exceptionDetailsFailure(root, session(root), "Exception details interrupted."); }
        }
    }

    ModulesResult modules(Path workspace, int startModule, int count, Duration timeout) {
        Path root = root(workspace);
        Connection connection;
        synchronized (this) {
            Session session = session(root);
            if (startModule < 0 || count < 1 || count > 100) return modulesFailure(root, session, "Module inspection arguments are invalid.");
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null
                || !session.plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.MODULES)
                || !session.runtimeCapabilities.contains(DebugAdapterRegistry.Capability.MODULES)) {
                return modulesFailure(root, session, "Module inspection is unavailable for the active adapter.");
            }
            connection = session.connection;
        }
        try {
            Map<String, Object> body = body(connection.request("modules", Map.of("startModule", startModule, "moduleCount", count), timeout), "modules");
            List<ModuleInfo> modules = modules(body, connection, count);
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING) return new ModulesResult(snapshot(root, session), modules, false);
                session.detail = "Loaded " + modules.size() + " module" + (modules.size() == 1 ? "." : "s.");
                return new ModulesResult(snapshot(root, session), modules, true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) { return modulesFailure(root, session(root), "Module inspection failed: " + message(error)); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) { return modulesFailure(root, session(root), "Module inspection interrupted."); }
        }
    }

    LoadedSourcesResult loadedSources(Path workspace, Duration timeout) {
        Path root = root(workspace);
        Connection connection;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null
                || !session.plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.LOADED_SOURCES)
                || !session.runtimeCapabilities.contains(DebugAdapterRegistry.Capability.LOADED_SOURCES)) {
                return loadedSourcesFailure(root, session, "Loaded-source inspection is unavailable for the active adapter.");
            }
            connection = session.connection;
        }
        try {
            Map<String, Object> body = body(connection.request("loadedSources", Map.of(), timeout), "loadedSources");
            List<LoadedSource> sources = loadedSources(body, connection);
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING) {
                    return new LoadedSourcesResult(snapshot(root, session), sources, false);
                }
                session.detail = "Loaded " + sources.size() + " source" + (sources.size() == 1 ? "." : "s.");
                return new LoadedSourcesResult(snapshot(root, session), sources, true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) { return loadedSourcesFailure(root, session(root), "Loaded-source inspection failed: " + message(error)); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) { return loadedSourcesFailure(root, session(root), "Loaded-source inspection interrupted."); }
        }
    }

    MemoryReadResult readMemory(Path workspace, String memoryReference, int offset, int count, Duration timeout) {
        Path root = root(workspace);
        String reference = memoryReference == null ? "" : memoryReference.trim();
        Connection connection;
        synchronized (this) {
            Session session = session(root);
            if (!validMemoryReference(reference) || offset < -1_048_576 || offset > 1_048_576 || count < 1 || count > 4_096) {
                return memoryReadFailure(root, session, "Memory inspection arguments are invalid.");
            }
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null
                || !session.plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.READ_MEMORY)
                || !session.runtimeCapabilities.contains(DebugAdapterRegistry.Capability.READ_MEMORY)) {
                return memoryReadFailure(root, session, "Memory inspection is unavailable for the active adapter.");
            }
            connection = session.connection;
        }
        try {
            Map<String, Object> body = body(connection.request("readMemory", Map.of("memoryReference", reference, "offset", offset, "count", count), timeout),
                "readMemory");
            MemoryRead memory = memoryRead(body, count);
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING) {
                    return new MemoryReadResult(snapshot(root, session), memory, false);
                }
                session.detail = "Read " + memory.data().length + " byte" + (memory.data().length == 1 ? "" : "s") + " from debug memory.";
                return new MemoryReadResult(snapshot(root, session), memory, true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) { return memoryReadFailure(root, session(root), "Memory inspection failed: " + message(error)); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) { return memoryReadFailure(root, session(root), "Memory inspection interrupted."); }
        }
    }

    DisassemblyResult disassemble(Path workspace, String memoryReference, int offset, int count, Duration timeout) {
        Path root = root(workspace);
        String reference = memoryReference == null ? "" : memoryReference.trim();
        Connection connection;
        synchronized (this) {
            Session session = session(root);
            if (!validMemoryReference(reference) || offset < -1_048_576 || offset > 1_048_576 || count < 1 || count > 1_024) {
                return disassemblyFailure(root, session, "Disassembly arguments are invalid.");
            }
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null
                || !session.plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.DISASSEMBLE)
                || !session.runtimeCapabilities.contains(DebugAdapterRegistry.Capability.DISASSEMBLE)) {
                return disassemblyFailure(root, session, "Disassembly is unavailable for the active adapter.");
            }
            connection = session.connection;
        }
        try {
            Map<String, Object> body = body(connection.request("disassemble", Map.of("memoryReference", reference, "offset", offset,
                "instructionCount", count), timeout), "disassemble");
            List<DisassembledInstruction> instructions = disassembly(body, count);
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING) {
                    return new DisassemblyResult(snapshot(root, session), instructions, false);
                }
                session.detail = "Loaded " + instructions.size() + " disassembled instruction" + (instructions.size() == 1 ? "." : "s.");
                return new DisassemblyResult(snapshot(root, session), instructions, true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) { return disassemblyFailure(root, session(root), "Disassembly failed: " + message(error)); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) { return disassemblyFailure(root, session(root), "Disassembly interrupted."); }
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
            if ((requested == Control.REVERSE_CONTINUE || requested == Control.STEP_BACK)
                && !session.runtimeCapabilities.contains(requested.capability)) {
                return controlFailure(root, session, "Debug adapter did not advertise support for " + requested.dapCommand + " during initialization.");
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

    ControlResult restartFrame(Path workspace, Duration timeout) {
        Path root = root(workspace);
        Connection connection;
        DebugAdapterRegistry.Plan plan;
        DebugInspection.Snapshot inspection;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null) {
                return controlFailure(root, session, "No running debug session is available.");
            }
            if (!session.plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.RESTART_FRAME)) {
                return controlFailure(root, session, "Debug adapter does not declare support for restartFrame.");
            }
            if (!session.runtimeCapabilities.contains(DebugAdapterRegistry.Capability.RESTART_FRAME)) {
                return controlFailure(root, session, "Debug adapter did not advertise support for restartFrame during initialization.");
            }
            inspection = session.inspection.snapshot();
            if (!inspection.paused() || inspection.frameId() < 1) {
                return controlFailure(root, session, "Debug restartFrame requires a selected paused frame.");
            }
            connection = session.connection;
            plan = session.plan;
        }
        try {
            DebugAdapterTransport.Response response = connection.request("restartFrame", Map.of("frameId", inspection.frameId()), timeout);
            if (!response.success()) {
                synchronized (this) { return controlFailure(root, session(root), responseFailure("restartFrame", response)); }
            }
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || session.lifecycle != Lifecycle.RUNNING || session.plan != plan) {
                    return new ControlResult(snapshot(root, session), false);
                }
                session.inspection.invalidated("Debug restartFrame requested.");
                session.detail = "Debug restartFrame requested.";
                return new ControlResult(snapshot(root, session), true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) {
                return controlFailure(root, session(root), "Debug restartFrame failed: " + message(error));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) { return controlFailure(root, session(root), "Debug restartFrame interrupted."); }
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

    InspectionResult expandVariables(Path workspace, int variablesReference, Duration timeout) {
        Path root = root(workspace);
        Connection connection;
        DebugInspection.VariableLoad load;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null
                || !supports(session.plan, session.features, DebugAdapterRegistry.Capability.VARIABLES)) {
                return new InspectionResult(session.inspection.snapshot(), false);
            }
            load = session.inspection.beginVariableLoad(variablesReference);
            if (load == null) return new InspectionResult(session.inspection.snapshot(), false);
            connection = session.connection;
        }
        try {
            List<DebugInspection.Variable> variables = variables(connection, load.variablesReference(), timeout,
                () -> variableLoadActive(root, connection, load.generation()));
            synchronized (this) {
                Session session = session(root);
                boolean applied = session.connection == connection && session.inspection.completeVariableLoad(load.generation(), load.variablesReference(), variables);
                return new InspectionResult(session.inspection.snapshot(), applied);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) {
                Session session = session(root);
                session.diagnostics.add("Nested variable inspection failed: " + message(error));
                return new InspectionResult(session.inspection.snapshot(), false);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                Session session = session(root);
                session.diagnostics.add("Nested variable inspection interrupted.");
                return new InspectionResult(session.inspection.snapshot(), false);
            }
        }
    }

    EvaluationResult evaluate(Path workspace, String expression, Duration timeout) {
        Path root = root(workspace);
        String source = expression == null ? "" : expression.trim();
        if (!validExpression(source)) {
            synchronized (this) {
                Session session = session(root);
                return new EvaluationResult(session.console.snapshot(), new Evaluation(source, "", "", 0,
                    "Debug expression is empty, too long, or contains a control character."), false);
            }
        }
        Connection connection;
        DebugInspection.EvaluationLoad load;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null
                || !supports(session.plan, session.features, DebugAdapterRegistry.Capability.EVALUATE)) {
                return new EvaluationResult(session.console.snapshot(), new Evaluation(source, "", "", 0,
                    "Debug expression evaluation is unavailable for the active adapter."), false);
            }
            load = session.inspection.beginEvaluation();
            if (load == null) {
                return new EvaluationResult(session.console.snapshot(), new Evaluation(source, "", "", 0,
                    "Debug expression evaluation requires a selected paused frame."), false);
            }
            connection = session.connection;
        }
        try {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("expression", source);
            arguments.put("context", "repl");
            arguments.put("frameId", load.frameId());
            DebugAdapterTransport.Response response = connection.request("evaluate", arguments, timeout);
            if (!response.success()) throw new IOException(responseFailure("evaluate", response));
            Map<String, Object> body = MiniJson.asObject(response.body());
            String result = body == null ? null : MiniJson.asString(body.get("result"));
            if (result == null) throw new IOException("DAP evaluate response is missing result");
            Evaluation evaluation = new Evaluation(source, result, string(body.get("type")), integer(body.get("variablesReference")), "");
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || !session.inspection.current(load.generation(), load.frameId())) {
                    return new EvaluationResult(session.console.snapshot(), new Evaluation(source, "", "", 0,
                        "Debug state changed before evaluation completed."), false);
                }
                session.console.appendEvaluation(evaluation);
                session.detail = "Evaluated debug expression.";
                return new EvaluationResult(session.console.snapshot(), evaluation, true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) {
                Session session = session(root);
                String detail = "Debug expression evaluation failed: " + message(error);
                session.diagnostics.add(detail);
                return new EvaluationResult(session.console.snapshot(), new Evaluation(source, "", "", 0, detail), false);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                Session session = session(root);
                String detail = "Debug expression evaluation interrupted.";
                session.diagnostics.add(detail);
                return new EvaluationResult(session.console.snapshot(), new Evaluation(source, "", "", 0, detail), false);
            }
        }
    }

    VariableMutationResult setVariable(Path workspace, int variablesReference, String name, String value, Duration timeout) {
        Path root = root(workspace);
        String variableName = name == null ? "" : name.trim();
        String variableValue = value == null ? "" : value.trim();
        if (!validVariableText(variableName, 1024) || !validVariableText(variableValue, 4096)) {
            synchronized (this) {
                Session session = session(root);
                return new VariableMutationResult(session.console.snapshot(), new VariableMutation(variableName, "", "", 0,
                    "Variable name or value is empty, too long, or contains a control character."), false);
            }
        }
        Connection connection;
        DebugInspection.VariableMutationLoad load;
        synchronized (this) {
            Session session = session(root);
            if (session.lifecycle != Lifecycle.RUNNING || session.connection == null || session.plan == null
                || !supports(session.plan, session.features, DebugAdapterRegistry.Capability.SET_VARIABLE)) {
                return new VariableMutationResult(session.console.snapshot(), new VariableMutation(variableName, "", "", 0,
                    "Changing variables is unavailable for the active adapter."), false);
            }
            if (!session.runtimeCapabilities.contains(DebugAdapterRegistry.Capability.SET_VARIABLE)) {
                return new VariableMutationResult(session.console.snapshot(), new VariableMutation(variableName, "", "", 0,
                    "The active adapter did not advertise DAP setVariable support."), false);
            }
            load = session.inspection.beginVariableMutation(variablesReference, variableName);
            if (load == null) {
                return new VariableMutationResult(session.console.snapshot(), new VariableMutation(variableName, "", "", 0,
                    "Changing a variable requires a currently displayed paused variable scope."), false);
            }
            connection = session.connection;
        }
        try {
            DebugAdapterTransport.Response response = connection.request("setVariable", Map.of("variablesReference", load.variablesReference(),
                "name", variableName, "value", variableValue), timeout);
            if (!response.success()) throw new IOException(responseFailure("setVariable", response));
            Map<String, Object> body = MiniJson.asObject(response.body());
            String appliedValue = body == null ? null : MiniJson.asString(body.get("value"));
            if (appliedValue == null) throw new IOException("DAP setVariable response is missing value");
            VariableMutation mutation = new VariableMutation(variableName, appliedValue, string(body.get("type")), integer(body.get("variablesReference")), "");
            synchronized (this) {
                Session session = session(root);
                if (session.connection != connection || !session.inspection.updateVariable(load.generation(), load.frameId(), load.variablesReference(),
                    variableName, mutation.value(), mutation.type(), mutation.variablesReference())) {
                    return new VariableMutationResult(session.console.snapshot(), new VariableMutation(variableName, "", "", 0,
                        "Debug state changed or no longer displays that variable."), false);
                }
                session.console.append("repl", "set " + variableName + " = " + appliedValue + "\n");
                session.detail = "Changed debug variable '" + variableName + "'.";
                return new VariableMutationResult(session.console.snapshot(), mutation, true);
            }
        } catch (IOException | TimeoutException error) {
            synchronized (this) {
                Session session = session(root);
                String detail = "Changing debug variable failed: " + message(error);
                session.diagnostics.add(detail);
                return new VariableMutationResult(session.console.snapshot(), new VariableMutation(variableName, "", "", 0, detail), false);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                Session session = session(root);
                String detail = "Changing debug variable interrupted.";
                session.diagnostics.add(detail);
                return new VariableMutationResult(session.console.snapshot(), new VariableMutation(variableName, "", "", 0, detail), false);
            }
        }
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

    private synchronized boolean variableLoadActive(Path workspace, Connection connection, long generation) {
        Session session = session(root(workspace));
        return session.connection == connection && session.inspection.current(generation);
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

    private static boolean validExpression(String expression) {
        if (expression == null || expression.isEmpty() || expression.length() > 1024) return false;
        for (int index = 0; index < expression.length(); index++) if (Character.isISOControl(expression.charAt(index))) return false;
        return true;
    }

    private static boolean validVariableText(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum) return false;
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return false;
        return true;
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

    private static BreakpointSynchronization synchronizeFunctionBreakpoints(DebugAdapterRegistry.Plan plan, DebugFeatureSettings settings,
        Set<DebugAdapterRegistry.Capability> runtimeCapabilities, Connection connection, FunctionBreakpointStore functionBreakpointStore,
        Duration timeout) {
        if (functionBreakpointStore == null || settings == null || !settings.breakpoints() || plan == null || connection == null
            || !plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.FUNCTION_BREAKPOINTS)
            || runtimeCapabilities == null || !runtimeCapabilities.contains(DebugAdapterRegistry.Capability.FUNCTION_BREAKPOINTS)) {
            return new BreakpointSynchronization(false, List.of());
        }
        List<String> diagnostics = new ArrayList<>();
        try {
            List<FunctionBreakpointStore.Breakpoint> requested = new ArrayList<>();
            List<Map<String, Object>> arguments = new ArrayList<>();
            for (FunctionBreakpointStore.Breakpoint breakpoint : functionBreakpointStore.breakpoints(plan.workspace())) {
                if (!breakpoint.enabled()) continue;
                String unsupported = unsupportedFunctionBreakpointOption(plan.adapter(), runtimeCapabilities, breakpoint);
                if (unsupported != null) {
                    String detail = "Function breakpoint option is unsupported by adapter " + plan.adapter().id() + ": " + unsupported + ".";
                    functionBreakpointStore.reject(plan.workspace(), breakpoint, detail);
                    diagnostics.add("Function breakpoint '" + breakpoint.name() + "' " + detail);
                    continue;
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("name", breakpoint.name());
                if (!breakpoint.condition().isBlank()) value.put("condition", breakpoint.condition());
                if (!breakpoint.hitCondition().isBlank()) value.put("hitCondition", breakpoint.hitCondition());
                requested.add(breakpoint);
                arguments.add(Map.copyOf(value));
            }
            DebugAdapterTransport.Response response = connection.request("setFunctionBreakpoints", Map.of("breakpoints", List.copyOf(arguments)), timeout);
            if (!response.success()) {
                return new BreakpointSynchronization(true, List.of("DAP setFunctionBreakpoints failed"
                    + (response.message().isBlank() ? "." : ": " + response.message())));
            }
            diagnostics.addAll(functionBreakpointStore.apply(plan.workspace(), requested, response.body()).diagnostics());
        } catch (IOException | TimeoutException error) {
            diagnostics.add("Function breakpoint synchronization failed: " + message(error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            diagnostics.add("Function breakpoint synchronization interrupted.");
        }
        return new BreakpointSynchronization(true, diagnostics);
    }

    private static BreakpointSynchronization synchronizeDataBreakpoints(DebugAdapterRegistry.Plan plan, DebugFeatureSettings settings,
        Set<DebugAdapterRegistry.Capability> runtimeCapabilities, Connection connection, DataBreakpointStore dataBreakpointStore,
        Duration timeout) {
        if (dataBreakpointStore == null || settings == null || !settings.breakpoints() || plan == null || connection == null
            || !plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.DATA_BREAKPOINTS)
            || runtimeCapabilities == null || !runtimeCapabilities.contains(DebugAdapterRegistry.Capability.DATA_BREAKPOINTS)) {
            return new BreakpointSynchronization(false, List.of());
        }
        try {
            List<String> diagnostics = new ArrayList<>();
            List<DataBreakpointStore.Breakpoint> requested = new ArrayList<>();
            List<Map<String, Object>> arguments = new ArrayList<>();
            for (DataBreakpointStore.Breakpoint breakpoint : dataBreakpointStore.breakpoints(plan.workspace())) {
                if (!breakpoint.enabled()) continue;
                String unsupported = unsupportedDataBreakpointOption(plan.adapter(), runtimeCapabilities, breakpoint);
                if (unsupported != null) {
                    String detail = "Data breakpoint option is unsupported by adapter " + plan.adapter().id() + ": " + unsupported + ".";
                    dataBreakpointStore.reject(plan.workspace(), breakpoint, detail);
                    diagnostics.add("Data breakpoint '" + breakpoint.description() + "' " + detail);
                    continue;
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("dataId", breakpoint.dataId());
                value.put("accessType", breakpoint.accessType().dapValue());
                if (!breakpoint.condition().isBlank()) value.put("condition", breakpoint.condition());
                if (!breakpoint.hitCondition().isBlank()) value.put("hitCondition", breakpoint.hitCondition());
                requested.add(breakpoint);
                arguments.add(Map.copyOf(value));
            }
            DebugAdapterTransport.Response response = connection.request("setDataBreakpoints", Map.of("breakpoints", List.copyOf(arguments)), timeout);
            if (!response.success()) {
                return new BreakpointSynchronization(true, List.of("DAP setDataBreakpoints failed"
                    + (response.message().isBlank() ? "." : ": " + response.message())));
            }
            diagnostics.addAll(dataBreakpointStore.apply(plan.workspace(), requested, response.body()).diagnostics());
            return new BreakpointSynchronization(true, diagnostics);
        } catch (IOException | TimeoutException error) {
            return new BreakpointSynchronization(true, List.of("Data breakpoint synchronization failed: " + message(error)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new BreakpointSynchronization(true, List.of("Data breakpoint synchronization interrupted."));
        }
    }

    private static BreakpointSynchronization synchronizeInstructionBreakpoints(DebugAdapterRegistry.Plan plan, DebugFeatureSettings settings,
        Set<DebugAdapterRegistry.Capability> runtimeCapabilities, Connection connection, InstructionBreakpointStore instructionBreakpointStore,
        Duration timeout) {
        if (instructionBreakpointStore == null || settings == null || !settings.breakpoints() || plan == null || connection == null
            || !plan.adapter().capabilities().contains(DebugAdapterRegistry.Capability.INSTRUCTION_BREAKPOINTS)
            || runtimeCapabilities == null || !runtimeCapabilities.contains(DebugAdapterRegistry.Capability.INSTRUCTION_BREAKPOINTS)) {
            return new BreakpointSynchronization(false, List.of());
        }
        try {
            List<String> diagnostics = new ArrayList<>();
            List<InstructionBreakpointStore.Breakpoint> requested = new ArrayList<>();
            List<Map<String, Object>> arguments = new ArrayList<>();
            for (InstructionBreakpointStore.Breakpoint breakpoint : instructionBreakpointStore.breakpoints(plan.workspace())) {
                if (!breakpoint.enabled()) continue;
                String unsupported = unsupportedInstructionBreakpointOption(plan.adapter(), runtimeCapabilities, breakpoint);
                if (unsupported != null) {
                    String detail = "Instruction breakpoint option is unsupported by adapter " + plan.adapter().id() + ": " + unsupported + ".";
                    instructionBreakpointStore.reject(plan.workspace(), breakpoint, detail);
                    diagnostics.add("Instruction breakpoint '" + breakpoint.instructionReference() + "' " + detail);
                    continue;
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("instructionReference", breakpoint.instructionReference());
                value.put("offset", breakpoint.offset());
                if (!breakpoint.condition().isBlank()) value.put("condition", breakpoint.condition());
                if (!breakpoint.hitCondition().isBlank()) value.put("hitCondition", breakpoint.hitCondition());
                requested.add(breakpoint);
                arguments.add(Map.copyOf(value));
            }
            DebugAdapterTransport.Response response = connection.request("setInstructionBreakpoints", Map.of("breakpoints", List.copyOf(arguments)), timeout);
            if (!response.success()) {
                return new BreakpointSynchronization(true, List.of("DAP setInstructionBreakpoints failed"
                    + (response.message().isBlank() ? "." : ": " + response.message())));
            }
            diagnostics.addAll(instructionBreakpointStore.apply(plan.workspace(), requested, response.body()).diagnostics());
            return new BreakpointSynchronization(true, diagnostics);
        } catch (IOException | TimeoutException error) {
            return new BreakpointSynchronization(true, List.of("Instruction breakpoint synchronization failed: " + message(error)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new BreakpointSynchronization(true, List.of("Instruction breakpoint synchronization interrupted."));
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

    private static String unsupportedFunctionBreakpointOption(DebugAdapterRegistry.Adapter adapter,
        Set<DebugAdapterRegistry.Capability> runtimeCapabilities, FunctionBreakpointStore.Breakpoint breakpoint) {
        if (adapter == null || breakpoint == null) return "function breakpoint";
        if (!breakpoint.condition().isBlank()) {
            String unsupported = unsupportedCapability(adapter, runtimeCapabilities, DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS, "condition");
            if (unsupported != null) return unsupported;
        }
        if (!breakpoint.hitCondition().isBlank()) {
            String unsupported = unsupportedCapability(adapter, runtimeCapabilities, DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, "hit condition");
            if (unsupported != null) return unsupported;
        }
        return null;
    }

    private static String unsupportedDataBreakpointOption(DebugAdapterRegistry.Adapter adapter,
        Set<DebugAdapterRegistry.Capability> runtimeCapabilities, DataBreakpointStore.Breakpoint breakpoint) {
        if (adapter == null || breakpoint == null) return "data breakpoint";
        if (!breakpoint.condition().isBlank()) {
            String unsupported = unsupportedCapability(adapter, runtimeCapabilities, DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS, "condition");
            if (unsupported != null) return unsupported;
        }
        if (!breakpoint.hitCondition().isBlank()) {
            String unsupported = unsupportedCapability(adapter, runtimeCapabilities, DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, "hit condition");
            if (unsupported != null) return unsupported;
        }
        return null;
    }

    private static String unsupportedInstructionBreakpointOption(DebugAdapterRegistry.Adapter adapter,
        Set<DebugAdapterRegistry.Capability> runtimeCapabilities, InstructionBreakpointStore.Breakpoint breakpoint) {
        if (adapter == null || breakpoint == null) return "instruction breakpoint";
        if (!breakpoint.condition().isBlank()) {
            String unsupported = unsupportedCapability(adapter, runtimeCapabilities, DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS, "condition");
            if (unsupported != null) return unsupported;
        }
        if (!breakpoint.hitCondition().isBlank()) {
            String unsupported = unsupportedCapability(adapter, runtimeCapabilities, DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, "hit condition");
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
        String fileName = context.activeFile().getFileName() == null ? "" : context.activeFile().getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String id : BuiltInDebugAdapterSupport.contextualProfileIds()) {
            DebugAdapterRegistry.Configuration profile = validation.configurations().get(id);
            if (profile == null || profile.request() != DebugAdapterRegistry.Request.LAUNCH || !id.equals(profile.adapter())) continue;
            for (String extension : profile.fileExtensions()) {
                if (extension != null && !extension.isBlank() && fileName.endsWith(extension.toLowerCase(java.util.Locale.ROOT))) return profile.name();
            }
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
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.FUNCTION_BREAKPOINTS, "supportsFunctionBreakpoints");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.DATA_BREAKPOINTS, "supportsDataBreakpoints");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS, "supportsConditionalBreakpoints");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, "supportsHitConditionalBreakpoints");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.LOG_POINTS, "supportsLogPoints");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.SET_VARIABLE, "supportsSetVariable");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.GOTO, "supportsGotoTargetsRequest");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.REVERSE_CONTINUE, "supportsReverseContinue");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.STEP_BACK, "supportsStepBack");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.RESTART_FRAME, "supportsRestartFrame");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.EXCEPTION_DETAILS, "supportsExceptionInfoRequest");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.MODULES, "supportsModulesRequest");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.LOADED_SOURCES, "supportsLoadedSourcesRequest");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.READ_MEMORY, "supportsReadMemoryRequest");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.INSTRUCTION_BREAKPOINTS, "supportsInstructionBreakpoints");
        retainAdvertised(result, capabilities, DebugAdapterRegistry.Capability.DISASSEMBLE, "supportsDisassembleRequest");
        return Set.copyOf(result);
    }

    private static void retainAdvertised(EnumSet<DebugAdapterRegistry.Capability> capabilities, Map<String, Object> initialize,
        DebugAdapterRegistry.Capability capability, String key) {
        if (capabilities.contains(capability) && !Boolean.TRUE.equals(initialize == null ? null : initialize.get(key))) capabilities.remove(capability);
    }

    private static DataBreakpointInfo dataBreakpointInfo(Object responseBody, String fallbackDescription, DataBreakpointStore.AccessType preferred)
        throws IOException {
        Map<String, Object> body = MiniJson.asObject(responseBody);
        String dataId = body == null ? null : MiniJson.asString(body.get("dataId"));
        if (dataId == null || dataId.isBlank()) throw new IOException("DAP dataBreakpointInfo response is missing dataId");
        String description = MiniJson.asString(body.get("description"));
        if (description == null || description.isBlank()) description = MiniJson.asString(body.get("text"));
        if (description == null || description.isBlank()) description = fallbackDescription;
        List<DataBreakpointStore.AccessType> accessTypes = new ArrayList<>();
        List<Object> supplied = MiniJson.asArray(body.get("accessTypes"));
        if (supplied == null) {
            accessTypes.add(DataBreakpointStore.AccessType.READ);
            accessTypes.add(DataBreakpointStore.AccessType.WRITE);
            accessTypes.add(DataBreakpointStore.AccessType.READ_WRITE);
        } else {
            for (Object value : supplied) {
                String access = MiniJson.asString(value);
                if (access == null) throw new IOException("DAP dataBreakpointInfo response contains an invalid access type");
                try {
                    DataBreakpointStore.AccessType type = DataBreakpointStore.AccessType.parse(access);
                    if (!accessTypes.contains(type)) accessTypes.add(type);
                } catch (IllegalArgumentException error) {
                    throw new IOException("DAP dataBreakpointInfo response contains an invalid access type", error);
                }
            }
            if (accessTypes.isEmpty()) throw new IOException("DAP dataBreakpointInfo response has no supported access types");
        }
        try {
            return new DataBreakpointInfo(new DataBreakpointStore.Breakpoint(dataId, description,
                selectDataBreakpointAccess(accessTypes, preferred)).dataId(), description, accessTypes);
        } catch (IllegalArgumentException error) {
            throw new IOException("DAP dataBreakpointInfo response is invalid", error);
        }
    }

    private static DataBreakpointStore.AccessType selectDataBreakpointAccess(List<DataBreakpointStore.AccessType> accessTypes,
        DataBreakpointStore.AccessType preferred) {
        List<DataBreakpointStore.AccessType> available = accessTypes == null ? List.of() : accessTypes;
        if (preferred != null && available.contains(preferred)) return preferred;
        if (available.contains(DataBreakpointStore.AccessType.WRITE)) return DataBreakpointStore.AccessType.WRITE;
        return available.isEmpty() ? DataBreakpointStore.AccessType.WRITE : available.getFirst();
    }

    private static ExceptionDetails exceptionDetails(Map<String, Object> body) throws IOException {
        String exceptionId = MiniJson.asString(body == null ? null : body.get("exceptionId"));
        String breakMode = MiniJson.asString(body == null ? null : body.get("breakMode"));
        if (exceptionId == null || exceptionId.isBlank() || breakMode == null || breakMode.isBlank()) {
            throw new IOException("DAP exceptionInfo response is missing exceptionId or breakMode");
        }
        Map<String, Object> details = MiniJson.asObject(body.get("details"));
        return new ExceptionDetails(display(exceptionId, 512), display(MiniJson.asString(body.get("description")), 8 * 1024),
            display(breakMode, 64), display(MiniJson.asString(details == null ? null : details.get("typeName")), 512),
            display(MiniJson.asString(details == null ? null : details.get("fullTypeName")), 1024),
            display(MiniJson.asString(details == null ? null : details.get("stackTrace")), 32 * 1024));
    }

    private static List<ModuleInfo> modules(Map<String, Object> body, Connection connection, int maximum) throws IOException {
        List<Object> values = MiniJson.asArray(body == null ? null : body.get("modules"));
        if (values == null || values.size() > maximum) throw new IOException("DAP modules response is invalid or exceeds the requested count");
        List<ModuleInfo> result = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> module = MiniJson.asObject(value);
            if (module == null) throw new IOException("DAP modules response contains an invalid module");
            String id = display(module.get("id"), 512);
            String name = display(MiniJson.asString(module.get("name")), 1024);
            if (id.isBlank() || name.isBlank()) throw new IOException("DAP modules response contains a module without id or name");
            String path = display(MiniJson.asString(module.get("path")), 8 * 1024);
            Path local = connection == null ? null : connection.localPath(path);
            if (local != null) path = local.toString();
            result.add(new ModuleInfo(id, name, path, display(MiniJson.asString(module.get("version")), 512),
                display(MiniJson.asString(module.get("symbolStatus")), 512)));
        }
        return List.copyOf(result);
    }

    private static List<LoadedSource> loadedSources(Map<String, Object> body, Connection connection) throws IOException {
        List<Object> values = MiniJson.asArray(body == null ? null : body.get("sources"));
        if (values == null || values.size() > 100) throw new IOException("DAP loadedSources response is invalid or too large");
        List<LoadedSource> result = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> source = MiniJson.asObject(value);
            if (source == null) throw new IOException("DAP loadedSources response contains an invalid source");
            String name = display(MiniJson.asString(source.get("name")), 1024);
            String path = display(MiniJson.asString(source.get("path")), 8 * 1024);
            int sourceReference = integer(source.get("sourceReference"));
            if (name.isBlank() && path.isBlank() && sourceReference < 1) throw new IOException("DAP loadedSources response contains an empty source");
            Path local = connection == null ? null : connection.localPath(path);
            if (local != null) path = local.toString();
            result.add(new LoadedSource(name, path, sourceReference, display(MiniJson.asString(source.get("origin")), 512),
                display(MiniJson.asString(source.get("presentationHint")), 512)));
        }
        return List.copyOf(result);
    }

    private static MemoryRead memoryRead(Map<String, Object> body, int requestedCount) throws IOException {
        String address = MiniJson.asString(body == null ? null : body.get("address"));
        String encoded = MiniJson.asString(body == null ? null : body.get("data"));
        Object unreadableValue = body == null ? null : body.get("unreadableBytes");
        int unreadable = integer(unreadableValue);
        int maximumEncodedLength = ((requestedCount + 2) / 3) * 4;
        if (!validMemoryReference(address) || (unreadableValue != null && !validNonNegativeInteger(unreadableValue))
            || unreadable > requestedCount || (encoded != null && encoded.length() > maximumEncodedLength)) {
            throw new IOException("DAP readMemory response is invalid");
        }
        byte[] bytes;
        try {
            bytes = encoded == null || encoded.isBlank() ? new byte[0] : Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new IOException("DAP readMemory response data is not base64", error);
        }
        if (bytes.length > requestedCount || bytes.length + unreadable > requestedCount) {
            throw new IOException("DAP readMemory response exceeds the requested byte count");
        }
        return new MemoryRead(display(address, 512), unreadable, bytes);
    }

    private static List<DisassembledInstruction> disassembly(Map<String, Object> body, int requestedCount) throws IOException {
        List<Object> values = MiniJson.asArray(body == null ? null : body.get("instructions"));
        if (values == null || values.size() > requestedCount) throw new IOException("DAP disassemble response is invalid or exceeds the requested count");
        List<DisassembledInstruction> result = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> instruction = MiniJson.asObject(value);
            if (instruction == null) throw new IOException("DAP disassemble response contains an invalid instruction");
            String address = MiniJson.asString(instruction.get("address"));
            String text = MiniJson.asString(instruction.get("instruction"));
            String bytes = MiniJson.asString(instruction.get("instructionBytes"));
            String symbol = MiniJson.asString(instruction.get("symbol"));
            if (!validMemoryReference(address) || !validDisassemblyText(text, 4 * 1024)
                || (bytes != null && !validDisassemblyText(bytes, 1024)) || (symbol != null && !validDisassemblyText(symbol, 1024))) {
                throw new IOException("DAP disassemble response contains unsafe instruction text");
            }
            result.add(new DisassembledInstruction(display(address, 512), display(bytes, 1024), display(text, 4 * 1024), display(symbol, 1024)));
        }
        return List.copyOf(result);
    }

    private static String display(Object value, int maximum) {
        String text = value instanceof String string ? string : value instanceof Number || value instanceof Boolean ? String.valueOf(value) : "";
        if (text.length() > maximum) return text.substring(0, maximum) + "…";
        return text;
    }

    private static boolean validMemoryReference(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) return false;
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return false;
        return true;
    }

    private static boolean validNonNegativeInteger(Object value) {
        return value instanceof Number number && number.doubleValue() == number.longValue() && number.longValue() >= 0
            && number.longValue() <= Integer.MAX_VALUE;
    }

    private static boolean validDisassemblyText(String value, int maximum) {
        if (value == null || value.length() > maximum) return false;
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return false;
        return true;
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
    private static DataBreakpointResult dataBreakpointFailure(Path root, Session session, String detail) {
        String message = detail == null || detail.isBlank() ? "Data breakpoint failed." : detail;
        session.detail = message;
        session.diagnostics.add(message);
        return new DataBreakpointResult(snapshot(root, session), null, false);
    }
    private static ExceptionDetailsResult exceptionDetailsFailure(Path root, Session session, String detail) {
        String message = detail == null || detail.isBlank() ? "Exception details failed." : detail;
        session.detail = message;
        session.diagnostics.add(message);
        return new ExceptionDetailsResult(snapshot(root, session), null, false);
    }
    private static ModulesResult modulesFailure(Path root, Session session, String detail) {
        String message = detail == null || detail.isBlank() ? "Module inspection failed." : detail;
        session.detail = message;
        session.diagnostics.add(message);
        return new ModulesResult(snapshot(root, session), List.of(), false);
    }
    private static LoadedSourcesResult loadedSourcesFailure(Path root, Session session, String detail) {
        String message = detail == null || detail.isBlank() ? "Loaded-source inspection failed." : detail;
        session.detail = message;
        session.diagnostics.add(message);
        return new LoadedSourcesResult(snapshot(root, session), List.of(), false);
    }
    private static MemoryReadResult memoryReadFailure(Path root, Session session, String detail) {
        String message = detail == null || detail.isBlank() ? "Memory inspection failed." : detail;
        session.detail = message;
        session.diagnostics.add(message);
        return new MemoryReadResult(snapshot(root, session), null, false);
    }
    private static DisassemblyResult disassemblyFailure(Path root, Session session, String detail) {
        String message = detail == null || detail.isBlank() ? "Disassembly failed." : detail;
        session.detail = message;
        session.diagnostics.add(message);
        return new DisassemblyResult(snapshot(root, session), List.of(), false);
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
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("host", configuration.host());
            arguments.put("port", configuration.port());
            arguments.put("cwd", requireAdapterPath(connection, plan.cwd()));
            arguments.put("args", plan.args());
            arguments.putAll(configuration.adapterOptions());
            return Map.copyOf(arguments);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (!configuration.program().isBlank()) arguments.put("program", requireAdapterPath(connection, plan.program()));
        else if (!plan.module().isBlank()) arguments.put("module", plan.module());
        else if (!plan.code().isBlank()) arguments.put("code", plan.code());
        else throw new IOException("Debug launch target is unavailable");
        arguments.put("cwd", requireAdapterPath(connection, plan.cwd()));
        arguments.put("args", plan.args());
        if (!configuration.environment().isEmpty()) arguments.put("env", configuration.environment());
        for (Map.Entry<String, String> entry : plan.adapter().launchDefaults().entrySet()) {
            arguments.putIfAbsent(entry.getKey(), entry.getValue());
        }
        arguments.putAll(configuration.adapterOptions());
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
