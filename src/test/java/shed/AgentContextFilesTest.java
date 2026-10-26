package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentContextFilesTest {
    @TempDir Path temporary;

    @Test void fullUnsavedTextBeyondPromptExcerptsIsPrivateVersionedAndRemovedAfterTheAction() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source.java"), "saved text");
        var buffer = new FileBuffer(file.toFile()); String unsaved = "// context\n".repeat(3000) + "int importantUnsavedValue = 42;\n";
        SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably(unsaved));
        Path sessionDirectory, batch;
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root);
             var contexts = new AgentContextFiles()) {
            sessionDirectory = contexts.directory();
            try (var snapshot = contexts.capture(new WorkspaceQueryService(root, AgentPolicy.readOnly(), documents).visibleDocuments())) {
                assertEquals(1, snapshot.count()); assertTrue(snapshot.instruction().contains("COMPLETE unsaved text"));
                try (var children = Files.list(sessionDirectory)) { batch = children.findFirst().orElseThrow(); }
                var manifest = MiniJson.asObject(MiniJson.parseStrict(new String(PrivateWorkspaceFiles.read(batch.resolve("index.json"), 65536), java.nio.charset.StandardCharsets.UTF_8)));
                var entry = MiniJson.asObject(((List<?>) manifest.get("unsavedDocuments")).getFirst());
                assertEquals(documents.snapshots().getFirst().version(), entry.get("version"));
                assertEquals(unsaved, new String(PrivateWorkspaceFiles.read(Path.of((String) entry.get("snapshot")), 65536), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("saved text", Files.readString(file));
                SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("later human change"));
                assertEquals(unsaved, Files.readString(Path.of((String) entry.get("snapshot"))));
            }
            assertFalse(Files.exists(batch));
            try (var snapshot = contexts.capture(documents.snapshots())) {
                assertEquals(1, snapshot.count()); contexts.close(); assertFalse(Files.exists(sessionDirectory));
            }
        }
        assertFalse(Files.exists(sessionDirectory)); assertEquals("saved text", Files.readString(file));
    }

    @Test void cleanDocumentsDoNotCreateExportsAndExternalAccessIsRestrictedToTheOwnedContextDirectory() throws Exception {
        var original = OpenCodePreset.environment("example/model");
        try (var contexts = new AgentContextFiles(); var snapshot = contexts.capture(List.of())) {
            assertThrows(java.util.concurrent.CancellationException.class, () -> contexts.capture(List.of(), () -> true));
            assertEquals("", snapshot.instruction()); assertEquals(0, snapshot.count());
            var configured = OpenCodePreset.withContext(original, contexts.directory());
            var json = MiniJson.asObject(MiniJson.parseStrict(configured.get("OPENCODE_CONFIG_CONTENT")));
            var permissions = MiniJson.asObject(json.get("permission"));
            var external = MiniJson.asObject(permissions.get("external_directory"));
            assertEquals(2, external.size()); assertEquals("deny", external.get("*"));
            assertEquals("allow", external.get(contexts.directory().toString().replace('\\', '/') + "/**"));
            assertEquals("deny", permissions.get("edit")); assertEquals("deny", permissions.get("bash"));
            assertEquals(permissions, MiniJson.asObject(MiniJson.asObject(json.get("agent")).get("shed-review")).get("permission"));
            assertEquals("deny", MiniJson.asObject(MiniJson.asObject(MiniJson.parseStrict(original.get("OPENCODE_CONFIG_CONTENT"))).get("permission")).get("external_directory"));
        }
    }
}
