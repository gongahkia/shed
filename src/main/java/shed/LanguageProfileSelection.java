package shed;

import shed.api.LanguageProfile;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Buffer-local override for extension language-profile detection. */
final class LanguageProfileSelection {
    private final Map<FileBuffer, String> selected = Collections.synchronizedMap(new WeakHashMap<>());

    LanguageProfile profileFor(FileBuffer buffer, ExtensionRegistry registry) {
        if (buffer == null || registry == null) return null;
        String requested = selected.get(buffer);
        if (requested != null) {
            ExtensionRegistry.Owned<LanguageProfile> profile = find(registry, requested);
            if (profile != null) return profile.value();
            selected.remove(buffer);
        }
        if (buffer.getFile() == null) return null;
        ExtensionRegistry.Owned<LanguageProfile> automatic = registry.languageProfileFor(buffer.getFile(), buffer.textSnapshot().text());
        return automatic == null ? null : automatic.value();
    }

    LanguageProfile select(FileBuffer buffer, ExtensionRegistry registry, String requested) {
        if (buffer == null) throw new IllegalArgumentException("current buffer is unavailable");
        ExtensionRegistry.Owned<LanguageProfile> profile = find(registry, requested);
        if (profile == null) throw new IllegalArgumentException("language profile is unavailable or ambiguous: " + requested);
        selected.put(buffer, qualified(profile));
        return profile.value();
    }

    void automatic(FileBuffer buffer) {
        if (buffer != null) selected.remove(buffer);
    }

    boolean isManual(FileBuffer buffer) {
        return buffer != null && selected.containsKey(buffer);
    }

    List<ExtensionRegistry.Owned<LanguageProfile>> profiles(ExtensionRegistry registry) {
        return registry == null ? List.of() : registry.languageProfiles();
    }

    private static ExtensionRegistry.Owned<LanguageProfile> find(ExtensionRegistry registry, String requested) {
        if (registry == null || requested == null || requested.isBlank()) return null;
        String key = requested.trim().toLowerCase(Locale.ROOT);
        ExtensionRegistry.Owned<LanguageProfile> match = null;
        for (ExtensionRegistry.Owned<LanguageProfile> candidate : registry.languageProfiles()) {
            if (qualified(candidate).equalsIgnoreCase(key)) return candidate;
            if (!candidate.value().languageId().equalsIgnoreCase(key)) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    static String qualified(ExtensionRegistry.Owned<LanguageProfile> profile) {
        return profile.extensionId() + ":" + profile.value().languageId();
    }
}
