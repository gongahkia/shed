package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class TestService {
    static final String CONFIG_FILE = ".shedtests";
    static final int CONFIG_SCHEMA_VERSION = 1;
    private final TestAdapterRegistry adapters;

    enum Status {
        UNKNOWN, PASSED, FAILED, SKIPPED, ERRORED;
        boolean failed() { return this == FAILED || this == ERRORED; }
    }

    record TestCase(String adapterId, String id, String name, String suite, Path file, int line, Status status, long durationMillis, String output) {
        TestCase {
            adapterId = adapterId == null ? "" : adapterId;
            id = id == null || id.isBlank() ? name : id;
            name = name == null || name.isBlank() ? id : name;
            suite = suite == null ? "" : suite;
            line = Math.max(1, line);
            status = status == null ? Status.UNKNOWN : status;
            durationMillis = Math.max(0, durationMillis);
            output = output == null ? "" : output;
        }

        TestCase withStatus(Status nextStatus, long nextDurationMillis, String nextOutput) {
            return new TestCase(adapterId, id, name, suite, file, line, nextStatus, nextDurationMillis, nextOutput);
        }

        String label() { return suite.isBlank() ? name : suite + " › " + name; }
    }

    record AdapterSpec(String id, List<String> command) {
        AdapterSpec {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            command = command == null ? List.of() : List.copyOf(command);
        }
    }

    record Command(List<String> argv, List<Path> reports) {
        Command {
            argv = argv == null ? List.of() : List.copyOf(argv);
            reports = reports == null ? List.of() : List.copyOf(reports);
        }
        boolean executable() { return !argv.isEmpty(); }
    }

    record LoadResult(List<AdapterSpec> specs, List<String> diagnostics, boolean configured) {
        LoadResult {
            specs = specs == null ? List.of() : List.copyOf(specs);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
        boolean valid() { return diagnostics.isEmpty(); }
    }

    TestService() { this(new TestAdapterRegistry()); }
    TestService(TestAdapterRegistry adapters) { this.adapters = adapters == null ? new TestAdapterRegistry() : adapters; }

    LoadResult load(Path root) {
        if (root == null) return new LoadResult(List.of(), List.of("workspace root unavailable"), false);
        Path config = root.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(config)) return new LoadResult(autoDetect(root), List.of(), false);
        List<String> diagnostics = new ArrayList<>();
        List<AdapterSpec> specs = new ArrayList<>();
        try {
            TomlParseResult parsed = Toml.parse(config);
            for (TomlParseError error : parsed.errors()) diagnostics.add(location(error) + error.getMessage());
            if (!diagnostics.isEmpty()) return new LoadResult(List.of(), diagnostics, true);
            for (String key : parsed.keySet()) {
                if (!"schema_version".equals(key) && !"adapter".equals(key)) diagnostics.add("unknown key: " + key);
            }
            Object schemaValue = parsed.get("schema_version");
            if (!(schemaValue instanceof Long) || ((Long) schemaValue).intValue() != CONFIG_SCHEMA_VERSION) {
                diagnostics.add("schema_version must be " + CONFIG_SCHEMA_VERSION);
            }
            Object adapterValue = parsed.get("adapter");
            if (!(adapterValue instanceof TomlArray array) || array.size() == 0) {
                diagnostics.add("adapter must declare one or more [[adapter]] tables");
            } else {
                for (int index = 0; index < array.size(); index++) {
                    TomlTable table = array.getTable(index);
                    if (table == null) { diagnostics.add("adapter[" + index + "] must be a TOML table"); continue; }
                    AdapterSpec spec = parseSpec(table, index, diagnostics);
                    if (spec != null) specs.add(spec);
                }
            }
        } catch (IOException error) {
            diagnostics.add("read failed: " + error.getMessage());
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (AdapterSpec spec : specs) if (!unique.add(spec.id())) diagnostics.add("adapter declared more than once: " + spec.id());
        return new LoadResult(diagnostics.isEmpty() ? specs : List.of(), diagnostics, true);
    }

    List<TestAdapter> resolve(LoadResult loaded) {
        if (loaded == null || !loaded.valid()) return List.of();
        List<TestAdapter> result = new ArrayList<>();
        for (AdapterSpec spec : loaded.specs()) {
            TestAdapter adapter = adapters.find(spec.id());
            if (adapter != null) result.add(adapter);
        }
        return List.copyOf(result);
    }

    TestAdapter adapter(String id) { return adapters.find(id); }

    AdapterSpec resolvedSpec(Path root, AdapterSpec spec) {
        TestAdapter adapter = spec == null ? null : adapters.find(spec.id());
        if (adapter == null) return spec;
        return spec.command().isEmpty() ? new AdapterSpec(spec.id(), adapter.defaultCommand(root)) : spec;
    }

    List<TestCase> staticDiscovery(Path root, AdapterSpec spec) {
        if (spec == null || !"maven".equals(spec.id()) && !"gradle".equals(spec.id())) return List.of();
        return discoverJavaTests(root, spec.id());
    }

    Path reportCache(Path root, String adapterId) throws IOException {
        String value = Integer.toUnsignedString(Objects.toString(root, "").hashCode(), 36);
        Path cache = Path.of(System.getProperty("user.home", "."), ".shed", "test-reports", value, adapterId);
        Files.createDirectories(cache);
        return cache;
    }

    private List<AdapterSpec> autoDetect(Path root) {
        List<AdapterSpec> result = new ArrayList<>();
        for (TestAdapter adapter : adapters.all()) if (adapter.supports(root)) result.add(new AdapterSpec(adapter.id(), adapter.defaultCommand(root)));
        return List.copyOf(result);
    }

    private AdapterSpec parseSpec(TomlTable table, int index, List<String> diagnostics) {
        for (String key : table.keySet()) if (!"id".equals(key) && !"command".equals(key)) diagnostics.add("adapter[" + index + "] unknown key: " + key);
        Object idValue = table.get("id");
        if (!(idValue instanceof String rawId) || rawId.isBlank()) { diagnostics.add("adapter[" + index + "].id must be a non-empty string"); return null; }
        String id = rawId.trim().toLowerCase(Locale.ROOT);
        if (adapters.find(id) == null) { diagnostics.add("adapter[" + index + "].id unsupported: " + id); return null; }
        List<String> command = new ArrayList<>();
        Object commandValue = table.get("command");
        if (commandValue != null) {
            if (!(commandValue instanceof TomlArray array) || array.isEmpty()) {
                diagnostics.add("adapter[" + index + "].command must be a non-empty array of strings");
            } else {
                for (int part = 0; part < array.size(); part++) {
                    Object value = array.get(part);
                    if (!(value instanceof String text) || text.isBlank() || text.indexOf('\0') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                        diagnostics.add("adapter[" + index + "].command[" + part + "] must be a non-empty single-line string");
                    } else command.add(text);
                }
            }
        }
        return new AdapterSpec(id, command);
    }

    private static String location(TomlParseError error) {
        return error.position() == null ? "" : "line " + error.position().line() + ", column " + error.position().column() + ": ";
    }

    private static List<TestCase> discoverJavaTests(Path root, String adapterId) {
        Path sourceRoot = root.resolve("src/test/java");
        if (!Files.isDirectory(sourceRoot)) return List.of();
        List<TestCase> result = new ArrayList<>();
        try (var files = Files.walk(sourceRoot, 24)) {
            List<Path> sources = files.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).limit(4_000).toList();
            for (Path source : sources) result.addAll(discoverJavaFile(sourceRoot, source, adapterId));
        } catch (IOException ignored) {
        }
        result.sort(Comparator.comparing(TestCase::suite).thenComparing(TestCase::name));
        return List.copyOf(result);
    }

    private static List<TestCase> discoverJavaFile(Path root, Path file, String adapterId) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String packageName = "";
            String className = file.getFileName().toString().replaceFirst("\\.java$", "");
            Pattern packagePattern = Pattern.compile("^\\s*package\\s+([A-Za-z_$][\\w.$]*)\\s*;");
            Pattern classPattern = Pattern.compile("\\b(?:class|interface|record)\\s+([A-Za-z_$][\\w$]*)");
            Pattern methodPattern = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\(");
            List<TestCase> result = new ArrayList<>();
            boolean testAnnotation = false;
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                Matcher packageMatch = packagePattern.matcher(line);
                if (packageMatch.find()) packageName = packageMatch.group(1);
                Matcher classMatch = classPattern.matcher(line);
                if (classMatch.find()) className = classMatch.group(1);
                if (line.matches(".*@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b.*")) { testAnnotation = true; continue; }
                if (!testAnnotation) continue;
                Matcher methodMatch = methodPattern.matcher(line);
                if (!methodMatch.find()) continue;
                String method = methodMatch.group(1);
                if ("if".equals(method) || "for".equals(method) || "while".equals(method)) continue;
                String suite = packageName.isBlank() ? className : packageName + "." + className;
                result.add(new TestCase(adapterId, suite + "#" + method, method, suite, file, index + 1, Status.UNKNOWN, 0, ""));
                testAnnotation = false;
            }
            return result;
        } catch (IOException error) {
            return List.of();
        }
    }

    static List<TestCase> parseJUnit(Path root, String adapterId, List<Path> reports) {
        List<TestCase> result = new ArrayList<>();
        for (Path report : expandReports(reports, ".xml")) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                NodeList cases = factory.newDocumentBuilder().parse(report.toFile()).getElementsByTagName("testcase");
                for (int index = 0; index < cases.getLength(); index++) {
                    if (!(cases.item(index) instanceof Element node)) continue;
                    String suite = node.getAttribute("classname");
                    String name = node.getAttribute("name");
                    Status status = child(node, "error") == null ? child(node, "failure") == null ? child(node, "skipped") == null ? Status.PASSED : Status.SKIPPED : Status.FAILED : Status.ERRORED;
                    Element detail = child(node, status == Status.ERRORED ? "error" : status == Status.FAILED ? "failure" : "system-out");
                    String output = detail == null ? "" : detail.getTextContent();
                    Path file = sourceForJava(root, suite);
                    result.add(new TestCase(adapterId, suite + "#" + name, name, suite, file, lineForJavaMethod(file, name), status,
                        durationMillis(node.getAttribute("time")), output));
                }
            } catch (Exception ignored) {
            }
        }
        return dedupe(result);
    }

    static List<TestCase> parseJsonResults(Path root, String adapterId, Path report) {
        if (report == null || !Files.isRegularFile(report)) return List.of();
        try {
            Map<String, Object> payload = MiniJson.asObject(MiniJson.parse(Files.readString(report, StandardCharsets.UTF_8)));
            List<Object> suites = payload == null ? null : MiniJson.asArray(payload.get("testResults"));
            if (suites == null) return List.of();
            List<TestCase> result = new ArrayList<>();
            for (Object rawSuite : suites) {
                Map<String, Object> suite = MiniJson.asObject(rawSuite);
                if (suite == null) continue;
                String suiteName = string(suite.get("name"));
                Path file = resolvePath(root, suiteName);
                List<Object> assertions = MiniJson.asArray(suite.get("assertionResults"));
                if (assertions == null) continue;
                for (Object rawAssertion : assertions) {
                    Map<String, Object> assertion = MiniJson.asObject(rawAssertion);
                    if (assertion == null) continue;
                    String name = nonBlank(string(assertion.get("fullName")), string(assertion.get("title")));
                    String statusText = string(assertion.get("status"));
                    List<Object> messages = MiniJson.asArray(assertion.get("failureMessages"));
                    result.add(new TestCase(adapterId, suiteName + "::" + name, name, suiteName, file, 1, status(statusText),
                        number(assertion.get("duration")), joinStrings(messages)));
                }
            }
            return dedupe(result);
        } catch (Exception error) {
            return List.of();
        }
    }

    static List<TestCase> parseGo(Path root, String output) {
        Map<String, GoCase> cases = new LinkedHashMap<>();
        for (String line : output == null ? List.<String>of() : output.lines().toList()) {
            try {
                Map<String, Object> event = MiniJson.asObject(MiniJson.parse(line));
                if (event == null) continue;
                String test = string(event.get("Test"));
                if (test.isBlank()) continue;
                String pkg = string(event.get("Package"));
                String id = pkg + "::" + test;
                GoCase value = cases.computeIfAbsent(id, ignored -> new GoCase(pkg, test));
                String action = string(event.get("Action"));
                if ("pass".equals(action)) value.status = Status.PASSED;
                else if ("fail".equals(action)) value.status = Status.FAILED;
                else if ("skip".equals(action)) value.status = Status.SKIPPED;
                value.duration = Math.max(value.duration, secondsMillis(event.get("Elapsed")));
                String text = string(event.get("Output"));
                if (!text.isBlank()) value.output.append(text);
            } catch (RuntimeException ignored) {
            }
        }
        List<TestCase> result = new ArrayList<>();
        for (GoCase value : cases.values()) {
            Location location = findGoLocation(root, value.output.toString());
            result.add(new TestCase("go", value.pkg + "::" + value.name, value.name, value.pkg, location.file, location.line,
                value.status, value.duration, value.output.toString()));
        }
        return List.copyOf(result);
    }

    private static AdapterSpec ignored() { return null; }
    private static List<Path> expandReports(List<Path> reports, String suffix) {
        List<Path> result = new ArrayList<>();
        for (Path report : reports == null ? List.<Path>of() : reports) {
            if (Files.isRegularFile(report) && report.toString().endsWith(suffix)) result.add(report);
            else if (Files.isDirectory(report)) {
                try (var paths = Files.walk(report, 8)) { paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(suffix)).forEach(result::add); } catch (IOException ignored) { }
            }
        }
        return result;
    }
    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && name.equals(element.getTagName())) return element;
        }
        return null;
    }
    private static Path sourceForJava(Path root, String suite) {
        if (suite == null || suite.isBlank()) return null;
        Path file = root.resolve("src/test/java").resolve(suite.replace('.', '/') + ".java");
        return Files.isRegularFile(file) ? file : null;
    }
    private static int lineForJavaMethod(Path file, String method) {
        if (file == null || method == null) return 1;
        try { List<String> lines = Files.readAllLines(file); for (int index = 0; index < lines.size(); index++) if (lines.get(index).matches(".*\\b" + Pattern.quote(method) + "\\s*\\(.*")) return index + 1; } catch (IOException ignored) { }
        return 1;
    }
    private static long durationMillis(String seconds) { try { return Math.round(Double.parseDouble(seconds) * 1000); } catch (RuntimeException error) { return 0; } }
    private static long secondsMillis(Object seconds) { return seconds instanceof Number number ? Math.round(number.doubleValue() * 1000) : 0; }
    private static List<TestCase> dedupe(List<TestCase> cases) { Map<String, TestCase> values = new LinkedHashMap<>(); for (TestCase value : cases) values.put(value.adapterId() + "\u0000" + value.id(), value); return List.copyOf(values.values()); }
    private static String string(Object value) { return value instanceof String text ? text : ""; }
    private static long number(Object value) { return value instanceof Number number ? Math.max(0, number.longValue()) : 0; }
    private static String nonBlank(String first, String second) { return first == null || first.isBlank() ? second == null ? "" : second : first; }
    private static String joinStrings(List<Object> values) { if (values == null) return ""; StringBuilder output = new StringBuilder(); for (Object value : values) { String text = string(value); if (!text.isBlank()) output.append(text).append('\n'); } return output.toString(); }
    static Status status(String value) { return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) { case "passed", "pass" -> Status.PASSED; case "skipped", "pending", "todo", "disabled" -> Status.SKIPPED; case "failed", "fail" -> Status.FAILED; case "error", "errored" -> Status.ERRORED; default -> Status.UNKNOWN; }; }
    static Path resolvePath(Path root, String value) { try { Path path = Path.of(value); if (!path.isAbsolute()) path = root.resolve(path); return Files.exists(path) ? path.normalize() : null; } catch (RuntimeException error) { return null; } }
    private static Location findGoLocation(Path root, String output) {
        Matcher match = Pattern.compile("(?:^|\\n)\\s*(.+?\\.go):(\\d+):").matcher(output == null ? "" : output);
        if (!match.find()) return new Location(null, 1);
        Path file = resolvePath(root, match.group(1));
        try { return new Location(file, Integer.parseInt(match.group(2))); } catch (NumberFormatException error) { return new Location(file, 1); }
    }
    private record Location(Path file, int line) { }
    private static final class GoCase { final String pkg; final String name; StringBuilder output = new StringBuilder(); Status status = Status.UNKNOWN; long duration; GoCase(String pkg, String name) { this.pkg = pkg; this.name = name; } }
}
