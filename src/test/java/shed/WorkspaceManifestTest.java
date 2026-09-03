package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    @Test
    void rejectsUnsupportedNamesAndMissingFolders() throws IOException {
        Path unsupported = tempDir.resolve("workspace.json");
        Path missing = tempDir.resolve("missing.shed-workspace");
        Files.writeString(missing, "{\"folders\":[{\"path\":\"does-not-exist\"}]}" );

        assertThrows(IOException.class, () -> WorkspaceManifest.write(unsupported, List.of(tempDir)));
        assertThrows(IOException.class, () -> WorkspaceManifest.read(missing));
    }
}
