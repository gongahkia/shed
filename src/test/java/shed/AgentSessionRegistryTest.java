package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSessionRegistryTest {
    @TempDir Path root;
    @Test void shutdownClosesExistingSessionsAndRejectsLateRegistrations() throws Exception {
        var queries = new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.START_AGENT, AgentPolicy.Capability.READ),
            List.of("**/README.md"), List.of(), true), WorkspaceDocumentSource.diskOnly());
        var provider = new AgentProvider("fixture", AcpConnectionTest.command("hang"), Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(10));
        var registry = new AgentSessionRegistry();
        try (var first = new AgentSessionService(provider, queries)) {
            registry.own(first); registry.close(); first.termination().get(10, TimeUnit.SECONDS);
            assertEquals(AgentSessionService.State.CLOSED, first.snapshot().state());
            try (var late = new AgentSessionService(provider, queries)) {
                assertEquals(WorkspaceApiException.Code.CANCELLED, assertThrows(WorkspaceApiException.class, () -> registry.own(late)).code());
                late.termination().get(10, TimeUnit.SECONDS); assertFalse(late.snapshot().diagnostics().running());
            }
        } finally { registry.close(); }
    }
}
