package shed;

final class GitHubReviewConsent {
    record State(boolean requested, boolean granted) {
        boolean enabled() {
            return requested && granted;
        }

        String detail() {
            if (enabled()) return "GitHub review integration is enabled by explicit consent.";
            if (requested) return "GitHub review integration is disabled until explicit consent is granted.";
            return "GitHub review integration is disabled.";
        }
    }

    private GitHubReviewConsent() { }

    static State from(boolean requested, boolean granted) {
        return new State(requested, granted);
    }

    static State accepted() {
        return new State(true, true);
    }

    static State revoked() {
        return new State(false, false);
    }
}
