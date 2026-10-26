package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentAnswerTest {
    @TempDir Path temporary;

    @Test void filtersEscapingMissingAndInvalidReferencesAndRejectsStaleNavigation() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source"), "first\n🙂second\n");
        Files.writeString(temporary.resolve("outside"), "private");
        var workspace = new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly());
        String response = MiniJson.stringify(Map.of("answer", "Helpful answer", "locations", List.of(
            location("source", 2, 3), location("../outside", 1, 1), location("missing", 1, 1),
            location("source", 10, 1), location("source", 2, 2), location("https://example.com", 1, 1))));
        var answer = AgentAnswer.parse(response, workspace); assertEquals("Helpful answer", answer.text());
        assertEquals(1, answer.locations().size()); assertEquals(5, answer.omittedLocations());
        var location = answer.locations().getFirst(); AgentAnswer.validateNavigation(location, workspace);
        Files.writeString(file, "changed\n"); assertThrows(IllegalArgumentException.class, () -> AgentAnswer.validateNavigation(location, workspace));
    }

    @Test void promptIncludesUnsavedOverridesAndIgnoreAwareFileSamplesWithoutMutation() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source"), "saved");
        Files.writeString(root.resolve(".gitignore"), "ignored\n"); Files.writeString(root.resolve("ignored"), "hidden");
        FileBuffer buffer = new FileBuffer(file.toFile()); SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human unsaved"));
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            var workspace = new WorkspaceQueryService(root, AgentPolicy.readOnly(), documents);
            String prompt = AgentAnswer.prompt("where?", workspace, () -> false);
            var context = MiniJson.asObject(MiniJson.parseStrict(prompt.substring(prompt.indexOf('\n') + 1)));
            assertTrue(((List<?>) context.get("sampleFiles")).contains("source")); assertFalse(((List<?>) context.get("sampleFiles")).contains("ignored"));
            assertTrue(MiniJson.stringify(context.get("unsavedBufferExcerpts")).contains("human unsaved"));
            assertEquals("saved", Files.readString(file)); assertTrue(prompt.length() <= 65536);
            assertThrows(WorkspaceApiException.class, () -> AgentAnswer.prompt("where?", workspace, () -> true));
        }
    }

    @Test void emptyResultsAreValidButMalformedOrUnboundedOutputIsNot() throws Exception {
        var workspace = new WorkspaceQueryService(temporary, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly());
        assertTrue(AgentAnswer.parse("```json\n{\"answer\":\"No matches\",\"locations\":[]}\n```", workspace).locations().isEmpty());
        for (String response : List.of("plain text", "[]", "{}", "{\"answer\":5,\"locations\":[]}", "x".repeat(65537)))
            assertThrows(IllegalArgumentException.class, () -> AgentAnswer.parse(response, workspace));
        assertThrows(IllegalArgumentException.class, () -> AgentAnswer.prompt("x".repeat(8193), workspace, () -> false));
    }

    private Map<String, Object> location(String path, int line, int column) {
        return Map.of("path", path, "line", line, "column", column, "note", "Relevant code");
    }
}
