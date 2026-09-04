package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteLspEndpointTest {
    @Test
    void mapsOnlyWorkspaceFilesBetweenLocalAndRemoteFileUris() {
        RemoteLspEndpoint endpoint = new RemoteLspEndpoint(Path.of("/local/mirror"), "/srv/project", List.of("ssh", "host", "pylsp"));

        assertEquals("file:///srv/project", endpoint.rootUri());
        assertEquals("file:///srv/project/src/main.py", endpoint.uriFor(Path.of("/local/mirror/src/main.py")));
        assertEquals(Path.of("/local/mirror/src/main.py"), endpoint.localPathFor("file:///srv/project/src/main.py"));
        assertNull(endpoint.uriFor(Path.of("/outside/main.py")));
        assertNull(endpoint.localPathFor("file:///etc/passwd"));
        assertNull(endpoint.localPathFor("file:///srv/project/../secrets.py"));
    }

    @Test
    void rejectsUnsafeRemoteRoots() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteLspEndpoint(Path.of("/local/mirror"), "/srv/../project", List.of("pylsp")));
    }
}
