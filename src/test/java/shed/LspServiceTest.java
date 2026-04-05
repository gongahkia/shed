package shed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class LspServiceTest {
    @Test
    void mapsFileTypesToLanguageIds() {
        LspService service = new LspService();
        assertEquals("java", service.languageId(FileType.JAVA));
        assertEquals("python", service.languageId(FileType.PYTHON));
        assertEquals("text", service.languageId(FileType.UNKNOWN));
    }

    @Test
    void providesBuiltinServerCommandsByExtension() {
        LspService service = new LspService();
        assertArrayEquals(new String[] {"pyright-langserver", "--stdio"}, service.builtinCommand("py"));
        assertArrayEquals(new String[] {"clangd"}, service.builtinCommand("cpp"));
        assertNull(service.builtinCommand("md"));
    }
}
