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
}
