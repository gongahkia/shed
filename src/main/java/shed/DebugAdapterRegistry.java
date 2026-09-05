package shed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DebugAdapterRegistry {
    enum Transport { STDIO, TCP }
    enum Capability { LAUNCH, ATTACH, CONFIGURATION_DONE, BREAKPOINTS, FUNCTION_BREAKPOINTS, DATA_BREAKPOINTS, EXCEPTION_BREAKPOINTS, CONDITIONAL_BREAKPOINTS, HIT_CONDITIONAL_BREAKPOINTS, LOG_POINTS,
        THREADS, STACK_TRACE, SCOPES, VARIABLES, SET_VARIABLE, EVALUATE, CONTINUE, NEXT, STEP_IN, STEP_OUT, PAUSE, GOTO,
        REVERSE_CONTINUE, STEP_BACK, RESTART_FRAME, EXCEPTION_DETAILS, MODULES, LOADED_SOURCES, READ_MEMORY, INSTRUCTION_BREAKPOINTS }
    enum Request { LAUNCH, ATTACH }

    /**
     * Metadata for a built-in adapter that opens its own local loopback TCP
     * endpoint. User TOML adapters remain connect-only when using TCP.
     */
    record SpawnedTcpStartup(List<String> arguments, String readinessPrefix) {
        SpawnedTcpStartup {
            List<String> suppliedArguments = arguments == null ? List.of() : arguments;
            readinessPrefix = readinessPrefix == null ? "" : readinessPrefix.trim();
            if (suppliedArguments.isEmpty() || readinessPrefix.isEmpty()) throw new IllegalArgumentException("TCP startup metadata is incomplete");
            for (String argument : suppliedArguments) {
                if (argument == null || argument.isBlank() || argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("TCP startup argument is invalid");
                }
            }
            arguments = List.copyOf(suppliedArguments);
        }
    }

    record Adapter(String id, Transport transport, String command, List<String> args, Set<Capability> capabilities,
        SpawnedTcpStartup spawnedTcpStartup, Map<String, String> launchDefaults) {
        Adapter {
            id = id == null ? "" : id;
            transport = transport == null ? Transport.STDIO : transport;
            command = command == null ? "" : command;
            args = args == null ? List.of() : List.copyOf(args);
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            Map<String, String> suppliedLaunchDefaults = launchDefaults == null ? Map.of() : launchDefaults;
            if (spawnedTcpStartup != null && transport != Transport.TCP) {
                throw new IllegalArgumentException("spawned TCP adapters must use TCP transport");
            }
            for (Map.Entry<String, String> entry : suppliedLaunchDefaults.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_]*") || value == null || value.indexOf('\0') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("adapter launch default is invalid");
                }
            }
            launchDefaults = Map.copyOf(suppliedLaunchDefaults);
        }

        Adapter(String id, Transport transport, String command, List<String> args, Set<Capability> capabilities) {
            this(id, transport, command, args, capabilities, null, Map.of());
        }

        boolean supports(Request request) {
            return request == Request.LAUNCH ? capabilities.contains(Capability.LAUNCH) : capabilities.contains(Capability.ATTACH);
        }
    }

    record Configuration(String name, String adapter, Request request, String scope, String program, String module, String code, String cwd, List<String> args,
        String prelaunchTask, String host, int port, List<String> fileExtensions, Map<String, String> environment, Map<String, Object> adapterOptions) {
        Configuration {
            name = name == null ? "" : name;
            adapter = adapter == null ? "" : adapter;
            request = request == null ? Request.LAUNCH : request;
            scope = scope == null ? "" : scope;
            program = program == null ? "" : program;
            module = module == null ? "" : module;
            code = code == null ? "" : code;
            cwd = cwd == null ? "" : cwd;
            args = args == null ? List.of() : List.copyOf(args);
            prelaunchTask = prelaunchTask == null ? "" : prelaunchTask;
            host = host == null ? "" : host;
            fileExtensions = fileExtensions == null ? List.of() : List.copyOf(fileExtensions);
            Map<String, String> suppliedEnvironment = environment == null ? Map.of() : environment;
            if (!DebugAdapterRegistry.safeEnvironment(suppliedEnvironment)) {
                throw new IllegalArgumentException("debug configuration environment is invalid");
            }
            environment = Map.copyOf(new LinkedHashMap<>(suppliedEnvironment));
            Map<String, Object> suppliedAdapterOptions = adapterOptions == null ? Map.of() : adapterOptions;
            if (!safeAdapterOptions(suppliedAdapterOptions)) throw new IllegalArgumentException("debug configuration adapter options are invalid");
            adapterOptions = Map.copyOf(new LinkedHashMap<>(suppliedAdapterOptions));
        }

        Configuration(String name, String adapter, Request request, String scope, String program, String module, String code, String cwd, List<String> args,
            String prelaunchTask, String host, int port, List<String> fileExtensions, Map<String, String> environment) {
            this(name, adapter, request, scope, program, module, code, cwd, args, prelaunchTask, host, port, fileExtensions, environment, Map.of());
        }

        Configuration(String name, String adapter, Request request, String scope, String program, String module, String code, String cwd, List<String> args,
            String prelaunchTask, String host, int port, List<String> fileExtensions) {
            this(name, adapter, request, scope, program, module, code, cwd, args, prelaunchTask, host, port, fileExtensions, Map.of());
        }

        Configuration(String name, String adapter, Request request, String scope, String program, String cwd, List<String> args,
            String prelaunchTask, String host, int port, List<String> fileExtensions) {
            this(name, adapter, request, scope, program, "", "", cwd, args, prelaunchTask, host, port, fileExtensions);
        }

        Configuration(String name, String adapter, Request request, String scope, String program, String cwd, List<String> args,
            String host, int port) {
            this(name, adapter, request, scope, program, "", "", cwd, args, "", host, port, List.of());
        }
    }

    record Error(String key, String message) {
        Error {
            key = key == null ? "" : key;
            message = message == null ? "" : message;
        }
    }

    record Validation(DebugAdapterRegistry registry, Map<String, Configuration> configurations, List<Error> errors) {
        Validation {
            registry = registry == null ? new DebugAdapterRegistry(Map.of()) : registry;
            configurations = configurations == null ? Map.of() : Map.copyOf(configurations);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        boolean valid() { return errors.isEmpty(); }
    }

    record LaunchContext(Path activeFile, String testId, Path testFile) {
        LaunchContext {
            activeFile = activeFile == null ? null : activeFile.toAbsolutePath().normalize();
            testId = testId == null ? "" : testId;
            testFile = testFile == null ? null : testFile.toAbsolutePath().normalize();
        }
    }

    record Plan(Adapter adapter, Configuration configuration, Path workspace, Path cwd, Path program, String module, String code, List<String> args) {
        Plan {
            module = module == null ? "" : module;
            code = code == null ? "" : code;
            args = args == null ? List.of() : List.copyOf(args);
        }
        Plan(Adapter adapter, Configuration configuration, Path workspace, Path cwd, Path program, List<String> args) {
            this(adapter, configuration, workspace, cwd, program, configuration == null ? "" : configuration.module(),
                configuration == null ? "" : configuration.code(), args);
        }
        Plan(Adapter adapter, Configuration configuration, Path workspace, Path cwd, Path program) {
            this(adapter, configuration, workspace, cwd, program, configuration == null ? "" : configuration.module(),
                configuration == null ? "" : configuration.code(), configuration == null ? List.of() : configuration.args());
        }
    }

    record PlanResult(Plan plan, String error) {
        PlanResult {
            error = error == null ? "" : error;
        }

        boolean launchable() { return plan != null && error.isEmpty(); }
    }

    private static final Set<String> CORE_KEYS = Set.of("debug.enabled", "debug.breakpoints.enabled", "debug.threads.enabled",
        "debug.stacktrace.enabled", "debug.scopes.enabled", "debug.variables.enabled", "debug.evaluate.enabled", "debug.attach.enabled",
        "debug.open.source.on.stop");
    private static final Set<String> ADAPTER_FIELDS = Set.of("transport", "command", "args", "capabilities");
    private static final Set<String> CONFIGURATION_FIELDS = Set.of("adapter", "request", "scope", "program", "module", "code", "cwd", "args", "prelaunch_task", "host", "port", "file_extensions", "adapter_options");
    private static final Set<String> FILE_ARGUMENT_VARIABLES = Set.of("${file}", "${fileWorkspaceFolder}", "${relativeFile}",
        "${relativeFileDirname}", "${fileBasename}", "${fileBasenameNoExtension}", "${fileExtname}", "${fileDirname}",
        "${fileDirnameBasename}");
    private final Map<String, Adapter> adapters;

    private DebugAdapterRegistry(Map<String, Adapter> adapters) {
        this.adapters = Map.copyOf(adapters);
    }

    Adapter adapter(String id) { return adapters.get(id); }
    Map<String, Adapter> adapters() { return adapters; }

    static Validation validate(Map<String, Object> values) {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        Map<String, Map<String, String>> configurations = new LinkedHashMap<>();
        List<Error> errors = new ArrayList<>();
        for (Map.Entry<String, Object> entry : (values == null ? Map.<String, Object>of() : values).entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey();
            if (!key.startsWith("debug.")) continue;
            if (CORE_KEYS.contains(key)) continue;
            String value = entry.getValue() instanceof String ? (String) entry.getValue() : null;
            if (value == null) { errors.add(new Error(key, key + " must be a TOML string")); continue; }
            collect(key, value, "debug.adapter.", ADAPTER_FIELDS, adapters, errors);
            collect(key, value, "debug.configuration.", CONFIGURATION_FIELDS, configurations, errors);
            if (!key.startsWith("debug.adapter.") && !key.startsWith("debug.configuration.")) {
                errors.add(new Error(key, "unsupported debug key " + key));
            }
        }
        Map<String, Adapter> parsedAdapters = parseAdapters(adapters, errors);
        Map<String, Configuration> parsedConfigurations = parseConfigurations(configurations, parsedAdapters, errors);
        return new Validation(new DebugAdapterRegistry(parsedAdapters), parsedConfigurations, errors);
    }

    static Validation withContributedAdapters(Validation base, Map<String, Adapter> contributed) {
        Map<String, Configuration> defaults = new LinkedHashMap<>();
        if (contributed != null) {
            for (Map.Entry<String, Adapter> entry : contributed.entrySet()) {
                String id = entry.getKey();
                Adapter adapter = entry.getValue();
                if (id != null && adapter != null && adapter.supports(Request.LAUNCH)) {
                    defaults.put(id, new Configuration(id, id, Request.LAUNCH, "workspace", "${file}", "${workspaceFolder}", List.of(), "127.0.0.1", 0));
                }
            }
        }
        return withAdapterDefaults(base, contributed, defaults);
    }

    static Validation withAdapterDefaults(Validation base, Map<String, Adapter> contributed, Map<String, Configuration> defaults) {
        Validation source = base == null ? validate(Map.of()) : base;
        if (!source.valid() || contributed == null || contributed.isEmpty()) return source;
        Map<String, Adapter> adapters = new LinkedHashMap<>(source.registry().adapters());
        Map<String, Configuration> configurations = new LinkedHashMap<>(source.configurations());
        Set<String> added = new java.util.HashSet<>();
        for (Map.Entry<String, Adapter> entry : contributed.entrySet()) {
            String id = entry.getKey();
            Adapter adapter = entry.getValue();
            if (id == null || adapter == null || adapters.containsKey(id)) continue;
            adapters.put(id, adapter);
            added.add(id);
        }
        if (defaults != null) for (Map.Entry<String, Configuration> entry : defaults.entrySet()) {
            Configuration configuration = entry.getValue();
            if (entry.getKey() == null || configuration == null || configurations.containsKey(entry.getKey())
                || !added.contains(configuration.adapter())) continue;
            configurations.put(entry.getKey(), configuration);
        }
        return new Validation(new DebugAdapterRegistry(adapters), configurations, source.errors());
    }

    /**
     * Adds externally supplied, already-validated launch profiles without changing the
     * adapter registry. This is intentionally package-private: callers must retain a
     * report for profiles that fail {@link #externalConfigurationError} rather than
     * converting a workspace file into executable configuration silently.
     */
    static Validation withExternalConfigurations(Validation base, Map<String, Configuration> external) {
        Validation source = base == null ? validate(Map.of()) : base;
        if (!source.valid() || external == null || external.isEmpty()) return source;
        Map<String, Configuration> configurations = new LinkedHashMap<>(source.configurations());
        for (Map.Entry<String, Configuration> entry : external.entrySet()) {
            String name = entry.getKey();
            Configuration configuration = entry.getValue();
            if (name == null || name.isBlank() || configuration == null || configurations.containsKey(name)
                || externalConfigurationError(configuration, source.registry().adapters()) != null) continue;
            configurations.put(name, configuration);
        }
        return new Validation(source.registry(), configurations, source.errors());
    }

    /** Returns a non-empty reason when a direct, non-TOML profile is unsafe or incomplete. */
    static String externalConfigurationError(Configuration configuration, Map<String, Adapter> availableAdapters) {
        if (configuration == null) return "configuration is missing";
        Adapter adapter = availableAdapters == null ? null : availableAdapters.get(configuration.adapter());
        if (adapter == null) return "the referenced Shed adapter is not registered";
        if (!adapter.supports(configuration.request())) return "the referenced Shed adapter does not support "
            + configuration.request().name().toLowerCase(Locale.ROOT);
        if (!"workspace".equals(configuration.scope())) return "only workspace-scoped configurations are supported";
        if (!workspaceScoped(configuration.cwd(), false)) return "cwd must remain within ${workspaceFolder}";
        int launchTargets = (configuration.program().isBlank() ? 0 : 1) + (configuration.module().isBlank() ? 0 : 1)
            + (configuration.code().isBlank() ? 0 : 1);
        if (configuration.request() == Request.LAUNCH) {
            if (launchTargets != 1) return "launch requires exactly one of program, module, or code";
            if (!configuration.program().isBlank() && !workspaceScoped(configuration.program(), true)) {
                return "program must remain within ${workspaceFolder} or ${file}";
            }
            if (!configuration.module().isBlank() && !safeModule(configuration.module())) {
                return "module must be a dotted identifier";
            }
            if (!configuration.code().isBlank() && !safeCode(configuration.code())) {
                return "code must be non-empty, contain no NUL, and be at most 64 KiB";
            }
        } else if (!configuration.module().isBlank() || !configuration.code().isBlank()) {
            return "module and code are launch-only";
        }
        if (!safeArguments(configuration.args())) return "args contain an invalid control character or exceed 64 KiB";
        if (!safeEnvironment(configuration.environment())) return "environment contains an invalid name/value or exceeds 64 KiB";
        if (!safeAdapterOptions(configuration.adapterOptions())) return "adapter options contain an invalid field or exceed limits";
        if (configuration.request() == Request.ATTACH && !configuration.environment().isEmpty()) {
            return "environment is launch-only";
        }
        if (!configuration.prelaunchTask().isEmpty() && !identifier(configuration.prelaunchTask())) {
            return "preLaunchTask must name a Shed task identifier";
        }
        if (configuration.request() == Request.ATTACH) {
            if (!loopback(configuration.host())) return "attach host must be loopback";
            if (configuration.port() < 1 || configuration.port() > 65535) return "attach port must be between 1 and 65535";
        }
        if (configuration.request() == Request.LAUNCH && configuration.program().isBlank() && !configuration.fileExtensions().isEmpty()) {
            return "file extensions require a program launch target";
        }
        for (String extension : configuration.fileExtensions()) {
            if (extension == null || !extension.matches("\\.[a-z0-9][a-z0-9_-]*")) return "file extensions are invalid";
        }
        return null;
    }

    static PlanResult plan(Validation validation, String configurationName, Path workspace) {
        return plan(validation, configurationName, workspace, new LaunchContext(null, "", null));
    }

    static PlanResult plan(Validation validation, String configurationName, Path workspace, Path activeFile) {
        return plan(validation, configurationName, workspace, new LaunchContext(activeFile, "", null));
    }

    static PlanResult plan(Validation validation, String configurationName, Path workspace, LaunchContext context) {
        if (validation == null || !validation.valid()) return new PlanResult(null, "Debug configuration is invalid; no process will be launched.");
        Configuration configuration = validation.configurations().get(configurationName == null ? "" : configurationName);
        if (configuration == null) return new PlanResult(null, "Debug configuration is unavailable; no process will be launched.");
        Adapter adapter = validation.registry().adapter(configuration.adapter());
        if (adapter == null) return new PlanResult(null, "Debug adapter is unavailable; no process will be launched.");
        if (workspace == null) return new PlanResult(null, "Workspace root is required; no process will be launched.");
        Path root = workspace.toAbsolutePath().normalize();
        LaunchContext launchContext = context == null ? new LaunchContext(null, "", null) : context;
        Path cwd = resolveWorkspacePath(root, configuration.cwd(), launchContext);
        Path program = configuration.request() == Request.LAUNCH && !configuration.program().isBlank()
            ? resolveWorkspacePath(root, configuration.program(), launchContext) : null;
        if (cwd == null || (configuration.request() == Request.LAUNCH && !configuration.program().isBlank() && program == null)) {
            return new PlanResult(null, "Debug configuration escapes the workspace scope; no process will be launched.");
        }
        List<String> args = resolveArguments(configuration.args(), root, launchContext);
        if (args == null) return new PlanResult(null, "Debug configuration has invalid launch placeholders; no process will be launched.");
        if (configuration.request() == Request.LAUNCH && !matchesFileExtension(program, configuration.fileExtensions())) {
            return new PlanResult(null, "Debug configuration does not support this file type; no process will be launched.");
        }
        return new PlanResult(new Plan(adapter, configuration, root, cwd, program, configuration.module(), configuration.code(), args), "");
    }

    private static void collect(String key, String value, String prefix, Set<String> fields, Map<String, Map<String, String>> grouped, List<Error> errors) {
        if (!key.startsWith(prefix)) return;
        String[] parts = key.substring(prefix.length()).split("\\.", -1);
        if (parts.length != 2 || !identifier(parts[0]) || !fields.contains(parts[1])) {
            errors.add(new Error(key, "invalid debug key " + key));
            return;
        }
        grouped.computeIfAbsent(parts[0], ignored -> new LinkedHashMap<>()).put(parts[1], value);
    }

    private static Map<String, Adapter> parseAdapters(Map<String, Map<String, String>> source, List<Error> errors) {
        Map<String, Adapter> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            String id = entry.getKey(); Map<String, String> fields = entry.getValue(); String prefix = "debug.adapter." + id;
            Transport transport = transport(prefix + ".transport", fields.get("transport"), errors);
            String command = fields.getOrDefault("command", "").trim();
            if (transport == Transport.STDIO && !safeText(command)) errors.add(new Error(fieldKey(prefix, fields, "command"), prefix + ".command is required for stdio adapters"));
            if (transport == Transport.TCP && !command.isEmpty()) errors.add(new Error(prefix + ".command", prefix + ".command is unsupported for tcp adapters"));
            List<String> args = tokens(fields.get("args"));
            if (fields.containsKey("args") && args == null) errors.add(new Error(prefix + ".args", prefix + ".args contains an invalid control character"));
            Set<Capability> capabilities = capabilities(prefix + ".capabilities", fields.get("capabilities"), errors);
            if (errorsFor(errors, prefix)) continue;
            result.put(id, new Adapter(id, transport, command, args, capabilities));
        }
        return result;
    }

    private static Map<String, Configuration> parseConfigurations(Map<String, Map<String, String>> source, Map<String, Adapter> adapters,
        List<Error> errors) {
        Map<String, Configuration> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            String name = entry.getKey(); Map<String, String> fields = entry.getValue(); String prefix = "debug.configuration." + name;
            String adapterId = fields.getOrDefault("adapter", "").trim();
            if (!identifier(adapterId)) errors.add(new Error(fieldKey(prefix, fields, "adapter"), prefix + ".adapter is required"));
            Request request = request(fieldKey(prefix, fields, "request"), prefix + ".request", fields.get("request"), errors);
            String scope = fields.getOrDefault("scope", "workspace").trim();
            if (!"workspace".equals(scope)) errors.add(new Error(prefix + ".scope", prefix + ".scope must be workspace"));
            String cwd = fields.getOrDefault("cwd", "${workspaceFolder}").trim();
            if (!workspaceScoped(cwd, false)) errors.add(new Error(prefix + ".cwd", prefix + ".cwd must remain within ${workspaceFolder}"));
            String program = fields.getOrDefault("program", "").trim();
            String module = fields.getOrDefault("module", "").trim();
            String code = fields.getOrDefault("code", "");
            int launchTargets = (program.isBlank() ? 0 : 1) + (module.isBlank() ? 0 : 1) + (code.isBlank() ? 0 : 1);
            if (request == Request.LAUNCH) {
                if (launchTargets != 1) {
                    errors.add(new Error(prefix + ".program", prefix + " launch requires exactly one of program, module, or code"));
                } else if (!program.isBlank() && !workspaceScoped(program, true)) {
                    errors.add(new Error(prefix + ".program", prefix + ".program must remain within ${workspaceFolder} or ${file}"));
                } else if (!module.isBlank() && !safeModule(module)) {
                    errors.add(new Error(prefix + ".module", prefix + ".module must be a non-empty single-line identifier or dotted identifier"));
                } else if (!code.isBlank() && !safeCode(code)) {
                    errors.add(new Error(prefix + ".code", prefix + ".code must be non-empty, contain no NUL, and be at most 64 KiB"));
                }
            } else if (!module.isBlank() || !code.isBlank()) {
                errors.add(new Error(prefix + ".module", prefix + ".module and .code are launch-only"));
            }
            List<String> args = tokens(fields.get("args"));
            if (fields.containsKey("args") && args == null) errors.add(new Error(prefix + ".args", prefix + ".args contains an invalid control character"));
            String prelaunchTask = fields.getOrDefault("prelaunch_task", "").trim();
            if (!prelaunchTask.isEmpty() && !identifier(prelaunchTask)) {
                errors.add(new Error(prefix + ".prelaunch_task", prefix + ".prelaunch_task must be a task identifier"));
            }
            List<String> fileExtensions = fileExtensions(prefix + ".file_extensions", fields.get("file_extensions"), errors);
            if (request == Request.LAUNCH && program.isBlank() && !fileExtensions.isEmpty()) {
                errors.add(new Error(prefix + ".file_extensions", prefix + ".file_extensions requires a program launch target"));
            }
            Map<String, Object> adapterOptions = adapterOptions(prefix + ".adapter_options", fields.get("adapter_options"), errors);
            String host = fields.getOrDefault("host", "127.0.0.1").trim();
            int port = port(fieldKey(prefix, fields, "port"), prefix + ".port", fields.get("port"), request, errors);
            if (request == Request.ATTACH && !loopback(host)) errors.add(new Error(prefix + ".host", prefix + ".host must be loopback in M0"));
            Adapter adapter = adapters.get(adapterId);
            if (adapter == null) errors.add(new Error(fieldKey(prefix, fields, "adapter"), prefix + ".adapter is not registered"));
            else if (!adapter.supports(request)) errors.add(new Error(prefix + ".request", prefix + ".request is not supported by adapter " + adapterId));
            if (errorsFor(errors, prefix)) continue;
            result.put(name, new Configuration(name, adapterId, request, scope, program, module, code, cwd, args, prelaunchTask, host, port,
                fileExtensions, Map.of(), adapterOptions));
        }
        return result;
    }

    private static Transport transport(String key, String value, List<Error> errors) {
        if (value == null || value.isBlank() || "stdio".equalsIgnoreCase(value.trim())) return Transport.STDIO;
        if ("tcp".equalsIgnoreCase(value.trim())) return Transport.TCP;
        errors.add(new Error(key, key + " must be stdio or tcp")); return Transport.STDIO;
    }

    private static Request request(String key, String field, String value, List<Error> errors) {
        if ("launch".equalsIgnoreCase(value == null ? "" : value.trim())) return Request.LAUNCH;
        if ("attach".equalsIgnoreCase(value == null ? "" : value.trim())) return Request.ATTACH;
        errors.add(new Error(key, field + " must be launch or attach")); return Request.LAUNCH;
    }

    private static Set<Capability> capabilities(String key, String value, List<Error> errors) {
        Set<Capability> values = EnumSet.noneOf(Capability.class);
        if (value == null || value.isBlank()) return values;
        for (String raw : value.split(",")) {
            try { values.add(Capability.valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException error) { errors.add(new Error(key, key + " contains unsupported capability " + raw.trim())); }
        }
        return values;
    }

    private static List<String> fileExtensions(String key, String value, List<Error> errors) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String raw : value.split(",")) {
            String extension = raw.trim().toLowerCase(Locale.ROOT);
            if (!extension.matches("\\.[a-z0-9][a-z0-9_-]*")) {
                errors.add(new Error(key, key + " must be comma-separated file extensions such as .py,.pyw"));
                return List.of();
            }
            if (!result.contains(extension)) result.add(extension);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> adapterOptions(String key, String value, List<Error> errors) {
        if (value == null || value.isBlank()) return Map.of();
        if (value.length() > 64 * 1024) {
            errors.add(new Error(key, key + " exceeds 64 KiB"));
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MiniJson.asObject(MiniJson.parse(value));
            if (parsed == null || !safeAdapterOptions(parsed)) {
                errors.add(new Error(key, key + " must be a bounded JSON object with non-core adapter fields"));
                return Map.of();
            }
            return Map.copyOf(new LinkedHashMap<>(parsed));
        } catch (RuntimeException error) {
            errors.add(new Error(key, key + " must be a valid JSON object"));
            return Map.of();
        }
    }

    private static int port(String key, String field, String value, Request request, List<Error> errors) {
        if (value == null || value.isBlank()) {
            if (request == Request.ATTACH) errors.add(new Error(key, field + " is required for attach"));
            return 0;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException error) {
            errors.add(new Error(key, field + " must be an integer between 1 and 65535")); return 0;
        }
    }

    private static boolean errorsFor(List<Error> errors, String prefix) {
        return errors.stream().anyMatch(error -> error.key().startsWith(prefix + "."));
    }

    private static String fieldKey(String prefix, Map<String, String> fields, String field) {
        if (fields.containsKey(field)) return prefix + "." + field;
        return prefix + "." + fields.keySet().iterator().next();
    }

    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_-]+"); }
    private static boolean safeText(String value) { return value != null && !value.isBlank() && value.indexOf('\u0000') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0; }
    private static boolean safeArguments(List<String> values) {
        int length = 0;
        for (String value : values == null ? List.<String>of() : values) {
            if (value == null || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.length() > 8 * 1024) return false;
            length += value.length();
            if (length > 64 * 1024) return false;
        }
        return true;
    }
    static boolean safeEnvironment(Map<String, String> values) {
        if (values == null || values.size() > 100) return false;
        int length = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]*") || value == null || value.indexOf('\u0000') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.length() > 8 * 1024) return false;
            length += key.length() + value.length();
            if (length > 64 * 1024) return false;
        }
        return true;
    }
    static boolean safeAdapterOptions(Map<String, Object> values) {
        if (values == null || values.size() > 100) return false;
        int[] length = {0};
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]*") || coreAdapterOption(key)) return false;
            if (!safeAdapterOptionValue(entry.getValue(), 0, length)) return false;
        }
        return length[0] <= 64 * 1024;
    }
    private static boolean safeAdapterOptionValue(Object value, int depth, int[] length) {
        if (depth > 16 || length == null || length.length == 0) return false;
        if (value == null || value instanceof Boolean) return true;
        if (value instanceof Number number) return Double.isFinite(number.doubleValue());
        if (value instanceof String text) {
            if (text.length() > 8 * 1024 || text.indexOf('\u0000') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return false;
            length[0] += text.length();
            return length[0] <= 64 * 1024;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 100) return false;
            for (Object item : list) if (!safeAdapterOptionValue(item, depth + 1, length)) return false;
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 100) return false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !key.matches("[A-Za-z_][A-Za-z0-9_]*")) return false;
                length[0] += key.length();
                if (length[0] > 64 * 1024 || !safeAdapterOptionValue(entry.getValue(), depth + 1, length)) return false;
            }
            return true;
        }
        return false;
    }
    private static boolean coreAdapterOption(String key) {
        return switch (key) {
            case "program", "module", "code", "cwd", "args", "env", "host", "port", "request" -> true;
            default -> false;
        };
    }
    private static boolean safeModule(String value) { return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"); }
    private static boolean safeCode(String value) { return value != null && !value.isBlank() && value.length() <= 64 * 1024 && value.indexOf('\u0000') < 0; }
    private static boolean loopback(String host) { return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host); }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        if (!safeText(value)) return null;
        return List.of(value.trim().split("\\s+"));
    }

    private static boolean workspaceScoped(String value, boolean program) {
        if (!safeText(value)) return false;
        if (program && ("${file}".equals(value) || "${testFile}".equals(value))) return true;
        String prefix = "${workspaceFolder}";
        if (!value.startsWith(prefix)) return false;
        String suffix = value.substring(prefix.length());
        return suffix.isEmpty() || ((suffix.startsWith("/") || suffix.startsWith("\\")) && noParentSegment(suffix));
    }

    private static boolean noParentSegment(String value) {
        for (String segment : value.split("[/\\\\]")) if ("..".equals(segment)) return false;
        return true;
    }

    private static boolean matchesFileExtension(Path program, List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) return true;
        if (program == null || program.getFileName() == null) return false;
        String name = program.getFileName().toString().toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(name::endsWith);
    }

    private static Path resolveWorkspacePath(Path workspace, String configured, LaunchContext context) {
        if ("${file}".equals(configured) || "${testFile}".equals(configured)) {
            Path selected = "${file}".equals(configured) ? context.activeFile() : context.testFile();
            if (selected == null) return null;
            Path file = selected.toAbsolutePath().normalize();
            return file.startsWith(workspace) ? file : null;
        }
        String suffix = configured.substring("${workspaceFolder}".length());
        Path resolved = suffix.isEmpty() ? workspace : workspace.resolve(suffix.substring(1).replace('\\', '/')).normalize();
        return resolved.startsWith(workspace) ? resolved : null;
    }

    private static List<String> resolveArguments(List<String> configured, Path workspace, LaunchContext context) {
        List<String> resolved = new ArrayList<>();
        for (String value : configured == null ? List.<String>of() : configured) {
            String argument = value == null ? "" : value;
            int index = 0;
            StringBuilder output = new StringBuilder();
            while (index < argument.length()) {
                int start = argument.indexOf("${", index);
                if (start < 0) { output.append(argument, index, argument.length()); break; }
                output.append(argument, index, start);
                int end = argument.indexOf('}', start + 2);
                if (end < 0) return null;
                String token = argument.substring(start, end + 1);
                String replacement = resolveArgumentVariable(token, workspace, context);
                if (replacement == null) return null;
                output.append(replacement);
                index = end + 1;
            }
            resolved.add(output.toString());
        }
        return List.copyOf(resolved);
    }

    private static String resolveArgumentVariable(String token, Path workspace, LaunchContext context) {
        if ("${workspaceFolder}".equals(token)) return workspace.toString();
        if ("${workspaceFolderBasename}".equals(token)) {
            Path name = workspace.getFileName();
            return name == null ? null : name.toString();
        }
        if ("${testFile}".equals(token)) return workspaceFile(workspace, context.testFile());
        if ("${testId}".equals(token)) return safeTestId(context.testId()) ? context.testId() : null;
        if (!FILE_ARGUMENT_VARIABLES.contains(token)) return null;
        Path file = workspaceFilePath(workspace, context.activeFile());
        if (file == null) return null;
        if ("${file}".equals(token)) return file.toString();
        if ("${fileWorkspaceFolder}".equals(token)) return workspace.toString();
        Path relative = workspace.relativize(file);
        if ("${relativeFile}".equals(token)) return relative.toString();
        if ("${relativeFileDirname}".equals(token)) {
            Path parent = relative.getParent();
            return parent == null ? "." : parent.toString();
        }
        Path name = file.getFileName();
        if (name == null) return null;
        String basename = name.toString();
        if ("${fileBasename}".equals(token)) return basename;
        int extension = basename.lastIndexOf('.');
        if ("${fileBasenameNoExtension}".equals(token)) return extension <= 0 ? basename : basename.substring(0, extension);
        if ("${fileExtname}".equals(token)) return extension <= 0 ? "" : basename.substring(extension);
        Path parent = file.getParent();
        if (parent == null) return null;
        if ("${fileDirname}".equals(token)) return parent.toString();
        Path parentName = parent.getFileName();
        return parentName == null ? null : parentName.toString();
    }

    private static String workspaceFile(Path workspace, Path file) {
        Path normalized = workspaceFilePath(workspace, file);
        return normalized == null ? null : normalized.toString();
    }

    private static Path workspaceFilePath(Path workspace, Path file) {
        if (file == null) return null;
        Path normalized = file.toAbsolutePath().normalize();
        return normalized.startsWith(workspace) ? normalized : null;
    }

    private static boolean safeTestId(String value) {
        return value != null && !value.isBlank() && value.length() <= 4096 && value.indexOf('\0') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }
}
