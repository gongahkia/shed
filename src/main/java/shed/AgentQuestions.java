package shed;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Connection-owned questions. Answers and external page content are never added to activity history. */
final class AgentQuestions implements AutoCloseable {
    interface Navigator { void open(URI uri) throws Exception; }
    record Question(String id, String sessionId, Object requestId, String toolCallId, String message,
                    AgentQuestionSchema form, URI url, String externalId) { }
    private static final class Pending {
        final Question question;
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        boolean responding, accepted;
        Pending(Question question) { this.question = question; }
    }
    private final AgentPolicy policy;
    private final Navigator navigator;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Set<String> externalIds = new java.util.HashSet<>(), acceptedIds = new java.util.HashSet<>();
    private boolean closed;

    AgentQuestions(AgentPolicy policy, Navigator navigator) { this.policy = policy; this.navigator = navigator; }
    boolean supportsUrl() { return policy.capabilities().contains(AgentPolicy.Capability.OPEN_UI) && !policy.backgroundOnly(); }
    synchronized List<Question> snapshot() { return pending.values().stream().map(item -> item.question).toList(); }

    Question prepare(Map<String, Object> params) {
        String session = params.get("sessionId") == null ? null : text(params, "sessionId", 1024);
        Object request = params.get("requestId");
        if ((session == null) == (request == null) || request != null && !(request instanceof String || request instanceof Long)) throw invalid("Choose one valid elicitation scope");
        if (request instanceof String id && (id.isBlank() || id.length() > 256)) throw invalid("Invalid request scope");
        String tool = params.get("toolCallId") == null ? null : text(params, "toolCallId", 1024);
        if (tool != null && session == null) throw invalid("A tool call requires a session scope");
        String message = text(params, "message", 16384), mode = text(params, "mode", 16);
        AgentQuestionSchema form = null; URI url = null; String external = null;
        if (mode.equals("form")) {
            if (params.get("url") != null || params.get("elicitationId") != null) throw invalid("Form requests cannot contain external interactions");
            form = AgentQuestionSchema.parse(params.get("requestedSchema"));
        } else if (mode.equals("url") && supportsUrl()) {
            if (params.get("requestedSchema") != null) throw invalid("URL requests cannot collect form answers");
            external = text(params, "elicitationId", 1024); url = externalUrl(text(params, "url", 8192));
        } else throw invalid("This elicitation mode was not advertised");
        return new Question(UUID.randomUUID().toString(), session, request, tool, message, form, url, external);
    }

    synchronized CompletableFuture<Map<String, Object>> add(Question question) {
        if (closed) throw new AcpException(-32800, "Question connection is closed");
        if (pending.size() >= 32) throw invalid("Too many questions are pending");
        if (question.externalId() != null && (externalIds.size() >= 32 || !externalIds.add(question.externalId()))) throw invalid("External interaction ID is already outstanding or its limit was reached");
        var item = new Pending(question); pending.put(question.id(), item);
        item.result.whenComplete((result, failure) -> {
            synchronized (this) {
                pending.remove(question.id(), item);
                if (!item.accepted) externalIds.remove(question.externalId());
            }
        });
        return item.result;
    }

    boolean answer(String id, String action, Map<String, Object> content) {
        if (!Set.of("accept", "decline", "cancel").contains(action)) throw invalid("Unknown question response");
        Pending item;
        synchronized (this) { item = pending.get(id); if (item == null || item.responding) throw invalid("This question is no longer available"); }
        Map<String, Object> response = Map.of("action", action);
        if (action.equals("accept") && item.question.form() != null) response = Map.of("action", action, "content", item.question.form().validate(content));
        synchronized (this) {
            if (pending.get(id) != item || item.responding || item.result.isDone()) throw invalid("This question changed while validating the response");
            item.responding = true;
            if (!action.equals("accept") || item.question.url() == null) return item.result.complete(response);
        }
        // Consent is claimed above. Browser handoff stays off the EDT and outside the registry lock.
        try {
            policy.require(AgentPolicy.Capability.OPEN_UI);
            if (item.result.isDone()) return false;
            navigator.open(item.question.url());
            synchronized (this) {
                if (!item.result.isDone()) { item.accepted = true; acceptedIds.add(item.question.externalId()); return item.result.complete(response); }
                return false;
            }
        } catch (Exception failed) {
            synchronized (this) { item.responding = false; }
            if (item.result.isDone()) return false;
            throw new AcpException(-32603, "Browser handoff failed; the request remains available to decline or retry");
        }
    }

    synchronized void cancelSession(String session) { cancel(item -> java.util.Objects.equals(session, item.question.sessionId())); }
    synchronized void cancelRequest(String request) { cancel(item -> request.equals(item.question.requestId())); }
    synchronized boolean completeExternal(String id) {
        if (!acceptedIds.remove(id)) return false;
        externalIds.remove(id); return true;
    }
    @Override public synchronized void close() { closed = true; cancel(item -> true); externalIds.clear(); acceptedIds.clear(); }
    private void cancel(java.util.function.Predicate<Pending> matches) {
        for (var item : new ArrayList<>(pending.values())) if (matches.test(item)) item.result.complete(Map.of("action", "cancel"));
    }

    static URI externalUrl(String text) {
        try {
            URI uri = new URI(text); String host = uri.getHost(), scheme = uri.getScheme();
            if (host == null || host.isBlank() || uri.getRawUserInfo() != null) throw invalid("The external URL must have an unambiguous host and no embedded credentials");
            boolean local = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && local)) throw invalid("External interactions require HTTPS, or HTTP on a local development host");
            return uri;
        } catch (java.net.URISyntaxException malformed) { throw invalid("Invalid external URL"); }
    }
    static void openBrowser(URI uri) throws Exception {
        if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) throw new IllegalStateException("Browser unavailable");
        java.awt.Desktop.getDesktop().browse(uri);
    }
    private static String text(Map<String, Object> params, String name, int limit) {
        String value = AgentQuestionSchema.bounded(params.get(name), limit); if (value.isBlank()) throw invalid("Missing " + name); return value;
    }
    private static AcpException invalid(String message) { return AgentQuestionSchema.invalid(message); }
}
