package shed;

final class UpdateConsent {
    record State(boolean requested, boolean granted) {
        boolean enabled() {
            return requested && granted;
        }

        String detail() {
            if (enabled()) return "Automatic update checks are enabled by explicit consent.";
            if (requested) return "Automatic update checks are disabled until explicit consent is granted.";
            return "Automatic update checks are disabled.";
        }
    }

    private UpdateConsent() { }

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
