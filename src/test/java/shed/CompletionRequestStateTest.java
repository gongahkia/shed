package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class CompletionRequestStateTest {
    @Test
    void rejectsStaleDocumentCaretAndGenerationSnapshots() {
        CompletionRequestState state = new CompletionRequestState();
        CompletionRequestState.Snapshot first = state.begin("file:///tmp/a.java", 3, 12, "comp");

        assertTrue(state.matches(first, "file:///tmp/a.java", 3, 12, "comp"));
        assertFalse(state.matches(first, "file:///tmp/a.java", 4, 12, "comp"));
        assertFalse(state.matches(first, "file:///tmp/a.java", 3, 13, "comp"));
        assertFalse(state.matches(first, "file:///tmp/b.java", 3, 12, "comp"));
        assertFalse(state.matches(first, "file:///tmp/a.java", 3, 12, "compose"));
        CompletionRequestState.Snapshot second = state.begin("file:///tmp/a.java", 3, 12, "comp");
        assertFalse(state.matches(first, "file:///tmp/a.java", 3, 12, "comp"));
        assertTrue(state.matches(second, "file:///tmp/a.java", 3, 12, "comp"));
        state.invalidate();
        assertFalse(state.matches(second, "file:///tmp/a.java", 3, 12, "comp"));
    }
}
