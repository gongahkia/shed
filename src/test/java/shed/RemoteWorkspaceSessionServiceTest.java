package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import shed.api.RemoteTerminalRequest;
import shed.api.RemoteWorkspace;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteWorkspaceSessionServiceTest {
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
        assertEquals(Path.of("/mirror/nested/src/Main.java"), mapper.map("Main.java", Path.of("/ignored")));
        assertEquals(Path.of("/mirror/nested/src/Main.java"), mapper.map("/srv/project/nested/src/Main.java", Path.of("/ignored")));
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
