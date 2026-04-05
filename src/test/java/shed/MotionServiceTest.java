package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class MotionServiceTest {
    @Test
    void movesWordForward() {
        MotionService motion = new MotionService();
        String text = "alpha beta gamma";
        assertEquals(6, motion.moveWordForward(text, 0));
    }

    @Test
    void movesWordBackward() {
        MotionService motion = new MotionService();
        String text = "alpha beta gamma";
        assertEquals(6, motion.moveWordBackward(text, 10));
    }

    @Test
    void movesToWordEnd() {
        MotionService motion = new MotionService();
        String text = "alpha beta";
        assertEquals(4, motion.moveWordEnd(text, 0));
    }
}
