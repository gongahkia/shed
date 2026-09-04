package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavaStructuralServiceTest {

    @Test
    void extractsNestedTypesConstructorsMethodsAndFieldsFromTheJdkAst() {
        String source = """
            package example;
            public class Outer {
                private static final String VALUE = "class NotASymbol {}";
                Outer() { }
                void run() {
                    String text = "interface AlsoNotASymbol {}";
                }
                record Entry(int id) { }
                interface Nested { void work(); }
                @interface Marker { }
            }
            """;

        JavaStructuralService.Result result = new JavaStructuralService().collectSymbols(source);

        assertTrue(result.parserAvailable());
        assertSymbol(result.symbols(), "Outer", "class");
        assertSymbol(result.symbols(), "VALUE", "field");
        assertSymbol(result.symbols(), "Outer", "constructor");
        assertSymbol(result.symbols(), "run", "method");
        assertSymbol(result.symbols(), "Entry", "record");
        assertSymbol(result.symbols(), "Nested", "interface");
        assertSymbol(result.symbols(), "Marker", "annotation");
        assertFalse(result.symbols().stream().anyMatch(symbol -> symbol.getName().equals("NotASymbol")));
        assertFalse(result.symbols().stream().anyMatch(symbol -> symbol.getName().equals("AlsoNotASymbol")));
    }

    @Test
    void keepsRecoveredDeclarationsWhenTheDocumentHasSyntaxErrors() {
        String source = """
            class Broken {
                void running( {
            """;

        JavaStructuralService.Result result = new JavaStructuralService().collectSymbols(source);

        assertTrue(result.parserAvailable());
        assertTrue(result.symbols().stream().map(SymbolService.Symbol::getName).toList().contains("Broken"));
    }

    @Test
    void symbolServiceUsesTheParserInsteadOfRegexMatchingStringsOrComments() {
        String source = """
            class Real {
                String value = "class Pretend {}";
                // interface CommentOnly { }
                void run() { }
            }
            """;

        List<SymbolService.Symbol> symbols = new SymbolService().collectSymbols(source, FileType.JAVA);

        assertTrue(symbols.stream().anyMatch(symbol -> symbol.getName().equals("Real") && symbol.getKind().equals("class")));
        assertTrue(symbols.stream().anyMatch(symbol -> symbol.getName().equals("run") && symbol.getKind().equals("method")));
        assertFalse(symbols.stream().anyMatch(symbol -> symbol.getName().equals("Pretend") || symbol.getName().equals("CommentOnly")));
    }

    private void assertSymbol(List<SymbolService.Symbol> symbols, String name, String kind) {
        assertTrue(symbols.stream().anyMatch(symbol -> symbol.getName().equals(name) && symbol.getKind().equals(kind)),
            () -> "Missing " + kind + " symbol: " + name);
    }
}
