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

    @Test
    void collectsLocalGoRustAndCppSymbolsWithoutTreatingControlsAsFunctions() {
        SymbolService service = new SymbolService();

        List<SymbolService.Symbol> go = service.collectSymbols("type Server struct {\nfunc (s *Server) Serve() {}\n", FileType.GO);
        assertEquals(List.of("Server", "Serve"), go.stream().map(SymbolService.Symbol::getName).toList());
        assertEquals(List.of("type", "function"), go.stream().map(SymbolService.Symbol::getKind).toList());

        List<SymbolService.Symbol> rust = service.collectSymbols("pub struct Worker;\nimpl Worker {\n    pub async fn run() {}\n}\n", FileType.RUST);
        assertEquals(List.of("Worker", "impl Worker", "run"), rust.stream().map(SymbolService.Symbol::getName).toList());
        assertEquals(List.of("item", "implementation", "function"), rust.stream().map(SymbolService.Symbol::getKind).toList());

        List<SymbolService.Symbol> cpp = service.collectSymbols("namespace demo {\nclass Engine {\nint start(int port) { return port; }\nif (ready) {}\n}\n}\n", FileType.CPP);
        assertEquals(List.of("demo", "Engine", "start"), cpp.stream().map(SymbolService.Symbol::getName).toList());
        assertEquals(List.of("type", "type", "function"), cpp.stream().map(SymbolService.Symbol::getKind).toList());
    }

    @Test
    void collectsCautiousConfigurationSqlAndShellOutlines() {
        SymbolService service = new SymbolService();

        List<SymbolService.Symbol> yaml = service.collectSymbols("service:\n  port: 8080\n", FileType.YAML);
        assertEquals(List.of("service", "port"), yaml.stream().map(SymbolService.Symbol::getName).toList());
        assertEquals(List.of(1, 2), yaml.stream().map(SymbolService.Symbol::getLevel).toList());

        List<SymbolService.Symbol> toml = service.collectSymbols("[package]\nname = \"shed\"\n[[bin]]\n", FileType.TOML);
        assertEquals(List.of("package", "bin"), toml.stream().map(SymbolService.Symbol::getName).toList());

        List<SymbolService.Symbol> sql = service.collectSymbols("CREATE TABLE users (id integer);\ncreate view active_users as select 1;\n", FileType.SQL);
        assertEquals(List.of("users", "active_users"), sql.stream().map(SymbolService.Symbol::getName).toList());

        List<SymbolService.Symbol> shell = service.collectSymbols("deploy() { echo deploy; }\nif true; then :; fi\n", FileType.SHELL);
        assertEquals(List.of("deploy"), shell.stream().map(SymbolService.Symbol::getName).toList());
    }
}
