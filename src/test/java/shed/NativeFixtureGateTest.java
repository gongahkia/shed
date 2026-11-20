package shed;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NativeFixtureGateTest {
    @Test void nativePtyTestFailsBeforeAnyFixtureSetup() {
        var error = assertThrows(IllegalStateException.class,
            () -> NativeFixtureGate.requireReviewed("PtyTerminalConnectorTest"));
        assertTrue(error.getMessage().contains("blocked"));
    }

    @Test void interactiveWorkbenchTestsRemainBlocked() {
        assertThrows(IllegalStateException.class,
            () -> NativeFixtureGate.requireReviewed("TexteditorSwingIntegrationTest"));
        assertThrows(IllegalStateException.class,
            () -> NativeFixtureGate.requireReviewed("TerminalShellIntegrationTest"));
    }

    @Test void unknownTestsFailClosed() {
        assertThrows(IllegalStateException.class,
            () -> NativeFixtureGate.requireReviewed("FutureNativeFixtureTest"));
    }

    @Test void reviewedPortableTestAndContextAreAccepted() throws Exception {
        NativeFixtureGate.requireReviewed("TerminalControllerTest");
        NativeFixtureGate.requireContext();
    }
}
