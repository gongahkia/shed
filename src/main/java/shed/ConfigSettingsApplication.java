package shed;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Canonical validation/persistence use case; presentation is supplied by the composition root. */
final class ConfigSettingsApplication implements SettingsApplication {
    private final ConfigManager config;
    private final Runnable applied;

    ConfigSettingsApplication(ConfigManager config, Runnable applied) {
        this.config = Objects.requireNonNull(config);
        this.applied = Objects.requireNonNull(applied);
    }

    @Override public List<Setting> search(String query) {
        return config.searchTypedSettings(query).stream().map(d -> new Setting(d.key(), d.category(), d.type(),
            d.currentValue(), d.defaultValue(), d.description(), d.allowedValues(), d.applyBehavior())).toList();
    }

    @Override public String update(String key, String value) {
        if (key == null || key.isEmpty()) return "Error: Missing config key";
        if (config.isSchemaVersionKey(key)) return "Error: schema_version is managed by Shed";
        String requested = value == null ? "" : value;
        String repaired = config.selfHealedSettingValue(key, requested);
        String error = config.validateSettingValue(key, repaired);
        if (error != null) return "Error: " + error;
        try {
            config.setAndPersist(key, repaired);
        } catch (IOException failure) {
            return "Error saving config: " + failure.getMessage();
        }
        applied.run();
        return "Set and saved " + key + (Objects.equals(requested, repaired) ? ""
            : " (self-healed \"" + requested + "\" -> \"" + repaired + "\")");
    }
}
