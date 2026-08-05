package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TestAdapterRegistry {
    private final Map<String, TestAdapter> adapters = new LinkedHashMap<>();

    TestAdapterRegistry() {
        register(new MavenAdapter());
        register(new GradleAdapter());
        register(new PytestAdapter());
        register(new JestAdapter());
        register(new VitestAdapter());
        register(new GoAdapter());
    }

    void register(TestAdapter adapter) {
        if (adapter == null || adapter.id() == null || adapter.id().isBlank()) throw new IllegalArgumentException("test adapter id required");
        String id = adapter.id().trim().toLowerCase(Locale.ROOT);
        if (adapters.putIfAbsent(id, adapter) != null) throw new IllegalArgumentException("duplicate test adapter: " + id);
    }

    TestAdapter find(String id) { return id == null ? null : adapters.get(id.trim().toLowerCase(Locale.ROOT)); }
    List<TestAdapter> all() { return List.copyOf(adapters.values()); }

    private abstract static class BuiltInAdapter implements TestAdapter {
        final List<String> base(TestService.AdapterSpec spec) { return spec.command(); }
        final TestService.Command command(List<String> argv, Path... reports) { return new TestService.Command(argv, List.of(reports)); }
        final Path executable(Path root, String name, String fallback) {
            Path local = root.resolve(name);
            return Files.isExecutable(local) ? local : Path.of(fallback);
        }
        final boolean packageHas(Path root, String dependency) {
            Path file = root.resolve("package.json");
            if (!Files.isRegularFile(file)) return false;
            try { return Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("\"" + dependency.toLowerCase(Locale.ROOT) + "\""); }
            catch (IOException error) { return false; }
        }
        final List<TestService.TestCase> noDiscovery(Path root, String output) { return List.of(); }
    }

    private static final class MavenAdapter extends BuiltInAdapter {
        @Override public String id() { return "maven"; }
        @Override public boolean supports(Path root) { return Files.isRegularFile(root.resolve("pom.xml")); }
        @Override public List<String> defaultCommand(Path root) { return List.of(executable(root, "mvnw", "mvn").toString()); }
        @Override public TestService.Command discovery(TestService.AdapterSpec spec) { return new TestService.Command(List.of(), List.of()); }
        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            List<String> command = new ArrayList<>(base(spec));
            if (!selected.isEmpty()) command.add("-Dtest=" + selected.stream().map(TestService.TestCase::id).collect(java.util.stream.Collectors.joining(",")));
            command.add("test");
            return command(command, Path.of("target", "surefire-reports"), Path.of("target", "failsafe-reports"));
        }
        @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) { return noDiscovery(root, output); }
        @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) {
            return TestService.parseJUnit(root, id(), command.reports().stream().map(root::resolve).toList());
        }
    }

    private static final class GradleAdapter extends BuiltInAdapter {
        @Override public String id() { return "gradle"; }
        @Override public boolean supports(Path root) { return Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts")) || Files.exists(root.resolve("settings.gradle")) || Files.exists(root.resolve("settings.gradle.kts")); }
        @Override public List<String> defaultCommand(Path root) { return List.of(executable(root, "gradlew", "gradle").toString()); }
        @Override public TestService.Command discovery(TestService.AdapterSpec spec) { return new TestService.Command(List.of(), List.of()); }
        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            List<String> command = new ArrayList<>(base(spec));
            command.add("test");
            for (TestService.TestCase test : selected) { command.add("--tests"); command.add(test.id().replace('#', '.')); }
            return command(command, Path.of("build", "test-results", "test"));
        }
        @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) { return noDiscovery(root, output); }
        @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) {
            return TestService.parseJUnit(root, id(), command.reports().stream().map(root::resolve).toList());
        }
    }

    private static final class PytestAdapter extends BuiltInAdapter {
        @Override public String id() { return "pytest"; }
        @Override public boolean supports(Path root) {
            return Files.exists(root.resolve("pytest.ini")) || Files.exists(root.resolve("tox.ini")) || Files.exists(root.resolve("setup.cfg"))
                || packageHas(root, "pytest") || Files.isDirectory(root.resolve("tests"));
        }
        @Override public List<String> defaultCommand(Path root) {
            Path virtual = root.resolve(".venv/bin/pytest");
            return Files.isExecutable(virtual) ? List.of(virtual.toString()) : List.of("pytest");
        }
        @Override public TestService.Command discovery(TestService.AdapterSpec spec) {
            List<String> command = new ArrayList<>(base(spec)); command.add("--collect-only"); command.add("-q"); return new TestService.Command(command, List.of());
        }
        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            Path report = cache.resolve("pytest.xml");
            List<String> command = new ArrayList<>(base(spec));
            for (TestService.TestCase test : selected) command.add(test.id());
            command.add("--junit-xml=" + report);
            return command(command, report);
        }
        @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) {
            List<TestService.TestCase> result = new ArrayList<>();
            for (String line : output == null ? List.<String>of() : output.lines().toList()) {
                String id = line.strip(); if (!id.contains("::") || id.startsWith("<")) continue;
                String path = id.substring(0, id.indexOf("::"));
                String name = id.substring(id.lastIndexOf("::") + 2);
                result.add(new TestService.TestCase(id(), id, name, path, TestService.resolvePath(root, path), 1, TestService.Status.UNKNOWN, 0, ""));
            }
            return result;
        }
        @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) { return TestService.parseJUnit(root, id(), command.reports()); }
    }

    private abstract static class NodeAdapter extends BuiltInAdapter {
        abstract String packageName();
        @Override public boolean supports(Path root) { return packageHas(root, packageName()); }
        @Override public List<String> defaultCommand(Path root) {
            Path local = root.resolve("node_modules/.bin").resolve(packageName());
            return Files.isExecutable(local) ? List.of(local.toString()) : List.of(packageName());
        }
        @Override public TestService.Command discovery(TestService.AdapterSpec spec) {
            List<String> command = new ArrayList<>(base(spec)); command.add("--listTests"); return new TestService.Command(command, List.of());
        }
        @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) {
            List<TestService.TestCase> result = new ArrayList<>();
            for (String line : output == null ? List.<String>of() : output.lines().toList()) {
                String path = line.strip(); if (path.isBlank() || path.startsWith("[")) continue;
                Path file = TestService.resolvePath(root, path);
                if (file == null) continue;
                result.add(new TestService.TestCase(id(), file.toString(), file.getFileName().toString(), file.toString(), file, 1, TestService.Status.UNKNOWN, 0, ""));
            }
            return result;
        }
        @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) {
            return command.reports().isEmpty() ? List.of() : TestService.parseJsonResults(root, id(), command.reports().getFirst());
        }
    }

    private static final class JestAdapter extends NodeAdapter {
        @Override public String id() { return "jest"; }
        @Override String packageName() { return "jest"; }
        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            Path report = cache.resolve("jest.json");
            List<String> command = new ArrayList<>(base(spec));
            for (TestService.TestCase test : selected) command.add(test.file() == null ? test.id() : test.file().toString());
            command.add("--json"); command.add("--outputFile=" + report);
            return command(command, report);
        }
    }

    private static final class VitestAdapter extends NodeAdapter {
        @Override public String id() { return "vitest"; }
        @Override String packageName() { return "vitest"; }
        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            Path report = cache.resolve("vitest.json");
            List<String> command = new ArrayList<>(base(spec));
            command.add("run");
            for (TestService.TestCase test : selected) command.add(test.file() == null ? test.id() : test.file().toString());
            command.add("--reporter=json"); command.add("--outputFile=" + report);
            return command(command, report);
        }
    }

    private static final class GoAdapter extends BuiltInAdapter {
        @Override public String id() { return "go"; }
        @Override public boolean supports(Path root) { return Files.isRegularFile(root.resolve("go.mod")); }
        @Override public List<String> defaultCommand(Path root) { return List.of("go"); }
        @Override public TestService.Command discovery(TestService.AdapterSpec spec) { List<String> command = new ArrayList<>(base(spec)); command.addAll(List.of("test", "-list", ".", "./...")); return new TestService.Command(command, List.of()); }
        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            List<String> command = new ArrayList<>(base(spec)); command.addAll(List.of("test", "-json", "./..."));
            if (!selected.isEmpty()) {
                command.add("-run");
                command.add("^(?:" + selected.stream().map(TestService.TestCase::name).map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining("|")) + ")$");
            }
            return new TestService.Command(command, List.of());
        }
        @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) {
            List<TestService.TestCase> result = new ArrayList<>();
            for (String line : output == null ? List.<String>of() : output.lines().toList()) { String name = line.strip(); if (name.matches("(?:Test|Benchmark|Example)[A-Za-z0-9_]+")) result.add(new TestService.TestCase(id(), name, name, "go", null, 1, TestService.Status.UNKNOWN, 0, "")); }
            return result;
        }
        @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) { return TestService.parseGo(root, output); }
    }
}
