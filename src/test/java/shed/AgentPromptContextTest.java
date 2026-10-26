package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentPromptContextTest {
    @TempDir Path temporary;
    private WorkspaceQueryService workspace(Path root) {
        return new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ), List.of(), List.of("**/*.env"), true), WorkspaceDocumentSource.diskOnly());
    }
    @Test void resolvesInstructionsRootToLeafAndExplicitFilesRulesWithUnsavedText() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")).toRealPath();
        Path nested = Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("AGENTS.md"), "root rules"); Files.writeString(nested.resolve("AGENT.md"), "local rules");
        Path file = Files.writeString(nested.resolve("some file.java"), "disk");
        Path skill = Files.createDirectories(root.resolve(".agents/skills/testing")); Files.writeString(skill.resolve("SKILL.md"), "test rules");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            javax.swing.SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("unsaved content"));
            var queries = new WorkspaceQueryService(root, AgentPolicy.readOnly(), editor);
            var result = AgentPromptContext.resolve(queries, AgentPromptContext.Settings.defaults(), file, "Fix @\"src/some file.java\" #testing", () -> false);
            assertEquals(List.of("root rules", "local rules", "unsaved content", "test rules"), result.documents().stream().map(WorkspaceDocument::content).toList());
            assertTrue(result.instruction(false).contains("unsaved content"));
            assertFalse(result.instruction(true).contains("unsaved content"));
            assertEquals("disk", Files.readString(file));
        }
    }
    @Test void respectsIgnoresExclusionsAndMissingReferences() throws Exception {
        Path root = temporary.toRealPath(); Files.writeString(root.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(root.resolve("ignored.txt"), "hidden"); Files.writeString(root.resolve("secret.env"), "private");
        Files.writeString(root.resolve("source.txt"), "public");
        var queries = workspace(root); var config = AgentPromptContext.Settings.defaults();
        var catalog = AgentPromptContext.catalog(queries, config, () -> false);
        assertTrue(catalog.items().stream().anyMatch(i -> i.name().equals("source.txt")));
        assertFalse(catalog.items().stream().anyMatch(i -> i.name().equals("secret.env") || i.name().equals("ignored.txt")));
        for (String token : List.of("@secret.env", "@ignored.txt", "@../outside", "#missing"))
            assertThrows(IllegalArgumentException.class, () -> AgentPromptContext.resolve(queries, config, null, token, () -> false));
        assertTrue(AgentPromptContext.resolve(queries, config, null, "email me@example.com", () -> false).sources().isEmpty());
    }
    @Test void supportsExplicitExternalSkillsAndRejectsAmbiguousNamesAndOversize() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")).toRealPath();
        Path rules = Files.createDirectories(temporary.resolve("external/testing")).toRealPath();
        Files.writeString(rules.resolve("SKILL.md"), "external rule");
        var config = new AgentPromptContext.Settings(List.of(), List.of(rules.toString()));
        assertEquals("external rule", AgentPromptContext.resolve(workspace(root), config, null, "#testing", () -> false).documents().getFirst().content());
        Path second = Files.createDirectories(root.resolve("rules/testing")); Files.writeString(second.resolve("SKILL.md"), "duplicate");
        assertThrows(java.io.IOException.class, () -> AgentPromptContext.catalog(workspace(root), new AgentPromptContext.Settings(List.of(), List.of(rules.toString(), "rules")), () -> false));
        Files.writeString(root.resolve("AGENTS.md"), "x".repeat(AgentPromptContext.FILE_LIMIT + 1));
        assertThrows(IllegalArgumentException.class, () -> AgentPromptContext.resolve(workspace(root), AgentPromptContext.Settings.defaults(), null, "question", () -> false));
        assertThrows(java.util.concurrent.CancellationException.class, () -> AgentPromptContext.resolve(workspace(root), config, null, "#testing", () -> true));
    }
    @Test void exportsSavedReferencesWithoutChangingDirtyStateAndCleansThemUp() throws Exception {
        Path root = temporary.toRealPath(); Path file = Files.writeString(root.resolve("source"), "reference text");
        var doc = workspace(root).snapshot(WorkspacePaths.id(root), file.toString());
        assertFalse(doc.dirty());
        Path directory;
        try (var context = new AgentContextFiles()) {
            directory = context.directory();
            try (var snapshot = context.captureReferences(List.of(doc), () -> false)) {
                assertEquals(1, snapshot.count()); assertTrue(snapshot.instruction().contains("requested context"));
                try (var files = Files.walk(directory)) { assertTrue(files.anyMatch(p -> p.getFileName().toString().equals("buffer-0.txt"))); }
            }
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        }
        assertFalse(Files.exists(directory)); assertEquals("reference text", Files.readString(file));
    }
    @Test void promptTokensHandleQuotedPathsAndFuzzyFiltering() {
        assertNull(AgentContextCompletion.token("me@example.com", 14));
        var token = AgentContextCompletion.token("fix @\"src/some f", 16);
        assertNotNull(token); assertEquals("src/some f", token.query());
        var item = new AgentPromptContext.Item('@', "src/some file.java", Path.of("source"));
        assertEquals("@\"src/some file.java\"", item.token());
        assertEquals(List.of(item), AgentContextCompletion.matches(List.of(item), new AgentContextCompletion.Token(0, 3, '@', "ssf")));
    }
}
