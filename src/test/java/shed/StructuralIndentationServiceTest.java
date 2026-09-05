package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StructuralIndentationServiceTest {
    private final StructuralIndentationService service = new StructuralIndentationService();
    private final GrammarHighlightService grammar = new GrammarHighlightService();

    @Test
    void increasesIndentAfterCodeDelimitersButNotStringsOrComments() {
        assertEquals("    ", indentation("if (ready) {", FileType.JAVA, true));
        assertEquals("", indentation("String value = \"{\"", FileType.JAVA, true));
        assertEquals("", indentation("// {", FileType.JAVA, true));
        assertEquals("\t", indentation("items = [", FileType.JAVASCRIPT, false));
    }

    @Test
    void recognizesNarrowLanguageSpecificBlockHeaders() {
        assertEquals("  ", indentation("if ready:", FileType.PYTHON, true, 2));
        assertEquals("", indentation("value = {\"key\":", FileType.PYTHON, true));
        assertEquals("    ", indentation("def render", FileType.RUBY, true));
        assertEquals("    ", indentation("services:", FileType.YAML, true));
        assertEquals("    ", indentation("if(DEFINED TARGET)", FileType.CMAKE, true));
    }

    @Test
    void preservesLeadingIndentAndFallsBackForUnsupportedContent() {
        assertEquals("  \t    ", indentation("  \tcall() {", FileType.GO, true));
        assertEquals("  ", indentation("  plain text", FileType.TEXT, true));
        assertEquals("", indentation("# Heading", FileType.MARKDOWN, true));
    }

    private String indentation(String source, FileType type, boolean expandTabs) {
        return indentation(source, type, expandTabs, 4);
    }

    private String indentation(String source, FileType type, boolean expandTabs, int tabSize) {
        return service.indentationForNewLine(source, source.length(), type, expandTabs, tabSize, grammar);
    }
}
