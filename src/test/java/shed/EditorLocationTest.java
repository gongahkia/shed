package shed;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

class EditorLocationTest {
    @Test
    void clampsToLineContentAndHandlesEmptyAndFinalLines() throws Exception {
        JTextArea area = new JTextArea("abc\r\n\nlast");
        assertEquals(3, EditorLocation.offset(area, 1, Integer.MAX_VALUE));
        assertEquals(5, EditorLocation.offset(area, 2, 20));
        assertEquals(10, EditorLocation.offset(area, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, EditorLocation.offset(area, 0, 0));
        assertEquals(0, EditorLocation.offset(new JTextArea(), 2, 2));
        assertEquals(2, EditorLocation.offset(new JTextArea("x\n"), 3, 100));
    }

    @Test
    void doesNotSplitASurrogatePair() throws Exception {
        assertEquals(1, EditorLocation.offset(new JTextArea("a😀z"), 1, 3));
    }
}
