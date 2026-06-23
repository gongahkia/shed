package shed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void parsesWorkspaceResourceOperationsInOrder() {
        Map<String, Object> edit = MiniJson.asObject(MiniJson.parse(
            "{\"documentChanges\":["
                + "{\"kind\":\"create\",\"uri\":\"file:///tmp/new.java\",\"options\":{\"overwrite\":true}},"
                + "{\"kind\":\"rename\",\"oldUri\":\"file:///tmp/new.java\",\"newUri\":\"file:///tmp/final.java\",\"options\":{\"ignoreIfExists\":true}},"
                + "{\"kind\":\"delete\",\"uri\":\"file:///tmp/final.java\",\"options\":{\"recursive\":true}}"
                + "]}"
        ));

        List<LspClient.WorkspaceEditOperation> operations = LspClient.parseWorkspaceOperations(edit);

        assertEquals(3, operations.size());
        assertEquals(LspClient.WorkspaceEditOperation.Kind.CREATE, operations.get(0).getKind());
        assertTrue(operations.get(0).isOverwrite());
        assertEquals(LspClient.WorkspaceEditOperation.Kind.RENAME, operations.get(1).getKind());
        assertTrue(operations.get(1).isIgnoreIfExists());
        assertEquals(LspClient.WorkspaceEditOperation.Kind.DELETE, operations.get(2).getKind());
        assertTrue(operations.get(2).isRecursive());
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

    @Test
    void workspaceCreateOverwriteResetsEarlierStagedText() throws Exception {
        Path root = Path.of("target", "lsp-plan").toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path file = root.resolve("overwrite.txt");
        Files.writeString(file, "old", StandardCharsets.UTF_8);
        String uri = file.toUri().toString();
        LspController controller = new LspController(null);
        WorkspaceEditApplyResult result = new WorkspaceEditApplyResult();

        LspController.WorkspaceEditPlan plan = controller.buildWorkspaceEditPlan(List.of(
            LspClient.WorkspaceEditOperation.textEdit(new LspClient.TextEdit(uri, 0, 0, 0, 3, "edited")),
            LspClient.WorkspaceEditOperation.create(uri, true, false),
            LspClient.WorkspaceEditOperation.textEdit(new LspClient.TextEdit(uri, 0, 0, 0, 0, "new"))
        ), result);

        assertNotNull(plan);
        assertEquals(0, result.failedFiles);
        assertEquals("new", plan.stagedTextByPath.get(file.toString()));
    }

    @Test
    void bufferUriEncodesPathSpacesAndUriDecodeRestoresPath() throws Exception {
        Path root = Path.of("target", "lsp uri").toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path file = root.resolve("has space.java");
        Files.writeString(file, "class A {}\n", StandardCharsets.UTF_8);
        FileBuffer buffer = new FileBuffer(file.toFile());
        LspController controller = new LspController(null);

        String uri = controller.bufferUri(buffer);

        assertEquals(file.toUri().toString(), uri);
        assertTrue(uri.contains("%20"));
        assertEquals(file.toFile().getAbsolutePath(), controller.filePathFromUri(uri));
    }

    @Test
    void resolveWorkspaceRootPrefersProjectMarkers() throws Exception {
        Path root = Path.of("target", "lsp-root").toAbsolutePath().normalize();
        Path nested = root.resolve("src/main/java");
        Files.createDirectories(nested);
        Files.writeString(root.resolve("pom.xml"), "<project />", StandardCharsets.UTF_8);
        LspController controller = new LspController(null);

        assertEquals(root.toRealPath(), controller.resolveWorkspaceRoot(nested));
    }
}
