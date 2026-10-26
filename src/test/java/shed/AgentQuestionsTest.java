package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentQuestionsTest {
    @Test void invalidAnswersRemainEditableAndDeclineCancelNeverForwardContent() {
        try (var questions = new AgentQuestions(AgentPolicy.readOnly(), uri -> fail("No browser should open"))) {
            var question = questions.prepare(form("session")); var reply = questions.add(question);
            assertThrows(AcpException.class, () -> questions.answer(question.id(), "accept", Map.of())); assertFalse(reply.isDone());
            questions.answer(question.id(), "accept", Map.of("name", "Ada")); assertEquals(Map.of("action", "accept", "content", Map.of("name", "Ada")), reply.join());
            assertTrue(questions.snapshot().isEmpty());
            for (String action : List.of("decline", "cancel")) {
                var next = questions.prepare(form("session")); var nextReply = questions.add(next);
                questions.answer(next.id(), action, Map.of("ignored", "private")); assertEquals(Map.of("action", action), nextReply.join());
            }
        }
    }

    @Test void cancellationIsScopedAndTransportCancellationRemovesTheQuestion() {
        try (var questions = new AgentQuestions(AgentPolicy.readOnly(), uri -> fail("No browser should open"))) {
            var session = questions.add(questions.prepare(form("session")));
            var requestParams = new java.util.LinkedHashMap<>(form("session")); requestParams.remove("sessionId"); requestParams.put("requestId", "shed-1");
            var request = questions.add(questions.prepare(requestParams)); questions.cancelSession("different"); assertFalse(session.isDone());
            questions.cancelSession("session"); assertEquals("cancel", session.join().get("action")); assertFalse(request.isDone());
            questions.cancelRequest("shed-1"); assertEquals("cancel", request.join().get("action"));
            var direct = questions.add(questions.prepare(form("session"))); direct.cancel(true); assertTrue(questions.snapshot().isEmpty());
            assertThrows(AcpException.class, () -> questions.prepare(Map.of("sessionId", "s", "requestId", "r", "mode", "form", "message", "Question", "requestedSchema", Map.of())));
        }
    }

    @Test void browserRequiresAdvertisedForegroundCapabilityAndExplicitConsent() {
        var calls = new java.util.ArrayList<URI>();
        try (var background = new AgentQuestions(AgentPolicy.readOnly(), calls::add)) {
            assertThrows(AcpException.class, () -> background.prepare(url("https://example.com/authorize")));
        }
        try (var questions = new AgentQuestions(foreground(), calls::add)) {
            var pending = questions.prepare(url("https://xn--bcher-kva.example/authorize?id=opaque")); var reply = questions.add(pending);
            assertTrue(calls.isEmpty()); assertFalse(reply.isDone()); assertFalse(questions.completeExternal("external"));
            questions.answer(pending.id(), "accept", Map.of()); assertEquals(Map.of("action", "accept"), reply.join()); assertEquals(1, calls.size());
            assertThrows(AcpException.class, () -> questions.add(questions.prepare(url("https://example.com/authorize"))));
            assertTrue(questions.completeExternal("external")); assertFalse(questions.completeExternal("external"));
            var next = questions.prepare(url("https://example.com/authorize")); var dismissed = questions.add(next);
            questions.answer(next.id(), "decline", Map.of()); assertEquals("decline", dismissed.join().get("action")); assertEquals(1, calls.size());
        }
    }

    @Test void failedBrowserHandoffCanBeRetriedWithoutClaimingCompletion() {
        var count = new AtomicInteger();
        try (var questions = new AgentQuestions(foreground(), uri -> { if (count.incrementAndGet() == 1) throw new Exception("unavailable"); })) {
            var pending = questions.prepare(url("https://example.com/authorize")); var reply = questions.add(pending);
            assertThrows(AcpException.class, () -> questions.answer(pending.id(), "accept", Map.of())); assertFalse(reply.isDone());
            questions.answer(pending.id(), "accept", Map.of()); assertEquals("accept", reply.join().get("action")); assertEquals(2, count.get());
        }
        for (String url : List.of("javascript:alert(1)", "file:///tmp/page", "http://example.com/login", "https://user:password@example.com/", "https:///missing-host"))
            assertThrows(AcpException.class, () -> AgentQuestions.externalUrl(url));
        assertEquals("localhost", AgentQuestions.externalUrl("http://localhost:8080/login").getHost());
    }
    static Map<String, Object> form(String session) { return Map.of("sessionId", session, "mode", "form", "message", "Your display name?", "requestedSchema", Map.of("properties", Map.of("name", Map.of("type", "string", "minLength", 1)), "required", List.of("name"))); }
    static Map<String, Object> url(String url) { return Map.of("sessionId", "session", "mode", "url", "message", "Authorize this integration", "url", url, "elicitationId", "external"); }
    private static AgentPolicy foreground() { return new AgentPolicy(Set.of(AgentPolicy.Capability.OPEN_UI), List.of(), List.of(), false); }
}
