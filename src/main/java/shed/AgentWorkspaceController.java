package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/** Human-owned start/stop lifecycle. Machine requests never route through editor commands. */
final class AgentWorkspaceController implements AutoCloseable {
    private final Texteditor editor;
    private final Set<WorkspaceBridgeServer> owned = ConcurrentHashMap.newKeySet();
    private final Set<WorkspaceBridgeServer> releasing = ConcurrentHashMap.newKeySet();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private WorkspaceBridgeServer active;
    private int startingJob = -1;
    private boolean stopping;
    private String status = "Agent API is stopped; no listener is running.";

    AgentWorkspaceController(Texteditor editor) { this.editor = editor; }

    String handle(String argument) {
        if (active != null && !active.running()) { active = null; status = "Agent API connection has stopped."; }
        String value = argument == null ? "" : argument.trim();
        return switch (value) {
            case "", "status", "api", "api status" -> status;
            case "api start" -> start();
            case "api stop" -> stop();
            default -> "Usage: :agent api start|status|stop";
        };
    }

    private String start() {
        if (disposed.get()) return "Agent API is unavailable while the editor closes.";
        if (active != null && active.running()) return status;
        if (startingJob >= 0) return "Agent API startup is already running.";
        Path root = editor.workspaceController.activeRoot();
        if (root == null) return "Select a workspace folder before starting the agent API.";
        long request = generation.incrementAndGet();
        stopping = false;
        Path directory = Path.of(editor.configManager.getSessionDirectory(), "agent-api");
        status = "Starting read-only agent API…";
        startingJob = editor.asyncJobService.submit("Start local agent API", token -> {
            WorkspaceBridgeServer server = new WorkspaceBridgeServer(editor.workspaceQueries(root, AgentPolicy.readOnly()), directory);
            owned.add(server);
            token.onCancel(() -> release(server));
            if (disposed.get() || generation.get() != request || token.isCancelled()) { release(server); return null; }
            return server;
        }, (snapshot, server, error) -> {
            if (disposed.get() || generation.get() != request) { if (server != null) release(server); return; }
            startingJob = -1;
            if (error != null || snapshot.getStatus() != AsyncJobService.Status.SUCCEEDED || server == null || !server.running()) {
                if (server != null) release(server);
                status = "Agent API could not start. Check local diagnostics and owner-only session directory permissions.";
            } else {
                active = server;
                status = "Read-only agent API for " + root + ". Connection descriptor: " + server.descriptor();
            }
            editor.showMessage(status);
        });
        return status;
    }

    private String stop() {
        stopping = true;
        generation.incrementAndGet();
        if (startingJob >= 0) editor.asyncJobService.cancel(startingJob);
        startingJob = -1; active = null;
        for (WorkspaceBridgeServer server : owned) release(server);
        status = owned.isEmpty() ? "Agent API is stopped; no listener is running."
            : "Agent API stopping; existing connections are being revoked.";
        return status;
    }

    private void release(WorkspaceBridgeServer server) {
        if (!releasing.add(server)) return;
        Thread.ofVirtual().name("shed-workspace-stop").start(() -> {
            try { server.close(); }
            catch (IOException cleanup) {
                System.getLogger("shed.workspace").log(System.Logger.Level.WARNING,
                    "Workspace connection stopped; its private descriptor could not be removed.");
            } finally {
                owned.remove(server); releasing.remove(server);
                if (!disposed.get()) SwingUtilities.invokeLater(() -> {
                    if (stopping && owned.isEmpty() && startingJob < 0)
                        status = "Agent API is stopped; no listener is running.";
                });
            }
        });
    }

    @Override public void close() {
        disposed.set(true); stop();
    }
}
