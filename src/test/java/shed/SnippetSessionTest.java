package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

class SnippetSessionTest {
    @Test
    void movesToNextPlaceholderAfterEditingTheCurrentOne() {
        JTextArea area = new JTextArea("name = value;");
        SnippetSession session = new SnippetSession();

        assertTrue(session.begin(area, 0, List.of(
            new SnippetExpansion.Placeholder(1, 0, 4),
            new SnippetExpansion.Placeholder(2, 7, 12),
            new SnippetExpansion.Placeholder(0, 13, 13)
        )));
        area.replaceSelection("result");

        assertTrue(session.move(area, 1));
        assertEquals("value", area.getSelectedText());
        assertTrue(session.move(area, 1));
        assertEquals(15, area.getCaretPosition());
    }
}
