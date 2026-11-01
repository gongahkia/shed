package shed;

import java.awt.Rectangle;
import java.awt.geom.PathIterator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaretMotionTest {
    @Test
    void firstPositionIsImmediateAndUnchangedTargetsDoNotRestartMotion() {
        CaretMotion motion = new CaretMotion();
        Rectangle start = new Rectangle(10, 20, 8, 18);
        motion.moveTo(start, 0);
        assertEquals(start, motion.bounds());
        assertFalse(motion.moving());
        Rectangle end = new Rectangle(210, 80, 12, 22);
        motion.moveTo(end, 0);
        motion.moveTo(end, CaretMotion.DURATION_NANOS / 2);
        motion.advance(CaretMotion.DURATION_NANOS);
        assertEquals(end, motion.bounds());
        assertFalse(motion.moving());
    }

    @Test
    void leadingEdgeArrivesBeforeTheTailAndSettlesExactly() {
        CaretMotion motion = new CaretMotion();
        motion.snap(new Rectangle(0, 0, 10, 20));
        motion.moveTo(new Rectangle(200, 0, 10, 20), 0);
        motion.advance(CaretMotion.DURATION_NANOS / 2);
        double[] corners = coordinates(motion);
        assertEquals(210, corners[2], .001);
        assertTrue(corners[0] > 0 && corners[0] < 200);
        assertTrue(motion.bounds().width > 10, "Moving caret should stretch");
        motion.advance(CaretMotion.DURATION_NANOS);
        assertEquals(new Rectangle(200, 0, 10, 20), motion.bounds());
        assertFalse(motion.moving());
    }

    @Test
    void retargetingPreservesEveryCornerWithoutJumping() {
        for (Rectangle end : new Rectangle[] {new Rectangle(-120, 0, 8, 18),
            new Rectangle(0, 120, 8, 18), new Rectangle(120, -120, 8, 18)}) {
            CaretMotion motion = new CaretMotion();
            motion.snap(new Rectangle(0, 0, 8, 18));
            motion.moveTo(new Rectangle(200, 100, 8, 18), 0);
            long mid = CaretMotion.DURATION_NANOS / 3;
            motion.advance(mid);
            double[] before = coordinates(motion);
            motion.moveTo(end, mid);
            assertArrayEquals(before, coordinates(motion), .00001);
            motion.advance(mid + CaretMotion.DURATION_NANOS);
            assertEquals(end, motion.bounds());
        }
    }

    @Test
    void snapCancelsMotionIncludingAfterLongFrameStalls() {
        CaretMotion motion = new CaretMotion();
        motion.snap(new Rectangle(0, 0, 8, 18));
        motion.moveTo(new Rectangle(200, 100, 8, 18), 0);
        Rectangle reset = new Rectangle(30, 40, 2, 18);
        motion.snap(reset);
        motion.advance(10_000_000_000L);
        assertEquals(reset, motion.bounds());
        assertFalse(motion.moving());
    }

    private static double[] coordinates(CaretMotion motion) {
        double[] result = new double[8], segment = new double[6];
        PathIterator iterator = motion.shape().getPathIterator(null);
        for (int i = 0; i < 4; i++) {
            iterator.currentSegment(segment);
            result[2 * i] = segment[0]; result[2 * i + 1] = segment[1];
            iterator.next();
        }
        return result;
    }
}
