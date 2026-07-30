package shed;

import java.util.Locale;

enum KeymapProfile {
    VIM("vim"),
    PLAIN("plain");

    static final String CONFIG_KEY = "keymap.profile";

    private final String configValue;

    KeymapProfile(String configValue) {
        this.configValue = configValue;
    }

    String configValue() {
        return configValue;
    }

    static KeymapProfile fromConfig(String value) {
        if (value != null && PLAIN.configValue.equals(value.trim().toLowerCase(Locale.ROOT))) {
            return PLAIN;
        }
        return VIM;
    }
}
