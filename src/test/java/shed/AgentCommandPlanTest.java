package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentCommandPlanTest {
    @TempDir Path root;
    private WorkspaceQueryService workspace() { return new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.RUN_HEADLESS), List.of("**/README.md"), List.of(), true), WorkspaceDocumentSource.diskOnly()); }
    private String request(String mode, int seconds) {
        return MiniJson.stringify(Map.of("reason", "Run a synthetic check", "commands", List.of(Map.of("argv", AcpConnectionTest.javaCommand(AgentCheckFixture.class.getName(), mode), "directory", ".", "timeoutSeconds", seconds))));
    }
    private String execute(AgentCommandPlan plan, WorkspaceQueryService workspace) throws Exception {
        var jobs = new AsyncJobService(5);
        try {
            var result = new CompletableFuture<String>();
            jobs.submitLongRunning("check", token -> plan.execute(workspace, token), (job, value, error) -> { if (error != null) result.completeExceptionally(error); else result.complete(value); });
            return result.get(15, TimeUnit.SECONDS);
        } finally { jobs.shutdownNow(); }
    }
    @Test void onlyExplicitCommandEnvelopesAreAcceptedAndPathsRemainScoped() {
        assertNull(AgentCommandPlan.requested("{\"answer\":\"ok\",\"locations\":[]}", workspace()));
        assertThrows(AgentReplyFormatException.class, () -> AgentCommandPlan.requested("{\"commands\":[],\"reason\":\"empty\"}", workspace()));
        assertThrows(AgentReplyFormatException.class, () -> AgentCommandPlan.requested(request("ok", 601), workspace()));
        assertThrows(WorkspaceApiException.class, () -> AgentCommandPlan.requested(request("ok", 10).replace("\"directory\":\".\"", "\"directory\":\"../outside\""), workspace()));
        assertTrue(AgentCommandPlan.requested(request("ok", 10), workspace()).describe().contains("not sandboxed"));
    }
    @Test void returnsActualFailuresAndBoundsNoisyOutput() throws Exception {
        var queries = workspace(); String failed = execute(AgentCommandPlan.requested(request("fail", 10), queries), queries);
        assertTrue(failed.contains("\"exitCode\":7")); assertTrue(failed.contains("Synthetic check failed"));
        String noisy = execute(AgentCommandPlan.requested(request("large", 10), queries), queries);
        assertTrue(noisy.length() < 12000); assertTrue(noisy.contains("truncated"));
    }
    @Test void deadlineStopsAStalledCommandAndReturnsFailure() throws Exception {
        var queries = workspace(); long start = System.nanoTime();
        String result = execute(AgentCommandPlan.requested(request("wait", 1), queries), queries);
        assertTrue(result.contains("timed out")); assertTrue(result.contains("\"exitCode\":-1"));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);
    }
    @Test void refusesDiskChecksWhenEditorHasUnsavedContent() throws Exception {
        Path file = Files.writeString(root.resolve("source"), "saved"); FileBuffer buffer = new FileBuffer(file.toFile());
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            javax.swing.SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("unsaved"));
            var queries = new WorkspaceQueryService(root, workspace().policy(), documents);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> execute(AgentCommandPlan.requested(request("ok", 10), queries), queries));
            assertTrue(failure.getCause().getMessage().contains("unsaved buffers")); assertEquals("saved", Files.readString(file));
        }
    }
    @Test void cancellationTerminatesTrackedChildren() throws Exception {
        Path childPid = root.resolve("pid");
        var command = AcpConnectionTest.javaCommand(AgentCheckFixture.class.getName(), "child", childPid.toString());
        var process = BackgroundTaskProcess.start(command, root.toFile(), Map.of(), 4096, "");
        var completion = CompletableFuture.supplyAsync(() -> process.awaitCompletion(null, 10000));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(childPid) && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(Files.exists(childPid)); long pid = Long.parseLong(Files.readString(childPid));
            process.stop(); completion.get(5, TimeUnit.SECONDS);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(25);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally { process.stop(); }
    }
}
