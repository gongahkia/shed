package shed;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict bounded JSONC parser for workspace compatibility readers. */
final class Jsonc {
    private Jsonc() {
    }

    static Map<String, Object> parseObject(String text) throws IOException {
        Object value = new Parser(text).parseDocument();
        Map<String, Object> result = MiniJson.asObject(value);
        if (result == null) throw new IOException("the JSONC root must be an object");
        return result;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        private final String text;
        private int index;
        private int depth;

        Parser(String text) { this.text = text == null ? "" : text; }

        Object parseDocument() throws IOException {
            skipIgnored();
            Object result = parseValue();
            skipIgnored();
            if (index != text.length()) throw error("unexpected content");
            return result;
        }

        private Object parseValue() throws IOException {
            skipIgnored();
            if (index >= text.length()) throw error("expected a value");
            return switch (text.charAt(index)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() throws IOException {
            enter();
            try {
                expect('{');
                skipIgnored();
                Map<String, Object> result = new LinkedHashMap<>();
                if (consume('}')) return result;
                while (true) {
                    skipIgnored();
                    if (index >= text.length() || text.charAt(index) != '"') throw error("object keys must be strings");
                    String key = parseString();
                    if (result.containsKey(key)) throw error("duplicate object key '" + oneLine(key) + "'");
                    skipIgnored();
                    expect(':');
                    result.put(key, parseValue());
                    skipIgnored();
                    if (consume('}')) return result;
                    expect(',');
                    skipIgnored();
                    if (consume('}')) return result;
                }
            } finally { exit(); }
        }

        private List<Object> parseArray() throws IOException {
            enter();
            try {
                expect('[');
                skipIgnored();
                List<Object> result = new ArrayList<>();
                if (consume(']')) return result;
                while (true) {
                    result.add(parseValue());
                    skipIgnored();
                    if (consume(']')) return result;
                    expect(',');
                    skipIgnored();
                    if (consume(']')) return result;
                }
            } finally { exit(); }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char value = text.charAt(index++);
                if (value == '"') return result.toString();
                if (value < 0x20) throw error("unescaped control character in string");
                if (value != '\\') { result.append(value); continue; }
                if (index >= text.length()) throw error("unfinished escape sequence");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append((char) unicode());
                    default -> throw error("invalid escape sequence");
                }
            }
            throw error("unterminated string");
        }

        private int unicode() throws IOException {
            if (index + 4 > text.length()) throw error("unfinished unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) throw error("invalid unicode escape");
                value = (value << 4) | digit;
            }
            return value;
        }

        private Object literal(String literal, Object value) throws IOException {
            if (!text.startsWith(literal, index)) throw error("invalid literal");
            index += literal.length();
            return value;
        }

        private Number parseNumber() throws IOException {
            int start = index;
            if (consume('-') && index >= text.length()) throw error("invalid number");
            if (consume('0')) {
                if (index < text.length() && Character.isDigit(text.charAt(index))) throw error("invalid number");
            } else {
                if (index >= text.length() || !Character.isDigit(text.charAt(index))) throw error("invalid value");
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            }
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                if (index >= text.length() || !Character.isDigit(text.charAt(index))) throw error("invalid number");
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
                if (index >= text.length() || !Character.isDigit(text.charAt(index))) throw error("invalid number");
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            }
            String raw = text.substring(start, index);
            try { return decimal ? Double.parseDouble(raw) : Long.parseLong(raw); }
            catch (NumberFormatException error) { throw error("number is out of range"); }
        }

        private void skipIgnored() throws IOException {
            boolean advanced;
            do {
                advanced = false;
                while (index < text.length() && Character.isWhitespace(text.charAt(index))) { index++; advanced = true; }
                if (index + 1 >= text.length() || text.charAt(index) != '/') continue;
                char next = text.charAt(index + 1);
                if (next == '/') {
                    index += 2;
                    while (index < text.length() && text.charAt(index) != '\n' && text.charAt(index) != '\r') index++;
                    advanced = true;
                } else if (next == '*') {
                    int end = text.indexOf("*/", index + 2);
                    if (end < 0) throw error("unterminated block comment");
                    index = end + 2;
                    advanced = true;
                }
            } while (advanced);
        }

        private void enter() throws IOException { if (++depth > MAX_DEPTH) throw error("JSON nesting exceeds " + MAX_DEPTH); }
        private void exit() { depth--; }
        private boolean consume(char expected) { if (index < text.length() && text.charAt(index) == expected) { index++; return true; } return false; }
        private void expect(char expected) throws IOException { if (!consume(expected)) throw error("expected '" + expected + "'"); }
        private IOException error(String message) { return new IOException(message + " at character " + index); }
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }
}
