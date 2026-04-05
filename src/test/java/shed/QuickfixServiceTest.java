package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
