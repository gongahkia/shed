package shed;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** ACP waits for the shared human review transaction; this adapter never applies a proposal itself. */
final class AgentWriteReview implements AutoCloseable {
    record Pending(String id, String proposal, String path, String turn) { }
    private static final class Write {
        final String id = UUID.randomUUID().toString(), path, content, turn;
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        volatile boolean cancelled;
        volatile String proposal = "";
        Write(String path, String content, String turn) { this.path = path; this.content = content; this.turn = turn; }
        Pending pending() { return new Pending(id, proposal, path, turn); }
    }
    private final WorkspaceQueryService workspace;
    private final WorkspaceProposalService proposals;
    private final String task = "acp-" + UUID.randomUUID();
    private final BiConsumer<String, Map<String, Object>> events;
    private final Map<String, Write> pending = new LinkedHashMap<>();
    private boolean closed;

    AgentWriteReview(WorkspaceQueryService workspace, WorkspaceDocumentEditor editor, Path storage, String actor,
                     BiConsumer<String, Map<String, Object>> events) {
        workspace.policy().require(AgentPolicy.Capability.PROPOSE);
        workspace.policy().require(AgentPolicy.Capability.READ);
        this.workspace = workspace; this.events = events;
        proposals = new WorkspaceProposalService(workspace, editor, storage, actor);
    }

    synchronized List<Pending> pending() { return pending.values().stream().map(Write::pending).toList(); }

    synchronized CompletableFuture<Map<String, Object>> write(String path, String content, String turn) {
        if (closed) throw new AcpException(-32800, "Write review is closed");
        if (pending.size() >= 32) throw new AcpException(-32602, "Too many writes are awaiting review");
        if (path == null || path.isBlank() || path.length() > 8192 || !Path.of(path).isAbsolute())
            throw new AcpException(-32602, "ACP file paths must be absolute");
        if (content == null || content.length() > WorkspaceProposalService.MAX_TEXT_CHARACTERS)
            throw new AcpException(-32602, "Write content exceeds the proposal limit");
        var write = new Write(path, content.replace("\r\n", "\n").replace('\r', '\n'), turn);
        write.result.whenComplete((result, error) -> { if (write.result.isCancelled()) write.cancelled = true; });
        pending.put(write.id, write);
        Thread.ofVirtual().name("shed-agent-write-review").start(() -> review(write));
        return write.result;
    }

    synchronized void cancelTurn(String turn) {
        pending.values().stream().filter(write -> write.turn.equals(turn)).forEach(write -> write.cancelled = true);
    }

    @Override public synchronized void close() { closed = true; pending.values().forEach(write -> write.cancelled = true); }

    private void review(Write write) {
        try {
            WorkspaceProposalService.Input input = input(write);
            while (!write.cancelled) {
                try {
                    var proposed = proposals.propose(write.id, task, write.turn, List.of(input));
                    write.proposal = (String) proposed.get("id");
                    emit(write, "write_proposed", Map.of("taskId", task)); break;
                } catch (WorkspaceApiException busy) { if (!retryable(busy)) throw busy; pause(); }
            }
            if (write.proposal.isEmpty()) throw cancelled(write);
            while (true) {
                try {
                    var state = write.cancelled ? proposals.withdraw(write.proposal) : proposals.reviewState(write.proposal);
                    switch (state.status()) {
                        case "PROPOSED" -> { pause(); continue; }
                        case "APPLIED" -> {
                            boolean exact = state.appliedText().equals(List.of(write.content));
                            emit(write, "write_applied", Map.of("exactRequestedText", exact, "pendingSave", state.pendingSave(), "revision", state.revision()));
                            if (!exact) throw new AcpException(-32603, "Review applied different content; read the file before continuing",
                                Map.of("code", "REVIEW_CHANGED_CONTENT", "proposalId", write.proposal, "applied", true));
                            write.result.complete(Map.of()); return;
                        }
                        case "REJECTED" -> {
                            if (write.cancelled) throw cancelled(write);
                            throw new AcpException(-32603, "The file change was rejected in review", Map.of("code", "REVIEW_REJECTED", "proposalId", write.proposal));
                        }
                        default -> throw new AcpException(-32603, "The reviewed write is not applied: " + state.status(),
                            Map.of("code", "REVIEW_NOT_APPLIED", "proposalId", write.proposal, "status", state.status()));
                    }
                } catch (WorkspaceApiException busy) { if (!retryable(busy)) throw busy; pause(); }
            }
        } catch (RuntimeException failed) {
            // A failed wait must not leave an ordinary, still-applicable proposal behind.
            if (!write.proposal.isEmpty()) withdrawAfterFailure(write);
            Throwable failure = failed instanceof WorkspaceApiException domain
                ? new AcpException(-32602, domain.getMessage(), domain.toMap()) : failed;
            emit(write, "write_failed", Map.of("message", java.util.Objects.toString(failed.getMessage(), "Write failed")));
            write.result.completeExceptionally(failure);
        } finally { synchronized (this) { pending.remove(write.id); } }
    }

    private WorkspaceProposalService.Input input(Write write) {
        Path path = WorkspacePaths.resolve(workspace.root(), write.path);
        workspace.policy().checkPath(workspace.root().relativize(path), AgentPolicy.Capability.PROPOSE);
        try {
            var document = workspace.snapshot(workspace.workspaceId(), path.toString());
            return new WorkspaceProposalService.Input(path.toString(), document.version(), write.content);
        } catch (WorkspaceApiException absent) {
            if (absent.code() != WorkspaceApiException.Code.NOT_FOUND) throw absent;
            return WorkspaceProposalService.Input.create(path.toString(), write.content);
        }
    }

    private void withdrawAfterFailure(Write write) {
        while (true) {
            try { proposals.withdraw(write.proposal); return; }
            catch (WorkspaceApiException busy) {
                if (retryable(busy)) { pause(); continue; }
                emit(write, "write_cleanup_required", Map.of("message", "Unable to withdraw proposal; inspect it in Review changes.")); return;
            }
        }
    }
    private void emit(Write write, String type, Map<String, Object> detail) {
        var data = new LinkedHashMap<String, Object>(detail);
        data.put("requestId", write.id); data.put("proposalId", write.proposal); data.put("path", write.path); data.put("turnId", write.turn);
        events.accept(type, Map.copyOf(data));
    }
    private static AcpException cancelled(Write write) { return new AcpException(-32800, "Reviewed write cancelled", Map.of("proposalId", write.proposal)); }
    private static boolean retryable(WorkspaceApiException failure) { return Boolean.TRUE.equals(failure.details().get("retryable")); }
    private static void pause() {
        try { Thread.sleep(100); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AcpException(-32800, "Write review interrupted"); }
    }
}
