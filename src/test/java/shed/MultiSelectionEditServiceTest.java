package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import javax.swing.JTextArea;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MultiSelectionEditServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsKeepMultiSelectionDisabled() {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home-multi-selection-default").toString());
        try {
            assertEquals(new MultiSelectionPolicy(false, MultiSelectionPolicy.DEFAULT_MAX_CURSORS),
                new ConfigManager().getMultiSelectionPolicy());
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void replacesSelectionsDescendingAndRebasesCarets() {
        JTextArea area = new JTextArea("e\u0301 one e\u0301 two");
        List<MultiSelection> selections = List.of(new MultiSelection(7, 9), new MultiSelection(0, 2));

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

    @Test
    void rejectsOverlappingOrInteriorRangesWithoutMutation() {
        JTextArea overlapping = new JTextArea("abcdef");
        assertThrows(IllegalArgumentException.class, () -> MultiSelectionEditService.insert(overlapping,
            List.of(new MultiSelection(0, 3), new MultiSelection(2, 5)), "X"));
        assertEquals("abcdef", overlapping.getText());

        JTextArea interior = new JTextArea("e\u0301");
        assertThrows(IllegalArgumentException.class, () -> MultiSelectionEditService.delete(interior,
            List.of(new MultiSelection(1, 2))));
        assertEquals("e\u0301", interior.getText());
    }

    @Test
    void preservesCommonUnicodeThroughUndoAndRedo() {
        String original = "e\u0301|👩🏽|👩‍💻|🇸🇬";
        JTextArea area = new JTextArea(original);
        UndoManager undo = new UndoManager();
        area.getDocument().addUndoableEditListener(undo);
        int modifierStart = original.indexOf("👩🏽");
        int flagStart = original.indexOf("🇸🇬");

        List<MultiSelection> updated = MultiSelectionEditService.insert(area,
            List.of(new MultiSelection(flagStart, flagStart + "🇸🇬".length()),
                new MultiSelection(modifierStart, modifierStart + "👩🏽".length())), "X");

        assertEquals("e\u0301|X|👩‍💻|X", area.getText());
        assertEquals(List.of(MultiSelection.caret(4), MultiSelection.caret(12)), updated);
        assertFalse(area.getText().contains("\uFFFD"));

        while (undo.canUndo()) undo.undo();
        assertEquals(original, area.getText());
        while (undo.canRedo()) undo.redo();
        assertEquals("e\u0301|X|👩‍💻|X", area.getText());
    }
}
