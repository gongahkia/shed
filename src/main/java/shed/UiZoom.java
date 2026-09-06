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
        double value = clamp(current) + (direction < 0 ? -STEP : STEP);
        return Math.round(clamp(value) * 10.0) / 10.0;
    }

    static int scale(int value, double zoom) {
        return Math.max(1, (int) Math.round(value * clamp(zoom)));
    }
}
