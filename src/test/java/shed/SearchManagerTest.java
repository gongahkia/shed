package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

class SearchManagerTest {
    @Test
    void plainSearchTreatsRegexCharactersAsLiteralText() {
        JTextArea area = new JTextArea("a+b aab");
        SearchManager search = new SearchManager(area);

        assertEquals("Match 1 of 1", search.searchLiteral("a+b", true));
        assertEquals(0, area.getCaretPosition());
        assertEquals(1, search.getMatchCount());
        assertFalse(search.isUseRegex());

        assertEquals("Match 1 of 1", search.searchForward("a+b"));
        assertEquals(4, area.getCaretPosition());
        assertTrue(search.isUseRegex());
    }
}
