import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MiniJson {
    private MiniJson() {}

    public static Object parse(String json) {
        return new Parser(json).parse();
    }

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    public static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    public static Integer asInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String) {
            builder.append('"').append(escape((String) value)).append('"');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
            return;
        }
        if (value instanceof Map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':');
                writeValue(builder, entry.getValue());
            }
            builder.append('}');
            return;
        }
        if (value instanceof List) {
            builder.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeValue(builder, item);
            }
            builder.append(']');
            return;
        }
        builder.append('"').append(escape(String.valueOf(value))).append('"');
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = json == null ? "" : json;
            this.index = 0;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= json.length()) {
                return null;
            }
            char c = json.charAt(index);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    index += 4;
                    return Boolean.TRUE;
                case 'f':
                    index += 5;
                    return Boolean.FALSE;
                case 'n':
                    index += 4;
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            while (index < json.length()) {
                String key = parseString();
                skipWhitespace();
                index++;
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    break;
                }
                index++;
                skipWhitespace();
            }
            return result;
        }

        private List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            index++;
            skipWhitespace();
            if (peek(']')) {
                index++;
                return result;
            }
            while (index < json.length()) {
                result.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    break;
                }
                index++;
                skipWhitespace();
            }
            return result;
        }

        private String parseString() {
            StringBuilder builder = new StringBuilder();
            index++;
            while (index < json.length()) {
                char c = json.charAt(index++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && index < json.length()) {
                    char escaped = json.charAt(index++);
                    switch (escaped) {
                        case '"':
                            builder.append('"');
                            break;
                        case '\\':
                            builder.append('\\');
                            break;
                        case '/':
                            builder.append('/');
                            break;
                        case 'b':
                            builder.append('\b');
                            break;
                        case 'f':
                            builder.append('\f');
                            break;
                        case 'n':
                            builder.append('\n');
                            break;
                        case 'r':
                            builder.append('\r');
                            break;
                        case 't':
                            builder.append('\t');
                            break;
                        case 'u':
                            String hex = json.substring(index, Math.min(index + 4, json.length()));
                            builder.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                            break;
                        default:
                            builder.append(escaped);
                            break;
                    }
                } else {
                    builder.append(c);
                }
            }
            return builder.toString();
        }

        private Number parseNumber() {
            int start = index;
            while (index < json.length()) {
                char c = json.charAt(index);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    index++;
                } else {
                    break;
                }
            }
            String raw = json.substring(start, index);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char expected) {
            return index < json.length() && json.charAt(index) == expected;
        }
    }
}
