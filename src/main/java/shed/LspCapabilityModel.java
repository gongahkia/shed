package shed;

import java.util.EnumMap;
import java.util.Map;

final class LspCapabilityModel {
    enum Availability {
        AVAILABLE,
        DISABLED,
        UNSUPPORTED,
        UNINITIALIZED
    }

    private final EnumMap<LspCapability, Availability> availability;
    private final String serverName;
    private final String serverVersion;

    private LspCapabilityModel(EnumMap<LspCapability, Availability> availability, String serverName, String serverVersion) {
        this.availability = availability;
        this.serverName = serverName == null ? "" : serverName;
        this.serverVersion = serverVersion == null ? "" : serverVersion;
    }

    static LspCapabilityModel uninitialized() {
        EnumMap<LspCapability, Availability> availability = new EnumMap<>(LspCapability.class);
        for (LspCapability capability : LspCapability.values()) {
            availability.put(capability, Availability.UNINITIALIZED);
        }
        return new LspCapabilityModel(availability, "", "");
    }

    static LspCapabilityModel fromInitializeResult(Map<String, Object> response, Map<LspCapability, Boolean> clientEnabled) {
        Map<String, Object> result = MiniJson.asObject(response == null ? null : response.get("result"));
        Map<String, Object> serverCapabilities = MiniJson.asObject(result == null ? null : result.get("capabilities"));
        Map<String, Object> serverInfo = MiniJson.asObject(result == null ? null : result.get("serverInfo"));
        EnumMap<LspCapability, Availability> availability = new EnumMap<>(LspCapability.class);
        for (LspCapability capability : LspCapability.values()) {
            boolean enabled = clientEnabled == null || !Boolean.FALSE.equals(clientEnabled.get(capability));
            Object advertised = serverCapabilities == null ? null : serverCapabilities.get(capability.serverField());
            availability.put(capability, !enabled ? Availability.DISABLED
                : advertises(capability, advertised) ? Availability.AVAILABLE : Availability.UNSUPPORTED);
        }
        String serverName = MiniJson.asString(serverInfo == null ? null : serverInfo.get("name"));
        String serverVersion = MiniJson.asString(serverInfo == null ? null : serverInfo.get("version"));
        return new LspCapabilityModel(availability, serverName, serverVersion);
    }

    Availability availability(LspCapability capability) {
        return availability.getOrDefault(capability, Availability.UNINITIALIZED);
    }

    boolean allows(LspCapability capability) {
        return availability(capability) == Availability.AVAILABLE;
    }

    String unavailableReason(LspCapability capability) {
        return switch (availability(capability)) {
            case DISABLED -> "LSP " + capability.displayName() + " is disabled by client policy; enable it in LSP settings";
            case UNSUPPORTED -> "LSP " + capability.displayName() + " is unavailable: server did not advertise "
                + capability.serverField() + serverDescription();
            case UNINITIALIZED -> "LSP " + capability.displayName() + " is unavailable: server initialization is incomplete";
            case AVAILABLE -> "";
        };
    }

    String serverDescription() {
        if (serverName.isBlank()) {
            return "";
        }
        return serverVersion.isBlank() ? " (" + serverName + ")" : " (" + serverName + " " + serverVersion + ")";
    }

    private static boolean advertises(LspCapability capability, Object value) {
        if (capability == LspCapability.WORKSPACE_DIAGNOSTICS) {
            Map<String, Object> diagnosticProvider = MiniJson.asObject(value);
            return Boolean.TRUE.equals(diagnosticProvider == null ? null : diagnosticProvider.get("workspaceDiagnostics"));
        }
        return Boolean.TRUE.equals(value) || value instanceof Map<?, ?>;
    }
}
