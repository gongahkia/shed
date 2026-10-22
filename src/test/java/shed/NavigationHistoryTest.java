package shed;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NavigationHistoryTest {
    @Test
    void firstBackReturnsToOriginAndForwardReturnsToDestination() {
        NavigationHistory<String> history = new NavigationHistory<>(100);
        history.record("file-a:12");
        assertEquals("file-a:12", history.back("file-b:12", value -> true));
        assertEquals("file-b:12", history.forward("file-a:12", value -> true));
        assertNull(history.forward("file-b:12", value -> true));
    }

    @Test
    void newJumpAfterGoingBackDiscardsTheForwardBranch() {
        NavigationHistory<String> history = new NavigationHistory<>(100);
        history.record("a");
        history.record("b");
        assertEquals("b", history.back("c", value -> true));
        history.record("b");
        assertNull(history.forward("d", value -> true));
        assertEquals("b", history.back("d", value -> true));
        assertEquals("a", history.back("b", value -> true));
    }

    @Test
    void boundsHistoryAndSkipsDuplicatesAndUnavailableTargets() {
        NavigationHistory<String> history = new NavigationHistory<>(2);
        history.record("expired");
        history.record("a");
        history.record("a");
        history.record("closed");
        assertEquals("a", history.back("current", value -> !value.equals("closed")));
        assertNull(history.back("a", value -> true));
        assertEquals("current", history.forward("a", value -> true));
    }
}
