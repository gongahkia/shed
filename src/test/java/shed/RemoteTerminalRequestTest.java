package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import shed.api.RemoteTerminalRequest;

class RemoteTerminalRequestTest {
    @Test
    void acceptsAnOptionalDirectArgvTerminalCommand() {
        assertEquals(List.of(), new RemoteTerminalRequest(".", List.of()).command());
        assertEquals(List.of("bash", ""), new RemoteTerminalRequest("project", List.of("bash", "")).command());
    }

    @Test
    void rejectsTraversalAndMalformedCommands() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteTerminalRequest("../project", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RemoteTerminalRequest("", List.of("\n")));
    }
}
