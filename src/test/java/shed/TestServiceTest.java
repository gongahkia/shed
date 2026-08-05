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
        Files.writeString(root.resolve(TestService.CONFIG_FILE), "schema_version = 1\n\n[[adapter]]\nid = \"pytest\"\ncommand = [\"python\", \"-m\", \"pytest\"]\n");

        TestService.LoadResult loaded = new TestService().load(root);

        assertTrue(loaded.configured());
        assertTrue(loaded.valid());
        assertEquals(List.of("pytest"), loaded.specs().stream().map(TestService.AdapterSpec::id).toList());
        assertEquals(List.of("python", "-m", "pytest"), loaded.specs().getFirst().command());
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
}
