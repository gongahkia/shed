package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

public class MultiSelectionEditServiceTest {
    @Test
    void replacesSelectionsDescendingAndRebasesCarets() {
        JTextArea area = new JTextArea("e\u0301 one e\u0301 two");
        List<MultiSelection> selections = List.of(new MultiSelection(0, 2), new MultiSelection(7, 9));

        List<MultiSelection> updated = MultiSelectionEditService.insert(area, selections, "X");

        assertEquals("X one X two", area.getText());
        assertEquals(List.of(MultiSelection.caret(1), MultiSelection.caret(7)), updated);
    }

    @Test
    void deletesWholeGraphemesForCollapsedSelections() {
        JTextArea backspaceArea = new JTextArea("e\u0301 👩‍💻");
        List<MultiSelection> backspaced = MultiSelectionEditService.backspace(backspaceArea,
            List.of(MultiSelection.caret(2), MultiSelection.caret(backspaceArea.getText().length())));

        assertEquals(" ", backspaceArea.getText());
        assertEquals(List.of(MultiSelection.caret(0), MultiSelection.caret(1)), backspaced);

        JTextArea deleteArea = new JTextArea("e\u0301|👩‍💻");
        List<MultiSelection> deleted = MultiSelectionEditService.delete(deleteArea,
            List.of(MultiSelection.caret(0), MultiSelection.caret(3)));

        assertEquals("|", deleteArea.getText());
        assertEquals(List.of(MultiSelection.caret(0), MultiSelection.caret(1)), deleted);
    }
}
