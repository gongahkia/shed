package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import shed.api.RemoteCommandRequest;

class RemoteCommandRequestTest {
    @Test
    void acceptsAWorkspaceRelativeDirectCommandAndEnvironment() {
        RemoteCommandRequest request = new RemoteCommandRequest(List.of("npm", "test"), "packages/app", Map.of("CI", "1"));

        assertEquals(List.of("npm", "test"), request.command());
        assertEquals("packages/app", request.relativeWorkingDirectory());
        assertEquals("1", request.environment().get("CI"));
    }

    @Test
    void acceptsAnEmptyNonCommandArgument() {
        RemoteCommandRequest request = new RemoteCommandRequest(List.of("tool", ""), "", Map.of("CI", "1"));

        assertEquals(List.of("tool", ""), request.command());
    }

    @Test
    void rejectsTraversalAndInvalidCommandOrEnvironmentValues() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteCommandRequest(List.of("npm", "test"), "../other", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RemoteCommandRequest(List.of("npm\nrun"), "", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RemoteCommandRequest(List.of("npm"), "", Map.of("BAD-NAME", "1")));
    }
}
