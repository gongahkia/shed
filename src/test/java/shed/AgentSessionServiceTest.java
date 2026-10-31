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
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSessionServiceTest {
    @TempDir Path root;
    private final List<AgentSessionService> sessions = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach void closeProcesses() throws Exception {
        for (var session : sessions) {
            session.close();
            session.termination().get(10, TimeUnit.SECONDS);
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                FixtureCleanup.deleteEventually(path);
            }
        }
    }

    private AgentSessionService session(AgentProvider provider, WorkspaceQueryService queries) throws Exception {
        var service = new AgentSessionService(provider, queries);
        sessions.add(service);
        return service;
    }

    @Test void independentSdkReadsTheLiveUnsavedBufferAndStreamsBeforeTurnCompletion() throws Exception {
        Path file = Files.writeString(root.resolve("source"), "saved\r\n"); FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("unsaved human work\n"));
            try (var service = session(sdk(file), queries(editor))) {
                service.initialize().get(15, TimeUnit.SECONDS); assertEquals(AgentSessionService.State.READY, service.snapshot().state());
                assertTrue(Boolean.TRUE.equals(service.snapshot().capabilities().get("loadSession")));
                service.newSession().get(10, TimeUnit.SECONDS);
                assertEquals("end_turn", service.prompt("read").get(10, TimeUnit.SECONDS).get("stopReason"));
                var events = service.snapshot().events();
                assertTrue(events.stream().anyMatch(event -> event.type().equals("session_update") && MiniJson.stringify(event.data()).contains("unsaved human work")));
                assertEquals("turn_finished", events.getLast().type()); assertEquals("saved\r\n", Files.readString(file));
                assertTrue(events.stream().anyMatch(event -> event.type().equals("file_read") && event.data().get("version").equals(editor.snapshots().getFirst().version())));
                assertThrows(UnsupportedOperationException.class, () -> service.snapshot().capabilities().put("injected", true));
            }
        }
    }

    @Test void independentSdkPermissionsAreExplicitAndCancellationAllowsAnotherTurn() throws Exception {
        try (var service = session(sdk(root.resolve("source")), queries(WorkspaceDocumentSource.diskOnly()))) {
            service.initialize().get(15, TimeUnit.SECONDS); service.newSession().get(10, TimeUnit.SECONDS);
            var prompt = service.prompt("permission"); AcpConnectionTest.await(() -> service.snapshot().permissions().size() == 1);
            assertFalse(prompt.isDone());
            assertTrue(service.snapshot().events().stream().anyMatch(event -> MiniJson.stringify(event.data()).contains("Before permission")));
            service.decide(service.snapshot().permissions().getFirst().id(), "allow");
            assertEquals("end_turn", prompt.get(10, TimeUnit.SECONDS).get("stopReason"));
            prompt = service.prompt("permission"); AcpConnectionTest.await(() -> service.snapshot().permissions().size() == 1);
            service.cancel(); assertEquals("cancelled", prompt.get(10, TimeUnit.SECONDS).get("stopReason"));
            assertTrue(service.snapshot().permissions().isEmpty()); assertEquals(AgentSessionService.State.IDLE, service.snapshot().state());
            assertEquals("end_turn", service.prompt("after cancellation").get(10, TimeUnit.SECONDS).get("stopReason"));
        }
    }

    @Test void permissionDecisionsCannotEscalateTheWorkspacePolicy() throws Exception {
        try (var service = session(sdk(root.resolve("source")), queries(WorkspaceDocumentSource.diskOnly()))) {
            service.initialize().get(15, TimeUnit.SECONDS); service.newSession().get(10, TimeUnit.SECONDS);
            var prompt = service.prompt("execute"); AcpConnectionTest.await(() -> !service.snapshot().permissions().isEmpty());
            String id = service.snapshot().permissions().getFirst().id();
            assertEquals(WorkspaceApiException.Code.PERMISSION_DENIED, assertThrows(WorkspaceApiException.class, () -> service.decide(id, "allow")).code());
            assertFalse(prompt.isDone()); service.decide(id, "deny"); prompt.get(10, TimeUnit.SECONDS);
            assertTrue(service.snapshot().permissions().isEmpty()); assertThrows(AcpException.class, () -> service.decide(id, "allow"));
        }
    }

    @Test void loadingRequiresAdvertisedCapabilityAndDoesNotPretendToPersistLocally() throws Exception {
        try (var service = session(sdk(root.resolve("source")), queries(WorkspaceDocumentSource.diskOnly()))) {
            service.initialize().get(15, TimeUnit.SECONDS); service.loadSession("sdk-session").get(10, TimeUnit.SECONDS);
            assertEquals("sdk-session", service.snapshot().sessionId()); assertEquals("end_turn", service.prompt("resumed").get(10, TimeUnit.SECONDS).get("stopReason"));
        }
        try (var service = session(provider("echo"), queries(WorkspaceDocumentSource.diskOnly()))) {
            service.initialize().get(10, TimeUnit.SECONDS);
            assertThrows(AcpException.class, () -> service.loadSession("unknown")); assertEquals(AgentSessionService.State.READY, service.snapshot().state());
        }
    }

    @Test void unsupportedProtocolIsStoppedAndReadOnlyPolicyDoesNotLaunchAProcess() throws Exception {
        assertEquals(WorkspaceApiException.Code.PERMISSION_DENIED, assertThrows(WorkspaceApiException.class,
            () -> new AgentSessionService(provider("echo"), new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()))).code());
        try (var service = session(provider("version"), queries(WorkspaceDocumentSource.diskOnly()))) {
            assertThrows(ExecutionException.class, () -> service.initialize().get(10, TimeUnit.SECONDS));
            assertEquals(AgentSessionService.State.FAILED, service.snapshot().state()); assertFalse(service.snapshot().diagnostics().running());
            assertThrows(AcpException.class, service::newSession);
        }
    }

    @Test void unresponsiveCancellationClosesTheOwnedConnectionInsteadOfClaimingSuccess() throws Exception {
        try (var service = session(provider("ignore-cancel"), queries(WorkspaceDocumentSource.diskOnly()))) {
            service.initialize().get(10, TimeUnit.SECONDS); service.newSession().get(10, TimeUnit.SECONDS);
            var prompt = service.prompt("work"); service.cancel();
            assertThrows(ExecutionException.class, () -> prompt.get(10, TimeUnit.SECONDS));
            AcpConnectionTest.await(() -> service.snapshot().state() == AgentSessionService.State.FAILED);
            assertFalse(service.snapshot().diagnostics().running());
            assertTrue(service.snapshot().error().contains("did not acknowledge cancellation"));
            assertTrue(service.snapshot().events().stream().noneMatch(event -> event.type().equals("turn_finished")));
        }
    }

    @Test void agentReadsStayWithinTheExplicitWorkspaceAndUnknownSessionsFail() throws Exception {
        Path outside = Files.createTempFile("shed-acp-outside", ".txt");
        try {
            Files.writeString(outside, "outside");
            try (var service = session(sdk(outside), queries(WorkspaceDocumentSource.diskOnly()))) {
                service.initialize().get(15, TimeUnit.SECONDS); service.newSession().get(10, TimeUnit.SECONDS);
                assertThrows(ExecutionException.class, () -> service.prompt("read").get(10, TimeUnit.SECONDS));
                assertEquals(AgentSessionService.State.IDLE, service.snapshot().state());
                assertThrows(ExecutionException.class, () -> service.request("fs/read_text_file", Map.of("sessionId", "another", "path", outside.toString())).toCompletableFuture().get());
                assertThrows(ExecutionException.class, () -> service.request("fs/write_text_file", Map.of("sessionId", "sdk-session", "path", root.resolve("new").toString(), "content", "new")).toCompletableFuture().get());
                assertFalse(Files.exists(root.resolve("new")));
            }
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void advertisedModernAndLegacyModelsCanChangeBetweenTurnsWithoutRestart() throws Exception {
        for (String mode : List.of("models", "legacy-models")) try (var service = session(provider(mode), queries(WorkspaceDocumentSource.diskOnly()))) {
            service.initialize().get(10, TimeUnit.SECONDS); service.newSession().get(10, TimeUnit.SECONDS);
            assertEquals("provider/fast", service.models().current());
            assertThrows(AcpException.class, () -> service.selectModel("not-advertised"));
            service.selectModel("provider/careful").get(10, TimeUnit.SECONDS);
            assertEquals("provider/careful", service.models().current());
            var result = service.actionPrompt("question", "question", false).get(10, TimeUnit.SECONDS);
            assertTrue(result.text().contains("provider/careful"));
            if (mode.equals("models")) {
                assertThrows(ExecutionException.class, () -> service.selectModel("provider/unavailable").get(10, TimeUnit.SECONDS));
                assertEquals(AgentSessionService.State.IDLE, service.snapshot().state()); assertEquals("provider/careful", service.models().current());
            }
        }
    }

    private AgentProvider sdk(Path file) { return new AgentProvider("sdk", AcpConnectionTest.javaCommand(AcpSdkAgentFixture.class.getName(), file.toAbsolutePath().toString()), Map.of(), Duration.ofSeconds(15), Duration.ofSeconds(20)); }
    private AgentProvider provider(String mode) { return new AgentProvider("fixture", AcpConnectionTest.command(mode), Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(20)); }
    private WorkspaceQueryService queries(WorkspaceDocumentSource documents) {
        return new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.START_AGENT), List.of("**/README.md"), List.of(), true), documents);
    }
}
