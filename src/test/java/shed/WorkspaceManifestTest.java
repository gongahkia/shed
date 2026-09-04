package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManifestTest {
    @TempDir
    Path tempDir;

    @Test
    void writesPortableFoldersAndReadsThemBack() throws IOException {
        Path client = Files.createDirectory(tempDir.resolve("client"));
        Path shared = Files.createDirectory(tempDir.resolve("shared"));
        Path manifest = tempDir.resolve("workspace.shed-workspace");

        WorkspaceManifest.write(manifest, List.of(client, shared));

        assertEquals(List.of(client.toRealPath(), shared.toRealPath()), WorkspaceManifest.read(manifest));
        assertTrue(Files.readString(manifest).contains("\"path\":\"client\""));
    }

    @Test
    void readsStandardCodeWorkspaceFoldersWithoutApplyingSettings() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path manifest = tempDir.resolve("sample.code-workspace");
        Files.writeString(manifest, "{\"folders\":[{\"path\":\"project\",\"name\":\"Project\"}],\"settings\":{\"editor.fontSize\":99}}");

        assertEquals(List.of(project.toRealPath()), WorkspaceManifest.read(manifest));
        assertEquals("Project", WorkspaceManifest.readDocument(manifest).folderNames().get(project.toRealPath()));
    }

    @Test
    void preservesCodeWorkspaceTaskAndLaunchDocumentsForASeparateExplicitCompatibilityImport() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("project-jsonc"));
        Path manifest = tempDir.resolve("sample-jsonc.code-workspace");
        Files.writeString(manifest, """
            {
              // VS Code workspace files are JSONC.
              "folders": [{"path": "project-jsonc",},],
              "settings": {"editor.tabSize": 2},
              "tasks": {"version": "2.0.0", "tasks": []},
              "launch": {"configurations": []},
            }
            """);

        WorkspaceManifest.Document document = WorkspaceManifest.readDocument(manifest);

        assertEquals(List.of(project.toRealPath()), document.folders());
        assertTrue(document.standardVsCodeWorkspace());
        assertTrue(document.hasTasks());
        assertTrue(document.hasLaunch());
        assertEquals(List.of(project.toRealPath()), WorkspaceManifest.read(manifest));
    }

    @Test
    void rejectsUnsupportedNamesAndMissingFolders() throws IOException {
        Path unsupported = tempDir.resolve("workspace.json");
        Path missing = tempDir.resolve("missing.shed-workspace");
        Files.writeString(missing, "{\"folders\":[{\"path\":\"does-not-exist\"}]}" );

        assertThrows(IOException.class, () -> WorkspaceManifest.write(unsupported, List.of(tempDir)));
        assertThrows(IOException.class, () -> WorkspaceManifest.read(missing));
    }

    @Test
    void rejectsInvalidCodeWorkspaceFolderNames() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("named-project"));
        Path manifest = tempDir.resolve("invalid-name.code-workspace");
        Files.writeString(manifest, "{\"folders\":[{\"path\":\"named-project\",\"name\":\"bad\\nname\"}]}");

        assertThrows(IOException.class, () -> WorkspaceManifest.readDocument(manifest));
    }

    @Test
    void keepsShedWorkspaceDocumentsFoldersOnly() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("shed-project"));
        Path manifest = tempDir.resolve("sample.shed-workspace");
        Files.writeString(manifest, """
            {"folders":[{"path":"shed-project"}],"tasks":{"version":"2.0.0","tasks":[]},"launch":{"configurations":[]}}
            """);

        WorkspaceManifest.Document document = WorkspaceManifest.readDocument(manifest);

        assertFalse(document.standardVsCodeWorkspace());
        assertTrue(document.hasTasks());
        assertTrue(document.hasLaunch());
    }
}
