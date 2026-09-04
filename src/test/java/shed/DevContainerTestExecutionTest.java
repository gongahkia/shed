package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevContainerTestExecutionTest {
    @TempDir Path root;

    @Test
    void mapsOnlyWorkspacePathsUsesMountedCacheAndCleansThatExactRunDirectory() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("project"));
        Path cache = DevContainerTestExecution.createReportCache(workspace);
        Path report = cache.resolve("pytest.xml");
        Path wrapper = Files.writeString(workspace.resolve("pytest-wrapper"), "");
        TestService.Command command = new TestService.Command(
            List.of(wrapper.toString(), "--junit-xml=" + report, "--prefix=" + workspace + "-not-a-path"), List.of(report));

        DevContainerTestExecution.Plan plan = DevContainerTestExecution.prepare(workspace, "/workspaces/project", command);

        assertEquals(List.of("devcontainer", "exec", "--workspace-folder", workspace.toString(), "/workspaces/project/pytest-wrapper",
            "--junit-xml=/workspaces/project/.shed-devcontainer-test-reports/" + cache.getFileName() + "/pytest.xml",
            "--prefix=" + workspace + "-not-a-path"), plan.invocation());

        Files.writeString(report, "<testsuite/>");
        List<String> diagnostics = new ArrayList<>();
        assertEquals(List.of(report), DevContainerTestExecution.validatedCommand(command, cache, diagnostics).reports());
        assertTrue(diagnostics.isEmpty());
        assertEquals("", DevContainerTestExecution.cleanupReportCache(workspace, cache));
        assertFalse(Files.exists(cache));
        assertFalse(Files.exists(workspace.resolve(".shed-devcontainer-test-reports")));
    }

    @Test
    void rejectsSymbolicLinkReportsBeforeParserUse() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("symbolic-project"));
        Path cache = DevContainerTestExecution.createReportCache(workspace);
        Path outside = Files.writeString(root.resolve("outside.xml"), "<testsuite/>");
        Path report = cache.resolve("report.xml");
        Files.createSymbolicLink(report, outside);
        TestService.Command command = new TestService.Command(List.of("pytest"), List.of(report));

        List<String> diagnostics = new ArrayList<>();
        assertTrue(DevContainerTestExecution.validatedCommand(command, cache, diagnostics).reports().isEmpty());
        assertTrue(diagnostics.getFirst().contains("rejected"));
        assertEquals("", DevContainerTestExecution.cleanupReportCache(workspace, cache));
    }
}
