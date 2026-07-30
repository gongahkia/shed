package shed;

import java.util.Locale;

enum KeymapProfile {
    VIM("vim"),
    PLAIN("plain"),
    EMACS("emacs");

    static final String CONFIG_KEY = "keymap.profile";

    private final String configValue;

    KeymapProfile(String configValue) {
        this.configValue = configValue;
    }

    String configValue() {
        return configValue;
    }

    static KeymapProfile fromConfig(String value) {
        if (value == null) {
            return VIM;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "plain" -> PLAIN;
            case "emacs" -> EMACS;
            default -> VIM;
        };
    }

    boolean usesVimModeHandling() {
        return this == VIM;
    }
}
