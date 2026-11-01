package shed;

import java.awt.Rectangle;
import java.awt.geom.Path2D;

/** Time-based corner interpolation. The leading edge arrives before the tail. */
final class CaretMotion {
    static final long DURATION_NANOS = 150_000_000L;
    private final double[] x = new double[4], y = new double[4];
    private final double[] fromX = new double[4], fromY = new double[4], duration = new double[4];
    private Rectangle target;
    private long started;
    private boolean moving;

    void snap(Rectangle bounds) {
        target = new Rectangle(bounds);
        for (int i = 0; i < 4; i++) {
            x[i] = bounds.x + (i == 1 || i == 2 ? bounds.width : 0);
            y[i] = bounds.y + (i >= 2 ? bounds.height : 0);
        }
        moving = false;
    }

    void moveTo(Rectangle bounds, long now) {
        if (target == null) { snap(bounds); return; }
        if (target.equals(bounds)) return;
        advance(now);
        double cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) { cx += x[i] / 4; cy += y[i] / 4; }
        double dx = bounds.getCenterX() - cx, dy = bounds.getCenterY() - cy;
        double span = Math.abs(dx) * bounds.width + Math.abs(dy) * bounds.height;
        for (int i = 0; i < 4; i++) {
            fromX[i] = x[i]; fromY[i] = y[i];
            double cornerX = (i == 1 || i == 2 ? .5 : -.5) * bounds.width;
            double cornerY = (i >= 2 ? .5 : -.5) * bounds.height;
            double leading = span == 0 ? 0 : .5 + (cornerX * dx + cornerY * dy) / span;
            duration[i] = DURATION_NANOS * (1 - .5 * leading);
        }
        target = new Rectangle(bounds);
        started = now;
        moving = true;
    }

    void advance(long now) {
        if (!moving) return;
        if (now - started >= DURATION_NANOS) { snap(target); return; }
        for (int i = 0; i < 4; i++) {
            double t = Math.max(0, Math.min(1, (now - started) / duration[i]));
            double eased = 1 - Math.pow(1 - t, 3);
            double tx = target.x + (i == 1 || i == 2 ? target.width : 0);
            double ty = target.y + (i >= 2 ? target.height : 0);
            x[i] = fromX[i] + (tx - fromX[i]) * eased;
            y[i] = fromY[i] + (ty - fromY[i]) * eased;
        }
    }

    Path2D shape() {
        Path2D path = new Path2D.Double();
        path.moveTo(x[0], y[0]);
        for (int i = 1; i < 4; i++) path.lineTo(x[i], y[i]);
        path.closePath();
        return path;
    }

    Rectangle bounds() { return shape().getBounds(); }
    boolean moving() { return moving; }
}
