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
    @Test
    void wordForwardStopsAtPunctuation() {
        MotionService motion = new MotionService();
        assertEquals(3, motion.moveWordForward("foo.bar baz", 0)); // stops at '.'
    }
    @Test
    void wordBackwardStopsAtPunctuation() {
        MotionService motion = new MotionService();
        assertEquals(4, motion.moveWordBackward("foo.bar baz", 7)); // stops at 'b' in bar
    }
    @Test
    void wordEndStopsAtPunctuation() {
        MotionService motion = new MotionService();
        assertEquals(2, motion.moveWordEnd("foo.bar", 0)); // stops at last 'o'
    }
    @Test
    void charClassCategories() {
        assertEquals(1, MotionService.charClass('a'));
        assertEquals(1, MotionService.charClass('_'));
        assertEquals(1, MotionService.charClass('9'));
        assertEquals(0, MotionService.charClass(' '));
        assertEquals(0, MotionService.charClass('\t'));
        assertEquals(2, MotionService.charClass('.'));
        assertEquals(2, MotionService.charClass('('));
    }
}
