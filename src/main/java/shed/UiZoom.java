package shed;

final class UiZoom {
    static final double DEFAULT = 1.0;
    static final double MINIMUM = 0.5;
    static final double MAXIMUM = 4.0;
    static final double STEP = 0.1;

    private UiZoom() {
    }

    static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT;
        }
        return Math.max(MINIMUM, Math.min(MAXIMUM, value));
    }

    static double adjust(double current, int direction) {
        return adjust(current, direction, STEP);
    }

    static double adjust(double current, int direction, double amount) {
        double safeAmount = Double.isFinite(amount) && amount > 0.0 ? amount : STEP;
        double value = clamp(current) + (direction < 0 ? -safeAmount : safeAmount);
        return Math.round(clamp(value) * 100.0) / 100.0;
    }

    static int scale(int value, double zoom) {
        return Math.max(1, (int) Math.round(value * clamp(zoom)));
    }
}
