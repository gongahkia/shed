package shed;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TypedSettings {
    private final Map<String, Object> defaults = new LinkedHashMap<>();
    private final Map<String, Object> values = new LinkedHashMap<>();

    void clearDefaults() {
        defaults.clear();
        values.clear();
    }

    void define(String key, Object value) {
        defaults.put(key, value);
    }

    void reset() {
        values.clear();
        values.putAll(defaults);
    }

    boolean knows(String key) {
        return defaults.containsKey(key);
    }

    Set<String> keys() {
        return Set.copyOf(defaults.keySet());
    }

    String validateToml(String key, Object value) {
        if (!knows(key)) {
            return value instanceof String ? null : key + " must be a TOML string";
        }
        Object defaultValue = defaults.get(key);
        if (defaultValue instanceof Boolean && !(value instanceof Boolean)) {
            return key + " must be TOML boolean";
        }
        if (defaultValue instanceof Integer && !(value instanceof Long)) {
            return key + " must be TOML integer";
        }
        if (defaultValue instanceof Long && !(value instanceof Long)) {
            return key + " must be TOML integer";
        }
        if (defaultValue instanceof Double && !(value instanceof Long || value instanceof Double)) {
            return key + " must be TOML number";
        }
        if (defaultValue instanceof String && !(value instanceof String)) {
            return key + " must be TOML string";
        }
        return valueError(key, value);
    }

    String validateRuntime(String key, String value) {
        if (!knows(key)) {
            return null;
        }
        Object defaultValue = defaults.get(key);
        try {
            Object typed;
            if (defaultValue instanceof Boolean) {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    return key + " must be boolean";
                }
                typed = Boolean.parseBoolean(value);
            } else if (defaultValue instanceof Integer) {
                typed = Long.parseLong(value);
            } else if (defaultValue instanceof Long) {
                typed = Long.parseLong(value);
            } else if (defaultValue instanceof Double) {
                typed = Double.parseDouble(value);
            } else {
                typed = value;
            }
            return valueError(key, typed);
        } catch (NumberFormatException error) {
            return key + " must be " + (defaultValue instanceof Double ? "number" : "integer");
        }
    }

    void apply(String key, Object value) {
        if (knows(key)) {
            values.put(key, coerce(value, defaults.get(key)));
        }
    }

    void applyRuntime(String key, String value) {
        if (!knows(key)) {
            return;
        }
        Object defaultValue = defaults.get(key);
        if (defaultValue instanceof Boolean) {
            values.put(key, Boolean.parseBoolean(value));
        } else if (defaultValue instanceof Integer || defaultValue instanceof Long) {
            values.put(key, coerce(Long.parseLong(value), defaultValue));
        } else if (defaultValue instanceof Double) {
            values.put(key, Double.parseDouble(value));
        } else {
            values.put(key, value);
        }
    }

    boolean booleanValue(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    int intValue(String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    long longValue(String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : fallback;
    }

    double doubleValue(String key, double fallback) {
        Object value = values.get(key);
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Integer) {
            return ((Integer) value).doubleValue();
        }
        if (value instanceof Long) {
            return ((Long) value).doubleValue();
        }
        return fallback;
    }

    String stringValue(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    String stringify(Object value) {
        return String.valueOf(value);
    }

    private Object coerce(Object value, Object defaultValue) {
        if (defaultValue instanceof Integer && value instanceof Long) {
            return Math.toIntExact((Long) value);
        }
        if (defaultValue instanceof Double && value instanceof Long) {
            return ((Long) value).doubleValue();
        }
        return value;
    }

    private String valueError(String key, Object value) {
        if (value instanceof Long) {
            long number = (Long) value;
            if (number < 0) {
                return key + " must be non-negative";
            }
            if (defaults.get(key) instanceof Integer && number > Integer.MAX_VALUE) {
                return key + " must be at most " + Integer.MAX_VALUE;
            }
            if ("tab.size".equals(key) && (number < 1 || number > 16)) {
                return key + " must be between 1 and 16";
            }
            if ("font.size".equals(key) && number < 1) {
                return key + " must be at least 1";
            }
            if ("ui.dramatic.sound.volume".equals(key) && number > 100) {
                return key + " must be between 0 and 100";
            }
            if ("ui.dramatic.animation.ms".equals(key) && number < 80) {
                return key + " must be at least 80";
            }
            if ("ui.dramatic.minimap.width".equals(key) && number < 40) {
                return key + " must be at least 40";
            }
            if ("ui.dramatic.performance.line.threshold".equals(key) && number < 1000) {
                return key + " must be at least 1000";
            }
        }
        if (value instanceof Double) {
            double number = (Double) value;
            if (!Double.isFinite(number)) {
                return key + " must be finite";
            }
            if ("ui.dramatic.performance.cpu.threshold".equals(key) && (number < 0.1 || number > 1.0)) {
                return key + " must be between 0.1 and 1.0";
            }
        }
        if ("line.numbers".equals(key) && value instanceof String) {
            String mode = ((String) value).trim().toLowerCase(Locale.ROOT);
            if (!mode.equals("none") && !mode.equals("absolute") && !mode.equals("relative")
                && !mode.equals("relativeabsolute") && !mode.equals("hybrid")) {
                return key + " must be none, absolute, relative, relativeabsolute, or hybrid";
            }
        }
        return null;
    }
}
