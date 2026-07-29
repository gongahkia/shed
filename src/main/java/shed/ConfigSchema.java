package shed;

import org.tomlj.TomlParseResult;

import java.util.List;

final class ConfigSchema {
    static final String VERSION_KEY = "schema_version";
    static final long VERSION = 1L;

    private ConfigSchema() {
    }

    static String versionError(TomlParseResult result) {
        Object value = result.get(VERSION_KEY);
        if (value == null) {
            return VERSION_KEY + " is required at the TOML root (expected " + VERSION + ")";
        }
        if (!(value instanceof Long)) {
            return VERSION_KEY + " must be integer " + VERSION;
        }
        long version = (Long) value;
        if (version != VERSION) {
            return "unsupported " + VERSION_KEY + " " + version + " (supported: " + VERSION + ")";
        }
        return null;
    }

    static boolean isVersionEntry(List<String> path) {
        return path.size() == 1 && VERSION_KEY.equals(path.get(0));
    }
}
