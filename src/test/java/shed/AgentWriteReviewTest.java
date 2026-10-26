package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentWriteReviewTest {
    @TempDir Path temporary;

    @Test void diskWriteWaitsForReviewAndJournalContentionThenPreservesTheExistingFormat() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("state"), file = root.resolve("source");
        Files.write(file, TextFileCodec.encodeRaw("old\r\n", "UTF-16LE", true));
        var reviewer = reviewer(root, storage, null);
        try (var writes = writes(root, storage, null)) {
            var result = writes.write(file.toString(), "new\r\n", "turn"); String id = proposed(writes);
            assertFalse(result.isDone()); assertEquals("old\r\n", TextFileCodec.decode(Files.readAllBytes(file), false).rawText());
            try (var lease = retry(() -> lease(storage))) {
                assertNotNull(lease); Thread.sleep(250); assertFalse(result.isDone());
            }
            retry(() -> reviewer.apply(id, 0)); assertEquals(Map.of(), result.get(10, TimeUnit.SECONDS));
            assertArrayEquals(TextFileCodec.encodeRaw("new\r\n", "UTF-16LE", true), Files.readAllBytes(file));
            var state = retry(() -> reviewer.reviewState(id)); assertEquals(List.of("new\n"), state.appliedText());
        }
    }

    @Test void creationAndEmptyContentUseTheSameReviewedTransaction() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("state"), file = root.resolve("new");
        var reviewer = reviewer(root, storage, null);
        try (var writes = writes(root, storage, null)) {
            var result = writes.write(file.toString(), "", "turn"); String id = proposed(writes);
            assertFalse(Files.exists(file)); var applied = retry(() -> reviewer.apply(id, 0));
            result.get(10, TimeUnit.SECONDS); assertEquals("", Files.readString(file));
            retry(() -> reviewer.undo(id, ((Number) applied.get("revision")).intValue())); assertFalse(Files.exists(file));
        }
    }

    @Test void reviewedAlternativeIsAppliedButNeverReportedAsTheRequestedWrite() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("state"), file = Files.writeString(root.resolve("source"), "old\n");
        var reviewer = reviewer(root, storage, null);
        try (var writes = writes(root, storage, null)) {
            var result = writes.write(file.toString(), "agent\n", "turn"); String id = proposed(writes);
            var revision = retry(() -> reviewer.revise(id, 0, 0, "human alternative\n", null));
            retry(() -> reviewer.apply(id, ((Number) revision.get("revision")).intValue()));
            var failure = failure(result); assertEquals("REVIEW_CHANGED_CONTENT", MiniJson.asObject(failure.data()).get("code"));
            assertEquals("human alternative\n", Files.readString(file)); assertEquals("APPLIED", retry(() -> reviewer.reviewState(id)).status());
        }
    }

    @Test void rejectionCancellationAndDisconnectWithdrawPendingWritesWithoutTouchingTheFile() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            Path root = Files.createDirectory(temporary.resolve("project" + mode)), storage = temporary.resolve("state" + mode), file = Files.writeString(root.resolve("source"), "human\n");
            var reviewer = reviewer(root, storage, null);
            try (var writes = writes(root, storage, null)) {
                var result = writes.write(file.toString(), "agent\n", "turn"); String id = proposed(writes);
                switch (mode) {
                    case 0 -> retry(() -> reviewer.reject(id, 0));
                    case 1 -> {
                        retry(() -> reviewer.revise(id, 0, 0, "reviewer's draft\n", null));
                        try (var lease = retry(() -> lease(storage))) {
                            assertNotNull(lease); writes.cancelTurn("turn"); Thread.sleep(250); assertFalse(result.isDone());
                        }
                    }
                    case 2 -> writes.close();
                    case 3 -> result.cancel(true);
                }
                AcpConnectionTest.await(() -> writes.pending().isEmpty());
                if (mode == 3) assertTrue(result.isCancelled()); else assertNotNull(failure(result));
                assertEquals("REJECTED", retry(() -> reviewer.reviewState(id)).status());
                assertThrows(WorkspaceApiException.class, () -> reviewer.apply(id, 0)); assertEquals("human\n", Files.readString(file));
            }
        }
    }

    @Test void liveUnsavedWritesStayUnsavedAndUseSnapshotPreconditions() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("state"), file = Files.writeString(root.resolve("source"), "disk\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root);
             var writes = writes(root, storage, documents)) {
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human draft\n"));
            var reviewer = reviewer(root, storage, documents); var result = writes.write(file.toString(), "agent draft\n", "turn");
            String id = proposed(writes); assertEquals("human draft\n", retry(() -> reviewer.preview(id, 0)).before());
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("later human draft\n"));
            assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class, () -> retry(() -> reviewer.apply(id, 0))).code());
            assertFalse(result.isDone()); writes.cancelTurn("turn"); failure(result);
            AcpConnectionTest.await(() -> writes.pending().isEmpty());
            assertEquals("later human draft\n", documents.snapshots().getFirst().content()); assertEquals("disk\n", Files.readString(file));
            var second = writes.write(file.toString(), "reviewed draft\n", "next-turn"); String secondId = proposed(writes);
            retry(() -> reviewer.apply(secondId, 0)); second.get(10, TimeUnit.SECONDS);
            assertEquals("reviewed draft\n", documents.snapshots().getFirst().content()); assertEquals("disk\n", Files.readString(file));
            assertTrue(retry(() -> reviewer.reviewState(secondId)).pendingSave());
        }
    }

    @Test void protectedAndOutsidePathsDoNotCreateProposals() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("state");
        try (var writes = writes(root, storage, null)) {
            assertNotNull(failure(writes.write(root.resolve("README.md").toString(), "no", "turn")));
            assertNotNull(failure(writes.write(temporary.resolve("outside").toString(), "no", "turn")));
            assertTrue(reviewer(root, storage, null).list(0, 10).isEmpty()); assertFalse(Files.exists(storage));
        }
    }

    @Test void shutdownDuringSnapshotCaptureCannotPublishALateProposal() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("state"), file = Files.writeString(root.resolve("source"), "human\n");
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        WorkspaceDocumentSource blocked = () -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Snapshot was not released"); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            return List.of();
        };
        var query = new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE), List.of(), List.of(), true), blocked);
        try (var writes = new AgentWriteReview(query, null, storage, "agent", (type, data) -> { })) {
            var result = writes.write(file.toString(), "agent\n", "turn");
            assertTrue(entered.await(5, TimeUnit.SECONDS)); writes.close(); release.countDown();
            failure(result); assertFalse(Files.exists(storage)); assertEquals("human\n", Files.readString(file));
        } finally { release.countDown(); }
    }

    private AgentWriteReview writes(Path root, Path storage, WorkspaceDocumentEditor documents) {
        return new AgentWriteReview(queries(root, documents, false), documents, storage, "agent", (type, data) -> { });
    }
    private WorkspaceProposalService reviewer(Path root, Path storage, WorkspaceDocumentEditor documents) {
        return new WorkspaceProposalService(queries(root, documents, true), documents, storage, "human");
    }
    private WorkspaceQueryService queries(Path root, WorkspaceDocumentEditor documents, boolean apply) {
        var capabilities = apply ? Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY)
            : Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE);
        return new WorkspaceQueryService(root, new AgentPolicy(capabilities, List.of("**/README.md"), List.of(), true), documents == null ? WorkspaceDocumentSource.diskOnly() : documents);
    }
    private static String proposed(AgentWriteReview writes) throws Exception {
        AcpConnectionTest.await(() -> !writes.pending().isEmpty() && !writes.pending().getFirst().proposal().isEmpty());
        return writes.pending().getFirst().proposal();
    }
    private static AcpException failure(CompletableFuture<?> result) { return assertInstanceOf(AcpException.class, assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS)).getCause()); }
    private static WorkspaceChangeJournal.Lease lease(Path storage) {
        try { return new WorkspaceChangeJournal(storage).open(false); }
        catch (java.io.IOException failed) { throw new AssertionError(failed); }
    }
    private static <T> T retry(java.util.function.Supplier<T> action) {
        for (int attempt = 0; ; attempt++) try { return action.get(); }
        catch (WorkspaceApiException busy) {
            if (!Boolean.TRUE.equals(busy.details().get("retryable")) || attempt >= 100) throw busy;
            try { Thread.sleep(10); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        }
    }
}
