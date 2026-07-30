package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TerminalSessionStateTest {
    @Test
    void serializesOnlyPaneAndWorkingDirectory() {
        String directory = Path.of(".").toAbsolutePath().normalize().toString();

        TerminalSessionState state = new TerminalSessionState(2, directory);

        assertEquals(Map.of("paneIndex", 2, "workingDirectory", directory), state.toMap());
        assertFalse(state.toMap().containsKey("command"));
        assertFalse(state.toMap().containsKey("shell"));
        assertFalse(state.toMap().containsKey("scrollback"));
    }

    @Test
    void ignoresUnsafeOrDuplicateEntriesWithoutDiscardingSafeMetadata() {
        String directory = Path.of(".").toAbsolutePath().normalize().toString();
        List<Object> values = List.of(
            Map.of("paneIndex", 1, "workingDirectory", directory, "command", "rm -rf /"),
            Map.of("paneIndex", 1, "workingDirectory", directory),
            Map.of("paneIndex", 2.5, "workingDirectory", directory),
            Map.of("paneIndex", 3, "workingDirectory", "relative")
        );

        TerminalSessionState.ParseResult result = TerminalSessionState.parseAll(values);

        assertEquals(List.of(new TerminalSessionState(1, directory)), result.states());
        assertEquals(3, result.ignored());
    }

    @Test
    void treatsMissingMetadataAsNoSavedTerminal() {
        TerminalSessionState.ParseResult result = TerminalSessionState.parseAll(null);

        assertTrue(result.states().isEmpty());
        assertEquals(0, result.ignored());
    }
}
