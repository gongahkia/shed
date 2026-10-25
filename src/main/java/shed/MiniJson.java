package shed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MiniJson {
    private MiniJson() {}

    public static Object parse(String json) {
        return new Parser(json).parse();
    }

    /** Strict, bounded decoding for external requests and versioned agent state. Legacy readers retain parse(). */
    public static Object parseStrict(String json) {
        if (json == null || json.isEmpty() || json.length() > 1024 * 1024)
            throw new IllegalArgumentException("JSON input is empty or exceeds its limit");
        return new StrictParser(json).parse();
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
            if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue && !Float.isFinite(floatValue))
                throw new IllegalArgumentException("JSON numbers must be finite");
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
        StringBuilder output = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    boolean unpaired = Character.isHighSurrogate(c) ? i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))
                        : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(value.charAt(i - 1)));
                    if (c < 32 || unpaired) {
                        output.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) output.append("0123456789abcdef".charAt(c >> shift & 15));
                    } else output.append(c);
                }
            }
        }
        return output.toString();
    }

    private static final class StrictParser {
        private final String json;
        private int index;
        StrictParser(String json) { this.json = json; }
        Object parse() {
            Object value = value(0); whitespace();
            if (index != json.length()) throw error("Trailing content");
            return value;
        }
        private Object value(int depth) {
            if (depth > 64) throw error("Nesting limit exceeded");
            whitespace();
            if (index == json.length()) throw error("Missing value");
            return switch (json.charAt(index)) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }
        private Map<String, Object> object(int depth) {
            index++; whitespace(); Map<String, Object> result = new LinkedHashMap<>();
            if (take('}')) return result;
            while (true) {
                whitespace();
                if (index == json.length() || json.charAt(index) != '"') throw error("Expected object key");
                String key = string(); whitespace(); expect(':');
                if (result.containsKey(key)) throw error("Duplicate object key");
                result.put(key, value(depth + 1)); whitespace();
                if (take('}')) return result;
                expect(',');
            }
        }
        private List<Object> array(int depth) {
            index++; whitespace(); List<Object> result = new ArrayList<>();
            if (take(']')) return result;
            while (true) {
                result.add(value(depth + 1)); whitespace();
                if (take(']')) return result;
                expect(',');
            }
        }
        private String string() {
            expect('"'); StringBuilder result = new StringBuilder();
            while (index < json.length()) {
                char c = json.charAt(index++);
                if (c == '"') return result.toString();
                if (c < 32) throw error("Unescaped control character");
                if (c != '\\') { result.append(c); continue; }
                if (index == json.length()) throw error("Incomplete escape");
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > json.length()) throw error("Incomplete Unicode escape");
                        int unit = 0;
                        for (int i = 0; i < 4; i++) {
                            char digit = json.charAt(index++);
                            int hex = digit >= '0' && digit <= '9' ? digit - '0' : digit >= 'a' && digit <= 'f' ? digit - 'a' + 10
                                : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
                            if (hex < 0) throw error("Invalid Unicode escape");
                            unit = unit * 16 + hex;
                        }
                        result.append((char) unit);
                    }
                    default -> throw error("Invalid escape");
                }
            }
            throw error("Unterminated string");
        }
        private Object literal(String expected, Object value) {
            if (!json.startsWith(expected, index)) throw error("Invalid literal");
            index += expected.length(); return value;
        }
        private Number number() {
            int start = index; take('-');
            if (take('0')) { if (digit()) throw error("Leading zero"); }
            else { if (!digit() || json.charAt(index) == '0') throw error("Expected number"); digits(); }
            if (take('.')) { if (!digit()) throw error("Missing fraction"); digits(); }
            if (take('e') || take('E')) { if (!take('+')) take('-'); if (!digit()) throw error("Missing exponent"); digits(); }
            String number = json.substring(start, index);
            if (number.length() > 256) throw error("Number limit exceeded");
            try {
                if (!number.contains(".") && !number.contains("e") && !number.contains("E")) {
                    try { return Long.parseLong(number); } catch (NumberFormatException large) { /* exact decimal fallback */ }
                }
                return new java.math.BigDecimal(number);
            } catch (NumberFormatException invalid) { throw error("Invalid number"); }
        }
        private void digits() { while (digit()) index++; }
        private boolean digit() { return index < json.length() && json.charAt(index) >= '0' && json.charAt(index) <= '9'; }
        private boolean take(char c) { if (index < json.length() && json.charAt(index) == c) { index++; return true; } return false; }
        private void expect(char c) { if (!take(c)) throw error("Expected '" + c + "'"); }
        private void whitespace() {
            while (index < json.length() && " \t\r\n".indexOf(json.charAt(index)) >= 0) index++;
        }
        private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at offset " + index); }
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
