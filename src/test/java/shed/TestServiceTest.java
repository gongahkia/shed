package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestServiceTest {
    @TempDir Path root;

    @Test
    void detectsMavenAndDiscoversAnnotatedJavaTests() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path source = root.resolve("src/test/java/demo/SampleTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo;\nclass SampleTest {\n @Test void works() {}\n}\n");

        TestService service = new TestService();
        TestService.LoadResult loaded = service.load(root);

        assertTrue(loaded.valid());
        assertEquals(List.of("maven"), loaded.specs().stream().map(TestService.AdapterSpec::id).toList());
        assertEquals("demo.SampleTest#works", service.staticDiscovery(root, loaded.specs().getFirst()).getFirst().id());
    }

    @Test
    void usesShedtestsInsteadOfAutoDetectionAndValidatesArgv() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve(TestService.CONFIG_FILE), "schema_version = 1\n\n[[adapter]]\nid = \"pytest\"\ncommand = [\"python\", \"-m\", \"pytest\"]\ndebug_configuration = \"pytest-test\"\n");

        TestService.LoadResult loaded = new TestService().load(root);

        assertTrue(loaded.configured());
        assertTrue(loaded.valid());
        assertEquals(List.of("pytest"), loaded.specs().stream().map(TestService.AdapterSpec::id).toList());
        assertEquals(List.of("python", "-m", "pytest"), loaded.specs().getFirst().command());
        assertEquals("pytest-test", loaded.specs().getFirst().debugConfiguration());
    }

    @Test
    void permitsAnExplicitImportedVsCodeDebugProfileForTestSelection() throws Exception {
        Files.writeString(root.resolve(TestService.CONFIG_FILE), """
            schema_version = 1

            [[adapter]]
            id = "pytest"
            debug_configuration = "vscode:Debug selected test"
            """);

        TestService.LoadResult loaded = new TestService().load(root);

        assertTrue(loaded.valid());
        assertEquals("vscode:Debug selected test", loaded.specs().getFirst().debugConfiguration());
    }

    @Test
    void parsesJUnitFailuresIntoSourceLocations() throws Exception {
        Path source = root.resolve("src/test/java/demo/SampleTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo;\nclass SampleTest {\n void fails() {}\n}\n");
        Path report = root.resolve("TEST-demo.SampleTest.xml");
        Files.writeString(report, "<testsuite><testcase classname=\"demo.SampleTest\" name=\"fails\" time=\"0.125\"><failure>expected true</failure></testcase><testcase classname=\"demo.SampleTest\" name=\"passes\" time=\"0.01\"/></testsuite>");

        List<TestService.TestCase> results = TestService.parseJUnit(root, "maven", List.of(report));

        TestService.TestCase failure = results.stream().filter(test -> test.name().equals("fails")).findFirst().orElseThrow();
        assertEquals(TestService.Status.FAILED, failure.status());
        assertEquals(source, failure.file());
        assertEquals(125, failure.durationMillis());
        assertEquals("expected true", failure.output());
    }

    @Test
    void parsesGoJsonTestEvents() {
        String events = "{\"Action\":\"run\",\"Package\":\"example.com/demo\",\"Test\":\"TestOne\"}\n"
            + "{\"Action\":\"output\",\"Package\":\"example.com/demo\",\"Test\":\"TestOne\",\"Output\":\"    x_test.go:12: boom\\n\"}\n"
            + "{\"Action\":\"fail\",\"Package\":\"example.com/demo\",\"Test\":\"TestOne\",\"Elapsed\":0.25}\n";

        List<TestService.TestCase> results = TestService.parseGo(root, events);

        assertEquals(1, results.size());
        assertEquals(TestService.Status.FAILED, results.getFirst().status());
        assertEquals(250, results.getFirst().durationMillis());
        assertFalse(results.getFirst().output().isBlank());
    }

    @Test
    void detectsDotnetAndParsesTrxResults() throws Exception {
        Files.writeString(root.resolve("demo.sln"), "");
        Path source = root.resolve("Demo.Tests/WidgetTests.cs");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class WidgetTests { void Works() {} }");
        Path report = root.resolve("result.trx");
        Files.writeString(report, "<TestRun><TestDefinitions><UnitTest id=\"one\"><TestMethod className=\"Demo.WidgetTests\" name=\"Works\"/></UnitTest></TestDefinitions>"
            + "<Results><UnitTestResult testId=\"one\" testName=\"Demo.WidgetTests.Works\" outcome=\"Passed\" duration=\"00:00:00.125\"/></Results></TestRun>");

        TestService service = new TestService();
        assertEquals(List.of("dotnet"), service.load(root).specs().stream().map(TestService.AdapterSpec::id).toList());
        TestService.TestCase result = TestService.parseTrx(root, "dotnet", List.of(report)).getFirst();
        assertEquals(TestService.Status.PASSED, result.status());
        assertEquals(source, result.file());
        assertEquals(125, result.durationMillis());
    }

    @Test
    void detectsCargoAndBuildsExactSelectedTestCommands() throws Exception {
        Files.writeString(root.resolve("Cargo.toml"), "[package]\nname = \"demo\"\nversion = \"0.1.0\"\n");
        TestService service = new TestService();
        TestService.AdapterSpec spec = service.load(root).specs().getFirst();
        TestAdapter adapter = service.adapter("cargo");

        assertEquals(List.of("cargo"), spec.command());
        assertEquals(List.of("cargo", "test", "--", "--list"), adapter.discovery(spec).argv());
        TestService.TestCase test = new TestService.TestCase("cargo", "module::works", "works", "rust", null, 1,
            TestService.Status.UNKNOWN, 0, "");
        assertEquals(List.of("cargo", "test", "module::works", "--", "--exact"), adapter.run(spec, List.of(test), root).argv());
        assertEquals(TestService.Status.FAILED, adapter.parseRun(root, new TestService.Command(List.of(), List.of()),
            "test module::works ... FAILED\n").getFirst().status());
    }

    @Test
    void detectsOneGeneratedCtestTreeAndUsesStableJsonAndJunitInterfaces() throws Exception {
        Path build = root.resolve("build");
        Files.createDirectories(build);
        Files.writeString(build.resolve("CTestTestfile.cmake"), "# generated by CMake\n");
        TestService service = new TestService();
        TestService.AdapterSpec spec = service.load(root).specs().getFirst();
        TestAdapter adapter = service.adapter("ctest");

        assertEquals(List.of("ctest"), service.load(root).specs().stream().map(TestService.AdapterSpec::id).toList());
        assertEquals(List.of("ctest", "--test-dir", build.toAbsolutePath().normalize().toString()), spec.command());
        assertEquals(List.of("ctest", "--test-dir", build.toAbsolutePath().normalize().toString(), "--show-only=json-v1"),
            adapter.discovery(spec).argv());
        assertEquals(List.of("unit.alpha", "unit+beta"), adapter.parseDiscovery(root, "warning before JSON\n" +
            "{\"kind\":\"ctestInfo\",\"tests\":[{\"name\":\"unit.alpha\"},{\"name\":\"unit+beta\"}]}\n")
            .stream().map(TestService.TestCase::id).toList());

        TestService.Command selected = adapter.run(spec, List.of(
            new TestService.TestCase("ctest", "unit.alpha", "unit.alpha", "ctest", null, 1, TestService.Status.UNKNOWN, 0, ""),
            new TestService.TestCase("ctest", "unit+beta", "unit+beta", "ctest", null, 1, TestService.Status.UNKNOWN, 0, "")
        ), root.resolve("cache"));
        assertEquals(List.of("ctest", "--test-dir", build.toAbsolutePath().normalize().toString(), "--output-on-failure", "--output-junit",
            root.resolve("cache/ctest.xml").toString(), "-R", "^(unit\\.alpha|unit\\+beta)$"), selected.argv());
        assertEquals(root.resolve("cache/ctest.xml"), selected.reports().getFirst());
    }

    @Test
    void doesNotGuessBetweenMultipleGeneratedCtestTrees() throws Exception {
        Path first = root.resolve("build");
        Path second = root.resolve("cmake-build-debug");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("CTestTestfile.cmake"), "# generated by CMake\n");
        Files.writeString(second.resolve("CTestTestfile.cmake"), "# generated by CMake\n");

        TestService.LoadResult loaded = new TestService().load(root);

        assertTrue(loaded.valid());
        assertTrue(loaded.specs().isEmpty());
    }

    @Test
    void detectsImportableStandardLibraryUnittestWithoutExecutingDiscoveryAndParsesVerboseResults() throws Exception {
        Path source = root.resolve("test_widget.py");
        Files.writeString(source, """
            import unittest

            class WidgetTests(unittest.TestCase):
                def test_passes(self):
                    self.assertTrue(True)

                def test_fails(self):
                    self.fail("no")

                def helper(self):
                    pass
            """);

        TestService service = new TestService();
        TestService.LoadResult loaded = service.load(root);
        TestService.TestCase selected = service.staticDiscovery(root, loaded.specs().getFirst()).stream()
            .filter(test -> test.name().equals("test_passes")).findFirst().orElseThrow();
        TestAdapter adapter = service.adapter("unittest");

        assertEquals(List.of("unittest"), loaded.specs().stream().map(TestService.AdapterSpec::id).toList());
        assertEquals("test_widget.WidgetTests.test_passes", selected.id());
        assertEquals(source, selected.file());
        assertEquals(4, selected.line());
        assertTrue(adapter.discovery(loaded.specs().getFirst()).argv().isEmpty());
        assertEquals(List.of("python", "-m", "unittest", "-v", selected.id()),
            adapter.run(loaded.specs().getFirst(), List.of(selected), root).argv());
        assertEquals(List.of("python", "-m", "unittest", "discover", "-v"),
            adapter.run(loaded.specs().getFirst(), List.of(), root).argv());

        List<TestService.TestCase> results = adapter.parseRun(root, new TestService.Command(List.of(), List.of()), """
            test_passes (test_widget.WidgetTests.test_passes) ... ok
            test_fails (test_widget.WidgetTests.test_fails) ... FAIL
            test_skipped (test_widget.WidgetTests.test_skipped) ... skipped 'not available'
            """);

        assertEquals(TestService.Status.PASSED, results.stream().filter(test -> test.name().equals("test_passes")).findFirst().orElseThrow().status());
        assertEquals(TestService.Status.FAILED, results.stream().filter(test -> test.name().equals("test_fails")).findFirst().orElseThrow().status());
        assertEquals(TestService.Status.SKIPPED, results.stream().filter(test -> test.name().equals("test_skipped")).findFirst().orElseThrow().status());
    }
}
