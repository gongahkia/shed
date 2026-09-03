package shed;

import shed.api.DebugAdapterContribution;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts declared extension adapters into explicit, safe default launch configurations. */
final class ExtensionDebugAdapterSupport {
    private ExtensionDebugAdapterSupport() {
    }

    static DebugAdapterRegistry.Validation effective(DebugAdapterRegistry.Validation base, ExtensionRegistry registry) {
        Map<String, DebugAdapterRegistry.Adapter> adapters = new LinkedHashMap<>();
        if (registry != null) {
            for (ExtensionRegistry.Owned<DebugAdapterContribution> owned : registry.debuggers()) {
                DebugAdapterContribution value = owned.value();
                String id = configurationId(owned);
                adapters.put(id, new DebugAdapterRegistry.Adapter(id, DebugAdapterRegistry.Transport.STDIO, value.command().getFirst(),
                    joinedArguments(value), capabilities(value)));
            }
        }
        return DebugAdapterRegistry.withContributedAdapters(base, adapters);
    }

    static String configurationId(ExtensionRegistry.Owned<DebugAdapterContribution> owned) {
        if (owned == null || owned.value() == null) throw new IllegalArgumentException("debug contribution is required");
        return "extension-" + encode(owned.extensionId()) + "-" + encode(owned.value().id());
    }

    private static java.util.List<String> joinedArguments(DebugAdapterContribution value) {
        java.util.List<String> result = new java.util.ArrayList<>(value.command().subList(1, value.command().size()));
        result.addAll(value.arguments());
        return java.util.List.copyOf(result);
    }

    private static java.util.Set<DebugAdapterRegistry.Capability> capabilities(DebugAdapterContribution value) {
        EnumSet<DebugAdapterRegistry.Capability> result = EnumSet.noneOf(DebugAdapterRegistry.Capability.class);
        for (String raw : value.capabilities()) {
            try {
                result.add(DebugAdapterRegistry.Capability.valueOf(raw.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // API v1 permits future capability names; older Shed versions ignore them.
            }
        }
        return result;
    }

    private static String encode(String value) {
        StringBuilder result = new StringBuilder();
        for (byte part : value.getBytes(StandardCharsets.UTF_8)) result.append(String.format(java.util.Locale.ROOT, "%02x", part));
        return result.toString();
    }
}
