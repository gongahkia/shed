package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
              "files.exclude": {"dist": true, "**/*.tmp": false},
              "search.exclude": {"**/generated/**": false, "**/vendor/**": true},
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
                "files.exclude": {"**/*.tmp": true, "**/generated/**": true},
                "search.exclude": {"**/generated/**": true},
                "[java][kotlin]": {"editor.insertSpaces": false},
                "[java]": {"editor.tabSize": 4}
              }
            }
            """);

        WorkspaceManifest.Document document = WorkspaceManifest.readDocument(manifest);
        assertTrue(document.hasSettings());
        WorkspaceEditorSettings.Snapshot settings = WorkspaceEditorSettings.read(document);
        assertEquals(2, settings.workspace().defaults().tabSize());
        assertEquals(4, settings.workspace().singleLanguages().get("java").tabSize());
        assertEquals(false, settings.workspace().combinedLanguages().get("java").insertSpaces());

        WorkspaceEditorSettings.Preferences clientJava = settings.preferencesFor(client.resolve("Main.java"), "java");
        assertEquals(4, clientJava.tabSize());
        assertEquals(false, clientJava.insertSpaces());

        WorkspaceEditorSettings.Preferences clientKotlin = settings.preferencesFor(client.resolve("Main.kt"), "kotlin");
        assertEquals(2, clientKotlin.tabSize());
        assertEquals(false, clientKotlin.insertSpaces());

        WorkspaceEditorSettings.Preferences serverJava = settings.preferencesFor(server.resolve("Main.java"), "JAVA");
        assertEquals(4, serverJava.tabSize());
        assertEquals(false, serverJava.insertSpaces());

        WorkspaceEditorSettings.Indentation serverJavaLayers = settings.indentationFor(server.resolve("Main.java"), "java");
        assertEquals(6, serverJavaLayers.generic().tabSize());
        assertEquals(4, serverJavaLayers.language().tabSize());

        WorkspaceEditorSettings.Preferences serverPython = settings.preferencesFor(server.resolve("main.py"), "python");
        assertEquals(3, serverPython.tabSize());
        assertEquals(true, serverPython.insertSpaces());

        assertTrue(settings.excluded(client.resolve("generated/Model.java")));
        assertTrue(settings.excluded(client.resolve("cache.tmp")));
        assertTrue(settings.excluded(server.resolve("dist/main.js")));
        assertFalse(settings.excluded(server.resolve("cache.tmp")));
        assertTrue(settings.searchExcluded(client.resolve("generated/Model.java")));
        assertFalse(settings.searchExcluded(server.resolve("generated/Model.java")));
        assertTrue(settings.searchExcluded(server.resolve("vendor/Tool.java")));
    }

    @Test
    void ignoresUnsupportedValuesAndReportsBoundedFolderSettingsFailures() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Files.createDirectories(project.resolve(".vscode"));
        Files.writeString(project.resolve(".vscode/settings.json"), "{\"editor.tabSize\": \"auto\", \"editor.insertSpaces\": 1}");
        Path manifest = tempDir.resolve("project.code-workspace");
        Files.writeString(manifest, """
            {"folders": [{"path": "project"}], "settings": {"editor.tabSize": 17, "[java][python": {"editor.tabSize": 2}}}
            """);

        WorkspaceEditorSettings.Snapshot settings = WorkspaceEditorSettings.read(WorkspaceManifest.readDocument(manifest));

        assertTrue(settings.preferencesFor(project.resolve("Main.java"), "java").empty());
        assertTrue(settings.diagnostics().stream().anyMatch(message -> message.contains("editor.tabSize")));
        assertTrue(settings.diagnostics().stream().anyMatch(message -> message.contains("editor.insertSpaces")));
    }
}
