package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSessionWriteTest {
    @TempDir Path temporary;

    @Test void independentSdkCreationWaitsForReviewAndRejectionIsAProtocolFailure() throws Exception {
        for (boolean accept : List.of(true, false)) {
            Path root = Files.createDirectory(temporary.resolve("project" + accept)), storage = temporary.resolve("state" + accept), file = root.resolve("created");
            var reviewer = new WorkspaceProposalService(queries(root, true), null, storage, "human");
            try (var session = session(root, storage, file)) {
                start(session); var turn = session.prompt("write"); String id = pending(session);
                assertFalse(turn.isDone()); assertFalse(Files.exists(file));
                if (accept) {
                    retry(() -> reviewer.apply(id, 0)); assertEquals("end_turn", turn.get(10, TimeUnit.SECONDS).get("stopReason"));
                    assertEquals("agent write\n", Files.readString(file));
                    assertTrue(session.snapshot().events().stream().anyMatch(event -> event.type().equals("write_applied")));
                } else {
                    retry(() -> reviewer.reject(id, 0)); assertThrows(ExecutionException.class, () -> turn.get(10, TimeUnit.SECONDS));
                    assertFalse(Files.exists(file)); assertEquals(AgentSessionService.State.IDLE, session.snapshot().state());
                }
            }
        }
    }

    @Test void cancellingOrClosingTheSessionWithdrawsOutstandingWrites() throws Exception {
        for (boolean cancel : List.of(true, false)) {
            Path root = Files.createDirectory(temporary.resolve("project" + cancel)), storage = temporary.resolve("state" + cancel), file = Files.writeString(root.resolve("source"), "human\n");
            var reviewer = new WorkspaceProposalService(queries(root, true), null, storage, "human");
            try (var session = session(root, storage, file)) {
                start(session); var turn = session.prompt("write"); String id = pending(session);
                if (cancel) {
                    try (var lease = retry(() -> lease(storage))) {
                        assertNotNull(lease); session.cancel(); assertFalse(turn.isDone());
                        assertThrows(AcpException.class, () -> session.prompt("next"));
                    }
                    assertEquals("cancelled", turn.get(10, TimeUnit.SECONDS).get("stopReason"));
                }
                else { session.close(); assertThrows(ExecutionException.class, () -> turn.get(10, TimeUnit.SECONDS)); }
                AcpConnectionTest.await(() -> session.snapshot().writes().isEmpty());
                assertEquals("REJECTED", retry(() -> reviewer.reviewState(id)).status()); assertEquals("human\n", Files.readString(file));
                assertFalse(session.snapshot().events().stream().anyMatch(event -> event.type().equals("write_applied")));
            }
        }
    }

    @Test void earlyCancellationAcknowledgementCannotStartANewTurnBeforeWithdrawalFinishes() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("state"), file = Files.writeString(root.resolve("source"), "human\n");
        var provider = new AgentProvider("fixture", AcpConnectionTest.javaCommand(AcpFixtureProcess.class.getName(), "early-write-cancel", file.toString()),
            Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(20));
        try (var session = new AgentSessionService(provider, queries(root, false), null, storage)) {
            start(session); var turn = session.prompt("write"); String id = pending(session);
            try (var lease = retry(() -> lease(storage))) {
                assertNotNull(lease); session.cancel(); assertEquals("cancelled", turn.get(10, TimeUnit.SECONDS).get("stopReason"));
                assertEquals(AgentSessionService.State.IDLE, session.snapshot().state()); assertFalse(session.snapshot().writes().isEmpty());
                assertThrows(AcpException.class, () -> session.prompt("next"));
            }
            AcpConnectionTest.await(() -> session.snapshot().writes().isEmpty());
            assertEquals("REJECTED", new WorkspaceProposalService(queries(root, true), null, storage, "human").reviewState(id).status());
            assertEquals("human\n", Files.readString(file));
        }
    }

    private AgentSessionService session(Path root, Path storage, Path file) throws Exception {
        var provider = new AgentProvider("sdk", AcpConnectionTest.javaCommand(AcpSdkAgentFixture.class.getName(), file.toString()),
            Map.of(), Duration.ofSeconds(15), Duration.ofSeconds(20));
        return new AgentSessionService(provider, queries(root, false), null, storage);
    }
    private static WorkspaceQueryService queries(Path root, boolean apply) {
        var capabilities = new java.util.HashSet<>(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.START_AGENT));
        if (apply) capabilities.add(AgentPolicy.Capability.APPLY);
        return new WorkspaceQueryService(root, new AgentPolicy(capabilities, List.of("**/README.md"), List.of(), true), WorkspaceDocumentSource.diskOnly());
    }
    private static void start(AgentSessionService session) throws Exception { session.initialize().get(15, TimeUnit.SECONDS); session.newSession().get(10, TimeUnit.SECONDS); }
    private static String pending(AgentSessionService session) throws Exception {
        AcpConnectionTest.await(() -> !session.snapshot().writes().isEmpty() && !session.snapshot().writes().getFirst().proposal().isEmpty());
        return session.snapshot().writes().getFirst().proposal();
    }
    private static WorkspaceChangeJournal.Lease lease(Path storage) {
        try { return new WorkspaceChangeJournal(storage).open(false); }
        catch (java.io.IOException failed) { throw new AssertionError(failed); }
    }
    private static <T> T retry(java.util.function.Supplier<T> operation) {
        for (int attempt = 0; ; attempt++) try { return operation.get(); }
        catch (WorkspaceApiException busy) {
            if (!Boolean.TRUE.equals(busy.details().get("retryable")) || attempt >= 100) throw busy;
            try { Thread.sleep(10); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        }
    }
}
