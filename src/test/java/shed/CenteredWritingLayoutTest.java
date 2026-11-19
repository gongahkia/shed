package shed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CenteredWritingLayoutTest {
    @Test void derivesColumnFromFontAndSubtractsChrome() {
        assertEquals(190, FocusModeController.centeredPadding(1200, 20, 80, 10));
        assertEquals(90, FocusModeController.centeredPadding(1200, 20, 100, 10));
        assertEquals(110, FocusModeController.centeredPadding(1200, 20, 80, 12));
    }

    @Test void narrowAndZeroWidthNeverCreateClippingInsets() {
        assertEquals(0, FocusModeController.centeredPadding(400, 40, 80, 10));
        assertEquals(0, FocusModeController.centeredPadding(0, 40, 80, 10));
    }

    @Test void configurationBoundsAndOverflowRemainSafe() {
        assertEquals(400, FocusModeController.centeredPadding(1000, 0, -1, 10));
        assertEquals(0, FocusModeController.centeredPadding(1000, 0, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(380, FocusModeController.centeredPadding(1000, 0, Integer.MAX_VALUE, 1));
    }
}
