package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the small, lossless DAP subset of a workspace {@code .vscode/launch.json}.
 *
 * <p>The file is never persisted into Shed configuration and never supplies an
 * adapter command. A profile can only refer to an adapter the user or an extension
 * has already configured in Shed. This keeps an explicit compatibility bridge from
 * widening the trusted executable-configuration boundary.</p>
 */
final class VsCodeLaunchConfigurationImporter {
    static final String NAME_PREFIX = "vscode:";
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final int MAX_CONFIGURATIONS = 100;
    private static final Set<String> SUPPORTED_FIELDS = Set.of("name", "type", "request", "program", "module", "code", "cwd", "args",
        "preLaunchTask", "host", "port");

    record Report(Path source, Map<String, DebugAdapterRegistry.Configuration> configurations, List<String> accepted, List<String> skipped,
        String failure) {
        Report {
            source = source == null ? null : source.toAbsolutePath().normalize();
            configurations = configurations == null ? Map.of() : Map.copyOf(configurations);
            accepted = accepted == null ? List.of() : List.copyOf(accepted);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
            failure = failure == null ? "" : failure;
        }

        boolean present() { return source != null; }
        boolean readable() { return present() && failure.isEmpty(); }
    }

    private VsCodeLaunchConfigurationImporter() {
    }

    static Report read(Path workspace, DebugAdapterRegistry.Validation base) {
        if (workspace == null || !Files.isDirectory(workspace)) return new Report(null, Map.of(), List.of(), List.of(), "Workspace root is unavailable.");
        Path root = workspace.toAbsolutePath().normalize();
        Path source = root.resolve(".vscode").resolve("launch.json").normalize();
        if (!source.startsWith(root) || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return new Report(null, Map.of(), List.of(), List.of(), "");
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return new Report(source, Map.of(), List.of(), List.of(), "launch.json is not a regular file.");
        }
        try {
            long size = Files.size(source);
            if (size > MAX_BYTES) return new Report(source, Map.of(), List.of(), List.of(), "launch.json exceeds the 1 MiB import limit.");
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Map<String, Object> document = Jsonc.parseObject(text);
            return importDocument(source, document, base);
        } catch (IOException | IllegalArgumentException error) {
            String detail = error.getMessage();
            if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
            return new Report(source, Map.of(), List.of(), List.of(), "launch.json could not be read: " + oneLine(detail));
        }
    }

    private static Report importDocument(Path source, Map<String, Object> document, DebugAdapterRegistry.Validation base) {
        Object entriesValue = document == null ? null : document.get("configurations");
        List<Object> entries = MiniJson.asArray(entriesValue);
        if (entries == null) return new Report(source, Map.of(), List.of(), List.of(), "launch.json must contain a configurations array.");
        if (entries.size() > MAX_CONFIGURATIONS) {
            return new Report(source, Map.of(), List.of(), List.of(), "launch.json has more than " + MAX_CONFIGURATIONS + " configurations.");
        }
        DebugAdapterRegistry.Validation validation = base == null ? DebugAdapterRegistry.validate(Map.of()) : base;
        Map<String, DebugAdapterRegistry.Configuration> imported = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>(validation.configurations().keySet());
        List<String> accepted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = MiniJson.asObject(entries.get(index));
            String prefix = "configuration " + (index + 1);
            if (entry == null) {
                skipped.add(prefix + ": entry must be an object.");
                continue;
            }
            ImportResult result = importEntry(entry, validation, names);
            if (result.error() != null) skipped.add(prefix + ": " + result.error());
            else {
                imported.put(result.configuration().name(), result.configuration());
                names.add(result.configuration().name());
                accepted.add(result.configuration().name());
            }
        }
        return new Report(source, imported, accepted, skipped, "");
    }

    private record ImportResult(DebugAdapterRegistry.Configuration configuration, String error) {
        static ImportResult rejected(String error) { return new ImportResult(null, error); }
    }

    private static ImportResult importEntry(Map<String, Object> entry, DebugAdapterRegistry.Validation validation, Set<String> names) {
        Set<String> unsupported = new LinkedHashSet<>(entry.keySet());
        unsupported.removeAll(SUPPORTED_FIELDS);
        if (!unsupported.isEmpty()) return ImportResult.rejected("uses unsupported field" + (unsupported.size() == 1 ? " " : "s ")
            + String.join(", ", unsupported.stream().sorted().toList()) + "; it was not altered or started.");
        String label = requiredText(entry, "name");
        if (label == null) return ImportResult.rejected("name must be a non-empty, single-line string of at most 120 characters.");
        String type = requiredText(entry, "type");
        if (type == null) return ImportResult.rejected("type must be a non-empty, single-line string of at most 120 characters.");
        String adapter = adapterFor(type, validation == null ? Map.of() : validation.registry().adapters());
        if (adapter == null) return ImportResult.rejected("type '" + oneLine(type) + "' has no matching configured Shed adapter.");
        String requestValue = requiredText(entry, "request");
        DebugAdapterRegistry.Request request = request(requestValue);
        if (request == null) return ImportResult.rejected("request must be launch or attach.");
        if (request == DebugAdapterRegistry.Request.LAUNCH && (entry.containsKey("host") || entry.containsKey("port"))) {
            return ImportResult.rejected("host and port are attach-only; the profile was not altered or started.");
        }
        String program = optionalText(entry, "program");
        String module = optionalText(entry, "module");
        String code = optionalText(entry, "code");
        String cwd = optionalText(entry, "cwd");
        String preLaunchTask = optionalText(entry, "preLaunchTask");
        String host = optionalText(entry, "host");
        if (program == null && entry.containsKey("program") || module == null && entry.containsKey("module") || code == null && entry.containsKey("code")
            || cwd == null && entry.containsKey("cwd") || preLaunchTask == null && entry.containsKey("preLaunchTask")
            || host == null && entry.containsKey("host")) {
            return ImportResult.rejected("program, module, code, cwd, preLaunchTask, and host must be strings when present.");
        }
        List<String> args = stringArray(entry.get("args"));
        if (args == null || entry.containsKey("args") && MiniJson.asArray(entry.get("args")) == null) {
            return ImportResult.rejected("args must be an array of strings when present.");
        }
        Integer port = integer(entry.get("port"));
        if (port == null && entry.containsKey("port")) return ImportResult.rejected("port must be an integer when present.");
        String name = uniqueName(label, names);
        DebugAdapterRegistry.Configuration configuration = new DebugAdapterRegistry.Configuration(name, adapter, request, "workspace",
            program == null ? "" : program, module == null ? "" : module, code == null ? "" : code,
            cwd == null || cwd.isBlank() ? "${workspaceFolder}" : cwd, args, preLaunchTask == null ? "" : preLaunchTask,
            host == null || host.isBlank() ? "127.0.0.1" : host, port == null ? 0 : port, List.of());
        String error = DebugAdapterRegistry.externalConfigurationError(configuration,
            validation == null ? Map.of() : validation.registry().adapters());
        return error == null ? new ImportResult(configuration, null) : ImportResult.rejected(error + ".");
    }

    private static String uniqueName(String label, Set<String> used) {
        String base = NAME_PREFIX + label;
        String result = base;
        int suffix = 2;
        while (used.contains(result)) result = base + " (" + suffix++ + ")";
        return result;
    }

    private static String adapterFor(String type, Map<String, DebugAdapterRegistry.Adapter> adapters) {
        if (adapters == null || adapters.isEmpty()) return null;
        if ("python".equalsIgnoreCase(type) || "debugpy".equalsIgnoreCase(type)) {
            if (adapters.containsKey(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY)) return BuiltInDebugAdapterSupport.PYTHON_DEBUGPY;
        }
        for (String id : adapters.keySet()) if (id.equalsIgnoreCase(type)) return id;
        return null;
    }

    private static DebugAdapterRegistry.Request request(String value) {
        if ("launch".equalsIgnoreCase(value)) return DebugAdapterRegistry.Request.LAUNCH;
        if ("attach".equalsIgnoreCase(value)) return DebugAdapterRegistry.Request.ATTACH;
        return null;
    }

    private static String requiredText(Map<String, Object> object, String field) {
        String value = optionalText(object, field);
        return validText(value) ? value : null;
    }

    private static String optionalText(Map<String, Object> object, String field) {
        return object == null ? null : MiniJson.asString(object.get(field));
    }

    private static boolean validText(String value) {
        if (value == null || value.isBlank() || value.length() > 120) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }

    private static List<String> stringArray(Object value) {
        if (value == null) return List.of();
        List<Object> values = MiniJson.asArray(value);
        if (values == null || values.size() > 256) return null;
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String string = MiniJson.asString(item);
            if (string == null) return null;
            result.add(string);
        }
        return List.copyOf(result);
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Long number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return null;
        return number.intValue();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    /** Small strict JSONC parser: comments and trailing commas are allowed, malformed input is rejected. */
    private static final class Jsonc {
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
    }
}
