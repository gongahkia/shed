package shed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import org.junit.jupiter.api.Test;

public class TerminalConformanceFixtureTest {
    @Test
    void rendersSupportedAnsiOutputWithoutControlSequenceLeakage() throws Exception {
        TerminalConformanceFixture.Result result = TerminalConformanceFixture.render("plain \u001b[31mred\u001b[0m\nline two");

        assertTrue(result.screen().contains("plain red"), result.screen());
        assertTrue(result.screen().contains("line two"), result.screen());
        assertFalse(result.screen().contains("\u001b"), result.screen());
    }

    @Test
    void keepsOutputIntactAroundUnsupportedEscapeSequences() throws Exception {
        TerminalConformanceFixture.Result result = TerminalConformanceFixture.render("before\u001b[?9999zafter");

        assertTrue(result.screen().contains("before"), result.screen());
        assertTrue(result.screen().contains("after"), result.screen());
    }

    @Test
    void emitsSupportedApplicationInputCodes() throws Exception {
        TerminalConformanceFixture.Result result = TerminalConformanceFixture.render("");

        assertArrayEquals("\u001b[A".getBytes(java.nio.charset.StandardCharsets.UTF_8), result.terminal().getCodeForKey(KeyEvent.VK_UP, 0));
        assertArrayEquals("\r".getBytes(java.nio.charset.StandardCharsets.UTF_8), result.terminal().getCodeForKey(KeyEvent.VK_ENTER, 0));
    }
}
