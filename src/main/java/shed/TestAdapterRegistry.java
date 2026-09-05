package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
        register(new UnittestAdapter());
        register(new JestAdapter());
        register(new VitestAdapter());
        register(new GoAdapter());
        register(new DotnetAdapter());
        register(new CargoAdapter());
        register(new CtestAdapter());
    }

    /** Returns the sole conventional generated CTest tree, never guessing between multiple builds. */
    static Path ctestTestDirectory(Path root) {
        return conventionalCmakeDirectory(root, "CTestTestfile.cmake");
    }

    /** Returns the sole conventional CMake binary tree, never guessing between multiple builds. */
    static Path cmakeBuildDirectory(Path root) {
        return conventionalCmakeDirectory(root, "CMakeCache.txt");
    }

    private static Path conventionalCmakeDirectory(Path root, String marker) {
        if (root == null || !Files.isDirectory(root)) return null;
        List<Path> candidates = CtestAdapter.COMMON_BUILD_DIRECTORIES.stream().map(root::resolve).map(path -> path.toAbsolutePath().normalize())
            .filter(path -> Files.isRegularFile(path.resolve(marker))).sorted(Comparator.naturalOrder()).toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
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
        static boolean packageHas(Path root, String dependency) {
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
            return projectUsesPytest(root);
        }
        private static boolean projectUsesPytest(Path root) {
            if (Files.exists(root.resolve("pytest.ini")) || Files.exists(root.resolve("tox.ini")) || Files.exists(root.resolve("setup.cfg"))
                || packageHas(root, "pytest")) return true;
            return Files.isDirectory(root.resolve("tests")) && !TestService.hasImportableUnittestTests(root);
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

    /** Python's standard-library runner. Discovery stays source-only because unittest has no collect-only mode. */
    private static final class UnittestAdapter extends BuiltInAdapter {
        @Override public String id() { return "unittest"; }

        @Override public boolean supports(Path root) {
            return !PytestAdapter.projectUsesPytest(root) && TestService.hasImportableUnittestTests(root);
        }

        @Override public List<String> defaultCommand(Path root) {
            Path unixVirtualEnvironment = root.resolve(".venv/bin/python");
            if (Files.isExecutable(unixVirtualEnvironment)) return List.of(unixVirtualEnvironment.toString(), "-m", "unittest");
            Path windowsVirtualEnvironment = root.resolve(".venv/Scripts/python.exe");
            if (Files.isExecutable(windowsVirtualEnvironment)) return List.of(windowsVirtualEnvironment.toString(), "-m", "unittest");
            return List.of("python", "-m", "unittest");
        }

        @Override public TestService.Command discovery(TestService.AdapterSpec spec) {
            return new TestService.Command(List.of(), List.of());
        }

        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            List<String> command = new ArrayList<>(base(spec));
            if (selected == null || selected.isEmpty()) command.addAll(List.of("discover", "-v"));
            else {
                command.add("-v");
                for (TestService.TestCase test : selected) command.add(test.id());
            }
            return new TestService.Command(command, List.of());
        }

        @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) { return List.of(); }

        @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) {
            return TestService.parseUnittest(root, output);
        }
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

    private static final class DotnetAdapter extends BuiltInAdapter {
        @Override public String id() { return "dotnet"; }

        @Override public boolean supports(Path root) {
            if (root == null || !Files.isDirectory(root)) return false;
            try (var entries = Files.list(root)) {
                return entries.anyMatch(path -> Files.isRegularFile(path)
                    && (path.getFileName().toString().endsWith(".sln") || path.getFileName().toString().endsWith(".csproj")));
            } catch (IOException error) {
                return false;
            }
        }

        @Override public List<String> defaultCommand(Path root) { return List.of("dotnet"); }

        @Override public TestService.Command discovery(TestService.AdapterSpec spec) {
            List<String> command = new ArrayList<>(base(spec));
            command.addAll(List.of("test", "--list-tests"));
            return new TestService.Command(command, List.of());
        }

        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            List<String> command = new ArrayList<>(base(spec));
            command.addAll(List.of("test", "--logger", "trx", "--results-directory", cache.toString()));
            if (selected != null && !selected.isEmpty()) {
                String filter = selected.stream().map(TestService.TestCase::id)
                    .map(id -> "FullyQualifiedName=" + id).collect(java.util.stream.Collectors.joining("|"));
                command.add("--filter");
                command.add(filter);
            }
            return command(command, cache);
        }

        @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) {
            List<TestService.TestCase> result = new ArrayList<>();
            boolean listed = false;
            for (String line : output == null ? List.<String>of() : output.lines().toList()) {
                if (line.contains("The following Tests are available:")) { listed = true; continue; }
                if (!listed) continue;
                String id = line.strip();
                if (id.isEmpty() || id.startsWith("Total") || id.startsWith("[")) continue;
                result.add(new TestService.TestCase(id(), id, id, "dotnet", null, 1, TestService.Status.UNKNOWN, 0, ""));
            }
            return List.copyOf(result);
        }

        @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) {
            return TestService.parseTrx(root, id(), command.reports());
        }
    }

    private static final class CargoAdapter extends BuiltInAdapter {
        @Override public String id() { return "cargo"; }
        @Override public boolean supports(Path root) { return Files.isRegularFile(root.resolve("Cargo.toml")); }
        @Override public List<String> defaultCommand(Path root) { return List.of("cargo"); }
        @Override public boolean supportsMultipleSelection() { return false; }

        @Override public TestService.Command discovery(TestService.AdapterSpec spec) {
            List<String> command = new ArrayList<>(base(spec));
            command.addAll(List.of("test", "--", "--list"));
            return new TestService.Command(command, List.of());
        }

        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            List<String> command = new ArrayList<>(base(spec));
            command.add("test");
            if (selected != null && !selected.isEmpty()) {
                command.add(selected.getFirst().id());
                command.addAll(List.of("--", "--exact"));
            }
            return new TestService.Command(command, List.of());
        }

        @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) {
            List<TestService.TestCase> result = new ArrayList<>();
            for (String line : output == null ? List.<String>of() : output.lines().toList()) {
                String value = line.strip();
                if (!value.endsWith(": test")) continue;
                String id = value.substring(0, value.length() - ": test".length());
                if (id.isBlank()) continue;
                String name = id.substring(id.lastIndexOf("::") + 2);
                result.add(new TestService.TestCase(id(), id, name, "rust", null, 1, TestService.Status.UNKNOWN, 0, ""));
            }
            return List.copyOf(result);
        }

        @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) {
            java.util.regex.Pattern resultLine = java.util.regex.Pattern.compile("^test\\s+(.+?)\\s+\\.\\.\\.\\s+(ok|FAILED|ignored)$");
            List<TestService.TestCase> result = new ArrayList<>();
            for (String line : output == null ? List.<String>of() : output.lines().toList()) {
                java.util.regex.Matcher match = resultLine.matcher(line.strip());
                if (!match.matches()) continue;
                String id = match.group(1);
                String outcome = match.group(2);
                TestService.Status status = "ok".equals(outcome) ? TestService.Status.PASSED
                    : "ignored".equals(outcome) ? TestService.Status.SKIPPED : TestService.Status.FAILED;
                String name = id.substring(id.lastIndexOf("::") + 2);
                result.add(new TestService.TestCase(id(), id, name, "rust", null, 1, status, 0, ""));
            }
            return List.copyOf(result);
        }
    }

    /** Runs an already-generated CTest tree; it never configures or builds a CMake project. */
    private static final class CtestAdapter extends BuiltInAdapter {
        private static final List<String> COMMON_BUILD_DIRECTORIES = List.of("", "build", "cmake-build-debug", "cmake-build-release", "out/build");

        @Override public String id() { return "ctest"; }

        @Override public boolean supports(Path root) { return ctestTestDirectory(root) != null; }

        @Override public List<String> defaultCommand(Path root) {
            Path directory = ctestTestDirectory(root);
            return directory == null ? List.of("ctest") : List.of("ctest", "--test-dir", directory.toString());
        }

        @Override public TestService.Command discovery(TestService.AdapterSpec spec) {
            List<String> command = new ArrayList<>(base(spec));
            command.add("--show-only=json-v1");
            return new TestService.Command(command, List.of());
        }

        @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selected, Path cache) {
            Path report = cache.resolve("ctest.xml");
            List<String> command = new ArrayList<>(base(spec));
            command.add("--output-on-failure");
            command.add("--output-junit");
            command.add(report.toString());
            if (selected != null && !selected.isEmpty()) {
                command.add("-R");
                command.add(exactNameExpression(selected));
            }
            return command(command, report);
        }

        @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) {
            try {
                int object = output == null ? -1 : output.indexOf('{');
                Map<String, Object> payload = object < 0 ? null : MiniJson.asObject(MiniJson.parse(output.substring(object)));
                if (payload == null || !"ctestInfo".equals(MiniJson.asString(payload.get("kind")))) return List.of();
                List<Object> tests = MiniJson.asArray(payload.get("tests"));
                if (tests == null) return List.of();
                List<TestService.TestCase> result = new ArrayList<>();
                for (Object raw : tests) {
                    Map<String, Object> test = MiniJson.asObject(raw);
                    String name = test == null ? "" : MiniJson.asString(test.get("name"));
                    if (name == null || name.isBlank()) continue;
                    result.add(new TestService.TestCase(id(), name, name, "ctest", null, 1, TestService.Status.UNKNOWN, 0, ""));
                }
                return List.copyOf(result);
            } catch (RuntimeException error) {
                return List.of();
            }
        }

        @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) {
            List<TestService.TestCase> parsed = TestService.parseJUnit(root, id(), command.reports());
            List<TestService.TestCase> result = new ArrayList<>();
            for (TestService.TestCase test : parsed) {
                String name = test.name();
                if (name == null || name.isBlank()) continue;
                // CTest JSON discovery identifies a test by name, while its JUnit classname is implementation-defined.
                result.add(new TestService.TestCase(id(), name, name, "ctest", null, 1, test.status(), test.durationMillis(), test.output()));
            }
            return List.copyOf(result);
        }

        private static String exactNameExpression(List<TestService.TestCase> selected) {
            List<String> names = selected.stream().map(TestService.TestCase::id).filter(name -> name != null && !name.isBlank())
                .distinct().map(CtestAdapter::regexLiteral).toList();
            return names.size() == 1 ? "^" + names.getFirst() + "$" : "^(" + String.join("|", names) + ")$";
        }

        private static String regexLiteral(String value) {
            StringBuilder escaped = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if ("\\.^$|?*+()[]{}".indexOf(character) >= 0) escaped.append('\\');
                escaped.append(character);
            }
            return escaped.toString();
        }
    }
}
