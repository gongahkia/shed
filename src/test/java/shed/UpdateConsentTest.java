package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class UpdateConsentTest {
    @Test
    void requiresRequestedEnablementAndGrantedConsent() {
        assertFalse(UpdateConsent.from(false, false).enabled());
        assertFalse(UpdateConsent.from(true, false).enabled());
        assertFalse(UpdateConsent.from(false, true).enabled());
        assertTrue(UpdateConsent.accepted().enabled());
        assertFalse(UpdateConsent.revoked().enabled());
    }
}
