package shed;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DebugAdapterDetector {
    enum Availability { AVAILABLE, DISABLED, EXECUTABLE_MISSING, INVALID_CONFIGURATION }
    enum VersionState { NOT_PROBED }
    enum CapabilityState { AVAILABLE, DISABLED, UNDECLARED }

    interface ExecutableResolver {
        Path resolve(String command, Path workspace);
    }

    record AdapterReport(String id, String command, String executable, Availability availability, VersionState versionState,
        Map<DebugAdapterRegistry.Capability, CapabilityState> capabilities, String remediation) {
        AdapterReport {
            id = id == null ? "" : id;
            command = command == null ? "" : command;
            executable = executable == null ? "" : executable;
            availability = availability == null ? Availability.INVALID_CONFIGURATION : availability;
            versionState = versionState == null ? VersionState.NOT_PROBED : versionState;
            EnumMap<DebugAdapterRegistry.Capability, CapabilityState> states = new EnumMap<>(DebugAdapterRegistry.Capability.class);
            if (capabilities != null) states.putAll(capabilities);
            capabilities = Collections.unmodifiableMap(states);
            remediation = remediation == null ? "" : remediation;
        }

        boolean usable() { return availability == Availability.AVAILABLE; }
    }

    record ConfigurationReport(String name, String adapter, DebugAdapterRegistry.Request request, Availability availability,
        String remediation) {
        ConfigurationReport {
            name = name == null ? "" : name;
            adapter = adapter == null ? "" : adapter;
            request = request == null ? DebugAdapterRegistry.Request.LAUNCH : request;
            availability = availability == null ? Availability.INVALID_CONFIGURATION : availability;
            remediation = remediation == null ? "" : remediation;
        }

        boolean usable() { return availability == Availability.AVAILABLE; }
    }

    record WorkspaceReport(Path workspace, boolean normalEditingAvailable, List<AdapterReport> adapters,
        List<ConfigurationReport> configurations, List<String> validationErrors) {
        WorkspaceReport {
            workspace = workspace == null ? null : workspace.toAbsolutePath().normalize();
            adapters = adapters == null ? List.of() : List.copyOf(adapters);
            configurations = configurations == null ? List.of() : List.copyOf(configurations);
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        }

        boolean validConfiguration() { return validationErrors.isEmpty(); }
    }

    private final ExecutableResolver resolver;

    DebugAdapterDetector(ExecutableResolver resolver) {
        this.resolver = resolver == null ? DebugAdapterDetector::resolveOnPath : resolver;
    }

    WorkspaceReport detect(Path workspace, DebugAdapterRegistry.Validation validation, DebugFeatureSettings features) {
        Path root = workspace == null ? null : workspace.toAbsolutePath().normalize();
        DebugFeatureSettings settings = features == null ? DebugFeatureSettings.defaults() : features;
        if (validation == null) {
            return new WorkspaceReport(root, true, List.of(), List.of(), List.of("Debug configuration has not been loaded"));
        }
        Map<String, AdapterReport> adapters = new LinkedHashMap<>();
        for (Map.Entry<String, DebugAdapterRegistry.Adapter> entry : validation.registry().adapters().entrySet()) {
            adapters.put(entry.getKey(), inspectAdapter(entry.getValue(), root, settings));
        }
        List<ConfigurationReport> configurations = new ArrayList<>();
        for (DebugAdapterRegistry.Configuration configuration : validation.configurations().values()) {
            AdapterReport adapter = adapters.get(configuration.adapter());
            configurations.add(inspectConfiguration(configuration, adapter, settings));
        }
        List<String> errors = validation.errors().stream().map(DebugAdapterRegistry.Error::message).toList();
        return new WorkspaceReport(root, true, new ArrayList<>(adapters.values()), configurations, errors);
    }

    private AdapterReport inspectAdapter(DebugAdapterRegistry.Adapter adapter, Path workspace, DebugFeatureSettings settings) {
        String id = adapter.id();
        if (!settings.enabled()) {
            return new AdapterReport(id, adapter.command(), "", Availability.DISABLED, VersionState.NOT_PROBED,
                capabilities(adapter, settings), "Set debug.enabled=true before detecting or starting this adapter.");
        }
        Path executable = resolver.resolve(adapter.command(), workspace);
        if (executable == null) {
            return new AdapterReport(id, adapter.command(), "", Availability.EXECUTABLE_MISSING, VersionState.NOT_PROBED,
                capabilities(adapter, settings), "Install the adapter or set debug.adapter." + id + ".command to an executable on PATH.");
        }
        return new AdapterReport(id, adapter.command(), executable.toString(), Availability.AVAILABLE, VersionState.NOT_PROBED,
            capabilities(adapter, settings), "Adapter version is adapter-specific and is not executed during read-only detection.");
    }

    private ConfigurationReport inspectConfiguration(DebugAdapterRegistry.Configuration configuration, AdapterReport adapter,
        DebugFeatureSettings settings) {
        if (!settings.enabled()) {
            return new ConfigurationReport(configuration.name(), configuration.adapter(), configuration.request(), Availability.DISABLED,
                "Set debug.enabled=true before starting this configuration.");
        }
        if (configuration.request() == DebugAdapterRegistry.Request.ATTACH && !settings.attach()) {
            return new ConfigurationReport(configuration.name(), configuration.adapter(), configuration.request(), Availability.DISABLED,
                "Set debug.attach.enabled=true before starting an attach configuration.");
        }
        if (adapter == null) {
            return new ConfigurationReport(configuration.name(), configuration.adapter(), configuration.request(), Availability.INVALID_CONFIGURATION,
                "Register the referenced debug adapter before starting this configuration.");
        }
        if (!adapter.usable()) {
            return new ConfigurationReport(configuration.name(), configuration.adapter(), configuration.request(), adapter.availability(), adapter.remediation());
        }
        CapabilityState requestCapability = adapter.capabilities().get(configuration.request() == DebugAdapterRegistry.Request.LAUNCH
            ? DebugAdapterRegistry.Capability.LAUNCH : DebugAdapterRegistry.Capability.ATTACH);
        if (requestCapability != CapabilityState.AVAILABLE) {
            return new ConfigurationReport(configuration.name(), configuration.adapter(), configuration.request(), Availability.INVALID_CONFIGURATION,
                "Enable the required adapter capability before starting this configuration.");
        }
        return new ConfigurationReport(configuration.name(), configuration.adapter(), configuration.request(), Availability.AVAILABLE,
            "Adapter is configured; start remains explicit.");
    }

    private static Map<DebugAdapterRegistry.Capability, CapabilityState> capabilities(DebugAdapterRegistry.Adapter adapter,
        DebugFeatureSettings settings) {
        EnumMap<DebugAdapterRegistry.Capability, CapabilityState> result = new EnumMap<>(DebugAdapterRegistry.Capability.class);
        for (DebugAdapterRegistry.Capability capability : DebugAdapterRegistry.Capability.values()) {
            if (!adapter.capabilities().contains(capability)) {
                result.put(capability, CapabilityState.UNDECLARED);
            } else if (!settings.enabled() || !featureEnabled(capability, settings)) {
                result.put(capability, CapabilityState.DISABLED);
            } else {
                result.put(capability, CapabilityState.AVAILABLE);
            }
        }
        return result;
    }

    private static boolean featureEnabled(DebugAdapterRegistry.Capability capability, DebugFeatureSettings settings) {
        return switch (capability) {
            case ATTACH -> settings.attach();
            case BREAKPOINTS, CONDITIONAL_BREAKPOINTS, HIT_CONDITIONAL_BREAKPOINTS, LOG_POINTS -> settings.breakpoints();
            case THREADS -> settings.threads();
            case STACK_TRACE -> settings.stackTrace();
            case SCOPES -> settings.scopes();
            case VARIABLES -> settings.variables();
            case EVALUATE -> settings.evaluate();
            case LAUNCH, CONFIGURATION_DONE, CONTINUE, NEXT, STEP_IN, STEP_OUT, PAUSE -> true;
        };
    }

    private static Path resolveOnPath(String command, Path workspace) {
        if (command == null || command.isBlank()) return null;
        Path direct = Path.of(command);
        if (direct.isAbsolute() || command.contains("/") || command.contains("\\")) {
            Path candidate = direct.isAbsolute() ? direct : workspace == null ? direct : workspace.resolve(direct).normalize();
            return executable(candidate) ? candidate.toAbsolutePath().normalize() : null;
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) continue;
            for (String name : executableNames(command)) {
                Path candidate = Path.of(directory, name);
                if (executable(candidate)) return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static List<String> executableNames(String command) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") || command.contains(".")) return List.of(command);
        String extensions = System.getenv("PATHEXT");
        if (extensions == null || extensions.isBlank()) extensions = ".COM;.EXE;.BAT;.CMD";
        List<String> names = new ArrayList<>();
        names.add(command);
        for (String extension : extensions.split(";")) if (!extension.isBlank()) names.add(command + extension.toLowerCase(Locale.ROOT));
        return names;
    }

    private static boolean executable(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
