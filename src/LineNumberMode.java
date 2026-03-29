// Line Number Mode Enum
// Supports bitsy-style absolute, relative, and hybrid gutters

public enum LineNumberMode {
    NONE,
    ABSOLUTE,
    RELATIVE,
    RELATIVE_ABSOLUTE;

    public static LineNumberMode fromConfigValue(String value) {
        if (value == null) {
            return ABSOLUTE;
        }

        switch (value.trim().toLowerCase()) {
            case "false":
            case "none":
            case "off":
                return NONE;
            case "true":
            case "number":
            case "absolute":
            case "nu":
                return ABSOLUTE;
            case "relative":
            case "relativenumber":
            case "rnu":
                return RELATIVE;
            case "relativeabsolute":
            case "hybrid":
                return RELATIVE_ABSOLUTE;
            default:
                return ABSOLUTE;
        }
    }

    public String toConfigValue() {
        switch (this) {
            case NONE:
                return "none";
            case RELATIVE:
                return "relative";
            case RELATIVE_ABSOLUTE:
                return "relativeabsolute";
            case ABSOLUTE:
            default:
                return "absolute";
        }
    }
}
