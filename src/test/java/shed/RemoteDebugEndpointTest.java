package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteDebugEndpointTest {
    @Test
    void mapsOnlyPathsInsideTheConnectedMirror() {
        RemoteDebugEndpoint endpoint = new RemoteDebugEndpoint(Path.of("/local/mirror"), "/srv/project", List.of("ssh", "host", "debugpy-adapter"));

        assertEquals("/srv/project/src/main.py", endpoint.remotePathFor(Path.of("/local/mirror/src/main.py")));
        assertEquals(Path.of("/local/mirror/src/main.py"), endpoint.localPathFor("/srv/project/src/main.py"));
        assertNull(endpoint.remotePathFor(Path.of("/outside/main.py")));
        assertNull(endpoint.localPathFor("/etc/passwd"));
        assertNull(endpoint.localPathFor("/srv/project/../secret.py"));
    }

    @Test
    void rejectsUnsafeRootsAndCommands() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteDebugEndpoint(Path.of("/local/mirror"), "/srv/../project", List.of("adapter")));
        assertThrows(IllegalArgumentException.class, () -> new RemoteDebugEndpoint(Path.of("/local/mirror"), "/srv/project", List.of("adapter\n")));
    }
}
