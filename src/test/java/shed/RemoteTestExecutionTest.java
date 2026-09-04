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
import shed.api.RemoteCommandRequest;
import shed.api.RemoteCommandResult;
import shed.api.RemoteWorkspace;

class RemoteTestExecutionTest {
    @TempDir Path root;

    @Test
    void mapsManagedPathsRunsRemotelyAndFetchesStructuredReports() throws Exception {
        Path cache = Files.createDirectories(root.resolve("reports"));
        Path wrapper = Files.writeString(root.resolve("mvnw"), "");
        Path report = cache.resolve("pytest.xml");
        FakeWorkspace workspace = new FakeWorkspace(root, "/srv/project");
        RemoteWorkspaceTaskTargets.Target target = new RemoteWorkspaceTaskTargets.Target("ssh", workspace, root);
        TestService.Command command = new TestService.Command(List.of(wrapper.toString(), "--outputFile=" + report), List.of(report, Path.of("target", "surefire-reports")));

        RemoteTestExecution.Plan plan = RemoteTestExecution.prepare(target, root, command, cache);
        RemoteTestExecution.Result result = RemoteTestExecution.execute(plan);

        assertEquals(0, result.result().exitCode);
        assertEquals(root.resolve("src/test/java/DemoTest.java") + ":7: failed", result.result().stdout);
        assertEquals(2, result.command().reports().size());
        assertTrue(Files.isRegularFile(result.command().reports().getFirst()));
        assertTrue(Files.isDirectory(result.command().reports().get(1)));
        assertEquals(List.of("mkdir", "-p", plan.remoteReportDirectory()), workspace.requests.getFirst().command());
        assertEquals("/srv/project/mvnw", workspace.requests.get(1).command().getFirst());
        assertTrue(workspace.requests.get(1).command().get(1).startsWith("--outputFile=/srv/project/.shed-remote-test-reports/"));
        assertEquals(List.of("rm", "-rf", plan.remoteReportDirectory()), workspace.requests.getLast().command());
        assertTrue(result.diagnostics().isEmpty());
    }

    private static final class FakeWorkspace implements RemoteWorkspace {
        private final Path localRoot;
        private final String remoteRoot;
        private final List<RemoteCommandRequest> requests = new ArrayList<>();

        private FakeWorkspace(Path localRoot, String remoteRoot) {
            this.localRoot = localRoot;
            this.remoteRoot = remoteRoot;
        }

        @Override public String displayName() { return "fake"; }
        @Override public Path localRoot() { return localRoot; }
        @Override public String executionRoot() { return remoteRoot; }
        @Override public void synchronize() { }
        @Override public RemoteCommandResult execute(RemoteCommandRequest request) {
            requests.add(request);
            if (request.command().getFirst().equals("mkdir") || request.command().getFirst().equals("rm")) return new RemoteCommandResult(0, "");
            return new RemoteCommandResult(0, "/srv/project/src/test/java/DemoTest.java:7: failed");
        }
        @Override public Path fetchWorkspacePath(String relativePath, Path destination) throws Exception {
            if (relativePath.endsWith("pytest.xml")) return Files.writeString(destination.resolve("pytest.xml"), "<testsuite/>");
            if (relativePath.endsWith("target/surefire-reports")) {
                Path reports = Files.createDirectories(destination);
                Files.writeString(reports.resolve("TEST-demo.xml"), "<testsuite/>");
                return reports;
            }
            throw new IllegalArgumentException("unexpected path: " + relativePath);
        }
        @Override public void close() { }
    }
}
