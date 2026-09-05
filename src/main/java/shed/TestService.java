package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final int MAX_STATIC_TEST_FILES = 4_000;
    private static final Pattern PYTHON_UNITTEST_CLASS = Pattern.compile(
        "^([ \\t]*)class\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?TestCase\\b[^)]*)\\)\\s*:");
    private static final Pattern PYTHON_TEST_METHOD = Pattern.compile("^([ \\t]+)(?:async\\s+)?def\\s+(test[A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern UNITTEST_RESULT = Pattern.compile(
        "^([A-Za-z_][A-Za-z0-9_]*)\\s+\\(([^)]+)\\)\\s+\\.\\.\\.\\s+(ok|fail|error|skipped(?:\\s+.*)?|expected failure|unexpected success)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");
    private final TestAdapterRegistry adapters;
    private final ExtensionRegistry extensionRegistry;

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

    record AdapterSpec(String id, List<String> command, String debugConfiguration) {
        AdapterSpec {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            command = command == null ? List.of() : List.copyOf(command);
            debugConfiguration = debugConfiguration == null ? "" : debugConfiguration.trim();
        }
        AdapterSpec(String id, List<String> command) { this(id, command, ""); }
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

    TestService() { this(new TestAdapterRegistry(), null); }
    TestService(TestAdapterRegistry adapters) { this(adapters, null); }
    TestService(ExtensionRegistry extensionRegistry) { this(new TestAdapterRegistry(), extensionRegistry); }
    TestService(TestAdapterRegistry adapters, ExtensionRegistry extensionRegistry) {
        this.adapters = adapters == null ? new TestAdapterRegistry() : adapters;
        this.extensionRegistry = extensionRegistry;
    }

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
            TestAdapter adapter = adapter(spec.id());
            if (adapter != null) result.add(adapter);
        }
        return List.copyOf(result);
    }

    TestAdapter adapter(String id) {
        TestAdapter builtIn = adapters.find(id);
        if (builtIn != null) return builtIn;
        if (extensionRegistry == null || id == null) return null;
        for (ExtensionRegistry.Owned<shed.api.TestContribution> contribution : extensionRegistry.tests()) {
            ExtensionTestAdapter adapter = new ExtensionTestAdapter(contribution);
            if (adapter.id().equalsIgnoreCase(id.trim())) return adapter;
        }
        return null;
    }

    AdapterSpec resolvedSpec(Path root, AdapterSpec spec) {
        TestAdapter adapter = spec == null ? null : adapter(spec.id());
        if (adapter == null) return spec;
        return spec.command().isEmpty() ? new AdapterSpec(spec.id(), adapter.defaultCommand(root), spec.debugConfiguration()) : spec;
    }

    /** Replaces automatic CTest-tree detection with one explicit, session-selected test preset. */
    static List<AdapterSpec> withCtestPreset(LoadResult loaded, Path root, String preset) {
        if (loaded == null || !loaded.valid()) throw new IllegalArgumentException("test configuration is invalid");
        if (loaded.configured()) throw new IllegalArgumentException(".shedtests controls test adapters; declare the CTest command there instead");
        if (!CmakePresetSupport.hasPresetFile(root)) {
            throw new IllegalArgumentException("CMakePresets.json or CMakeUserPresets.json is required at the workspace root");
        }
        if (!CmakePresetSupport.isSafeName(preset)) throw new IllegalArgumentException("preset name is invalid");
        List<AdapterSpec> result = new ArrayList<>();
        for (AdapterSpec spec : loaded.specs()) if (!"ctest".equals(spec.id())) result.add(spec);
        result.add(new AdapterSpec("ctest", List.of("ctest", "--preset", preset.trim())));
        return List.copyOf(result);
    }

    List<TestCase> staticDiscovery(Path root, AdapterSpec spec) {
        if (spec == null) return List.of();
        if ("unittest".equals(spec.id())) return discoverUnittestTests(root);
        if (!"maven".equals(spec.id()) && !"gradle".equals(spec.id())) return List.of();
        return discoverJavaTests(root, spec.id());
    }

    Path reportCache(Path root, String adapterId, Path shedDirectory) throws IOException {
        String value = Integer.toUnsignedString(Objects.toString(root, "").hashCode(), 36);
        Path base = shedDirectory == null ? Path.of(System.getProperty("user.home", "."), ".shed") : shedDirectory;
        Path cache = base.resolve("test-reports").resolve(value).resolve(adapterId);
        Files.createDirectories(cache);
        return cache;
    }

    private List<AdapterSpec> autoDetect(Path root) {
        List<AdapterSpec> result = new ArrayList<>();
        for (TestAdapter adapter : allAdapters()) if (adapter.supports(root)) result.add(new AdapterSpec(adapter.id(), adapter.defaultCommand(root)));
        return List.copyOf(result);
    }

    private List<TestAdapter> allAdapters() {
        List<TestAdapter> result = new ArrayList<>(adapters.all());
        if (extensionRegistry != null) {
            for (ExtensionRegistry.Owned<shed.api.TestContribution> contribution : extensionRegistry.tests()) {
                result.add(new ExtensionTestAdapter(contribution));
            }
        }
        return List.copyOf(result);
    }

    private AdapterSpec parseSpec(TomlTable table, int index, List<String> diagnostics) {
        for (String key : table.keySet()) if (!"id".equals(key) && !"command".equals(key) && !"debug_configuration".equals(key)) diagnostics.add("adapter[" + index + "] unknown key: " + key);
        Object idValue = table.get("id");
        if (!(idValue instanceof String rawId) || rawId.isBlank()) { diagnostics.add("adapter[" + index + "].id must be a non-empty string"); return null; }
        String id = rawId.trim().toLowerCase(Locale.ROOT);
        if (adapter(id) == null) { diagnostics.add("adapter[" + index + "].id unsupported: " + id); return null; }
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
        Object debugValue = table.get("debug_configuration");
        String debugConfiguration = "";
        if (debugValue != null) {
            if (!(debugValue instanceof String value) || !debugConfigurationReference(value)) {
                diagnostics.add("adapter[" + index + "].debug_configuration must be a Shed configuration name or an imported vscode:<name> profile");
            } else debugConfiguration = value;
        }
        return new AdapterSpec(id, command, debugConfiguration);
    }

    private static boolean debugConfigurationReference(String value) {
        if (value == null) return false;
        String reference = value.trim();
        if (reference.matches("[A-Za-z0-9_-]+")) return true;
        if (!reference.startsWith(VsCodeLaunchConfigurationImporter.NAME_PREFIX)) return false;
        String label = reference.substring(VsCodeLaunchConfigurationImporter.NAME_PREFIX.length());
        if (label.isBlank() || label.length() > 120) return false;
        for (int index = 0; index < label.length(); index++) if (Character.isISOControl(label.charAt(index))) return false;
        return true;
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

    /**
     * Conservatively discovers importable unittest.TestCase methods without importing
     * project code. Python's unittest runner has no collect-only command, so a refresh
     * must not invoke it just to populate the Test Explorer.
     */
    static List<TestCase> discoverUnittestTests(Path root) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<TestCase> result = new ArrayList<>();
        try (var files = Files.walk(root, 24)) {
            List<Path> sources = files.filter(path -> Files.isRegularFile(path) && isPythonTestFile(path))
                .limit(MAX_STATIC_TEST_FILES).toList();
            for (Path source : sources) result.addAll(discoverUnittestFile(root, source));
        } catch (IOException | SecurityException ignored) {
        }
        result.sort(Comparator.comparing(TestCase::suite).thenComparing(TestCase::name));
        return List.copyOf(result);
    }

    static boolean hasImportableUnittestTests(Path root) {
        return !discoverUnittestTests(root).isEmpty();
    }

    static List<TestCase> parseUnittest(Path root, String output) {
        List<TestCase> result = new ArrayList<>();
        for (String rawLine : output == null ? List.<String>of() : output.lines().toList()) {
            Matcher match = UNITTEST_RESULT.matcher(ANSI_ESCAPE.matcher(rawLine).replaceAll("").strip());
            if (!match.matches()) continue;
            String name = match.group(1);
            String id = match.group(2).trim();
            if (!id.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*){2,}")) continue;
            String suite = id.substring(0, id.lastIndexOf('.'));
            Path file = sourceForPython(root, id);
            result.add(new TestCase("unittest", id, name, suite, file, lineForPythonMethod(file, name), unittestStatus(match.group(3)), 0,
                unittestStatus(match.group(3)).failed() ? output : ""));
        }
        return dedupe(result);
    }

    private static List<TestCase> discoverUnittestFile(Path root, Path source) {
        String module = pythonModuleName(root, source);
        if (module == null) return List.of();
        try {
            List<TestCase> result = new ArrayList<>();
            String activeClass = null;
            int classIndent = -1;
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                Matcher classMatch = PYTHON_UNITTEST_CLASS.matcher(line);
                if (classMatch.find()) {
                    activeClass = classMatch.group(2);
                    classIndent = indentationWidth(classMatch.group(1));
                    continue;
                }
                if (activeClass == null) continue;
                int indentation = indentationWidth(line);
                if (!line.strip().isEmpty() && indentation <= classIndent) {
                    activeClass = null;
                    classIndent = -1;
                    continue;
                }
                Matcher methodMatch = PYTHON_TEST_METHOD.matcher(line);
                if (!methodMatch.find() || indentationWidth(methodMatch.group(1)) <= classIndent) continue;
                String name = methodMatch.group(2);
                String suite = module + "." + activeClass;
                result.add(new TestCase("unittest", suite + "." + name, name, suite, source, index + 1, Status.UNKNOWN, 0, ""));
            }
            return List.copyOf(result);
        } catch (IOException | SecurityException error) {
            return List.of();
        }
    }

    private static boolean isPythonTestFile(Path path) {
        Path name = path == null ? null : path.getFileName();
        return name != null && name.toString().matches("test[A-Za-z0-9_]*\\.py");
    }

    /** Returns a module only when the source is importable by the default root discovery command. */
    private static String pythonModuleName(Path root, Path source) {
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedSource = source.toAbsolutePath().normalize();
            if (!normalizedSource.startsWith(normalizedRoot)) return null;
            Path relative = normalizedRoot.relativize(normalizedSource);
            Path fileName = relative.getFileName();
            if (fileName == null || !isPythonTestFile(fileName)) return null;
            Path parent = relative.getParent();
            Path cursor = parent;
            while (cursor != null) {
                if (!Files.isRegularFile(normalizedRoot.resolve(cursor).resolve("__init__.py"))) return null;
                cursor = cursor.getParent();
            }
            String base = fileName.toString().substring(0, fileName.toString().length() - ".py".length());
            return parent == null ? base : parent.toString().replace(java.io.File.separatorChar, '.') + "." + base;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Path sourceForPython(Path root, String id) {
        if (root == null || id == null) return null;
        int method = id.lastIndexOf('.');
        int type = method <= 0 ? -1 : id.lastIndexOf('.', method - 1);
        if (type <= 0) return null;
        String module = id.substring(0, type);
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path path = normalizedRoot.resolve(module.replace('.', java.io.File.separatorChar) + ".py").normalize();
            return path.startsWith(normalizedRoot) && Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static int lineForPythonMethod(Path file, String method) {
        if (file == null || method == null) return 1;
        Pattern pattern = Pattern.compile("^\\s*(?:async\\s+)?def\\s+" + Pattern.quote(method) + "\\s*\\(");
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) if (pattern.matcher(lines.get(index)).find()) return index + 1;
        } catch (IOException | SecurityException ignored) {
        }
        return 1;
    }

    private static int indentationWidth(String value) {
        int width = 0;
        for (int index = 0; value != null && index < value.length(); index++) width += value.charAt(index) == '\t' ? 4 : 1;
        return width;
    }

    private static Status unittestStatus(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("ok")) return Status.PASSED;
        if (normalized.startsWith("skipped") || normalized.equals("expected failure")) return Status.SKIPPED;
        return normalized.equals("error") ? Status.ERRORED : Status.FAILED;
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
                if (line.matches(".*@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b.*")) testAnnotation = true;
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

    static List<TestCase> parseTrx(Path root, String adapterId, List<Path> reports) {
        List<TestCase> result = new ArrayList<>();
        for (Path report : expandReports(reports, ".trx")) {
            try {
                DocumentBuilderFactory factory = secureXmlFactory();
                org.w3c.dom.Document document = factory.newDocumentBuilder().parse(report.toFile());
                Map<String, DotnetDefinition> definitions = new LinkedHashMap<>();
                NodeList defined = document.getElementsByTagName("UnitTest");
                for (int index = 0; index < defined.getLength(); index++) {
                    if (!(defined.item(index) instanceof Element unit)) continue;
                    Element method = child(unit, "TestMethod");
                    if (method == null) continue;
                    definitions.put(unit.getAttribute("id"), new DotnetDefinition(method.getAttribute("className"), method.getAttribute("name")));
                }
                NodeList cases = document.getElementsByTagName("UnitTestResult");
                for (int index = 0; index < cases.getLength(); index++) {
                    if (!(cases.item(index) instanceof Element node)) continue;
                    DotnetDefinition definition = definitions.get(node.getAttribute("testId"));
                    String name = nonBlank(definition == null ? "" : definition.method(), node.getAttribute("testName"));
                    String suite = nonBlank(definition == null ? "" : definition.className(), "dotnet");
                    String id = nonBlank(node.getAttribute("testName"), suite + "#" + name);
                    Path file = sourceForDotnet(root, suite);
                    String outcome = node.getAttribute("outcome");
                    Status status = dotnetStatus(outcome);
                    result.add(new TestCase(adapterId, id, name, suite, file, lineForMethod(file, name), status,
                        dotnetDurationMillis(node.getAttribute("duration")), descendantText(node, "ErrorInfo")));
                }
            } catch (Exception ignored) {
            }
        }
        return dedupe(result);
    }

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
    private static DocumentBuilderFactory secureXmlFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
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
    private static Path sourceForDotnet(Path root, String suite) {
        if (root == null || suite == null || suite.isBlank() || !Files.isDirectory(root)) return null;
        String simple = suite.substring(suite.lastIndexOf('.') + 1).replaceAll("`\\d+$", "").replace('$', '.');
        try (var paths = Files.walk(root, 16)) {
            return paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals(simple + ".cs"))
                .limit(1).findFirst().orElse(null);
        } catch (IOException error) {
            return null;
        }
    }
    private static int lineForJavaMethod(Path file, String method) {
        if (file == null || method == null) return 1;
        try { List<String> lines = Files.readAllLines(file); for (int index = 0; index < lines.size(); index++) if (lines.get(index).matches(".*\\b" + Pattern.quote(method) + "\\s*\\(.*")) return index + 1; } catch (IOException ignored) { }
        return 1;
    }
    private static int lineForMethod(Path file, String method) {
        if (file == null || method == null || method.isBlank()) return 1;
        String bare = method.contains("(") ? method.substring(0, method.indexOf('(')) : method;
        try {
            List<String> lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) if (lines.get(index).matches(".*\\b" + Pattern.quote(bare) + "\\s*\\(.*")) return index + 1;
        } catch (IOException ignored) {
        }
        return 1;
    }
    private static long durationMillis(String seconds) { try { return Math.round(Double.parseDouble(seconds) * 1000); } catch (RuntimeException error) { return 0; } }
    private static long dotnetDurationMillis(String duration) {
        if (duration == null || !duration.matches("\\d{1,2}:\\d{2}:\\d{2}(?:\\.\\d+)?")) return 0;
        try {
            String[] parts = duration.split(":", -1);
            return Math.round((Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Double.parseDouble(parts[2])) * 1000);
        } catch (RuntimeException error) {
            return 0;
        }
    }
    private static long secondsMillis(Object seconds) { return seconds instanceof Number number ? Math.round(number.doubleValue() * 1000) : 0; }
    private static List<TestCase> dedupe(List<TestCase> cases) { Map<String, TestCase> values = new LinkedHashMap<>(); for (TestCase value : cases) values.put(value.adapterId() + "\u0000" + value.id(), value); return List.copyOf(values.values()); }
    private static String string(Object value) { return value instanceof String text ? text : ""; }
    private static long number(Object value) { return value instanceof Number number ? Math.max(0, number.longValue()) : 0; }
    private static String nonBlank(String first, String second) { return first == null || first.isBlank() ? second == null ? "" : second : first; }
    private static String joinStrings(List<Object> values) { if (values == null) return ""; StringBuilder output = new StringBuilder(); for (Object value : values) { String text = string(value); if (!text.isBlank()) output.append(text).append('\n'); } return output.toString(); }
    static Status status(String value) { return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) { case "passed", "pass" -> Status.PASSED; case "skipped", "pending", "todo", "disabled" -> Status.SKIPPED; case "failed", "fail" -> Status.FAILED; case "error", "errored" -> Status.ERRORED; default -> Status.UNKNOWN; }; }
    private static Status dotnetStatus(String value) { return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) { case "passed" -> Status.PASSED; case "failed" -> Status.FAILED; case "notexecuted", "notrunnable", "skipped" -> Status.SKIPPED; default -> Status.UNKNOWN; }; }
    static Path resolvePath(Path root, String value) { try { Path path = Path.of(value); if (!path.isAbsolute()) path = root.resolve(path); return Files.exists(path) ? path.normalize() : null; } catch (RuntimeException error) { return null; } }
    private static Location findGoLocation(Path root, String output) {
        Matcher match = Pattern.compile("(?:^|\\n)\\s*(.+?\\.go):(\\d+):").matcher(output == null ? "" : output);
        if (!match.find()) return new Location(null, 1);
        Path file = resolvePath(root, match.group(1));
        try { return new Location(file, Integer.parseInt(match.group(2))); } catch (NumberFormatException error) { return new Location(file, 1); }
    }
    private static String descendantText(Element parent, String tag) {
        NodeList matches = parent.getElementsByTagName(tag);
        return matches.getLength() == 0 ? "" : matches.item(0).getTextContent();
    }
    private record Location(Path file, int line) { }
    private record DotnetDefinition(String className, String method) { }
    private static final class GoCase { final String pkg; final String name; StringBuilder output = new StringBuilder(); Status status = Status.UNKNOWN; long duration; GoCase(String pkg, String name) { this.pkg = pkg; this.name = name; } }
}
