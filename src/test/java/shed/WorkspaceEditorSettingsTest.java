package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceEditorSettingsTest {
    @TempDir
    Path tempDir;

    @Test
    void overlaysWorkspaceFolderAndLanguageSpecificEditorSettings() throws Exception {
        Path client = Files.createDirectory(tempDir.resolve("client"));
        Path server = Files.createDirectory(tempDir.resolve("server"));
        Files.createDirectories(server.resolve(".vscode"));
        Files.writeString(server.resolve(".vscode/settings.json"), """
            {
              "editor.tabSize": 6,
              "[java]": {"editor.insertSpaces": false},
              "[python]": {"editor.tabSize": 3}
            }
            """);
        Path manifest = tempDir.resolve("team.code-workspace");
        Files.writeString(manifest, """
            {
              "folders": [{"path": "client"}, {"path": "server"}],
              "settings": {
                "editor.tabSize": 2,
                "editor.insertSpaces": true,
                "[java]": {"editor.tabSize": 4}
              }
            }
            """);

        WorkspaceManifest.Document document = WorkspaceManifest.readDocument(manifest);
        WorkspaceEditorSettings.Snapshot settings = WorkspaceEditorSettings.read(document);

        WorkspaceEditorSettings.Preferences clientJava = settings.preferencesFor(client.resolve("Main.java"), "java");
        assertEquals(4, clientJava.tabSize());
        assertEquals(true, clientJava.insertSpaces());

        WorkspaceEditorSettings.Preferences serverJava = settings.preferencesFor(server.resolve("Main.java"), "JAVA");
        assertEquals(6, serverJava.tabSize());
        assertEquals(false, serverJava.insertSpaces());

        WorkspaceEditorSettings.Preferences serverPython = settings.preferencesFor(server.resolve("main.py"), "python");
        assertEquals(3, serverPython.tabSize());
        assertEquals(true, serverPython.insertSpaces());
    }

    @Test
    void ignoresUnsupportedValuesAndReportsBoundedFolderSettingsFailures() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Files.createDirectories(project.resolve(".vscode"));
        Files.writeString(project.resolve(".vscode/settings.json"), "{\"editor.tabSize\": \"auto\", \"editor.insertSpaces\": 1}");
        Path manifest = tempDir.resolve("project.code-workspace");
        Files.writeString(manifest, """
            {"folders": [{"path": "project"}], "settings": {"editor.tabSize": 17, "[java][python]": {"editor.tabSize": 2}}}
            """);

        WorkspaceEditorSettings.Snapshot settings = WorkspaceEditorSettings.read(WorkspaceManifest.readDocument(manifest));

        assertTrue(settings.preferencesFor(project.resolve("Main.java"), "java").empty());
        assertTrue(settings.diagnostics().stream().anyMatch(message -> message.contains("editor.tabSize")));
        assertTrue(settings.diagnostics().stream().anyMatch(message -> message.contains("editor.insertSpaces")));
    }
}
