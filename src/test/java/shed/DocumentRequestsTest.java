package shed;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DocumentRequestsTest {
    @Test void newerRequestOrClosedPaneRejectsLateLoadsWithoutInvalidatingOtherPanes() {
        DocumentRequests<Object> requests = new DocumentRequests<>();
        Object a = new Object(), b = new Object();
        long old = requests.issue(a), other = requests.issue(b), latest = requests.issue(a);
        assertFalse(requests.current(a, old));
        assertTrue(requests.current(a, latest));
        assertTrue(requests.current(b, other));
        requests.invalidate(a);
        assertFalse(requests.current(a, latest));
        assertTrue(requests.current(b, other));
        long reopened = requests.issue(a);
        assertTrue(requests.current(a, reopened));
        assertFalse(requests.current(a, latest));
    }
}
