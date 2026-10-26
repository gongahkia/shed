package shed;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AgentReplyFormatExceptionTest {
    @Test void correctionRepeatsTheCapturedRequestAndRespectsTheWireLimit() {
        var error = new AgentReplyFormatException("wrong shape");
        String original = "original instruction, version, and unsaved text 🙂";
        for (var action : AgentAction.values()) {
            String correction = error.correction(action, original);
            assertTrue(correction.endsWith(original));
            assertTrue(correction.contains("Do not return an error object"));
            assertTrue(correction.contains("SAME original instruction"));
            String bounded = error.correction(action, "x".repeat(65536));
            assertTrue(bounded.length() <= 65536);
            assertFalse(bounded.contains("Original request (unchanged):"));
        }
    }
}
