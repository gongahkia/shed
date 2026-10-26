package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentActionReplyTest {
    @TempDir Path temporary;

    @Test void focusedReplyCollectsChunksAndKeepsTransportContextOutOfTheUserPrompt() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        try (var session = session(root)) {
            connect(session);
            var reply = session.actionPrompt("My actual instruction", "reply-chunked", false).get(10, TimeUnit.SECONDS);
            assertEquals("first second", reply.text()); assertEquals("end_turn", reply.stopReason()); assertFalse(reply.turnId().isBlank());
            assertEquals("My actual instruction", session.snapshot().events().stream().filter(event -> event.type().equals("user_prompt")).findFirst().orElseThrow().data().get("text"));
            var next = session.actionPrompt("Another instruction", "another", false).get(10, TimeUnit.SECONDS);
            assertEquals("Echo: another", next.text()); assertNotEquals(reply.turnId(), next.turnId());
        }
    }

    @Test void focusedReadOnlyActionCannotCreateAWriteProposalEvenOnAReviewEnabledConnection() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")); Files.writeString(root.resolve("source"), "human\n");
        try (var session = session(root)) {
            connect(session);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> session.actionPrompt("Read only", "write", false).get(10, TimeUnit.SECONDS));
            assertEquals("human\n", Files.readString(root.resolve("source")));
            assertTrue(session.snapshot().writes().isEmpty());
            assertFalse(session.snapshot().events().stream().anyMatch(event -> event.type().equals("write_proposed")));
            assertEquals(AgentSessionService.State.IDLE, session.snapshot().state());
        }
    }

    @Test void oversizedAnswersFailExplicitlyWithoutPoisoningTheNextAction() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        try (var session = session(root)) {
            connect(session);
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> session.actionPrompt("large", "reply-large", false).get(10, TimeUnit.SECONDS));
            assertTrue(failed.getCause().getMessage().contains("65,536"));
            assertEquals("Echo: ok", session.actionPrompt("ok", "ok", false).get(10, TimeUnit.SECONDS).text());
        }
    }

    @Test void delayedCancellationOfAnOldActionCannotCancelItsSuccessor() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        try (var session = session(root)) {
            connect(session);
            var old = session.actionPrompt("first", "first", false); old.get(10, TimeUnit.SECONDS);
            var current = session.actionPrompt("second", "permission", false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (session.snapshot().permissions().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(session.snapshot().permissions().isEmpty());
            session.cancelAction(old);
            assertEquals(AgentSessionService.State.PROMPTING, session.snapshot().state());
            assertFalse(current.isDone());
            session.cancelAction(current);
            assertEquals("cancelled", current.get(10, TimeUnit.SECONDS).stopReason());
            assertEquals(AgentSessionService.State.IDLE, session.snapshot().state());
            assertEquals("Echo: third", session.actionPrompt("third", "third", false).get(10, TimeUnit.SECONDS).text());
        }
    }

    private AgentSessionService session(Path root) throws Exception {
        var workspace = new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ,
            AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.START_AGENT), List.of("**/README.md"), List.of(), true), WorkspaceDocumentSource.diskOnly());
        var provider = new AgentProvider("fixture", AcpConnectionTest.javaCommand(AcpSdkAgentFixture.class.getName(), root.resolve("source").toString()),
            java.util.Map.of(), java.time.Duration.ofSeconds(15), java.time.Duration.ofSeconds(20));
        return new AgentSessionService(provider, workspace, null, temporary.resolve("state"));
    }
    private void connect(AgentSessionService session) throws Exception {
        session.initialize().get(15, TimeUnit.SECONDS); session.newSession().get(10, TimeUnit.SECONDS);
    }
}
