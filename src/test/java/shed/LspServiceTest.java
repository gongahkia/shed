package shed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
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

    @Test
    void parsesWorkspaceEditChangesFallback() {
        Map<String, Object> edit = MiniJson.asObject(MiniJson.parse(
            "{\"changes\":{\"file:///tmp/b.java\":[{\"range\":{\"start\":{\"line\":0,\"character\":1},\"end\":{\"line\":0,\"character\":2}},\"newText\":\"B\"}]}}"
        ));
        assertNotNull(edit);

        List<LspClient.TextEdit> edits = LspClient.parseWorkspaceEdits(edit);

        assertEquals(1, edits.size());
        assertEquals("file:///tmp/b.java", edits.get(0).getUri());
        assertNull(edits.get(0).getDocumentVersion());
        assertEquals("B", edits.get(0).getNewText());
    }

    @Test
    void parsesVersionedDocumentChangesAndIgnoresResourceOperations() {
        Map<String, Object> edit = MiniJson.asObject(MiniJson.parse(
            "{"
                + "\"changes\":{\"file:///tmp/b.java\":[{\"range\":{\"start\":{\"line\":0,\"character\":1},\"end\":{\"line\":0,\"character\":2}},\"newText\":\"B\"}]},"
                + "\"documentChanges\":["
                + "{\"textDocument\":{\"uri\":\"file:///tmp/a.java\",\"version\":7},\"edits\":[{\"range\":{\"start\":{\"line\":1,\"character\":2},\"end\":{\"line\":1,\"character\":4}},\"newText\":\"AA\",\"annotationId\":\"a\"}]},"
                + "{\"kind\":\"create\",\"uri\":\"file:///tmp/new.java\"}"
                + "]"
                + "}"
        ));
        assertNotNull(edit);

        List<LspClient.TextEdit> edits = LspClient.parseWorkspaceEdits(edit);

        assertEquals(1, edits.size());
        assertEquals("file:///tmp/a.java", edits.get(0).getUri());
        assertEquals(7, edits.get(0).getDocumentVersion());
        assertEquals("AA", edits.get(0).getNewText());
    }

    @Test
    void sameOffsetWorkspaceInsertsKeepServerOrder() {
        LspController controller = new LspController(null);
        List<LspClient.TextEdit> edits = List.of(
            new LspClient.TextEdit("file:///tmp/a", 0, 0, 0, 0, "a"),
            new LspClient.TextEdit("file:///tmp/a", 0, 0, 0, 0, "b")
        );

        String result = controller.applyResolvedTextEdits("x", controller.resolveTextEdits("x", edits));

        assertEquals("abx", result);
    }
}
