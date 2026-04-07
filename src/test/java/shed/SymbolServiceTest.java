package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class SymbolServiceTest {
    @Test
    void collectsMarkdownHeadingsAndTrail() {
        SymbolService service = new SymbolService();
        String markdown = "# Title\n"
            + "text\n"
            + "## Section\n"
            + "### Detail\n"
            + "content\n";

        List<SymbolService.Symbol> symbols = service.collectSymbols(markdown, FileType.MARKDOWN);
        assertEquals(3, symbols.size());
        assertEquals("Title", symbols.get(0).getName());
        assertEquals("Section", symbols.get(1).getName());
        assertEquals("Detail", symbols.get(2).getName());

        List<SymbolService.Symbol> trail = service.breadcrumbTrail(symbols, 5);
        assertEquals(3, trail.size());
        assertEquals("Title", trail.get(0).getName());
        assertEquals("Section", trail.get(1).getName());
        assertEquals("Detail", trail.get(2).getName());
    }

    @Test
    void collectsCodeSymbolsForClassAndMethods() {
        SymbolService service = new SymbolService();
        String code = "public class App {\n"
            + "  public void run() {\n"
            + "  }\n"
            + "  static int calc(int n) {\n"
            + "    return n;\n"
            + "  }\n"
            + "}\n";
        List<SymbolService.Symbol> symbols = service.collectSymbols(code, FileType.JAVA);

        assertFalse(symbols.isEmpty());
        assertEquals("App", symbols.get(0).getName());
        assertEquals("class", symbols.get(0).getKind());
        assertTrue(symbols.stream().anyMatch(s -> "run".equals(s.getName())));
        assertTrue(symbols.stream().anyMatch(s -> "calc".equals(s.getName())));
    }
}
