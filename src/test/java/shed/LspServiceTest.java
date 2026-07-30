package shed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class LspServiceTest {
    @Test
    void negotiatesAdvertisedDisabledAndUnsupportedCapabilities() {
        Map<String, Object> response = MiniJson.asObject(MiniJson.parse(
            "{\"result\":{\"capabilities\":{\"completionProvider\":{},\"hoverProvider\":true,\"renameProvider\":{\"prepareProvider\":true}},\"serverInfo\":{\"name\":\"testls\",\"version\":\"1.2\"}}}"
        ));
        Map<LspCapability, Boolean> clientEnabled = new EnumMap<>(LspCapability.class);
        clientEnabled.put(LspCapability.HOVER, Boolean.FALSE);

        LspCapabilityModel model = LspCapabilityModel.fromInitializeResult(response, clientEnabled);

        assertTrue(model.allows(LspCapability.COMPLETION));
        assertTrue(model.allows(LspCapability.RENAME));
        assertFalse(model.allows(LspCapability.HOVER));
        assertEquals(LspCapabilityModel.Availability.DISABLED, model.availability(LspCapability.HOVER));
        assertEquals("LSP hover is disabled by client policy; enable it in LSP settings",
            model.unavailableReason(LspCapability.HOVER));
        assertEquals(LspCapabilityModel.Availability.UNSUPPORTED, model.availability(LspCapability.DEFINITION));
        assertEquals("LSP definition is unavailable: server did not advertise definitionProvider (testls 1.2)",
            model.unavailableReason(LspCapability.DEFINITION));
    }

    @Test
    void marksCapabilitiesUnavailableUntilInitializationCompletes() {
        LspCapabilityModel model = LspCapabilityModel.uninitialized();

        assertFalse(model.allows(LspCapability.CODE_ACTION));
        assertEquals(LspCapabilityModel.Availability.UNINITIALIZED, model.availability(LspCapability.CODE_ACTION));
        assertEquals("LSP code actions is unavailable: server initialization is incomplete",
            model.unavailableReason(LspCapability.CODE_ACTION));
    }

    @Test
    void parsesCompletionDetailsAndMarkdownDocumentation() {
        List<LspClient.CompletionItem> items = LspClient.parseCompletionItems(MiniJson.parse(
            "{\"items\":[{\"label\":\"render\",\"detail\":\"void render()\",\"kind\":3,\"documentation\":{\"kind\":\"markdown\",\"value\":\"Renders a frame.\"}}]}"
        ));

        assertEquals(1, items.size());
        assertEquals("render", items.get(0).getLabel());
        assertEquals("void render()", items.get(0).getDetail());
        assertEquals("Renders a frame.", items.get(0).getDocumentation());
        assertEquals("render — void render()", items.get(0).toString());
    }

    @Test
    void parsesSnippetCompletionTextEdits() {
        List<LspClient.CompletionItem> items = LspClient.parseCompletionItems(MiniJson.parse(
            "[{\"label\":\"fn\",\"insertText\":\"fn ${1:name}\",\"insertTextFormat\":2,\"textEdit\":{\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":2}},\"newText\":\"fn ${1:name}\"}}]"
        ));

        assertEquals(1, items.size());
        assertTrue(items.get(0).isSnippet());
        assertEquals("fn ${1:name}", items.get(0).getInsertText());
        assertEquals(1, items.get(0).getTextEdits().size());
    }

    @Test
    void parsesActiveSignatureAndDocumentation() {
        LspClient.SignatureHelp help = LspClient.parseSignatureHelp(MiniJson.parse(
            "{\"activeSignature\":0,\"activeParameter\":1,\"signatures\":[{\"label\":\"sum(int left, int right)\",\"documentation\":\"Adds values.\"}]}"
        ));

        assertNotNull(help);
        assertEquals("sum(int left, int right)", help.getLabel());
        assertEquals("Adds values.", help.getDocumentation());
        assertEquals(1, help.getActiveParameter());
    }

    @Test
    void parsesDeltaEncodedSemanticTokensAndInlayHints() {
        List<LspClient.SemanticToken> tokens = LspClient.parseSemanticTokens(MiniJson.parse("{\"data\":[0,2,3,1,0,1,4,2,5,0]}"));
        List<LspClient.InlayHint> hints = LspClient.parseInlayHints(MiniJson.parse(
            "[{\"position\":{\"line\":2,\"character\":8},\"label\":\": String\"}]"
        ));

        assertEquals(List.of(new LspClient.SemanticToken(0, 2, 3, 1), new LspClient.SemanticToken(1, 4, 2, 5)), tokens);
        assertEquals(List.of(new LspClient.InlayHint(2, 8, ": String")), hints);
    }

    @Test
    void mapsFileTypesToLanguageIds() {
        LspService service = new LspService();
        assertEquals("java", service.languageId(FileType.JAVA));
        assertEquals("python", service.languageId(FileType.PYTHON));
        assertEquals(FileType.JAVASCRIPT, FileType.detect(new java.io.File("component.jsx"), ""));
        assertEquals(FileType.TYPESCRIPT, FileType.detect(new java.io.File("component.tsx"), ""));
        assertEquals("text", service.languageId(FileType.UNKNOWN));
    }

    @Test
    void providesBuiltinServerCommandsByExtension() {
        LspService service = new LspService();
        assertArrayEquals(new String[] {"jdtls"}, service.builtinCommand("java"));
        assertArrayEquals(new String[] {"rust-analyzer"}, service.builtinCommand("rs"));
        assertArrayEquals(new String[] {"pyright-langserver", "--stdio"}, service.builtinCommand("py"));
        assertArrayEquals(new String[] {"typescript-language-server", "--stdio"}, service.builtinCommand("tsx"));
        assertArrayEquals(new String[] {"gopls"}, service.builtinCommand("go"));
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
