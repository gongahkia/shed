package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteWorkspaceSessionServiceTest {
    @Test
    void aliasesRouteToTheSameSessionAndExplicitTaskTarget(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("workspace"));
        Path alias = directory.resolve("alias");
        Path outside = Files.createDirectory(directory.resolve("outside"));
        try {
            Files.createSymbolicLink(alias, root);
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException error) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + error.getMessage());
            return;
        }
        StubWorkspace workspace = new StubWorkspace(alias, "/srv/project");
        RemoteWorkspaceSessionService service = new RemoteWorkspaceSessionService();
        service.activate("remote", workspace);
        RemoteWorkspaceSessionService.Connection connection = service.connectionFor(root.toRealPath().resolve("src"));
        assertNotNull(connection);
        connection.terminalInvocation(root.toRealPath().resolve("src"), List.of("sh"));
        assertEquals("src", workspace.lastTerminalRequest.relativeWorkingDirectory());
        assertEquals(alias.resolve("src/Main.java"), connection.sourcePathMapper(root.toRealPath().resolve("src"))
            .map("Main.java", root));
        assertNull(service.connectionFor(alias.resolve("escape/new-file.txt")));

        RemoteWorkspaceTaskTargets targets = new RemoteWorkspaceTaskTargets();
        targets.register("remote", workspace);
        assertEquals("remote", targets.targetForPath(root.toRealPath()).id());
        assertEquals("remote", targets.targetFor("remote", root.resolve("new-file.txt")).id());
        assertNull(targets.targetForPath(alias.resolve("escape/new-file.txt")));

        service.activate("replacement", new StubWorkspace(root, "/srv/project"));
        assertEquals(1, service.activeConnections().size());
        assertEquals("replacement", service.connectionFor(alias).id());
    }

    @Test
    void usesDeepestExplicitSessionAndMapsTerminalDirectory() throws Exception {
        RemoteWorkspaceSessionService service = new RemoteWorkspaceSessionService();
        StubWorkspace root = new StubWorkspace(Path.of("/mirror"), "/srv/project");
        StubWorkspace nested = new StubWorkspace(Path.of("/mirror/nested"), "/srv/project/nested");

        service.activate("root", root);
        service.activate("nested", nested);
        RemoteWorkspaceSessionService.Connection connection = service.connectionFor(Path.of("/mirror/nested/src"));

        assertEquals("nested", connection.id());
        assertEquals(List.of("ssh", "nested"), connection.terminalInvocation(Path.of("/mirror/nested/src"), List.of("bash", "-l")));
        assertEquals("src", nested.lastTerminalRequest.relativeWorkingDirectory());
        assertEquals(List.of("bash", "-l"), nested.lastTerminalRequest.command());

        TerminalLinkResolver.SourcePathMapper mapper = connection.sourcePathMapper(Path.of("/mirror/nested/src"));
        assertNotNull(mapper);
        assertEquals(Path.of("/mirror/nested/src/Main.java").toAbsolutePath().normalize(), mapper.map("Main.java", Path.of("/ignored")));
        assertEquals(Path.of("/mirror/nested/src/Main.java").toAbsolutePath().normalize(), mapper.map("/srv/project/nested/src/Main.java", Path.of("/ignored")));
    }

    @Test
    void deactivationClearsOnlyRequestedSession() {
        RemoteWorkspaceSessionService service = new RemoteWorkspaceSessionService();
        service.activate("one", new StubWorkspace(Path.of("/one"), "/one"));
        service.activate("two", new StubWorkspace(Path.of("/two"), "/two"));

        assertEquals(true, service.deactivate("one"));
        assertNull(service.connectionFor(Path.of("/one/file.txt")));
        assertFalse(service.activeConnections().isEmpty());
        service.close();
        assertTrue(service.activeConnections().isEmpty());
    }

    private static final class StubWorkspace implements RemoteWorkspace {
        private final Path root;
        private final String remoteRoot;
        private RemoteTerminalRequest lastTerminalRequest;

        StubWorkspace(Path root, String remoteRoot) {
            this.root = root;
            this.remoteRoot = remoteRoot;
        }

        @Override public String displayName() { return "stub"; }
        @Override public Path localRoot() { return root; }
        @Override public String executionRoot() { return remoteRoot; }
        @Override public void synchronize() { }
        @Override public List<String> terminalCommand(RemoteTerminalRequest request) {
            lastTerminalRequest = request;
            return List.of("ssh", "nested");
        }
        @Override public String languageServerRoot() { return remoteRoot; }
        @Override public void close() { }
    }
}
