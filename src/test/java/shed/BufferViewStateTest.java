package shed;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class BufferViewStateTest {
    @Test
    void restoresSelectionDirectionAndTracksEditsInTheSharedDocument() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea("first\nsecond\nthird");
            area.setCaretPosition(12);
            area.moveCaretPosition(6);
            BufferViewState state = BufferViewState.capture(area, new JScrollPane(area));
            area.insert("prefix\n", 0);
            area.setCaretPosition(0);
            state.restoreSelection(area);
            assertEquals(13, area.getCaret().getDot());
            assertEquals(19, area.getCaret().getMark());
            assertEquals("second", area.getSelectedText());
            area.setText("short");
            assertDoesNotThrow(() -> state.restoreSelection(area));
            assertTrue(area.getCaretPosition() <= area.getDocument().getLength());
        });
    }
}
