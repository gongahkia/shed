package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

public class QuickfixServiceTest {
    @Test
    void navigatesEntriesWithWrapAround() {
        QuickfixService service = new QuickfixService();
        service.setEntries("grep", List.of(
            new QuickfixService.Entry("a.java", 3, 1, "one", "grep"),
            new QuickfixService.Entry("b.java", 5, 2, "two", "grep")
        ));

        QuickfixService.Entry first = service.current();
        assertNotNull(first);
        assertEquals("a.java", first.getFilePath());
        assertEquals("b.java", service.next().getFilePath());
        assertEquals("a.java", service.next().getFilePath());
        assertEquals("b.java", service.previous().getFilePath());
    }

    @Test
    void selectsEntryByOneBasedIndex() {
        QuickfixService service = new QuickfixService();
        service.setEntries("diag", List.of(
            new QuickfixService.Entry("x.py", 10, 4, "warn", "lsp"),
            new QuickfixService.Entry("y.py", 20, 1, "err", "lsp")
        ));

        assertEquals("y.py", service.select(2).getFilePath());
        assertNull(service.select(3));
    }

    @Test
    void rendersEntryList() {
        QuickfixService service = new QuickfixService();
        service.setEntries("shell", List.of(
            new QuickfixService.Entry("Main.java", 9, 7, "missing ;", "javac")
        ));

        String text = service.render();
        assertTrue(text.contains("1 Main.java:9:7: missing ; [javac]"));
    }

    @Test
    void clearResetsStateAndRenderIsEmpty() {
        QuickfixService service = new QuickfixService();
        service.setEntries("diag", List.of(
            new QuickfixService.Entry("file.java", 1, 1, "msg", "lsp")
        ));

        service.clear();

        assertFalse(service.hasEntries());
        assertEquals(0, service.size());
        assertEquals(-1, service.currentIndex());
        assertNull(service.current());
        assertEquals("", service.render());
    }

    @Test
    void blankTitleFallsBackToDefaultAndSelectClampsLowIndex() {
        QuickfixService service = new QuickfixService();
        service.setEntries("   ", List.of(
            new QuickfixService.Entry("x.java", 2, 3, "warn", "grep"),
            new QuickfixService.Entry("y.java", 3, 4, "warn2", "grep")
        ));

        assertEquals("quickfix", service.getTitle());
        assertEquals("x.java", service.select(0).getFilePath());
        assertEquals("x.java", service.atLine(1).getFilePath());
        assertEquals("y.java", service.atLine(2).getFilePath());
    }

    @Test
    void rendersDefaultMessageWhenEntryMessageIsBlank() {
        QuickfixService service = new QuickfixService();
        service.setEntries("diag", List.of(
            new QuickfixService.Entry("z.java", 7, 1, "   ", "   ")
        ));

        String text = service.render();
        assertTrue(text.contains("1 z.java:7:1: (no message)"));
        assertFalse(text.contains("[]"));
    }
}
