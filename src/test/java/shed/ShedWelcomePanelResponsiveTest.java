package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShedWelcomePanelResponsiveTest {
    @Test
    void enlargesWelcomeContentWhenTheWindowGrows() {
        assertTrue(ShedWelcomePanel.contentScale(1_920, 1_080) > ShedWelcomePanel.contentScale(960, 1_080));
        assertEquals(1.65, ShedWelcomePanel.contentScale(3_840, 2_160));
    }

    @Test
    void usesAStackedLayoutForAUsablePortraitWindowAndSideBySideForShortWindows() {
        assertFalse(ShedWelcomePanel.usesWideLayout(800, 1_080));
        assertTrue(ShedWelcomePanel.usesWideLayout(800, 600));
        assertTrue(ShedWelcomePanel.usesWideLayout(1_920, 1_080));
    }

    @Test
    void calculatesLargerContentScaleFromAvailableWindowSpace() {
        assertEquals(1.10, ShedWelcomePanel.contentScale(960, 640));
        assertTrue(ShedWelcomePanel.contentScale(1_440, 960) > ShedWelcomePanel.contentScale(960, 640));
    }
}
