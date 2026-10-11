package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

class MultiSelectionMotionServiceTest {
    @Test
    void movesEveryCaretHorizontallyAndAcrossLines() {
        JTextArea area = new JTextArea("abc\ndef\n");
        MultiSelectionMotionService.Result right = MultiSelectionMotionService.move(area,
            MultiSelection.caret(1), List.of(MultiSelection.caret(5)), KeyEvent.VK_RIGHT);
        assertEquals(MultiSelection.caret(2), right.primary());
        assertEquals(List.of(MultiSelection.caret(6)), right.extras());

        MultiSelectionMotionService.Result down = MultiSelectionMotionService.move(area,
            MultiSelection.caret(1), List.of(MultiSelection.caret(5)), KeyEvent.VK_DOWN);
        assertEquals(MultiSelection.caret(5), down.primary());
        assertEquals(List.of(MultiSelection.caret(8)), down.extras());
    }

    @Test
    void collapsesSelectionsAndDeduplicatesCarets() {
        JTextArea area = new JTextArea("abc");
        MultiSelectionMotionService.Result moved = MultiSelectionMotionService.move(area,
            new MultiSelection(0, 2), List.of(MultiSelection.caret(0), MultiSelection.caret(2)), KeyEvent.VK_LEFT);
        assertEquals(MultiSelection.caret(0), moved.primary());
        assertEquals(List.of(MultiSelection.caret(1)), moved.extras());
    }
}
