package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSelectionTest {
    @TempDir Path temporary;

    @Test void selectedUnsavedTextBecomesAReviewableProposalWithOneUndo() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source"), "prefix old suffix\r\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("prefix unsaved suffix\n"));
            AgentSelection selection = capture(documents, buffer, 7, 14).get();
            String prompt = selection.prompt("replace with new code");
            var payload = MiniJson.asObject(MiniJson.parseStrict(prompt.substring(prompt.indexOf('\n') + 1)));
            assertEquals("unsaved", payload.get("selection")); assertEquals("prefix ", payload.get("before")); assertEquals(" suffix\n", payload.get("after"));
            var proposals = service(root, documents, false);
            var proposed = selection.propose(proposals, "request", "turn", "{\"replacement\":\"new code\"}");
            String id = (String) proposed.get("id");
            assertEquals("prefix new code suffix\n", proposals.preview(id, 0).proposed());
            assertEquals("prefix unsaved suffix\n", buffer.getContent()); assertEquals("prefix old suffix\r\n", Files.readString(file));
            assertThrows(WorkspaceApiException.class, () -> proposals.apply(id, 0));
            var reviewer = service(root, documents, true); var applied = reviewer.apply(id, 0);
            assertEquals("prefix new code suffix\n", buffer.getContent()); assertEquals("prefix old suffix\r\n", Files.readString(file));
            reviewer.undo(id, ((Number) applied.get("revision")).intValue());
            assertEquals("prefix unsaved suffix\n", buffer.getContent());
        }
    }

    @Test void laterHumanEditCannotRetargetOrOverwriteTheCapturedSelection() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), file = Files.writeString(root.resolve("source"), "before old after");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            Supplier<AgentSelection> frozen = capture(documents, buffer, 7, 10);
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human changed everything"));
            AgentSelection selection = frozen.get(); assertEquals("before old after", selection.document().content());
            assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class,
                () -> selection.propose(service(root, documents, false), "r", "t", "{\"replacement\":\"new\"}")).code());
            assertEquals("human changed everything", buffer.getContent());
        }
    }

    @Test void scratchSelectionSupportsEmptyReplacementAndRejectsUnexpectedOutput() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); FileBuffer buffer = new FileBuffer((String) null);
        SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("keep remove end"));
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            AgentSelection selection = capture(documents, buffer, 5, 12).get(); var proposals = service(root, documents, false);
            var result = selection.propose(proposals, "r", "t", "```json\n{\"replacement\":\"\"}\n```");
            assertEquals("keep end", proposals.preview((String) result.get("id"), 0).proposed());
            for (String invalid : List.of("Here is the code", "{}", "{\"replacement\":42}",
                "{\"replacement\":\"x\",\"path\":\"other\"}", "{\"replacement\":\"x\",\"replacement\":\"y\"}"))
                assertThrows(IllegalArgumentException.class, () -> selection.propose(proposals, "bad", "t", invalid));
            assertEquals(1, proposals.list(0, 10).size()); assertEquals("keep remove end", buffer.getContent());
        }
    }

    @Test void selectionRequiresACompleteEditableRangeAndBoundedContext() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); FileBuffer buffer = new FileBuffer((String) null);
        SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("x".repeat(6000) + "🙂e\u0301" + "z".repeat(6000)));
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            AgentSelection selection = capture(documents, buffer, 6000, 6004).get();
            String prompt = selection.prompt("simplify"); var payload = MiniJson.asObject(MiniJson.parseStrict(prompt.substring(prompt.indexOf('\n') + 1)));
            assertEquals(4096, ((String) payload.get("before")).length()); assertEquals(4096, ((String) payload.get("after")).length());
            assertThrows(IllegalArgumentException.class, () -> new AgentSelection(selection.document(), 6000, 6001));
            assertThrows(IllegalArgumentException.class, () -> new AgentSelection(selection.document(), 6002, 6003));
            assertThrows(IllegalArgumentException.class, () -> new AgentSelection(selection.document(), 0, 0));
            assertThrows(IllegalArgumentException.class, () -> selection.prompt(" "));
            assertThrows(IllegalArgumentException.class, () -> selection.prompt("x".repeat(8193)));
        }
    }

    private Supplier<AgentSelection> capture(EditorWorkspaceDocuments documents, FileBuffer buffer, int start, int end) throws Exception {
        AtomicReference<Supplier<AgentSelection>> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(documents.captureSelection(buffer, start, end))); return result.get();
    }

    private WorkspaceProposalService service(Path root, EditorWorkspaceDocuments documents, boolean apply) {
        var capabilities = apply ? Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY)
            : Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE);
        return new WorkspaceProposalService(new WorkspaceQueryService(root, new AgentPolicy(capabilities, List.of("**/README.md"), List.of(), true), documents),
            documents, temporary.resolve("state"), apply ? "human" : "selection-agent");
    }
}
