package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GitHubReviewConsentTest {
    @Test
    void requiresRequestedEnablementAndGrantedConsent() {
        assertFalse(GitHubReviewConsent.from(false, false).enabled());
        assertFalse(GitHubReviewConsent.from(true, false).enabled());
        assertFalse(GitHubReviewConsent.from(false, true).enabled());
        assertTrue(GitHubReviewConsent.accepted().enabled());
        assertFalse(GitHubReviewConsent.revoked().enabled());
    }
}
